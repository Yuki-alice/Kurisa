package source.registry

import korlibs.crypto.sha512

/**
 * Ed25519（RFC 8032）。
 * 用途：仓库 `index.json` 的 ed25519 签名校验（架构 4.8），三端同一份代码。
 *
 * 结构：
 * - 有限域/群运算：10-limb radix-2^25.5（ref10 式），SHA-512 用 korlibs-crypto
 *  （M0 已验证同族库三端；手写哈希已删，避免常量表风险）；
 * - 标量域 mod L：朴素 bignum（32-bit limbs + 二进制长除法），变时但显然正确；
 * - 标量乘：double-and-add ladder（变时；签名校验为离线低频操作，可接受）。
 * - `d` / `sqrt(-1)` 由域运算运行时推导，不硬编码常量。
 */
internal object Ed25519 {
    // ---- field element (radix 2^25.5, 10 limbs) ----

    private fun feAdd(h: LongArray, f: LongArray, g: LongArray) {
        for (i in 0 until 10) h[i] = f[i] + g[i]
    }

    private fun feSub(h: LongArray, f: LongArray, g: LongArray) {
        for (i in 0 until 10) h[i] = f[i] - g[i]
    }

    private fun feCopy(h: LongArray, f: LongArray) {
        for (i in 0 until 10) h[i] = f[i]
    }

    private fun feNeg(h: LongArray, f: LongArray) {
        for (i in 0 until 10) h[i] = -f[i]
    }

    /**
     * 完全归一化：limbs 交替落入 [0,2^26)/[0,2^25)，2^255 按 19 折叠。
     * 无 bias 的 floor 进位（幂等，已规约输入不再变化）；扫 3 遍必收敛
     * （第 1 遍清主体，第 2 遍清 19·carry，第 3 遍兜底）。
     * 旧版 feCarry 的 +2^25 bias 对已规约值非幂等，会留下负 limbs，
     * 而 tobytes 手工链假设非负——两者叠加即 M2 的编解码 bug。
     */
    private fun feNormalize(h: LongArray) {
        repeat(4) {
            var carry = 0L
            for (i in 0 until 10) {
                val w = if (i % 2 == 0) 26 else 25
                val v = h[i] + carry
                val c = if (v >= 0) v shr w else -((-v + (1L shl w) - 1) shr w)
                h[i] = v - (c shl w)
                carry = c
            }
            h[0] += carry * 19
            if (carry == 0L) return
        }
    }

    private fun feCarry(h: LongArray) = feNormalize(h)

    private fun feMul(h: LongArray, f: LongArray, g: LongArray) {
        val f0 = f[0]; val f1 = f[1]; val f2 = f[2]; val f3 = f[3]; val f4 = f[4]
        val f5 = f[5]; val f6 = f[6]; val f7 = f[7]; val f8 = f[8]; val f9 = f[9]
        val g0 = g[0]; val g1 = g[1]; val g2 = g[2]; val g3 = g[3]; val g4 = g[4]
        val g5 = g[5]; val g6 = g[6]; val g7 = g[7]; val g8 = g[8]; val g9 = g[9]
        val g1_19 = g1 * 19; val g2_19 = g2 * 19; val g3_19 = g3 * 19
        val g4_19 = g4 * 19; val g5_19 = g5 * 19; val g6_19 = g6 * 19
        val g7_19 = g7 * 19; val g8_19 = g8 * 19; val g9_19 = g9 * 19
        val f1_2 = f1 * 2; val f3_2 = f3 * 2; val f5_2 = f5 * 2; val f7_2 = f7 * 2; val f9_2 = f9 * 2
        h[0] = f0 * g0 + f1_2 * g9_19 + f2 * g8_19 + f3_2 * g7_19 + f4 * g6_19 + f5_2 * g5_19 + f6 * g4_19 + f7_2 * g3_19 + f8 * g2_19 + f9_2 * g1_19
        h[1] = f0 * g1 + f1 * g0 + f2 * g9_19 + f3 * g8_19 + f4 * g7_19 + f5 * g6_19 + f6 * g5_19 + f7 * g4_19 + f8 * g3_19 + f9 * g2_19
        h[2] = f0 * g2 + f1_2 * g1 + f2 * g0 + f3_2 * g9_19 + f4 * g8_19 + f5_2 * g7_19 + f6 * g6_19 + f7_2 * g5_19 + f8 * g4_19 + f9_2 * g3_19
        h[3] = f0 * g3 + f1 * g2 + f2 * g1 + f3 * g0 + f4 * g9_19 + f5 * g8_19 + f6 * g7_19 + f7 * g6_19 + f8 * g5_19 + f9 * g4_19
        h[4] = f0 * g4 + f1_2 * g3 + f2 * g2 + f3_2 * g1 + f4 * g0 + f5_2 * g9_19 + f6 * g8_19 + f7_2 * g7_19 + f8 * g6_19 + f9_2 * g5_19
        h[5] = f0 * g5 + f1 * g4 + f2 * g3 + f3 * g2 + f4 * g1 + f5 * g0 + f6 * g9_19 + f7 * g8_19 + f8 * g7_19 + f9 * g6_19
        h[6] = f0 * g6 + f1_2 * g5 + f2 * g4 + f3_2 * g3 + f4 * g2 + f5_2 * g1 + f6 * g0 + f7_2 * g9_19 + f8 * g8_19 + f9_2 * g7_19
        h[7] = f0 * g7 + f1 * g6 + f2 * g5 + f3 * g4 + f4 * g3 + f5 * g2 + f6 * g1 + f7 * g0 + f8 * g9_19 + f9 * g8_19
        h[8] = f0 * g8 + f1_2 * g7 + f2 * g6 + f3_2 * g5 + f4 * g4 + f5_2 * g3 + f6 * g2 + f7_2 * g1 + f8 * g0 + f9_2 * g9_19
        h[9] = f0 * g9 + f1 * g8 + f2 * g7 + f3 * g6 + f4 * g5 + f5 * g4 + f6 * g3 + f7 * g2 + f8 * g1 + f9 * g0
        feCarry(h); feCarry(h)
    }

