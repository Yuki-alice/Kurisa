# :data:download

能力来源：**② KMP 库**（执行引擎为 **Ketch**，M3 接入；本模块现为编排骨架）。

宿主只做优先级编排（用户手动 > 当前阅读预下载 > 后台批量）与任务持久化
（落 `:data:db` 的 `download_task` 表，经 `:data:repository`），
暂停/续传/限速由 Ketch 承担（见架构第 8 章）。
