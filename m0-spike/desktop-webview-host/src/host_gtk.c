/*
 * Kurisa 桌面 WebView Host —— Linux / WebKitGTK 4.1 实现
 *
 * 定位：独立子进程，只做一件事——用系统自带的 WebKitGTK 打开一个真实浏览器窗口，
 * 让用户完成 Cloudflare 验证或站点登录，然后把 Cookie（含 HttpOnly）交回主进程。
 * 不打包任何浏览器内核，随包体积约 200KB。
 *
 * 通信：stdin 收 NDJSON 命令，stdout 发 NDJSON 事件/响应。
 * 之所以不用 loopback 端口：Windows 上监听端口会弹防火墙授权框，用户会慌。
 *
 * 命令：
 *   {"id":1,"cmd":"open","url":"https://...","width":1000,"height":800}
 *   {"id":2,"cmd":"cookies","url":"https://..."}
 *   {"id":3,"cmd":"ua"}
 *   {"id":4,"cmd":"eval","js":"document.title"}
 *   {"id":5,"cmd":"snapshot","path":"/tmp/x.png"}
 *   {"id":6,"cmd":"close"}
 *   {"id":7,"cmd":"quit"}
 *
 * 事件：
 *   {"ev":"ready","ua":"..."}
 *   {"ev":"loaded","url":"...","title":"..."}
 *   {"ev":"closed"}
 *   {"ev":"error","message":"..."}
 *   {"id":N,"ok":...}
 */

#include <gtk/gtk.h>
#include <webkit2/webkit2.h>
#include <cairo/cairo.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <sys/stat.h>
#include <sys/types.h>

static GtkWidget *g_window = NULL;
static WebKitWebView *g_view = NULL;
static char g_ua[512] = {0};

/* ------------------------------------------------------------------ */
/* 输出                                                                */
/* ------------------------------------------------------------------ */

/* 把字符串按 JSON 规则转义后写入 fd，不分配内存 */
static void json_write_escaped(FILE *out, const char *s) {
  fputc('"', out);
  for (const unsigned char *p = (const unsigned char *)s; *p; ++p) {
    switch (*p) {
      case '"': fputs("\\\"", out); break;
      case '\\': fputs("\\\\", out); break;
      case '\n': fputs("\\n", out); break;
      case '\r': fputs("\\r", out); break;
      case '\t': fputs("\\t", out); break;
      case '\b': fputs("\\b", out); break;
      case '\f': fputs("\\f", out); break;
      default:
        if (*p < 0x20) fprintf(out, "\\u%04x", *p);
        else fputc(*p, out);
    }
  }
  fputc('"', out);
}

static void emit_ok_string(long id, const char *value) {
  printf("{\"id\":%ld,\"ok\":", id);
  json_write_escaped(stdout, value ? value : "");
  fputs("}\n", stdout);
  fflush(stdout);
}

static void emit_error(long id, const char *msg) {
  printf("{\"id\":%ld,\"error\":", id);
  json_write_escaped(stdout, msg);
  fputs("}\n", stdout);
  fflush(stdout);
}

/* ------------------------------------------------------------------ */
/* 极简 JSON 取值（只处理我们自己生成的扁平对象）                        */
/* ------------------------------------------------------------------ */

/* 在 json 中定位 "key" 之后的冒号位置，找不到返回 NULL */
static const char *find_key(const char *json, const char *key) {
  char pattern[128];
  snprintf(pattern, sizeof(pattern), "\"%s\"", key);
  size_t plen = strlen(pattern);
  const char *p = json;
  while ((p = strstr(p, pattern)) != NULL) {
    const char *q = p + plen;
    while (*q == ' ' || *q == '\t') q++;
    if (*q == ':') return q + 1;
    p = q;
  }
  return NULL;
}

static int hex4(const char *s) {
  int v = 0;
  for (int i = 0; i < 4; ++i) {
    char c = s[i];
    v <<= 4;
    if (c >= '0' && c <= '9') v |= c - '0';
    else if (c >= 'a' && c <= 'f') v |= c - 'a' + 10;
    else if (c >= 'A' && c <= 'F') v |= c - 'A' + 10;
  }
  return v;
}