    private fun feSq(h: LongArray, f: LongArray) = feMul(h, f, f)

    private fun feInvert(out: LongArray, z: LongArray) {
        val t0 = LongArray(10); val t1 = LongArray(10)
        val t2 = LongArray(10); val t3 = LongArray(10)
        feSq(t0, z)
        feSq(t1, t0); feSq(t1, t1); feMul(t1, z, t1)
        feMul(t0, t0, t1)
        feSq(t2, t0); feMul(t1, t1, t2)
        feSq(t2, t1)
        for (i in 1 until 5) { feSq(t2, t2) }
        feMul(t1, t2, t1)
        feSq(t2, t1)
        for (i in 1 until 10) { feSq(t2, t2) }
        feMul(t2, t2, t1)
        feSq(t3, t2)
        for (i in 1 until 20) { feSq(t3, t3) }
        feMul(t2, t3, t2)
        feSq(t2, t2)
        for (i in 1 until 10) { feSq(t2, t2) }
        feMul(t1, t2, t1)
        feSq(t2, t1)
        for (i in 1 until 50) { feSq(t2, t2) }
        feMul(t2, t2, t1)
        feSq(t3, t2)
        for (i in 1 until 100) { feSq(t3, t3) }
        feMul(t2, t3, t2)
        feSq(t2, t2)
        for (i in 1 until 50) { feSq(t2, t2) }
        feMul(t1, t2, t1)
        feSq(t1, t1)
        for (i in 1 until 5) { feSq(t1, t1) }
        feMul(out, t1, t0)
    }

    private fun feTobytes(s: ByteArray, h: LongArray) {
        val q = LongArray(10)
        for (i in 0 until 10) q[i] = h[i]
        feNormalize(q) // limbs 已落位 [0,2^w)，值仍可能 ∈ [p, 2^255)
        // 条件减 p（值 < 2^255 < 2p，最多减一次；保证规范编码，p 编为全零）。
        // 缺此步则“值 ≡0 但整数 =p”被误判非零（M2 调试实录：d·121666+121665 明明 =p 却断言失败）。
        val P = longArrayOf(
            67108845L, 33554431L, 67108863L, 33554431L, 67108863L,
            33554431L, 67108863L, 33554431L, 67108863L, 33554431L,
        ) // p = 2^255-19 的 limbs（limb0 = 2^26-19，其余取满）
        val t = LongArray(10)
        var borrow = 0L
        for (i in 0 until 10) {
            val v = q[i] - P[i] - borrow
            if (v < 0) {
                val w = if (i % 2 == 0) 26 else 25
                t[i] = v + (1L shl w)
                borrow = 1
            } else {
                t[i] = v
                borrow = 0
            }
        }
        if (borrow == 0L) for (i in 0 until 10) q[i] = t[i]
        s[0] = ((q[0] shr 0) and 0xFF).toByte()
        s[1] = ((q[0] shr 8) and 0xFF).toByte()
        s[2] = ((q[0] shr 16) and 0xFF).toByte()
        s[3] = (((q[0] shr 24) or (q[1] shl 2)) and 0xFF).toByte()
        s[4] = ((q[1] shr 6) and 0xFF).toByte()
        s[5] = ((q[1] shr 14) and 0xFF).toByte()
        s[6] = (((q[1] shr 22) or (q[2] shl 3)) and 0xFF).toByte()
        s[7] = ((q[2] shr 5) and 0xFF).toByte()
        s[8] = ((q[2] shr 13) and 0xFF).toByte()
        s[9] = (((q[2] shr 21) or (q[3] shl 5)) and 0xFF).toByte()
        s[10] = ((q[3] shr 3) and 0xFF).toByte()
        s[11] = ((q[3] shr 11) and 0xFF).toByte()
        s[12] = (((q[3] shr 19) or (q[4] shl 6)) and 0xFF).toByte()
        s[13] = ((q[4] shr 2) and 0xFF).toByte()
        s[14] = ((q[4] shr 10) and 0xFF).toByte()
        s[15] = ((q[4] shr 18) and 0xFF).toByte()
        s[16] = ((q[5] shr 0) and 0xFF).toByte()
        s[17] = ((q[5] shr 8) and 0xFF).toByte()
        s[18] = ((q[5] shr 16) and 0xFF).toByte()
        s[19] = (((q[5] shr 24) or (q[6] shl 1)) and 0xFF).toByte()
        s[20] = ((q[6] shr 7) and 0xFF).toByte()
        s[21] = ((q[6] shr 15) and 0xFF).toByte()
        s[22] = (((q[6] shr 23) or (q[7] shl 3)) and 0xFF).toByte()
        s[23] = ((q[7] shr 5) and 0xFF).toByte()
        s[24] = ((q[7] shr 13) and 0xFF).toByte()
        s[25] = (((q[7] shr 21) or (q[8] shl 4)) and 0xFF).toByte()
        s[26] = ((q[8] shr 4) and 0xFF).toByte()
        s[27] = ((q[8] shr 12) and 0xFF).toByte()
        s[28] = (((q[8] shr 20) or (q[9] shl 6)) and 0xFF).toByte()
        s[29] = ((q[9] shr 2) and 0xFF).toByte()
        s[30] = ((q[9] shr 10) and 0xFF).toByte()
        s[31] = ((q[9] shr 18) and 0xFF).toByte()
    }

