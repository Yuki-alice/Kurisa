/**
 * Kurisa 源契约 · TypeScript 定义（M1 冻结版 v1）。
 * 源作者用此文件获得编辑器提示；源仓库 CI 用它做静态校验。
 * 对齐 Venera `ComicSource` 形状，新增 `kind` / `apiVersion` / `novel` 命名空间。
 * 运行时以 `docs/architecture.md` 第 4 章为准，本文件只做形态声明。
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
}

export interface Chapter {
  id: string;
  title: string;
  ordinal: number;
}

export interface WorkDetails {
  work: Work;
  chapters: Chapter[];
}

// ---------- 搜索 ----------

export interface SearchOption {
  label: string;
  value: string;
}

export interface SearchPage {
  works: Work[];
  maxPage: number;
}

export interface SearchNamespace {
  /** options 缺省时宿主按 optionList 首项默认值生成数组传入（传 null 会崩，见 M0 缺口 #3） */
  load(keyword: string, options: string[], page: number): Promise<SearchPage>;
  optionList?: Array<{ options: string[] }>;
}

// ---------- 漫画 / 小说 ----------

export interface ComicNamespace {
  loadInfo(id: string): Promise<WorkDetails>;
  /** 返回 { images: string[] }，宿主转 ChapterPayload.Images */
  loadEp(comicId: string, epId: string): Promise<{ images: string[] }>;
}

export interface NovelNamespace {
  loadInfo(id: string): Promise<WorkDetails>;
  loadChapter(
    workId: string,
    chapterId: string
  ): Promise<{ text: string; format: 'html' | 'markdown' | 'plain' }>;
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
  domains: string[];
  search?: SearchNamespace;
  comic?: ComicNamespace;
  novel?: NovelNamespace;
  account?: AccountNamespace;

  /** 源私有 KV（同步桥，宿主落 source_kv 表） */
  loadData(key: string): string | null;
  saveData(key: string, value: string): void;
  init?(): Promise<void>;
}

// ---------- Host API 全局 ----------

export interface HttpResponse {
  status: number;
  body: string;
}

export declare const Network: {
  get(url: string, headers?: Record<string, string>): Promise<HttpResponse>;
  post(url: string, headers?: Record<string, string>, data?: string): Promise<HttpResponse>;
  getCookies(url: string): Array<{ name: string; value: string; domain: string }>;
  setCookies(url: string, list: Array<{ name: string; value: string }>): boolean;
  deleteCookies(url: string): boolean;
};

export declare function fetch(
  url: string,
  options?: { method?: string; headers?: Record<string, string>; body?: string }
): Promise<{
  ok: boolean;
  status: number;
  url: string;
  text(): Promise<string>;
  json(): Promise<unknown>;
}>;

export declare class HtmlDocument {
  constructor(html: string);
  querySelector(sel: string): HtmlElement | null;
  querySelectorAll(sel: string): HtmlElement[];
  dispose(): void;
}

export declare class HtmlElement {
  text: string;
  innerHtml: string;
  attributes: Record<string, string>;
  children: HtmlElement[];
}

export declare const APP: { locale: string; version: string; platform: string };
export declare const console: { log(...args: unknown[]): void };