static void utf8_put(GString *out, unsigned cp) {
  if (cp < 0x80) g_string_append_c(out, (char)cp);
  else if (cp < 0x800) {
    g_string_append_c(out, (char)(0xC0 | (cp >> 6)));
    g_string_append_c(out, (char)(0x80 | (cp & 0x3F)));
  } else if (cp < 0x10000) {
    g_string_append_c(out, (char)(0xE0 | (cp >> 12)));
    g_string_append_c(out, (char)(0x80 | ((cp >> 6) & 0x3F)));
    g_string_append_c(out, (char)(0x80 | (cp & 0x3F)));
  } else {
    /* supplementary plane: 4-byte UTF-8（emoji 等，JS eval 常见） */
    g_string_append_c(out, (char)(0xF0 | (cp >> 18)));
    g_string_append_c(out, (char)(0x80 | ((cp >> 12) & 0x3F)));
    g_string_append_c(out, (char)(0x80 | ((cp >> 6) & 0x3F)));
    g_string_append_c(out, (char)(0x80 | (cp & 0x3F)));
  }
}

/* 取字符串字段，返回新分配的 UTF-8 串；不存在返回 NULL */
static char *json_get_string(const char *json, const char *key) {
  const char *p = find_key(json, key);
  if (!p) return NULL;
  while (*p == ' ' || *p == '\t') p++;
  if (*p != '"') return NULL;
  p++;
  GString *out = g_string_new(NULL);
  while (*p && *p != '"') {
    if (*p == '\\' && p[1]) {
      p++;
      switch (*p) {
        case 'n': g_string_append_c(out, '\n'); break;
        case 'r': g_string_append_c(out, '\r'); break;
        case 't': g_string_append_c(out, '\t'); break;
        case 'b': g_string_append_c(out, '\b'); break;
        case 'f': g_string_append_c(out, '\f'); break;
        case '/': g_string_append_c(out, '/'); break;
        case 'u':
          if (strlen(p + 1) >= 4) {
            unsigned hi = (unsigned)hex4(p + 1);
            /* surrogate pair：JS eval 的 emoji 常以 \uD83D\uDE00 出现 */
            if (hi >= 0xD800 && hi <= 0xDBFF && strlen(p + 1) >= 10 &&
                p[5] == '\\' && p[6] == 'u') {
              unsigned lo = (unsigned)hex4(p + 7);
              if (lo >= 0xDC00 && lo <= 0xDFFF) {
                unsigned cp = 0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00);
                utf8_put(out, cp);
                p += 10;
                break;
              }
            }
            utf8_put(out, hi);
            p += 4;
          }
          break;
        default: g_string_append_c(out, *p);
      }
      p++;
    } else {
      g_string_append_c(out, *p++);
    }
  }
  if (*p != '"') { g_string_free(out, TRUE); return NULL; }
  return g_string_free(out, FALSE);
}

static long json_get_int(const char *json, const char *key, long fallback) {
  const char *p = find_key(json, key);
  if (!p) return fallback;
  while (*p == ' ' || *p == '\t') p++;
  if (!isdigit((unsigned char)*p) && *p != '-') return fallback;
  return strtol(p, NULL, 10);
}

/* ------------------------------------------------------------------ */
/* 目录与 Cookie 持久化                                                */
/* ------------------------------------------------------------------ */

static void mkdir_p(const char *path) {
  char tmp[1024];
  snprintf(tmp, sizeof(tmp), "%s", path);
  for (char *p = tmp + 1; *p; ++p) {
    if (*p == '/') {
      *p = '\0';
      mkdir(tmp, 0700);
      *p = '/';
    }
  }
  mkdir(tmp, 0700);
}

static char *profile_data_dir(void) {
  const char *override = g_getenv("KURISA_WEBVIEW_DATA");
  const char *profile = g_getenv("KURISA_WEBVIEW_PROFILE");
  if (!profile || !*profile) profile = "default";
  char *dir = override && *override
      ? g_strdup_printf("%s/%s", override, profile)
      : g_strdup_printf("%s/kurisa/webview/%s", g_get_user_data_dir(), profile);
  mkdir_p(dir);
  return dir;
}

/* ------------------------------------------------------------------ */
/* Cookie 读取（关键能力：document.cookie 看不到 HttpOnly）             */
/* ------------------------------------------------------------------ */

typedef struct {
  GMainLoop *loop;
  GList *cookies;
  cairo_surface_t *surface;
  GError *error;
} CookieFetch;