    /**
     * 小端 32B → 10 limbs（radix 2^25.5，宽度 26/25 交替：偶 26、奇 25）。
     * 后半段：h5=128-152(25) h6=153-178(26) h7=179-203(25) h8=204-229(26) h9=230-254(25)。
     * h9 掩去 bit255（点压缩体的符号位；本函数唯一调用方即点解码，符号另读）。
     * 与 feTobytes 逐字节互逆（M2 调试实录：后半段错一位曾导致往返/解码全败）。
     */
    private fun feFrombytes(h: LongArray, s: ByteArray) {
        fun u(i: Int): Long = s[i].toLong() and 0xFF
        h[0] = u(0) or (u(1) shl 8) or (u(2) shl 16) or ((u(3) and 0x03) shl 24)
        h[1] = (u(3) ushr 2) or (u(4) shl 6) or (u(5) shl 14) or ((u(6) and 0x07) shl 22)
        h[2] = (u(6) ushr 3) or (u(7) shl 5) or (u(8) shl 13) or ((u(9) and 0x1F) shl 21)
        h[3] = (u(9) ushr 5) or (u(10) shl 3) or (u(11) shl 11) or ((u(12) and 0x3F) shl 19)
        h[4] = (u(12) ushr 6) or (u(13) shl 2) or (u(14) shl 10) or (u(15) shl 18)
        h[5] = u(16) or (u(17) shl 8) or (u(18) shl 16) or ((u(19) and 0x01) shl 24)
        h[6] = (u(19) ushr 1) or (u(20) shl 7) or (u(21) shl 15) or ((u(22) and 0x07) shl 23)
        h[7] = (u(22) ushr 3) or (u(23) shl 5) or (u(24) shl 13) or ((u(25) and 0x0F) shl 21)
        h[8] = (u(25) ushr 4) or (u(26) shl 4) or (u(27) shl 12) or ((u(28) and 0x3F) shl 20)
        h[9] = (u(28) ushr 6) or (u(29) shl 2) or (u(30) shl 10) or ((u(31) and 0x7F) shl 18)
    }

    private fun feIsnegative(f: LongArray): Int {
        val s = ByteArray(32)
        feTobytes(s, f)
        return s[0].toInt() and 1
    }

    private fun feIsnonzero(f: LongArray): Int {
        val s = ByteArray(32)
        feTobytes(s, f)
        var b = 0
        for (i in 0 until 32) b = b or (s[i].toInt() and 0xFF)
        return ((b - 1) ushr 8) and 1 xor 1
    }

    /**
     * 通用模幂（左→右二进制平方乘，显然正确优先）。
     * 手工展开的加法链已删（转录易错，见 M2 构建记录）。
     */
    private fun fePow(out: LongArray, z: LongArray, exp: ByteArray) {
        val r = LongArray(10); r[0] = 1
        for (bit in 255 downTo 0) {
            val t = LongArray(10); feSq(t, r)
            for (i in 0 until 10) r[i] = t[i]
            if (bitSet(exp, bit)) {
                val m = LongArray(10); feMul(m, r, z)
                for (i in 0 until 10) r[i] = m[i]
            }
        }
        for (i in 0 until 10) out[i] = r[i]
    }

    /** 2^252-3 的 LE 字节（byte0=0xFD，bytes1..30=0xFF，byte31=0x0F）。 */
    private val EXP_22523 = ByteArray(32) { 0xFF.toByte() }.also {
        it[0] = 0xFD.toByte(); it[31] = 0x0F.toByte()
    }

    /** x^((p-5)/8)，即 x^(2^252-3)。 */
    private fun fePow22523(out: LongArray, z: LongArray) = fePow(out, z, EXP_22523)

    // ---- group (extended coordinates X,Y,Z,T) ----
    private class GeP3(val x: LongArray = LongArray(10), val y: LongArray = LongArray(10), val z: LongArray = LongArray(10), val t: LongArray = LongArray(10))
    private class GeCached(val ypx: LongArray = LongArray(10), val ymx: LongArray = LongArray(10), val z: LongArray = LongArray(10), val t2d: LongArray = LongArray(10))

