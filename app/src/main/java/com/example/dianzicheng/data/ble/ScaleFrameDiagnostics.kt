package com.example.dianzicheng.data.ble

import com.example.dianzicheng.data.local.AppLogger

/**
 * 未知协议报文诊断探针（纯诊断用途，不参与测量解析）。
 *
 * 背景：薄荷 B1（`AFU-BH-TZ-B1`，沃莱代工）这类新一代固件既不走 `0xAC` 帧，
 * 也不暴露 `0xFFB0` 服务（自定义服务为 `af000002-...`），其报文对现有全部解析器
 * 都不命中。此时最缺的不是"猜一个换算公式"，而是"到底哪一帧、哪个字节在动"。
 *
 * 本探针按特征 UUID 分别累计**内容互不相同**的样本帧（重复帧只计一次），
 * 每累计一定数量后输出一份统计：
 *
 * - 哪些字节恒定（设备 ID / 固件版本 / 保留位），哪些字节在变化（真正的数据字段）；
 * - 变化字段按 16 位大端 / 小端解释后的原始值域与去重取值；
 * - 该字段是否存在能映射到 10~250kg 的"体重候选"换算系数。
 *
 * 拿到真实称重报文（人站在秤上那几秒）的统计后，即可一次性把偏移量和换算系数写死，
 * 不需要反复试错。
 *
 * 使用方式：
 * ```kotlin
 * // 解析失败时调用
 * ScaleFrameDiagnostics.observe(characteristicUuid, rawBytes)
 * // 重新连接 / 换设备时清空
 * ScaleFrameDiagnostics.reset()
 * ```
 */
object ScaleFrameDiagnostics {

    private const val TAG = "ScaleDiag"

    /** 每个特征最多保留的不同样本帧数量 */
    private const val MAX_SAMPLES = 40

    /** 每新增多少个不同样本输出一次统计 */
    private const val REPORT_EVERY = 15

    /** 疑似体重的合理区间（kg） */
    private const val MIN_HUMAN_KG = 10.0
    private const val MAX_HUMAN_KG = 250.0

    /** 候选换算系数（原始值 ÷ 系数 = kg） */
    private val DIVISORS = intArrayOf(1, 10, 50, 100, 200, 500, 1000, 2000)

    /** 各特征累计的样本帧 */
    private val samples = LinkedHashMap<String, MutableList<ByteArray>>()

    /** 各特征上次输出统计时的样本数量 */
    private val reportedCount = HashMap<String, Int>()

    /**
     * 清空全部累计样本（重新连接、切换设备、重新配对时调用）。
     */
    @Synchronized
    fun reset() {
        samples.clear()
        reportedCount.clear()
    }

    /**
     * 投喂一帧未识别的报文。
     *
     * 重复内容只记录一次，避免 8Hz 的重复帧把统计淹没。
     * 累计到 [REPORT_EVERY] 个不同样本时输出一次统计。
     *
     * @param charUuid 发出该通知的特征 UUID
     * @param data     原始字节
     */
    @Synchronized
    fun observe(charUuid: String, data: ByteArray) {
        if (data.size < 4) return
        val key = charUuid.uppercase()
        val list = samples.getOrPut(key) { mutableListOf() }
        if (list.any { it.contentEquals(data) }) return
        list.add(data.copyOf())
        if (list.size > MAX_SAMPLES) list.removeAt(0)

        val last = reportedCount[key] ?: 0
        if (list.size - last >= REPORT_EVERY) {
            reportedCount[key] = list.size
            report(key, list.toList())
        }
    }

