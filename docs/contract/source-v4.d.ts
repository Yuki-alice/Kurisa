/**
 * Kurisa 原生扩展 v4 · 类型增量（E1/E2a 已落地，U1/U1b 待定）。
 *
 * - 定位：ADR-011 的类型部分；只增补，不改 source.d.ts v2/v3 任一冻结语义。
 * - 用法：import type 复用 v2 基类，本文件只声明 v4 增量。
 * - 已实现：能力位（novelChapter/htmlHelper/cryptoAes/video/audio，ContractPolicy.V4_CAPABILITIES）、
 *   描述符小说双位（describe 探测 + fromJson 解析）、Html.list/text/attr、
 *   throwLoginExpired/throwNotFound/throwRateLimited、cleanNovelHtml、
 *   Novel format 透出（BridgeChapterProvider 映射 TextFormat）、AES-ECB/CBC/CFB/OFB、
 *   视频/音频类型与能力扩展点（渲染 deferred，见 D4）。
 * - 未实现：RSA/GBK 转正（U1/U1b，strictV4 下显式 CONTRACT 拒绝，垫片仍回空串保兼容）；
 *   Stream 载荷与播放器接线（R 渲染，deferred）。
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

/**
 * v4 内容类型扩展（source.d.ts 冻结 manga|novel 不动，此处增补 video|audio）。
 * 宿主解析已支持（ContentKind.of）；仓库索引暂不接纳视频/音频（fail-closed）；
 * 运行时调用侧显式 CONTRACT（渲染 deferred，见 ADR-011 D4）。
 */
export type ContentKindV4 = ContentKind | 'video' | 'audio';

/** v4 新增能力位（已锁定，ContractPolicy.V4_CAPABILITIES 与 index-schema 已同步）。 */
export type SourceCapabilityV4 =
  | SourceCapability
  | 'novelChapter'
  | 'htmlHelper'
  | 'cryptoAes'
  | 'video'
  | 'audio';

/** v4 描述符增量：只加存在位，不加函数对象（沿 describe 只读字段原则）。 */
export interface SourceDescriptorV4 extends SourceDescriptor {
  /** 小说双位：缺一半的实现 ADR-011 要求报 CONTRACT（对标 hasSearchLoad/Next）。 */
  hasNovelLoadInfo: boolean;
  hasNovelLoadChapter: boolean;
}

// ---------- A. 样板收敛 ----------

/** Html 三 helper（自动 dispose；M1 disposed 语义不变）。 */
export interface HtmlHelpers {
  /** 取列表并映射，内部 new HtmlDocument、query、dispose。 */
  list<T>(html: string, css: string, map: (el: HtmlElementV4, index: number) => T): T[];
  /** 取首个元素文本，缺省空串。 */
  text(html: string, css: string): string;
  /** 取首个元素属性，缺省 null。 */
  attr(html: string, css: string, name: string): string | null;
}

/** v4 元素视图：复用 v2 HtmlElement 形态，名字只为 helper 签名可读。 */
export type HtmlElementV4 = import('./source').HtmlElement;

export declare const Html: HtmlHelpers;

/**
 * 统一分页：v4 源可只实现 page（页码传数字，游标传字符串/null），宿主有 page 时优先调用；
 * 源内也可委托 load/loadNext。缺 load/loadNext 时宿主不再报错，改走 page。
 */
export interface SearchNamespaceV4 extends SearchNamespace {
  page?(keyword: string, options: string[], pageOrNext: number | string | null): Promise<import('./source').SearchPage>;
}

/** 错误 helper：替代抛字符串口口相传；宿主分类沿 plugin-engine 第 6 节子串规则。 */
export declare function throwLoginExpired(message?: string): never;
export declare function throwNotFound(message?: string): never;
export declare function throwRateLimited(message?: string): never;

// ---------- B. 小说一等公民 ----------

/** v4 小说章节：format 必透出（Bridge 已映射为 TextFormat.HTML/MARKDOWN/PLAIN）。 */
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

/** 正文清洗入口（NovelHost.stripHtml 的 v4 形态，已接 __host.novel.clean）。 */
export declare function cleanNovelHtml(html: string, options?: NovelCleanOptions): string;

/** 文内图片解析（通用 invoke("novel.onImageResolve") 可调，便利方法待 E3）。 */
export interface NovelNamespaceV4 {
  loadInfo(id: string): Promise<WorkDetails>;
  loadChapter(workId: string, chapterId: string): Promise<NovelChapterV4>;
  onImageResolve?(url: string): { url: string; headers?: Record<string, string> } | Promise<{ url: string; headers?: Record<string, string> }>;
}

// ---------- C. 密码学（转正或明拒，禁回空串） ----------

export interface ConvertV4 {
  /**
   * GBK：终策 deferred（korlibs-encoding 6.0.0 经核验仅 ASCII/Base64/Hex，无 GBK；
   * 自带码表/平台注入二选一待架构评审）。strictV4 显式 CONTRACT 拒绝；
   * 垫片（2/3）为保 34 源兼容仍回输入本身，不伪造转码。
   */
  encodeGbk(s: string): string;
  decodeGbk(s: string): string;
  /** SHA-1/512：已转正（korlibs-crypto，RFC/FIPS 向量锁定）。 */
  sha1(s: string): string;
  /** SHA-1/512：已转正（korlibs-crypto，RFC/FIPS 向量锁定）。 */
  sha512(s: string): string;
  /**
   * AES：已转正（korlibs-crypto，Base64 进出 + PKCS7）。
   * key 16/24/32 字节，iv 16 字节（CBC/CFB/OFB；ECB 忽略）；
   * 失败垫片回空串，strictV4 抛 CONTRACT。
   */
  decryptAesEcb(value: string, key: string): string;
  decryptAesCbc(value: string, key: string, iv: string): string;
  decryptAesCfb(value: string, key: string, iv: string): string;
  decryptAesOfb(value: string, key: string, iv: string): string;
  /**
   * RSA：终策 deferred（korlibs-crypto 6.0.0 经 jar 核验无 RSA 实现）。
   * strictV4 显式 CONTRACT 拒绝，垫片回空串保兼容。
   */
  decryptRsa(value: string, key: string): string;
}

// ---------- D. 流媒体预留（U6 字段冻结；渲染 deferred，见 ADR-011 D4） ----------

/** 与 Kotlin `StreamType` 同名同值；未知值宿主按 CONTRACT 拒绝。 */
export type StreamTypeV4 = 'hls' | 'mp4' | 'dash';

export interface StreamSubtitleV4 {
  language?: string;
  title: string;
  url: string;
}

/** 仅类型与归一化语义先行（危险头剥离同 ImageLoadConfig），不接播放器。 */
export interface StreamPlaybackV4 {
  url: string;
  streamType: StreamTypeV4;
  headers?: Record<string, string>;
  subtitles?: StreamSubtitleV4[];
  quality?: string | null;
}

// ---------- v4 源基类增量（无状态约束） ----------

/**
 * v4 源：禁止在 init 挂载实例方法/域名配置；所有状态走 loadData/saveData。
 * init 语义（U3 已决议）：单发每次求值重建全新 context，`init` 在本次上下文重跑；
 * v4 `init` 须无网络副作用（仅 KV 预热），跨调用状态一律经 loadData/saveData 落盘。
 */
export declare class KurisaSource extends ComicSource {
  apiVersion: ApiVersion4;
  kind: ContentKind[];
  novel?: NovelNamespaceV4;
  search?: SearchNamespaceV4;
}
