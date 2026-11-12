# 独立源仓库规范（DRAFT，未评审）

> 独立仓库，与主仓分离。主仓零预置（C1），本文件只定仓库侧面的布局与流程。
> 相关：ADR-011 D3、M2 SourceInstaller.plan/confirm、docs/contract/index-schema.json。
> 仓库已建：https://github.com/Yuki-alice/Kurisa-Sources（public，骨架已推）。新源许可证待定（MIT 确认？）。

## 1. 布局

- index.json：源索引（数组条目，见第 2 节）。
- scripts：<key>.js（一源一文件，文件名即 key，点转下划线）。
- signatures：<key>.sig（ed25519 分离签名，消息体见第 3 节）。
- keys：当前公钥与轮换历史（只读，轮换需双签过渡期）。
- docs：写源指南（S4 迁移小抄原文）。

## 2. index.json 条目

- key（反向域名，全局唯一）/ name / version（semver）/ apiVersion（2/3 老垫片，4 新引擎）。
- kind（manga/novel 数组）/ capabilities（能力位）/ domains（白名单，安装时用户可见）。
- sha256（脚本字节哈希）/ minApp（最低宿主版本）。
- 无头老源字段：allowUnheaded（缺头块时 domains 由索引声明，二次确认）。

## 3. 签名

- 沿 M2 链路：消息 = key|version|sha256 的 UTF-8 字节，ed25519 签名。
- 客户端按 SourceInstaller.plan/confirm 验签；无签名条目标未验证并要求二次确认。
- 轮换：新老双签 1 个版本，老 key 吊销进 keys/revoked。

## 4. CI 门（合并不通过不发布）

- d.ts 校验：tsc --noEmit，对照 source.d.ts（v2/3）或 source-v4.d.ts（v4）。
- 静态扫描：复用 SourceStaticScan 规则 + v4 新全局名白名单。
- 兼容矩阵：v2/3 跑垫片矩阵（T1 门禁），v4 跑新矩阵（T3，含小说四形状 + init 计数）。
- 许可证：新源只收 MIT（干净实现）；GPL 脚本禁止入库（refs 只读链路保留给本地验证）。
- 主仓扫描：Kurisa 主仓库 CI 拒绝任何站点 URL 与解析逻辑（C1）。

## 5. 发布流程

- PR 加源或升版 → CI 全绿 → 维护者签名 → index.json 更新 → 客户端下次同步可见。
- 回滚：index 保留上一版条目，客户端 SourceRegistry.rollback 可退回。

## 6. 日落

- 垫片删除阈值（U5）：新源覆盖常用站点且稳定两个版本后，删 2/3 路由，index 标 minApp。

## 7. 未决

- Q-ware1：已落定（Yuki-alice/Kurisa-Sources，public）。
- Q-ware2：新源许可证（建议 MIT，待确认）。
- Q-ware3：维护者签名密钥保管方式（M7 安全评审前定）。
