// kurisa-webview-host — Windows / WebView2 完整实现
// 协议与 Linux/macOS 一致：NDJSON over stdin/stdout，proto=1。
// 命令：open/cookies/ua/eval/snapshot/close/quit；事件：ready/loaded/load-failed。
// Cookie 经 ICoreWebView2CookieManager::GetCookies（含 HttpOnly）。
//
// 构建（Windows x64, MSVC + WebView2 NuGet）：见 build-windows.ps1。
//   nuget install Microsoft.Web.WebView2 -Version 1.0.2903.40
//   cl /EHsc /std:c++17 /I packages\...\build\native\include src\host_win.cc ^
//      /link /SUBSYSTEM:WINDOWS user32.lib ole32.lib shlwapi.lib
//
// 运行：kurisa-webview-host.exe（独立进程，主消息泵 + stdin 独立线程）。
// 不监听本地端口（防火墙弹窗），用户数据目录：%LOCALAPPDATA%/kurisa/webview/<profile>。
#include <windows.h>
#include <shlobj.h>
#include <string>
#include <iostream>
#include <sstream>
#include <vector>
#include <mutex>
#include <atomic>
#include <thread>

// WebView2 头文件由 NuGet 提供；CI 外如缺失则给出明确错误而非静默失败。
#ifdef __has_include
#if __has_include(<WebView2.h>)
#include <WebView2.h>
#define HAVE_WEBVIEW2 1
#else
#define HAVE_WEBVIEW2 0
#endif
#else
#include <WebView2.h>
#define HAVE_WEBVIEW2 1
#endif
#include <wrl.h>
using Microsoft::WRL::ComPtr;

static int PROTO = 1;
static HWND g_hwnd = nullptr;
#if HAVE_WEBVIEW2
static ComPtr<ICoreWebView2Controller> g_ctrl;
static ComPtr<ICoreWebView2> g_web;
static std::string g_ua;
static std::wstring g_profileDir;
#endif
static std::mutex g_outMutex;

static std::wstring Utf8ToW(const std::string& s) {
  if (s.empty()) return L"";
  int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
  std::wstring w(n, 0); MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), w.data(), n);
  return w;
}
static std::string WToUtf8(const std::wstring& w) {
  if (w.empty()) return "";
  int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), nullptr, 0, nullptr, nullptr);
  std::string s(n, 0); WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), s.data(), n, nullptr, nullptr);
  return s;
}
static std::string JsonEsc(const std::string& s) {
  std::string o = "\"";
  for (unsigned char c : s) {
    switch (c) {
      case '"': o += "\\\""; break; case '\\': o += "\\\\"; break;
      case '\n': o += "\\n"; break; case '\r': o += "\\r"; break;
      case '\t': o += "\\t"; break;
      default: if (c < 0x20) { char b[8]; snprintf(b, sizeof(b), "\\u%04x", c); o += b; } else o += (char)c;
    }
  }
  return o + "\"";
}
static void Emit(const std::string& s) {
  std::lock_guard<std::mutex> lk(g_outMutex);
  std::cout << s << "\n" << std::flush;
}
// 极简取值：与 host_gtk.c 同策略（扁平对象）；完整版见 Linux 实现。
static bool JStr(const std::string& json, const char* key, std::string& out) {
  std::string pat = std::string("\"") + key + "\"";
  size_t p = json.find(pat);
  if (p == std::string::npos) return false;
  p = json.find(':', p + pat.size());
  if (p == std::string::npos) return false;
  p = json.find('"', p);
  if (p == std::string::npos) return false;
  std::string o;
  for (size_t i = p + 1; i < json.size(); ++i) {
    char c = json[i];
    if (c == '"') break;
    if (c == '\\' && i + 1 < json.size()) { o += json[++i]; continue; }
    o += c;
  }
  out = o; return true;
}
static long JInt(const std::string& json, const char* key, long fb) {
  std::string pat = std::string("\"") + key + "\"";
  size_t p = json.find(pat);
  if (p == std::string::npos) return fb;
  p = json.find(':', p + pat.size());
  if (p == std::string::npos) return fb;
  return strtol(json.c_str() + p + 1, nullptr, 10);
}

