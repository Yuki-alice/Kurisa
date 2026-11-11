/**
 * Kurisa 源契约 · TypeScript 定义（M1 冻结版 v1 的向后兼容扩展）。
 * 源作者用此文件获得编辑器提示；源仓库 CI 用它做静态校验。
 * 对齐 Venera `ComicSource` 形状，新增 `kind` / `apiVersion` / `novel` 命名空间。
 * 运行时以 `docs/architecture.md` 第 4 章与 `docs/plugin-engine.md` 为准，
 * 本文件只做形态声明。
 *
 * 版本策略：`apiVersion = 2` 为冻结基线；`apiVersion = 3` 声明使用扩展能力集
 * （settings / 错误语义 / onImageLoad / explore / category / 游标分页）。
 * v2 源继续可用，宿主按 `@capabilities` 与运行时描述符探测结果降级。
 */

// ---------- 基础类型 ----------

export type ContentKind = 'manga' | 'novel';

export interface Work {
  id: string;
  title: string;
  subtitle?: string;
  cover?: string;
  tags?: string[];
  description?: string;
  language?: string;
  kind: ContentKind;
  /** Venera 兼容别名：宿主同时接受 `works` 与 `comics` 作为列表键。 */
  stars?: number;
}

export interface Chapter {
  id: string;
  title: string;
  ordinal: number;
}

export interface WorkDetails {
  work: Work;
  chapters: Chapter[] | Record<string, string | { title: string }> | Map<string, string>;
}

// ---------- 能力位（@capabilities 与运行时描述符共用） ----------

export type SourceCapability =
  | 'search' | 'explore' | 'category' | 'comic' | 'novel'
  | 'account' | 'favorites' | 'settings' | 'translation' | 'imageLoad';

/** 宿主把 JS 抛出的字符串按子串分类；见 plugin-engine.md §6。 */
export type SourceErrorMarker =
  | 'Login expired' | 'Rate limited' | 'Not found' | 'NotFound';

// ---------- 搜索 ----------

export interface SearchOption {
  label?: string;
  options: string[];
  /** select | multi-select | dropdown；缺省 select。 */
  type?: string;
  default?: string | null;
}

export interface SearchPage {
  /** 新名；宿主同时接受 Venera 的 `comics`。 */
  works?: Work[];
  comics?: Work[];
  maxPage?: number;
  /** 游标分页：存在 `loadNext` 时以此代替 page。 */
  next?: string | null;
}

export interface SearchNamespace {
  /** options 缺省时宿主按 optionList 首项默认值生成数组传入（传 null 会崩，见 M0 缺口 #3）。 */
  load?(keyword: string, options: string[], page: number): Promise<SearchPage>;
  /** `load` 缺失时宿主改调游标分页（Venera 语义）。 */
  loadNext?(keyword: string, options: string[], next: string | null): Promise<SearchPage>;
  optionList?: SearchOption[];
  enableTagsSuggestions?: boolean;
}

// ---------- 发现页 / 分类（可选能力） ----------

export interface ExplorePageDecl {
  title: string;
  type: 'singlePageWithMultiPart' | 'multiPartPage' | 'multiPageComicList' | 'mixed';
  load?(page: number): Promise<unknown>;
  loadNext?(next: string | null): Promise<{ comics: Work[]; next?: string | null }>;
}

export interface CategoryNamespace {
  title: string;
  parts?: unknown[];
  enableRankingPage?: boolean;
}

export interface CategoryComicsNamespace {
  load(category: string, param: string, options: string[], page: number): Promise<SearchPage>;
  optionList?: SearchOption[];
}

// ---------- 漫画 / 小说 ----------

/** `onImageLoad` 返回值；宿主的 UA / Cookie 注入不可被源覆盖。 */
export interface ImageLoadingConfig {
  url?: string;
  method?: string;
  headers?: Record<string, string>;
  referer?: string;
  data?: string;
  /** 失败重试次数（不含首次，宿主有界到 0..3）。 */
  maxRetries?: number;
  /** 函数字段（onResponse / modifyImage / onLoadFailed）当前宿主忽略。 */
  onResponse?: unknown;
  modifyImage?: unknown;
  onLoadFailed?: unknown;
}

export interface ComicNamespace {
  loadInfo(id: string): Promise<WorkDetails>;
  /** 返回 { images: string[] }，宿主转 ChapterPayload.Images */
  loadEp(comicId: string, epId: string): Promise<{ images: string[] }>;
  /** 防盗链配置；可同步或异步。未声明 imageLoad 能力时宿主不调用。 */
  onImageLoad?(url: string, comicId: string, epId: string): ImageLoadingConfig | Promise<ImageLoadingConfig>;
  onThumbnailLoad?(url: string): ImageLoadingConfig;
  idMatch?: RegExp;
  link?: { domains: string[]; linkToId(url: string): string | null };
}

export interface NovelNamespace {
  loadInfo(id: string): Promise<WorkDetails>;
  loadChapter(
    workId: string,
    chapterId: string
  ): Promise<{ text?: string; content?: string; format?: 'html' | 'markdown' | 'plain' }>;
  onImageResolve?(url: string): { url: string; headers?: Record<string, string> };
}

// ---------- 账号 ----------

export interface LoginWithWebview {
  url: string;
}

export interface LoginWithCookies {
  fields: string[];
}

export interface AccountNamespace {
  loginWithWebview?: LoginWithWebview;
  loginWithCookies?: LoginWithCookies;
  login?(): Promise<void>;
  logout?(): Promise<void>;
}