    /**
     * 输出指定特征的字段统计。
     *
     * @param charUuid 特征 UUID
     * @param list     该特征累计的不同样本帧
     */
    private fun report(charUuid: String, list: List<ByteArray>) {
        if (list.isEmpty()) return
        val key = shortUuid(charUuid)
        val len = list.first().size
        AppLogger.i(
            TAG,
            "特征 $key 未识别报文统计：${list.size} 个不同样本，帧长 $len，" +
                "首帧 ${hexOf(list.first())}"
        )

        // 统计每个偏移是否变化
        val active = (0 until len).filter { i -> list.any { it[i] != list[0][i] } }
        if (active.isEmpty()) {
            AppLogger.i(TAG, "特征 $key：全部 $len 字节恒定 → 心跳/状态帧，不含任何测量值")
            return
        }

        // 把连续变化的偏移合并成字段区间（如 2,3,4 连续 → 一个 3 字节字段）
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (i in 0 until len) {
            if (active.contains(i)) {
                if (start < 0) start = i
            } else if (start >= 0) {
                runs.add(start until i)
                start = -1
            }
        }
        if (start >= 0) runs.add(start until len)

        AppLogger.i(TAG, "特征 $key：变化字节 = ${active.joinToString(",")}；字段区间 ${runs.size} 个")

        for (range in runs) {
            val width = range.last - range.first + 1
            if (width == 1) {
                val values = list.map { it[range.first].toInt() and 0xFF }.distinct().sorted()
                AppLogger.i(
                    TAG,
                    "特征 $key：字段 [${range.first}] 单字节，取值 " +
                        values.joinToString(",") { "%02X".format(it) } +
                        "（${values.size} 种）"
                )
                continue
            }

            // 多字节字段：先给出每个字节各自的取值数量，可一眼看出"哪个是数据、哪个是计数器"
            val perByte = (range.first..range.last).joinToString(" ") { i ->
                "[$i]=${list.map { it[i].toInt() and 0xFF }.distinct().size}"
            }
            AppLogger.i(TAG, "特征 $key：字段 [${range.first}..${range.last}] 各字节取值数 $perByte")

            // 16 位大端 / 小端
            for (bigEndian in booleanArrayOf(true, false)) {
                val values = list.map { d ->
                    val a = d[range.first].toInt() and 0xFF
                    val b = d[range.first + 1].toInt() and 0xFF
                    if (bigEndian) (a shl 8) or b else (b shl 8) or a
                }
                val label = "字段 [${range.first}..${range.first + 1}] 16位${if (bigEndian) "大端" else "小端"}"
                AppLogger.i(
                    TAG,
                    "特征 $key：$label 原始 ${values.minOrNull() ?: 0}..${values.maxOrNull() ?: 0}" +
                        "（${values.distinct().size} 种）" + kgCandidates(values)
                )
            }

            // 24 位大端（AFU 旧协议体重就是 24 位大端，新固件很可能沿用）
            if (width >= 3) {
                val values = list.map { d ->
                    ((d[range.first].toInt() and 0xFF) shl 16) or
                        ((d[range.first + 1].toInt() and 0xFF) shl 8) or
                        (d[range.first + 2].toInt() and 0xFF)
                }
                AppLogger.i(
                    TAG,
                    "特征 $key：字段 [${range.first}..${range.first + 2}] 24位大端 原始 " +
                        "${values.minOrNull() ?: 0}..${values.maxOrNull() ?: 0}（${values.distinct().size} 种）" +
                        kgCandidates(values)
                )
            }
        }
    }

    /**
     * 判断一组原始值能否映射为人体体重，能则返回可读的候选描述。
     *
     * @param values 某字段的原始值集合
     * @return 命中时形如 " → 疑似体重 /1000 = 65.2~67.3kg"；否则返回空串
     */
    private fun kgCandidates(values: List<Int>): String {
        if (values.isEmpty()) return ""
        val minRaw = values.minOrNull() ?: return ""
        val maxRaw = values.maxOrNull() ?: return ""
        if (maxRaw <= 0) return ""
        for (divisor in DIVISORS) {
            val minKg = minRaw.toDouble() / divisor
            val maxKg = maxRaw.toDouble() / divisor
            if (minKg >= MIN_HUMAN_KG && maxKg <= MAX_HUMAN_KG) {
                return " → 疑似体重 /$divisor = %.2f~%.2fkg".format(minKg, maxKg)
            }
        }
        return ""
    }

    /**
     * 把 UUID 压缩成便于阅读的短标记，如 `af000003-9f2d-...` → `AF000003`。
     *
     * @param uuid 完整特征 / 服务 UUID 字符串
     * @return 前 8 个字符的大写形式
     */
    fun shortUuid(uuid: String): String =
        if (uuid.length >= 8) uuid.substring(0, 8).uppercase() else uuid.uppercase()

    /**
     * 把字节数组格式化成空格分隔的十六进制字符串。
     *
     * @param data 原始字节
     * @return 形如 "AF 45 3A 20 ..."
     */
    fun hexOf(data: ByteArray): String = data.joinToString(" ") { "%02X".format(it) }
}