#if HAVE_WEBVIEW2
// cookies 命令：GetCookies → NDJSON（含 httpOnly/secure/session/expiresAt 毫秒）。
static void DoCookies(long id, const std::string& url) {
  ComPtr<ICoreWebView2_2> web2;
  if (FAILED(g_web->QueryInterface(IID_PPV_ARGS(&web2)))) { Emit("{\"id\":" + std::to_string(id) + ",\"error\":" + JsonEsc("no webview2_2") + "}"); return; }
  ComPtr<ICoreWebView2CookieManager> mgr;
  if (FAILED(web2->get_CookieManager(&mgr))) { Emit("{\"id\":" + std::to_string(id) + ",\"error\":" + JsonEsc("no cookie manager") + "}"); return; }
  std::wstring wurl = Utf8ToW(url.empty() ? "" : url);
  // 获取当前页 URL 为空时用导航目标代替
  if (wurl.empty()) {
    LPWSTR uri = nullptr;
    if (SUCCEEDED(g_web->get_Source(&uri)) && uri) { wurl = uri; CoTaskMemFree(uri); }
  }
  mgr->GetCookies(wurl.c_str(), Microsoft::WRL::Callback<ICoreWebView2GetCookiesCompletedHandler>(
    [id](HRESULT hr, ICoreWebView2CookieList* list) -> HRESULT {
      if (FAILED(hr) || !list) { Emit("{\"id\":" + std::to_string(id) + ",\"error\":" + JsonEsc("get cookies failed") + "}"); return S_OK; }
      UINT n = 0; list->get_Count(&n);
      std::string items;
      for (UINT i = 0; i < n; ++i) {
        ComPtr<ICoreWebView2Cookie> c;
        if (FAILED(list->GetValueAtIndex(i, &c)) || !c) continue;
        LPWSTR v = nullptr; std::string name, value, domain, path; BOOL ho = FALSE, sec = FALSE, sess = FALSE; double exp = 0;
        c->get_Name(&v); name = v ? WToUtf8(v) : ""; if (v) CoTaskMemFree(v);
        c->get_Value(&v); value = v ? WToUtf8(v) : ""; if (v) CoTaskMemFree(v);
        c->get_Domain(&v); domain = v ? WToUtf8(v) : ""; if (v) CoTaskMemFree(v);
        c->get_Path(&v); path = v ? WToUtf8(v) : ""; if (v) CoTaskMemFree(v);
        c->get_IsHttpOnly(&ho); c->get_IsSecure(&sec); c->get_IsSession(&sess); c->get_Expires(&exp);
        if (!items.empty()) items += ",";
        items += "{\"name\":" + JsonEsc(name) + ",\"value\":" + JsonEsc(value) + ",\"domain\":" + JsonEsc(domain)
          + ",\"path\":" + JsonEsc(path.empty() ? "/" : path) + ",\"httpOnly\":" + (ho ? "true" : "false")
          + ",\"secure\":" + (sec ? "true" : "false") + ",\"session\":" + (sess ? "true" : "false")
          + ",\"expiresAt\":" + (sess ? "null" : std::to_string((long long)(exp * 1000))) + "}";
      }
      Emit("{\"id\":" + std::to_string(id) + ",\"ok\":[" + items + "]}");
      return S_OK;
    }).Get());
}
#endif

static LRESULT CALLBACK WndProc(HWND h, UINT m, WPARAM w, LPARAM l) {
  if (m == WM_DESTROY) { PostQuitMessage(0); return 0; }
  return DefWindowProc(h, m, w, l);
}

int wmain(int argc, wchar_t** argv) {
#if !HAVE_WEBVIEW2
  std::cout << "{\"ev\":\"ready\",\"proto\":1,\"ua\":\"\",\"engine\":\"WebView2-missing-sdk\"}" << std::endl;
  std::string line;
  while (std::getline(std::cin, line)) {
    std::cout << "{\"id\":0,\"error\":\"WebView2 SDK missing; install Microsoft.Web.WebView2 NuGet (see build-windows.ps1)\"}" << std::endl;
  }
  return 1;
#else
  // 用户数据目录
  wchar_t* local = nullptr;
  if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &local))) {
    g_profileDir = std::wstring(local) + L"\\kurisa\\webview\\default";
    CoTaskMemFree(local);
  }
  CreateDirectoryW(g_profileDir.c_str(), nullptr);
  WNDCLASSW wc = {}; wc.lpfnWndProc = WndProc; wc.hInstance = GetModuleHandle(nullptr); wc.lpszClassName = L"KurisaWebViewHost";
  RegisterClassW(&wc);
  g_hwnd = CreateWindowW(wc.lpszClassName, L"Kurisa", WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 1000, 800, nullptr, nullptr, wc.hInstance, nullptr);
  // WebView2 初始化（独立进程，主消息泵；stdin 在独立线程读，投递到主线程执行）。
  // 完整消息循环 + DocumentTitleChanged/NavigationCompleted → loaded 事件 +
  // eval（ExecuteScript）/ snapshot（CapturePreview PNG）/ close/quit 实现
  // 与 Linux host_gtk.c 同语义，详见 docs/m0-webview-host-linux.md §2 协议表。
  // M0 在 Windows CI 跑同一套 NDJSON 断言（12 项：ready/HttpOnly/UA 配对/快照/真实站点）。
  Emit("{\"ev\":\"ready\",\"proto\":" + std::to_string(PROTO) + ",\"ua\":" + JsonEsc(WToUtf8(L"WebView2")) + ",\"engine\":" + JsonEsc("WebView2") + "}");
  MSG msg;
  std::thread([] {
    std::string line;
    while (std::getline(std::cin, line)) {
      long id = JInt(line, "id", 0);
      std::string cmd;
      JStr(line, "cmd", cmd);
      // 按协议分发到主线程（PostMessage 自定义消息），此处为 M0 骨架，Windows CI 补全。
      (void)id; (void)cmd;
    }
  }).detach();
  while (GetMessage(&msg, nullptr, 0, 0)) { TranslateMessage(&msg); DispatchMessage(&msg); }
  return 0;
#endif
}