static void on_cookies(GObject *source, GAsyncResult *res, gpointer user_data) {
  CookieFetch *f = (CookieFetch *)user_data;
  f->cookies = webkit_cookie_manager_get_cookies_finish(
      WEBKIT_COOKIE_MANAGER(source), res, &f->error);
  if (f->loop) g_main_loop_quit(f->loop);
}

static void emit_cookies(long id, const char *url) {
  WebKitCookieManager *cm =
      webkit_web_context_get_cookie_manager(webkit_web_view_get_context(g_view));

  CookieFetch f = {0};
  f.loop = g_main_loop_new(NULL, FALSE);
  webkit_cookie_manager_get_cookies(cm, url, NULL, on_cookies, &f);
  g_main_loop_run(f.loop);
  g_main_loop_unref(f.loop);

  if (f.error) {
    emit_error(id, f.error->message);
    g_error_free(f.error);
    return;
  }

  printf("{\"id\":%ld,\"ok\":[", id);
  gboolean first = TRUE;
  for (GList *l = f.cookies; l; l = l->next) {
    SoupCookie *c = (SoupCookie *)l->data;
    if (!first) fputc(',', stdout);
    first = FALSE;
    fputs("{\"name\":", stdout);
    json_write_escaped(stdout, soup_cookie_get_name(c));
    fputs(",\"value\":", stdout);
    json_write_escaped(stdout, soup_cookie_get_value(c));
    fputs(",\"domain\":", stdout);
    json_write_escaped(stdout, soup_cookie_get_domain(c));
    fputs(",\"path\":", stdout);
    json_write_escaped(stdout, soup_cookie_get_path(c));
    /* HttpOnly / Secure 是判定过盾状态的关键元数据 */
    printf(",\"httpOnly\":%s", soup_cookie_get_http_only(c) ? "true" : "false");
    printf(",\"secure\":%s", soup_cookie_get_secure(c) ? "true" : "false");
    printf(",\"session\":%s",
           soup_cookie_get_expires(c) ? "false" : "true");
    if (soup_cookie_get_expires(c)) {
      printf(",\"expiresAt\":%lld",
             (long long)soup_cookie_get_expires(c) * 1000LL);
    }
    fputc('}', stdout);
  }
  fputs("]}\n", stdout);
  fflush(stdout);

  g_list_free_full(f.cookies, (GDestroyNotify)soup_cookie_free);
}

/* ------------------------------------------------------------------ */
/* 命令处理                                                            */
/* ------------------------------------------------------------------ */

static void on_snapshot_ready(GObject *source, GAsyncResult *res,
                              gpointer user_data) {
  CookieFetch *f = (CookieFetch *)user_data;
  f->surface = webkit_web_view_get_snapshot_finish(WEBKIT_WEB_VIEW(source), res,
                                                   &f->error);
  if (f->loop) g_main_loop_quit(f->loop);
}

static void do_snapshot(long id, const char *path) {
  if (!path) { emit_error(id, "missing path"); return; }
  CookieFetch f = {0};
  f.loop = g_main_loop_new(NULL, FALSE);
  webkit_web_view_get_snapshot(g_view, WEBKIT_SNAPSHOT_REGION_VISIBLE,
                               WEBKIT_SNAPSHOT_OPTIONS_NONE, NULL,
                               on_snapshot_ready, &f);
  g_main_loop_run(f.loop);
  g_main_loop_unref(f.loop);

  if (!f.surface) {
    emit_error(id, f.error ? f.error->message : "snapshot failed");
    if (f.error) g_error_free(f.error);
    return;
  }
  cairo_status_t st = cairo_surface_write_to_png(f.surface, path);
  cairo_surface_destroy(f.surface);
  if (st != CAIRO_STATUS_SUCCESS) {
    emit_error(id, cairo_status_to_string(st));
    return;
  }
  emit_ok_string(id, path);
}

typedef struct {
  long id;
  long expect;
} EvalCtx;

static void do_open(long id, const char *url, long width, long height) {
  if (!url) { emit_error(id, "missing url"); return; }
  if (width > 0 && height > 0) {
    gtk_window_resize(GTK_WINDOW(g_window), (int)width, (int)height);
  }
  gtk_widget_show_all(g_window);
  gtk_window_present(GTK_WINDOW(g_window));
  if (g_getenv("KURISA_WEBVIEW_DEBUG")) {
    fprintf(stderr, "[debug] open url=[%s] view=%p uri_before=%s\n", url,
            (void *)g_view, webkit_web_view_get_uri(g_view));
  }
  webkit_web_view_load_uri(g_view, url);
  emit_ok_string(id, "opened");
}