// ---------- 声明式源设置 ----------

export type SettingType = 'select' | 'switch' | 'input' | 'callback';

export interface SettingOption {
  value: string;
  text?: string;
}

export interface SourceSetting {
  title: string;
  type: SettingType;
  /** select 专用：对象数组（value 段被存储，不是显示文本）。 */
  options?: SettingOption[];
  /** 未设置时的回退值；switch 为 boolean，宿主统一按字符串返回。 */
  default?: string | boolean;
  /** input 专用：正则字符串。 */
  validator?: string | null;
  /** callback 专用。 */
  buttonText?: string;
  callback?(): void | Promise<unknown>;
}

export type SourceSettings = Record<string, SourceSetting>;
export type SourceTranslation = Record<string, Record<string, string>>;

/** `describe()` 的宿主侧输出；所有能力位为「是否存在」而非函数对象。 */
export interface SourceDescriptor {
  key: string;
  name: string;
  version: string;
  minAppVersion: string;
  apiVersion: number;
  kind: ContentKind[];
  domains: string[];
  capabilities: SourceCapability[];
  settings: Record<string, Omit<SourceSetting, 'callback'>>;
  translationLocales: string[];
  hasInit: boolean;
  hasImageLoad: boolean;
  hasSearchNext: boolean;
}

// ---------- 源基类 ----------

export declare class ComicSource {
  name: string;
  /** 全局唯一，反向域名 */
  key: string;
  version: string;
  minAppVersion: string;
  apiVersion: number;
  kind: ContentKind[];
  /** 更新地址 */
  url: string;
  /** 网络白名单（运行时由宿主强制，脚本内改值无效） */
  domains: string[];
  capabilities?: SourceCapability[];
  search?: SearchNamespace;
  explore?: ExplorePageDecl[];
  category?: CategoryNamespace;
  categoryComics?: CategoryComicsNamespace;
  comic?: ComicNamespace;
  novel?: NovelNamespace;
  account?: AccountNamespace;
  favorites?: object;
  settings?: SourceSettings;
  translation?: SourceTranslation;

  /** 源私有 KV（同步桥，宿主落 source_kv 表） */
  loadData(key: string): string | null;
  saveData(key: string, value: unknown): void;
  deleteData(key: string): void;
  /** 声明式设置：用户值 → default → null（宿主同步桥，见 plugin-engine.md §5）。 */
  loadSetting(key: string): string | null;
  /** 是否已登录（account 能力存在且宿主有会话）。 */
  readonly isLogged: boolean;
  /** 按 APP.locale 查 translation，未命中返回 key 本身。 */
  translate(key: string): string;
  init?(): Promise<void> | void;
  static sources: Record<string, ComicSource>;
}

// ---------- Host API 全局 ----------

export interface HttpResponse {
  status: number;
  body: string;
  headers?: Record<string, string>;
}

export interface Cookie {
  name: string;
  value: string;
  domain?: string;
}

export declare const Network: {
  get(url: string, headers?: Record<string, string>): Promise<HttpResponse>;
  post(url: string, headers?: Record<string, string>, data?: string): Promise<HttpResponse>;
  /** 以下方法在当前宿主版本返回 status 0 / UNSUPPORTED_METHOD（安全出口仅开放 GET/POST）。 */
  put(url: string, headers?: Record<string, string>, data?: string): Promise<HttpResponse>;
  patch(url: string, headers?: Record<string, string>, data?: string): Promise<HttpResponse>;
  delete(url: string, headers?: Record<string, string>): Promise<HttpResponse>;
  sendRequest(method: string, url: string, headers?: Record<string, string>, data?: string): Promise<HttpResponse>;
  getCookies(url: string): Cookie[];
  setCookies(url: string, list: Cookie[]): boolean;
  deleteCookies(url: string): boolean;
};

export declare function fetch(
  url: string,
  options?: { method?: string; headers?: Record<string, string>; body?: string }
): Promise<{
  ok: boolean;
  status: number;
  url: string;
  headers: Record<string, string>;
  text(): Promise<string>;
  json(): Promise<unknown>;
}>;

export declare function setTimeout(callback: () => void, delay: number): Promise<void>;

export declare class HtmlDocument {
  constructor(html: string);
  querySelector(sel: string): HtmlElement | null;
  querySelectorAll(sel: string): HtmlElement[];
  getElementById(id: string): HtmlElement | null;
  dispose(): void;
}

export declare class HtmlElement {
  text: string;
  innerHtml: string;
  innerHTML: string;
  attributes: Record<string, string>;
  /** 宿主扁平化输出：`attr:<name>`；标准属性也以 `attributes` 暴露。 */
  [key: `attr:${string}`]: string;
  children: HtmlElement[];
  id: string | null;
  classNames: string[];
  localName: string;
}

export declare const Convert: {
  md5(s: string): string;
  sha256(s: string): string;
  encodeBase64(s: string): string;
  decodeBase64(s: string): string;
  encodeUtf8(s: string): string;
  decodeUtf8(s: string): string;
  hexEncode(s: string): string;
};

export declare const Utils: {
  createUuid(): string;
  randomInt(min: number, max: number): number;
  randomDouble(min: number, max: number): number;
};

export declare const APP: { locale: string; version: string; platform: string };
export declare const console: {
  log(...args: unknown[]): void;
  error(...args: unknown[]): void;
  warn(...args: unknown[]): void;
};
