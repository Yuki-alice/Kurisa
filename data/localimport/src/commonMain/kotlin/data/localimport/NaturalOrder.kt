package data.localimport

/**
 * 自然排序（对齐 Komikku natsort `CaseInsensitiveSimpleNaturalComparator`，
 * 用于章节/漫画目录名排序："ch2" < "ch10"，大小写不敏感）。
 * 纯 Kotlin，三端一致。
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val partsA = split(a)
        val partsB = split(b)
        val common = minOf(partsA.size, partsB.size)
        for (i in 0 until common) {
            val pa = partsA[i]
            val pb = partsB[i]
            val c = if (pa.isNumber && pb.isNumber) {
                compareNumbers(pa.text, pb.text)
            } else {
                pa.text.compareTo(pb.text, ignoreCase = true)
            }
            if (c != 0) return c
        }
        return partsA.size.compareTo(partsB.size)
    }

    private data class Part(val text: String, val isNumber: Boolean)

    private fun split(s: String): List<Part> {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<Part>()
        var start = 0
        var numeric = s[0].isDigit()
        for (i in 1..s.length) {
            val boundary = i == s.length || s[i].isDigit() != numeric
            if (boundary) {
                out.add(Part(s.substring(start, i), numeric))
                if (i < s.length) {
                    start = i
                    numeric = s[i].isDigit()
                }
            }
        }
        return out
    }

    private fun compareNumbers(a: String, b: String): Int {
        // 去前导零后按长度（数值大小）比，再按字典序（等值但零数量不同时稳定）。
        val ta = a.trimStart('0')
        val tb = b.trimStart('0')
        val c = ta.length.compareTo(tb.length)
        if (c != 0) return c
        val d = ta.compareTo(tb)
        if (d != 0) return d
        return a.length.compareTo(b.length)
    }
}