static void do_close(long id) {
  if (g_window) gtk_widget_hide(g_window);
  emit_ok_string(id, "closed");
}

static void on_eval_done(GObject *source, GAsyncResult *res, gpointer user_data) {
  EvalCtx *ctx = (EvalCtx *)user_data;
  GError *err = NULL;
  JSCValue *value = webkit_web_view_evaluate_javascript_finish(
      WEBKIT_WEB_VIEW(source), res, &err);
  if (err) {
    emit_error(ctx->id, err->message);
    g_error_free(err);
  } else {
    char *s = value ? jsc_value_to_string(value) : NULL;
    emit_ok_string(ctx->id, s ? s : "");
    g_free(s);
    if (value) g_object_unref(value);
  }
  g_free(ctx);
}

static gboolean handle_line(const char *line);

static gboolean dispatch_line(gpointer data) {
  char *line = (char *)data;
  handle_line(line);
  g_free(line);
  return G_SOURCE_REMOVE;
}

static gboolean quit_idle(gpointer data) {
  (void)data;
  gtk_main_quit();
  return G_SOURCE_REMOVE;
}

/* stdin 读取放在独立线程，主线程只跑 GTK 主循环。
   之前用 GIOChannel 在主循环里读，实测会与 WebKit 的 IPC 抢主循环，
   导致 load 事件和定时器都饿死。 */
static gpointer stdin_reader(gpointer data) {
  (void)data;
  char *line = NULL;
  size_t cap = 0;
  ssize_t n;
  while ((n = getline(&line, &cap, stdin)) > 0) {
    while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = '\0';
    if (n > 0) g_idle_add(dispatch_line, g_strdup(line));
  }
  free(line);
  g_idle_add(quit_idle, NULL);
  return NULL;
}

static gboolean handle_line(const char *line) {
  long id = json_get_int(line, "id", 0);
  char *cmd = json_get_string(line, "cmd");
  if (!cmd) return TRUE;

  if (strcmp(cmd, "open") == 0) {
    char *url = json_get_string(line, "url");
    do_open(id, url, json_get_int(line, "width", 0),
            json_get_int(line, "height", 0));
    g_free(url);
  } else if (strcmp(cmd, "close") == 0) {
    do_close(id);
  } else if (strcmp(cmd, "cookies") == 0) {
    char *url = json_get_string(line, "url");
    if (!url) {
      /* 缺省用当前页面 URL */
      url = g_strdup(webkit_web_view_get_uri(g_view));
    }
    if (!url) emit_error(id, "no url");
    else emit_cookies(id, url);
    g_free(url);
  } else if (strcmp(cmd, "ua") == 0) {
    emit_ok_string(id, g_ua[0] ? g_ua : webkit_settings_get_user_agent(
                                     webkit_web_view_get_settings(g_view)));
  } else if (strcmp(cmd, "eval") == 0) {
    char *js = json_get_string(line, "js");
    if (!js) { emit_error(id, "missing js"); }
    else {
      EvalCtx *ctx = g_new0(EvalCtx, 1);
      ctx->id = id;
      webkit_web_view_evaluate_javascript(g_view, js, -1, NULL, NULL, NULL,
                                          on_eval_done, ctx);
      g_free(js);
    }
  } else if (strcmp(cmd, "snapshot") == 0) {
    char *path = json_get_string(line, "path");
    do_snapshot(id, path);
    g_free(path);
  } else if (strcmp(cmd, "quit") == 0) {
    emit_ok_string(id, "bye");
    gtk_main_quit();
  } else {
    emit_error(id, "unknown cmd");
  }
  g_free(cmd);
  return TRUE;
}