    private val d = LongArray(10)
    private val d2 = LongArray(10)
    private val sqrtm1 = LongArray(10)
    private var inited = false
    private var dInitDone = false
    /** 基点（解码一次后缓存；ladder 只读，可共享）。 */
    private val baseP3 = GeP3()

    /**
     * 曲线常量运行时推导（不硬编码）：
     * - d = -121665/121666；
     * - sqrt(-1) = 2^((p-1)/4) = 2·(2^(2^252-3))^2。
     */
    private fun initConstants() {
        if (inited) return
        inited = true
        initField()
        // 基点解码一次（失败即抛， loud failure，不静默）。
        val sb = ByteArray(32)
        sb[0] = 0x58.toByte()
        for (i in 1 until 32) sb[i] = 0x66.toByte()
        check(geFrombytesNegateVartime(baseP3, sb) == 0) { "基点解码失败" }
    }

    /** 域常量推导（d/sqrtm1，无限、不抛；供诊断拆步）。 */
    internal fun initField() {
        if (dInitDone) return
        dInitDone = true
        val n = LongArray(10); n[0] = 121665; feNeg(n, n)
        val m = LongArray(10); m[0] = 121666
        val inv = LongArray(10); feInvert(inv, m)
        feMul(d, n, inv)
        feAdd(d2, d, d)
        val two = LongArray(10); two[0] = 2
        val e = LongArray(10); fePow22523(e, two)
        val e2 = LongArray(10); feMul(e2, e, e)
        feAdd(sqrtm1, e2, e2)
    }

    /**
     * Hisil 加法（扩展坐标，a=-1；Q 取缓存形），直接输出 P3。
     * 关键修正：旧版经 GeP1p1 中转再“转换”（X=X'T' 等），该转换要求 T'=Z'，
     * 而加法输出的 T' 一般 ≠Z'（I+B 得 T'=4XY、Z'=4），转换后 y 变成 1/X。
     * 实际上加法输出已满足 T=XY/Z（T3=E·H=X3Y3/Z3 恒成立），本身就是合法 P3，
     * 无需任何转换——直接写 GeP3 即可（M2 调试实录：Python 逐项确认 I+B 输出无误）。
     */
    private fun geAdd(p: GeP3, m: GeP3, n: GeCached) {
        val a1 = LongArray(10); feAdd(a1, m.y, m.x) // Y1+X1
        val b1 = LongArray(10); feSub(b1, m.y, m.x) // Y1-X1
        val a = LongArray(10); feMul(a, b1, n.ymx) // A = (Y1-X1)(Y2-X2)
        val b = LongArray(10); feMul(b, a1, n.ypx) // B = (Y1+X1)(Y2+X2)
        val c = LongArray(10); feMul(c, m.t, n.t2d) // C = T1·2d·T2
        val z2 = LongArray(10); feAdd(z2, n.z, n.z)
        val d = LongArray(10); feMul(d, m.z, z2) // D = 2·Z1·Z2
        val e = LongArray(10); feSub(e, b, a) // E = B-A
        val f = LongArray(10); feSub(f, d, c) // F = D-C
        val g = LongArray(10); feAdd(g, d, c) // G = D+C
        val h = LongArray(10); feAdd(h, b, a) // H = B+A
        feMul(p.x, e, f)
        feMul(p.y, g, h)
        feMul(p.z, f, g)
        feMul(p.t, e, h)
    }

    private fun geP3ToCached(r: GeCached, p: GeP3) {
        feAdd(r.ypx, p.y, p.x); feSub(r.ymx, p.y, p.x)
        feCopy(r.z, p.z); feMul(r.t2d, p.t, d2)
    }

    private fun geTobytes(s: ByteArray, h: GeP3) {
        val recip = LongArray(10); val x = LongArray(10); val y = LongArray(10)
        feInvert(recip, h.z)
        feMul(x, h.x, recip); feMul(y, h.y, recip)
        feTobytes(s, y)
        s[31] = (s[31].toInt() xor (feIsnegative(x) shl 7)).toByte()
    }

    /** 点解码（含 x 恢复）；ref10 ge_frombytes_negate_vartime 的忠实顺序。 */
    private fun geFrombytesNegateVartime(h: GeP3, s: ByteArray): Int {
        val u = LongArray(10); val v = LongArray(10)
        val v3 = LongArray(10); val vxx = LongArray(10); val check = LongArray(10)
        feFrombytes(h.y, s)
        val one = LongArray(10); one[0] = 1
        feCopy(h.z, one)
        feSq(u, h.y)
        feMul(v, u, d)
        feSub(u, u, h.z) // u = y^2-1
        feAdd(v, v, h.z) // v = dy^2+1
        feSq(v3, v)
        feMul(v3, v3, v) // v3 = v^3
        feSq(h.x, v3)
        feMul(h.x, h.x, v)
        feMul(h.x, h.x, u) // x = u*v^7
        fePow22523(h.x, h.x) // x = (u*v^7)^((p-5)/8)
        feMul(h.x, h.x, v3)
        feMul(h.x, h.x, u) // x = u*v^3*(u*v^7)^((p-5)/8)
        feSq(vxx, h.x)
        feMul(vxx, vxx, v)
        feSub(check, vxx, u) // v*x^2-u
        if (feIsnonzero(check) == 1) {
            // 首轮得 v·x²=-u（ζ=-1）时，正确恢复是 x'=±x·i（乘），不是加。
            // 旧版 feAdd 在此恒得曲线外点；凡落入此分支的公钥（如全 0x09 seed 者）必验签失败。
            feMul(h.x, h.x, sqrtm1)
            feMul(vxx, h.x, h.x)
            feMul(vxx, vxx, v)
            feSub(check, vxx, u)
            if (feIsnonzero(check) == 1) return -1
        }
        // x 奇偶必须等于压缩体符号位；不等则取反（旧版此处 ==/!= 写反，导致恒取反）。
        if (feIsnegative(h.x) != (s[31].toInt() shr 7 and 1)) {
            feNeg(h.x, h.x)
        }
        feMul(h.t, h.x, h.y)
        return 0
    }

