# :data:cache

能力来源：**② KMP 库**（内存 LRU 用 **cache4k**，M3 接入；本模块现为配额语义骨架）。

字节预算（架构 6.2）：默认 Android/Desktop 128 MB，iOS 64 MB；
LRU 淘汰并显式回收解码位图。跨源请求去重（Store 5）按需引入。
