# :data:localimport

能力来源：**① 纯 Kotlin + ② KMP 库**（Kompress / XmlUtil / korlibs / Ksoup / PDF）。

格式识别（魔数+扩展名）已落地；容器解析（EPUB/CBZ/ZIP→Kompress、
OPF/NCX/XHTML→XmlUtil、TXT 编码探测→korlibs、PDF→ComposePdfReader）
随 M4 落地（见架构 6.3/6.4）。导入后与在线作品同构（`local_book` 表）。