    private fun identity(): GeP3 {
        val r = GeP3()
        r.y[0] = 1; r.z[0] = 1
        return r
    }

    /**
     * 专用倍点公式（Hisil 等，a=-1）。
     * 注意：绝不能用通用加法算倍点——加法公式在倍点/单位元处不完备，
     * 旧版 geDouble 经“自加”实现，遇单位元即归零（M2 调试实录：公钥恒为零）。
     */
    private fun geP3Dbl(r: GeP3, p: GeP3) {
        val a = LongArray(10); feSq(a, p.x) // A = X²
        val b = LongArray(10); feSq(b, p.y) // B = Y²
        val c = LongArray(10); feSq(c, p.z); feAdd(c, c, c) // C = 2Z²
        val dNegA = LongArray(10); feNeg(dNegA, a) // D = -A（a=-1）
        val e = LongArray(10)
        feAdd(e, p.x, p.y); feSq(e, e); feSub(e, e, a); feSub(e, e, b) // E
        val g = LongArray(10); feAdd(g, dNegA, b) // G
        val f = LongArray(10); feSub(f, g, c) // F
        val h = LongArray(10); feSub(h, dNegA, b) // H
        feMul(r.x, e, f)
        feMul(r.y, g, h)
        feMul(r.z, f, g)
        feMul(r.t, e, h)
    }

    private fun geDouble(p: GeP3): GeP3 {
        val o = GeP3()
        geP3Dbl(o, p)
        return o
    }

    private fun geAddFull(a: GeP3, b: GeP3): GeP3 {
        val c = GeCached()
        geP3ToCached(c, b)
        val o = GeP3()
        geAdd(o, a, c)
        return o
    }

    private fun geNeg(p: GeP3): GeP3 {
        val o = GeP3()
        feNeg(o.x, p.x); feCopy(o.y, p.y); feCopy(o.z, p.z); feNeg(o.t, p.t)
        return o
    }

    private fun bitSet(s: ByteArray, bit: Int): Boolean =
        ((s[bit shr 3].toInt() ushr (bit and 7)) and 1) == 1

    /** 通用标量乘（double-and-add，无 clamp；调用方决定是否 clamp）。 */
    private fun ladder(scalar32: ByteArray, p: GeP3): GeP3 {
        var r = identity()
        for (bit in 255 downTo 0) {
            r = geDouble(r)
            if (bitSet(scalar32, bit)) r = geAddFull(r, p)
        }
        return r
    }

    private fun clamp(k: ByteArray): ByteArray {
        val e = k.copyOf(32)
        e[0] = (e[0].toInt() and 248).toByte()
        e[31] = (e[31].toInt() and 127).toByte()
        e[31] = (e[31].toInt() or 64).toByte()
        return e
    }

    private fun basePoint(): GeP3 = baseP3

    // ---- scalar field mod L (朴素 bignum，显然正确优先) ----
    /** L = 2^252 + 27742317777372353535851937790883648493（RFC 8032），32-bit LE limbs。 */
    private val L_LIMBS = longArrayOf(
        0x5CF5D3EDL, 0x5812631AL, 0xA2F79CD6L, 0x14DEF9DEL,
        0L, 0L, 0L, 0x10000000L,
    )

    private fun limbsFromLeBytes(b: ByteArray, n: Int): LongArray {
        val l = LongArray(n)
        for (i in b.indices) {
            val k = i shr 2
            if (k < n) l[k] = l[k] or ((b[i].toLong() and 0xFF) shl ((i and 3) * 8))
        }
        return l
    }

    private fun limbsToLeBytes(l: LongArray, nbytes: Int): ByteArray {
        val o = ByteArray(nbytes)
        for (i in 0 until nbytes) o[i] = ((l[i shr 2] ushr ((i and 3) * 8)) and 0xFF).toByte()
        return o
    }

    private fun bitlen(a: LongArray): Int {
        for (i in a.size - 1 downTo 0) {
            val v = a[i] and 0xFFFFFFFFL
            if (v != 0L) return i * 32 + (64 - v.countLeadingZeroBits())
        }
        return 0
    }

