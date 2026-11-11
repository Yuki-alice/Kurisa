/**
 * Kurisa 原生扩展 v4 · 类型骨架（DRAFT，未实现）。
 *
 * - 定位：ADR-011 的类型部分；只增补，不改 source.d.ts v2/v3 任一冻结语义。
 * - 用法：import type 复用 v2 基类，本文件只声明 v4 增量。
 * - 约束：凡标 TODO(ADR-011) 的均为类型先行、运行时未实现；
 *   引擎（describe/Runtime/Convert/Html）零改动，Kotlin 侧接线在实现 ADR。
 * - 版本：源头 @apiVersion 4 + @capabilities 新位 opt-in；老源不受影响。
 */

import type {
  ComicSource,
  ContentKind,
  SearchNamespace,
  SourceCapability,
  SourceDescriptor,
  WorkDetails,
} from './source';

// ---------- v4 版本标记 ----------

/** v4 声明：源作者在头块写 @apiVersion 4，即表示使用本文件扩展集。 */
export type ApiVersion4 = 4;

/** v4 新增能力位（名字待 ADR 评审锁定，宿主门禁在实现 ADR 接线）。 */
// TODO(ADR-011): 评审锁定三位名字后同步 index-schema 与 ContractPolicy。
export type SourceCapabilityV4 =
  | SourceCapability
  | 'novelChapter'
  | 'htmlHelper'
  | 'cryptoAes';

/** v4 描述符增量：只加存在位，不加函数对象（沿 describe 只读字段原则）。 */
export interface SourceDescriptorV4 extends SourceDescriptor {
  /** 小说双位：缺一半的实现 ADR 要求报 CONTRACT（对标 hasSearchLoad/Next）。 */
  // TODO(ADR-011): 引擎 describe() 未实现，先锁类型。
  hasNovelLoadInfo: boolean;
  hasNovelLoadChapter: boolean;
}

// ---------- A. 样板收敛 ----------

/** Html 三 helper（自动 dispose；M1 disposed 语义不变）。 */
export interface HtmlHelpers {
  /** 取列表并映射，内部 new HtmlDocument、query、dispose。 */
  // TODO(ADR-011): 未实现。
  list<T>(html: string, css: string, map: (el: HtmlElementV4, index: number) => T): T[];
  /** 取首个元素文本，缺省空串。 */
  // TODO(ADR-011): 未实现。
  text(html: string, css: string): string;
  /** 取首个元素属性，缺省 null。 */
  // TODO(ADR-011): 未实现。
  attr(html: string, css: string, name: string): string | null;
}

/** v4 元素视图：复用 v2 HtmlElement 形态，名字只为 helper 签名可读。 */
export type HtmlElementV4 = import('./source').HtmlElement;

export declare const Html: HtmlHelpers;

/** 统一分页：源只调一个入口，宿主侧路由 search.load / search.loadNext。 */
export interface SearchNamespaceV4 extends SearchNamespace {
  // TODO(ADR-011): 未实现；语义 = hasSearchLoad ? load : loadNext。
  page?(keyword: string, options: string[], pageOrNext: number | string | null): Promise<import('./source').SearchPage>;
}

/** 错误 helper：替代抛字符串口口相传；宿主分类沿 plugin-engine 第 6 节子串规则。 */
export declare function throwLoginExpired(message?: string): never;
export declare function throwNotFound(message?: string): never;
export declare function throwRateLimited(message?: string): never;

// ---------- B. 小说一等公民 ----------

/** v4 小说章节：format 必透出（B：Bridge 现丢 format，v4 类型先锁）。 */
export interface NovelChapterV4 {
  text?: string;
  content?: string;
  /** html 进 clean，plain 原样，markdown 预留 M4。 */
  format: 'html' | 'markdown' | 'plain';
}

/** 正文清洗选项：文图混排 keepImages=true 走 onImageResolve 全链。 */
export interface NovelCleanOptions {
  keepImages?: boolean;
}

/** 正文清洗入口（NovelHost.stripHtml 的 v4 形态）。 */
// TODO(ADR-011): 未实现；正则实现已在 NovelHost，v4 只定签名。
export declare function cleanNovelHtml(html: string, options?: NovelCleanOptions): string;

/** 文内图片解析（v2 有声明、Kotlin 零实现；v4 补存在位与签名）。 */
export interface NovelNamespaceV4 {
  loadInfo(id: string): Promise<WorkDetails>;
  loadChapter(workId: string, chapterId: string): Promise<NovelChapterV4>;
  // TODO(ADR-011): 未实现。
  onImageResolve?(url: string): { url: string; headers?: Record<string, string> } | Promise<{ url: string; headers?: Record<string, string> }>;
}

// ---------- C. 密码学（转正或明拒，禁回空串） ----------

export interface ConvertV4 {
  /** GBK：转正（korlibs-encoding 同族，M0 已验）。 */
  // TODO(ADR-011): 未实现。
  encodeGbk(s: string): string;
  // TODO(ADR-011): 未实现。
  decodeGbk(s: string): string;
  /** SHA-1/512：纯算法转正。 */
  // TODO(ADR-011): 未实现。
  sha1(s: string): string;
  // TODO(ADR-011): 未实现。
  sha512(s: string): string;
  /**
   * AES/RSA：二选一（korlibs-crypto 转正，或显式 CONTRACT 拒绝）。
   * 在实现 ADR 定案前，类型保留、行为未定义——禁止按回空串理解。
   */
  // TODO(ADR-011): 未定（U1），未实现。
  decryptAesEcb(value: string, key: string): string;
  // TODO(ADR-011): 未定（U1），未实现。
  decryptAesCbc(value: string, key: string, iv: string): string;
  // TODO(ADR-011): 未定（U1），未实现。
  decryptRsa(value: string, key: string): string;
}

// ---------- v4 源基类增量（无状态约束） ----------

/**
 * v4 源：禁止在 init 挂载实例方法/域名配置；所有状态走 loadData/saveData。
 * 同 runtime 缓存期内 init 只跑一次（实现侧 U3），跨 invalidate 才重跑。
 */
export declare class KurisaSource extends ComicSource {
  apiVersion: ApiVersion4;
  kind: ContentKind[];
  novel?: NovelNamespaceV4;
  search?: SearchNamespaceV4;
}