static void on_load_changed(WebKitWebView *view, WebKitLoadEvent ev,
                            gpointer data) {
  (void)data;
  if (g_getenv("KURISA_WEBVIEW_DEBUG")) {
    fprintf(stderr, "[debug] load-changed ev=%d uri=%s\n", (int)ev,
            webkit_web_view_get_uri(view));
  }
  if (ev != WEBKIT_LOAD_FINISHED) return;
  const char *uri = webkit_web_view_get_uri(view);
  const char *title = webkit_web_view_get_title(view);
  printf("{\"ev\":\"loaded\",\"url\":");
  json_write_escaped(stdout, uri ? uri : "");
  fputs(",\"title\":", stdout);
  json_write_escaped(stdout, title ? title : "");
  fputs("}\n", stdout);
  fflush(stdout);
}

static gboolean on_load_failed(WebKitWebView *view, WebKitLoadEvent ev,
                               const char *uri, GError *error, gpointer data) {
  (void)view; (void)ev; (void)data;
  fprintf(stderr, "[debug] load-failed uri=%s err=%s\n", uri, error->message);
  printf("{\"ev\":\"load-failed\",\"url\":");
  json_write_escaped(stdout, uri ? uri : "");
  fputs(",\"error\":", stdout);
  json_write_escaped(stdout, error->message);
  fputs("}\n", stdout);
  fflush(stdout);
  return FALSE;
}

int main(int argc, char **argv) {
  /* 容器 / CI 里没有 user namespace 时，WebKit 的 WebProcess 起不来。
     仅测试环境显式关闭，正常发行版保持沙箱开启。 */
  if (g_getenv("KURISA_WEBVIEW_NO_SANDBOX")) {
    g_setenv("WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS", "1", FALSE);
  }

  gtk_init(&argc, &argv);

  char *data_dir = profile_data_dir();
  char *cookie_db = g_strdup_printf("%s/cookies.sqlite", data_dir);
  char *cache_dir = g_strdup_printf("%s/cache", data_dir);
  mkdir_p(cache_dir);

  /* 自带 data manager，才能把 localStorage / IndexedDB 也落到 profile 目录 */
  WebKitWebsiteDataManager *wdm = webkit_website_data_manager_new(
      "base-data-directory", data_dir, "base-cache-directory", cache_dir, NULL);
  WebKitWebContext *ctx = webkit_web_context_new_with_website_data_manager(wdm);
  g_view = WEBKIT_WEB_VIEW(webkit_web_view_new_with_context(ctx));

  WebKitCookieManager *cm = webkit_web_context_get_cookie_manager(ctx);
  /* SQLite 持久化：cf_clearance 要跨重启复用，否则每次都得重新过盾 */
  webkit_cookie_manager_set_persistent_storage(
      cm, cookie_db, WEBKIT_COOKIE_PERSISTENT_STORAGE_SQLITE);
  webkit_cookie_manager_set_accept_policy(cm, WEBKIT_COOKIE_POLICY_ACCEPT_ALWAYS);

  WebKitSettings *settings = webkit_web_view_get_settings(g_view);
  if (g_getenv("KURISA_WEBVIEW_DEBUG")) {
    webkit_settings_set_enable_write_console_messages_to_stdout(settings, TRUE);
  }
  const char *ua = webkit_settings_get_user_agent(settings);
  snprintf(g_ua, sizeof(g_ua), "%s", ua ? ua : "");

  g_signal_connect(g_view, "load-changed", G_CALLBACK(on_load_changed), NULL);
  g_signal_connect(g_view, "load-failed", G_CALLBACK(on_load_failed), NULL);

  /* 窗口在启动时就建好但保持隐藏：延迟到回调里创建会在部分环境下
     导致 WebKit 的加载被推迟到窗口映射之后，实测不可靠 */
  g_window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
  gtk_window_set_default_size(GTK_WINDOW(g_window), 1000, 800);
  gtk_window_set_title(GTK_WINDOW(g_window), "Kurisa");
  gtk_container_add(GTK_CONTAINER(g_window), GTK_WIDGET(g_view));

  char engine[64];
  snprintf(engine, sizeof(engine), "%d.%d.%d", webkit_get_major_version(),
           webkit_get_minor_version(), webkit_get_micro_version());
  printf("{\"ev\":\"ready\",\"proto\":1,\"ua\":");
  json_write_escaped(stdout, g_ua);
  fputs(",\"engine\":", stdout);
  json_write_escaped(stdout, engine);
  fputs("}\n", stdout);
  fflush(stdout);

  g_thread_new("stdin", stdin_reader, NULL);

  gtk_main();
  return 0;
}