    private fun geShifted(a: LongArray, b: LongArray, shift: Int): Boolean {
        val ws = shift shr 5; val bs = shift and 31
        for (i in a.size - 1 downTo 0) {
            var bv = 0L
            val j = i - ws
            if (bs == 0) {
                if (j in b.indices) bv = b[j]
            } else {
                if (j in b.indices) bv = bv or (b[j] shl bs)
                if (j - 1 in b.indices) bv = bv or (b[j - 1] ushr (32 - bs))
                bv = bv and 0xFFFFFFFFL
            }
            val av = a[i] and 0xFFFFFFFFL
            if (av != bv) return av > bv
        }
        return true
    }

    private fun subShifted(a: LongArray, b: LongArray, shift: Int) {
        val ws = shift shr 5; val bs = shift and 31
        var borrow = 0L
        for (i in a.indices) {
            var bv = 0L
            val j = i - ws
            if (bs == 0) {
                if (j in b.indices) bv = b[j]
            } else {
                if (j in b.indices) bv = bv or (b[j] shl bs)
                if (j - 1 in b.indices) bv = bv or (b[j - 1] ushr (32 - bs))
                bv = bv and 0xFFFFFFFFL
            }
            val v = (a[i] and 0xFFFFFFFFL) - bv - borrow
            a[i] = v and 0xFFFFFFFFL
            borrow = if (v < 0) 1 else 0
        }
    }

    private fun modReduce16(x: LongArray): LongArray {
        val r = x.copyOf()
        val lbits = 253
        var rbits = bitlen(r)
        var guard = 0
        while ((rbits > lbits || (rbits == lbits && geShifted(r, L_LIMBS, 0))) && guard++ < 600) {
            var s = rbits - lbits
            if (!geShifted(r, L_LIMBS, s)) s--
            if (s < 0) break
            subShifted(r, L_LIMBS, s)
            rbits = bitlen(r)
        }
        return r.copyOf(8)
    }

    /** 64B → mod L（低 32B 原地替换）。 */
    private fun scReduce(s: ByteArray) {
        require(s.size == 64)
        val r = modReduce16(limbsFromLeBytes(s, 16))
        limbsToLeBytes(r, 32).copyInto(s, 0, 0, 32)
    }

    /**
     * s = (a*b + c) mod L；a/b/c 均为 32B。
     * 乘法用 16-bit limbs（单项积 < 2^32，永不溢出），再拼回 32-bit 做长除法。
     */
    private fun scMuladd(s: ByteArray, a: ByteArray, b: ByteArray, c: ByteArray) {
        require(a.size == 32 && b.size == 32 && c.size == 32)
        fun to16(x: ByteArray): LongArray {
            val l = LongArray(16)
            for (i in x.indices) {
                val k = i shr 1
                l[k] = l[k] or ((x[i].toLong() and 0xFF) shl ((i and 1) * 8))
            }
            return l
        }
        val av = to16(a); val bv = to16(b)
        val t = LongArray(32)
        for (i in 0 until 16) {
            for (j in 0 until 16) {
                if (i + j < 32) t[i + j] += av[i] * bv[j]
            }
        }
        var carry = 0L
        for (i in t.indices) {
            val v = t[i] + carry
            t[i] = v and 0xFFFFL
            carry = v ushr 16
        }
        // 32×16-bit → 16×32-bit
        val t32 = LongArray(16)
        for (i in 0 until 16) t32[i] = t[i * 2] or (t[i * 2 + 1] shl 16)
        val cv = limbsFromLeBytes(c, 8)
        carry = 0L
        for (i in 0 until 16) {
            val v = t32[i] + (if (i < 8) cv[i] else 0L) + carry
            t32[i] = v and 0xFFFFFFFFL
            carry = v ushr 32
        }
        // 乘积极小概率触及 2^512（仅当 a,b 接近 2^256-1）；进位留在 t32[15] 高位，
        // modReduce 按 512-bit 处理，正确。
        val r = modReduce16(t32)
        limbsToLeBytes(r, 32).copyInto(s, 0, 0, 32)
    }

