package data.localimport

/** 最小单页 PDF（文本 Hello；xref 偏移实时计算，pdfium 可正常打开）。 */
object MinimalPdf {
    fun onePage(text: String = "Hello PDF"): ByteArray {
        val out = ArrayList<Byte>()
        fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)
        val offsets = mutableListOf<Int>()
        out += bytes("%PDF-1.4\n").toList()
        fun obj(body: String) {
            offsets += out.size
            out += bytes("${offsets.size} 0 obj\n$body\nendobj\n").toList()
        }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
                "/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        )
        val stream = "BT /F1 24 Tf 50 150 Td ($text) Tj ET"
        obj("<< /Length ${stream.length} >>\nstream\n$stream\nendstream")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        val xref = out.size
        out += bytes("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n").toList()
        offsets.forEach { out += bytes("%010d 00000 n \n".format(it)).toList() }
        out += bytes("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF").toList()
        return out.toByteArray()
    }
}
