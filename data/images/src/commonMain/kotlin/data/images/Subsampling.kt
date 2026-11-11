package data.images

/**
 * 分块预算数学（“超大图不 OOM”的可测部分，架构 R4）。
 *
 * 约定：采样率为 2 的幂；采样后仍覆盖视口（清晰度底线）；
 * 分块边长不超过 maxTexture（硬上限，超限 GPU 直接黑屏/崩溃）；
 * 预估按 ARGB_8888（4B/px，最坏情形）计算。
 *
 * 渲染侧（ZoomImage Subsampling）用同一套数选择分块；
 * 本对象是纯函数，可三端单测锁定（m0-spike 的 ZoomRenderTest 精神续作）。
 */
object SubsamplingCalculator {

    data class Plan(
        /** 采样率（2 的幂；1 = 全分辨率）。 */
        val sampleSize: Int,
        val decodedWidth: Int,
        val decodedHeight: Int,
        val tileCols: Int,
        val tileRows: Int,
        /** 预估内存（ARGB 最坏情形）。 */
        val estimatedBytes: Long,
        val fitsBudget: Boolean,
    )

    fun plan(
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        maxTexture: Int = 4096,
        budgetBytes: Long = 64 * 1024 * 1024,
    ): Plan {
        require(imageWidth > 0 && imageHeight > 0)
        require(viewportWidth > 0 && viewportHeight > 0)
        require(maxTexture > 0 && budgetBytes > 0)
        var sample = 1
        // 放大采样直到再翻倍就盖不住视口（封顶 8，避免糊成马赛克）。
        while (sample < 8 &&
            imageWidth / (sample * 2) >= viewportWidth &&
            imageHeight / (sample * 2) >= viewportHeight
        ) {
            sample *= 2
        }
        val decodedW = (imageWidth + sample - 1) / sample
        val decodedH = (imageHeight + sample - 1) / sample
        val cols = (decodedW + maxTexture - 1) / maxTexture
        val rows = (decodedH + maxTexture - 1) / maxTexture
        val estimated = decodedW.toLong() * decodedH * 4
        return Plan(
            sampleSize = sample,
            decodedWidth = decodedW,
            decodedHeight = decodedH,
            tileCols = cols.coerceAtLeast(1),
            tileRows = rows.coerceAtLeast(1),
            estimatedBytes = estimated,
            fitsBudget = estimated <= budgetBytes,
        )
    }
}