    // ---- public API ----
    /**
     * 内置一致性检查（供单测与 M7 审计）：逐项验证域原语与点编解码，
     * 返回失败项描述（空 = 全部通过）。
     */
    internal fun consistencyCheck(): List<String> {
        initConstants() // 含基点解码（失败即抛；G 系列测试依赖已解码的 baseP3）
        val bad = mutableListOf<String>()
        run {
            // d 的值仲裁：Σ limb·2^e mod p 应等于 -121665/121666
            val m = LongArray(10); m[0] = 121666
            val t = LongArray(10); feMul(t, d, m)
            }
        // N0. normalize 幂等：已规约值不变
        run {
            val h = longArrayOf(5, 7, 9, 11, 13, 15, 17, 19, 21, 23)
            val c = h.copyOf()
            feNormalize(h)
            if (!h.contentEquals(c)) bad += "normalize not idempotent: ${h.toList()}"
        }
        // N1. normalize 进位：limb0=2^26 → [0,1,0..]
        run {
            val h = LongArray(10); h[0] = 1L shl 26
            feNormalize(h)
            if (!(h[0] == 0L && h[1] == 1L && h.drop(2).all { it == 0L })) {
                bad += "normalize carry wrong: ${h.toList()}"
            }
        }
        // N2. tobytes(fe(1)) == 01 00..
        run {
            val f = LongArray(10); f[0] = 1
            val o = ByteArray(32); feTobytes(o, f)
            if (!(o[0] == 1.toByte() && o.drop(1).all { it == 0.toByte() })) {
                bad += "tobytes(1) wrong: ${o.take(4)}"
            }
        }
        // N3. tobytes(limb0=2^26) == bit26 置位
        run {
            val f = LongArray(10); f[0] = 1L shl 26
            val o = ByteArray(32); feTobytes(o, f)
            val e = ByteArray(32); e[3] = 4
            if (!o.contentEquals(e)) bad += "tobytes(2^26) wrong: ${o.take(5)}"
        }
        // S0. mod-L 约减已知答案：r=L → 0；r=L+1 → 1
        // L 的 32B LE（RFC 8032）：ED D3 F5 5C 1A 26 12 2B D0 42 7F 2D E5 27 35 2F
        //   B4 0E 4B B0 C6 02 1D D6 8F 14 2A DB 0E 00 00 10
        run {
            val lBytes = byteArrayOf(
                0xED.toByte(), 0xD3.toByte(), 0xF5.toByte(), 0x5C.toByte(),
                0x1A.toByte(), 0x63.toByte(), 0x12.toByte(), 0x58.toByte(),
                0xD6.toByte(), 0x9C.toByte(), 0xF7.toByte(), 0xA2.toByte(),
                0xDE.toByte(), 0xF9.toByte(), 0xDE.toByte(), 0x14.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x10.toByte(),
            )
            val z64 = ByteArray(64)
            lBytes.copyInto(z64)
            scReduce(z64)
            if (!z64.copyOf(32).all { it == 0.toByte() }) {
                bad += "modL(L) != 0: ${z64.copyOf(4).toList()}"
            }
            val o64 = ByteArray(64)
            lBytes.copyInto(o64)
            o64[0] = (o64[0] + 1).toByte() // L+1（byte0 无进位：0xED+1=0xEE）
            scReduce(o64)
            if (!(o64[0] == 1.toByte() && o64.drop(1).take(31).all { it == 0.toByte() })) {
                bad += "modL(L+1) != 1: ${o64.copyOf(4).toList()}"
            }
        }
        // S1. scMuladd：2·3+4 = 10
        run {
            val a = ByteArray(32); a[0] = 2
            val b = ByteArray(32); b[0] = 3
            val c = ByteArray(32); c[0] = 4
            val s = ByteArray(32)
            scMuladd(s, a, b, c)
            if (!(s[0] == 10.toByte() && s.drop(1).all { it == 0.toByte() })) {
                bad += "scMuladd(2,3,4) != 10: ${s.copyOf(4).toList()}"
            }
        }
        fun isZeroFe(f: LongArray): Boolean {
            val s = ByteArray(32); feTobytes(s, f)
            return s.all { it == 0.toByte() }
        }
        /** 模 p 判零（差值可能为 ±p 等非零整数，tobytes 直判会误报）。 */
        fun isZeroModP(f: LongArray): Boolean {
            val c = LongArray(10); for (i in 0 until 10) c[i] = f[i]
            feNormalize(c)
            val x = LongArray(16); for (i in 0 until 10) x[i] = c[i]
            return modReduce16(x).all { it == 0L }
        }
        // G0. 在曲线方程自检前，先确认 isZeroFe 对“p 本身”返回 true（规范编码下 p≡0）。
        run {
            val P = longArrayOf(
                67108845L, 33554431L, 67108863L, 33554431L, 67108863L,
                33554431L, 67108863L, 33554431L, 67108863L, 33554431L,
            )
            if (!isZeroFe(P)) bad += "isZeroFe(p) != true"
        }
        fun isOneFe(f: LongArray): Boolean {
            val s = ByteArray(32); feTobytes(s, f)
            return s[0] == 1.toByte() && s.drop(1).all { it == 0.toByte() }
        }
        // 1. d*121666 + 121665 == 0
        run {
            val m = LongArray(10); m[0] = 121666
            val t = LongArray(10); feMul(t, d, m)
            t[0] += 121665
            if (!isZeroFe(t)) bad += "d*121666+121665 != 0"
        }
        // 2b. pow22523 自检：e = a^(2^252-3) 则 e^8·a^4 == a^(2^255-20) == a^(p-1) == 1
        run {
            val a = LongArray(10); a[0] = 2
            val e = LongArray(10); fePow22523(e, a)
            var t = e
            repeat(3) { val n = LongArray(10); feSq(n, t); t = n } // t = e^8
            val a2 = LongArray(10); feSq(a2, a)
            val a4 = LongArray(10); feSq(a4, a2)
            val u = LongArray(10); feMul(u, t, a4)
            u[0] -= 1
            if (!isZeroFe(u)) bad += "pow22523 sanity failed"
        }
        // 2. sqrtm1^2 == -1
        run {
            val t = LongArray(10); feSq(t, sqrtm1)
            t[0] += 1
            if (!isZeroFe(t)) bad += "sqrtm1^2 != -1"
        }
        // 3. inv(2)*2 == 1
        run {
            val two = LongArray(10); two[0] = 2
            val inv = LongArray(10); feInvert(inv, two)
            val t = LongArray(10); feMul(t, inv, two)
            if (!isOneFe(t)) bad += "inv(2)*2 != 1"
        }
        // 3b. fe 编解码往返：tobytes(frombytes(b)) == b（b=0x00..0x1F，值 < p，必为规范形）
        run {
            val b = ByteArray(32) { it.toByte() }
            val f = LongArray(10); feFrombytes(f, b)
                val o = ByteArray(32); feTobytes(o, f)
                if (!o.contentEquals(b)) bad += "fe roundtrip mismatch"
        }
        // 4. 基点编解码往返（失败记项，不抛）
        run {
            val s = ByteArray(32)
            s[0] = 0x58.toByte(); for (i in 1 until 32) s[i] = 0x66.toByte()
            val p = GeP3()
            if (geFrombytesNegateVartime(p, s) != 0) bad += "base decode failed"
            else {
                val o = ByteArray(32); geTobytes(o, p)
                if (!o.contentEquals(s)) bad += "base roundtrip mismatch"
            }
        }
        // 5. ladder/纯倍点结果必在曲线上（-x²+y² == 1+d·x²y²，无需外部向量）
        fun onCurve(p: GeP3, tag: String) {
            val iz = LongArray(10); feInvert(iz, p.z)
            val x = LongArray(10); feMul(x, p.x, iz)
            val y = LongArray(10); feMul(y, p.y, iz)
            val x2 = LongArray(10); feMul(x2, x, x)
            val y2 = LongArray(10); feMul(y2, y, y)
            val lhs = LongArray(10); feSub(lhs, y2, x2)
            val dx2y2 = LongArray(10); feMul(dx2y2, d, x2); feMul(dx2y2, dx2y2, y2)
            val one = LongArray(10); one[0] = 1
            val rhs = LongArray(10); feAdd(rhs, one, dx2y2)
            val diff = LongArray(10); feSub(diff, lhs, rhs)
            if (!isZeroFe(diff)) bad += "$tag off curve"
        }
        run {
            val k = ByteArray(32) { (it * 37 + 11).toByte() }
            onCurve(ladder(k, basePoint()), "ladder")
        }
        run {
            var p = basePoint()
            repeat(8) { p = geDouble(p) }
            onCurve(p, "doubling chain")
        }
        run {
            // ladder(2) = 一次倍点 + 一次单位元加（单位元加已手算验证）
            val two = ByteArray(32); two[0] = 2
            onCurve(ladder(two, basePoint()), "double-B")
        }
        run {
            // ladder(3) = double-B + 一次普通加法（区分 doubling 与 addition 的责任）
            val three = ByteArray(32); three[0] = 3
            onCurve(ladder(three, basePoint()), "add-B-2B")
        }
        run {
            // D0. geDouble(单位元) 必须仍是单位元（编码 01 00..）
            val id = GeP3(); id.y[0] = 1; id.z[0] = 1
            val o = ByteArray(32); geTobytes(o, geDouble(id))
            val e = ByteArray(32); e[0] = 1
            if (!o.contentEquals(e)) bad += "double-identity wrong: ${o.take(4)}"
        }
        run {
            // D1. ladder(1) 必须精确等于基点（I+B 的精确回归）
            val one = ByteArray(32); one[0] = 1
            val o = ByteArray(32); geTobytes(o, ladder(one, basePoint()))
            val e = ByteArray(32)
            e[0] = 0x58.toByte(); for (i in 1 until 32) e[i] = 0x66.toByte()
            if (!o.contentEquals(e)) bad += "ladder(1) != base: ${o.take(4)}"
        }
        return bad
    }

    fun publicKeyFromSeed(seed: ByteArray): ByteArray {
        initConstants()
        require(seed.size == 32)
        val h = seed.sha512().bytes
        val a = clamp(h.copyOf(32))
        val p = ladder(a, basePoint())
        val out = ByteArray(32)
        geTobytes(out, p)
        return out
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        initConstants()
        require(seed.size == 32)
        val h = seed.sha512().bytes
        val a = clamp(h.copyOf(32))
        val prefix = h.copyOfRange(32, 64)
        val nonce = (prefix + message).sha512().bytes
        scReduce(nonce)
        val r = ladder(nonce.copyOf(32), basePoint())
        val rBytes = ByteArray(32)
        geTobytes(rBytes, r)
        val pub = publicKeyFromSeed(seed)
        val hram = (rBytes + pub + message).sha512().bytes
        scReduce(hram)
        val s = ByteArray(32)
        scMuladd(s, hram.copyOf(32), a, nonce.copyOf(32))
        return rBytes + s
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        initConstants()
        if (publicKey.size != 32 || signature.size != 64) return false
        if ((signature[63].toInt() and 0xE0) != 0) return false
        val a = GeP3()
        if (geFrombytesNegateVartime(a, publicKey) != 0) return false
        val h = (signature.copyOf(32) + publicKey + message).sha512().bytes
        scReduce(h)
        // R' = [s]B - [h]A，与签名 R 比较。
        val sPart = ladder(signature.copyOfRange(32, 64), basePoint())
        val hPart = ladder(h.copyOf(32), a)
        val rCalc = geAddFull(sPart, geNeg(hPart))
        val check = ByteArray(32)
        geTobytes(check, rCalc)
        for (i in 0 until 32) if (check[i] != signature[i]) return false
        return true
    }
}
