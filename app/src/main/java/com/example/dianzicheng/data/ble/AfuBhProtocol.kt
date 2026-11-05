package com.example.dianzicheng.data.ble

/**
 * 薄荷健康 B1（沃莱代工新固件，型号 `AFU-BH-TZ-B1`）蓝牙协议编解码器。
 *
 * ## 与旧 AFU（0xAC）协议的区别
 *
 * 旧款 AFU / 沃莱秤走 `0xFFB0` 服务、`0xFFB2` 通知、报文以魔数 `0xAC` 起始，
 * 由 [AFUPacketParser] 处理。薄荷 B1 是新固件：
 *
 * - 自定义服务 UUID 形如 `af0000xx-9f2d-4c8a-8d6f-6a7b45f90000`；
 * - **写入特征为 `af000002-...`**，通知特征为 `af000003-...` / `af000004-...`；
 * - 报文固定 **20 字节**，以 `AF 45` 起始，**第 18 字节是报文类型**，第 19 字节是校验；
 * - 必须先下发「校时 + 两帧同步」握手，秤端才会进入正式测量流程；
 * - 体重不再用 `0x68` 基准偏移，而是 18 位小端克数（见 [LiveWeight]）。
 *
 * ## 20 字节帧布局
 *
 * ```
 * 偏移  0    1    2..3   4   5      6     7..17   18     19
 *      AF   45   体重   (高) 标志  模式   保留   类型   校验
 * ```
 *
 * - `[2..5]` 32 位小端位域：bit0..17 = 体重（克），bit31 = 已锁定；
 * - 体重最大可表示 262143 g = 262 kg，覆盖家用秤全量程；
 * - 校验 = `sum(bytes[2..18]) and 0x1F`（不是 CRC，也不是累加和低 8 位）。
 *
 * ## 校验样例（来自真机日志）
 *
 * ```
 * AF 45 B0 30 09 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 08
 *       └── 0x000930B0 = 602288，低 18 位 = 78000 → 78.00 kg（连续 3 帧一致 = 已稳定）
 * ```
 *
 * ## 实现来源
 *
 * 协议结构、握手帧、状态机时序均对照开源项目 OHealth Device Bridge
 * （作者 BruceZhang1993，MIT 许可，已在该型号真机上验证）整理，
 * 并结合本项目真机抓包逐字节复核。
 */
object AfuBhProtocol {

    /** 固定帧长（薄荷 B1 每帧均为 20 字节）。 */
    const val FRAME_SIZE = 20

    /** 帧头魔数第 0 字节。 */
    const val HEAD_0 = 0xAF

    /** 帧头魔数第 1 字节。 */
    const val HEAD_1 = 0x45

    /** 报文类型（第 18 字节）：实时体重。 */
    const val WIRE_LIVE_WEIGHT = 0x51

    /** 报文类型：阻抗（BIA）。 */
    const val WIRE_IMPEDANCE = 0x52

    /** 报文类型：秤内历史记录。 */
    const val WIRE_STORED = 0x54

    /** 报文类型：控制/应答类。 */
    const val WIRE_CONTROL = 0x5F

    /** 报文类型：用户资料与测量流程控制。 */
    const val WIRE_PROFILE = 0x61

    /** 报文类型：校时与同步。 */
    const val WIRE_SYNC = 0x62

    /** 写入特征 UUID：所有下行指令都必须发到这个特征。 */
    const val WRITE_CHARACTERISTIC_UUID = "af000002-9f2d-4c8a-8d6f-6a7b45f90000"

    /** 通知特征 UUID（体重/阻抗主通道）。 */
    const val NOTIFY_CHARACTERISTIC_UUID = "af000003-9f2d-4c8a-8d6f-6a7b45f90000"

    /** 体重位域掩码：低 18 位为克数。 */
    private const val WEIGHT_MASK = 0x3FFFFL

    /** 已锁定标志：32 位位域的 bit31。 */
    private const val LOCKED_MASK = 0x80000000L

    /**
     * 用户资料帧的变体。固件用第 2 字节区分「当前处于流程哪一步」，
     * 第 10 字节（`openByte`）是固件要求的配对话术，两者必须成对出现。
     *
     * @property code     资料帧第 2 字节
     * @property openByte 资料帧第 10 字节
     */
    enum class ProfileVariant(val code: Int, val openByte: Int) {
        /** 首次上秤，告知秤端「开始一次测量」。 */
        INITIAL(0x3F, 0xB6),

        /** 稳定后重新称一次（阶段 1）。 */
        GET_WEIGH_AGAIN_1(0x4B, 0xB6),

        /** 稳定后重新称一次（阶段 2）。 */
        GET_WEIGH_AGAIN_2(0x5B, 0xD9),

        /** 阻抗测量收尾。 */
        FINAL(0x68, 0xD9),

        /** 请求秤端导出历史记录。 */
        HISTORY_DUMP(0x8E, 0xDE)
    }

    /** 解析后的帧基类，保留原始字节便于日志与二次分析。 */
    sealed interface Frame {
        val raw: ByteArray
    }

    /**
     * 实时体重帧（类型 0x51）。
     *
     * @property weightGrams 体重（克），已按 18 位掩码还原
     * @property locked      秤端是否已判定锁定（bit31）
     * @property mode        第 6 字节状态码（0x00 采样中 / 0x02 结果态）
     */
    data class LiveWeight(
        val weightGrams: Int,
        val locked: Boolean,
        val mode: Int,
        override val raw: ByteArray
    ) : Frame

    /**
     * 秤内历史记录帧（类型 0x54）。
     *
     * @property historyType    记录类型（第 2 字节低 2 位）
     * @property sequence       记录序号（第 2 字节 bit2..7），回 ack 时必须原样带回
     * @property timestampEpochSeconds 记录时间戳（秒）
     * @property weightGrams    体重（克）
     * @property resistanceOhm  阻抗（欧姆），0 表示该条无阻抗
     * @property locked         是否已锁定
     */
    data class StoredRecord(
        val historyType: Int,
        val sequence: Int,
        val timestampEpochSeconds: Long,
        val weightGrams: Int,
        val resistanceOhm: Int,
        val locked: Boolean,
        override val raw: ByteArray
    ) : Frame

    /**
     * 阻抗帧（类型 0x52）。
     *
     * @property valid         第 2 字节是否为 0x01（有效测量）
     * @property resistanceOhm 阻抗值（欧姆）
     */
    data class Impedance(
        val valid: Boolean,
        val resistanceOhm: Int,
        override val raw: ByteArray
    ) : Frame

    /** 分段上报帧（类型 0x5F / 子命令 0x14），体成分明细走这里。 */
    data class ReportChunk(
        val index: Int,
        val payload: ByteArray,
        override val raw: ByteArray
    ) : Frame

    /** 控制应答帧（类型 0x5F / 子命令 0x12）。 */
    data class ControlAck(
        val replyCommand: Int,
        val replyOperation: Int,
        override val raw: ByteArray
    ) : Frame

    /** 未处理的报文类型，仅用于日志统计。 */
    data class Unknown(
        val wireType: Int,
        override val raw: ByteArray
    ) : Frame

    /**
     * 校验一帧是否为合法的薄荷 B1 报文。
     *
     * 三个条件同时满足才认：长度 20、帧头 `AF 45`、校验位吻合。
     * 这一步是防止把其它厂商的 20 字节帧误判成体重（例如 0xAC 协议帧恰好 20 字节）。
     *
     * @param frame 原始通知字节
     * @return 合法返回 true
     */
    fun isValid(frame: ByteArray): Boolean {
        if (frame.size != FRAME_SIZE) return false
        if (u8(frame[0]) != HEAD_0 || u8(frame[1]) != HEAD_1) return false
        var sum = 0
        for (i in 2..18) sum += u8(frame[i])
        return (sum and 0x1F) == (u8(frame[19]) and 0x1F)
    }

    /**
     * 解析一帧报文。
     *
     * @param frame 原始通知字节
     * @return 解析结果；非法帧返回 null
     */
    fun parse(frame: ByteArray): Frame? {
        if (!isValid(frame)) return null
        val raw = frame.copyOf()
        val wireType = u8(raw[18])
        return when (wireType) {
            WIRE_LIVE_WEIGHT -> {
                val flags = le32(raw, 2)
                LiveWeight(
                    weightGrams = (flags and WEIGHT_MASK).toInt(),
                    locked = (flags and LOCKED_MASK) != 0L,
                    mode = u8(raw[6]),
                    raw = raw
                )
            }

            WIRE_STORED -> {
                val meta = u8(raw[2])
                val flags = le32(raw, 7)
                StoredRecord(
                    historyType = meta and 0x03,
                    sequence = (meta ushr 2) and 0x3F,
                    timestampEpochSeconds = le32(raw, 3),
                    weightGrams = (flags and WEIGHT_MASK).toInt(),
                    resistanceOhm = le16(raw, 13),
                    locked = (flags and LOCKED_MASK) != 0L,
                    raw = raw
                )
            }

            WIRE_IMPEDANCE -> Impedance(
                valid = u8(raw[2]) == 0x01,
                resistanceOhm = le16(raw, 4),
                raw = raw
            )

            WIRE_CONTROL -> when (u8(raw[2])) {
                0x14 -> ReportChunk(u8(raw[3]), raw.copyOfRange(4, 18), raw)
                0x12 -> ControlAck(u8(raw[3]), u8(raw[4]), raw)
                else -> Unknown(wireType, raw)
            }

            else -> Unknown(wireType, raw)
        }
    }

    /**
     * 构造校时帧（类型 0x5F，子命令 0x16）。
     *
     * 秤端没有网络，历史记录的时间戳完全依赖手机下发。
     * 握手第一帧必须发它，否则历史记录时间会落在 1970 年。
     *
     * @param epochSeconds Unix 时间戳（秒）
     */
    fun buildTimeSync(epochSeconds: Long): ByteArray = frame(WIRE_CONTROL) {
        this[2] = 0x16
        putLe32(this, 3, epochSeconds)
        this[7] = 0x20
    }

    /**
     * 构造握手同步帧 1（固定常量）。
     *
     * 这 20 字节是固件的「配对话术」，逐字节固定，不可改。
     */
    fun buildSyncFirst(): ByteArray = byteArrayOf(
        0xAF.toByte(), 0x45, 0x04, 0x00, 0x01, 0xAF.toByte(), 0xB6.toByte(),
        0x17, 0x1D, 0x01, 0x00, 0x02, 0xB4.toByte(), 0xC6.toByte(), 0x1B,
        0x56, 0x01, 0x00, 0x62, 0x00
    ).withChecksum()

    /**
     * 构造握手同步帧 2（固定常量）。
     *
     * 这一帧写成功后秤端才真正进入「可测量」状态，
     * 此时才会开始推送正式测量帧（而不是待机采样流）。
     */
    fun buildSyncSecond(): ByteArray = byteArrayOf(
        0xAF.toByte(), 0x45, 0x04, 0x01, 0x03, 0xA5.toByte(), 0xF8.toByte(),
        0x11, 0x1D, 0x02, 0x00, 0x04, 0xB4.toByte(), 0xC6.toByte(), 0x1B,
        0x1D, 0x01, 0x00, 0x62, 0x00
    ).withChecksum()

    /**
     * 构造用户资料 / 流程控制帧（类型 0x61）。
     *
     * 布局：`[2]=变体 [3..6]=时间戳 [7]=0 [8]=用户槽 [9]=0xAF [10]=openByte`
     * `[11]=0x17 [12]=年龄 [13]=性别(1男/0女) [14..15]=目标体重(kg×100) [16]=0x03 [17]=0`
     *
     * 注意：该固件的资料帧**不含身高**。身高只参与手机端 BIA 计算，
     * 秤端只用年龄与性别来标定阻抗激励，两者必须与实际成员一致，
     * 否则阻抗偏移会导致体脂率明显偏差。
     *
     * @param variant     流程变体
     * @param epochSeconds 当前时间戳（秒）
     * @param userSlot    用户槽位（0..255），单用户固定 0 即可
     * @param age         年龄（岁）
     * @param male        是否男性
     * @param targetKg    目标体重（kg），仅用于秤端进度显示
     */
    fun buildProfile(
        variant: ProfileVariant,
        epochSeconds: Long,
        userSlot: Int,
        age: Int,
        male: Boolean,
        targetKg: Double
    ): ByteArray = frame(WIRE_PROFILE) {
        this[2] = variant.code.toByte()
        putLe32(this, 3, epochSeconds)
        this[7] = 0
        this[8] = userSlot.coerceIn(0, 255).toByte()
        this[9] = 0xAF.toByte()
        this[10] = variant.openByte.toByte()
        this[11] = 0x17
        this[12] = age.coerceIn(0, 255).toByte()
        this[13] = (if (male) 1 else 0).toByte()
        putLe16(this, 14, (targetKg * 100.0).toInt().coerceIn(0, 0xFFFF))
        this[16] = 0x03
        this[17] = 0
    }

    /**
     * 构造应答帧（类型 0x5F，子命令 0x12）。
     *
     * **必须回 ack 的三种帧**：0x51（锁定）、0x52（阻抗）、0x54（历史记录）。
     * 少回任何一个，秤端都会停在当前步骤不再推进 —— 这也是「能连上但永远 0」的常见原因。
     *
     * @param replyType   被应答的报文类型（0x51 / 0x52 / 0x54 / 0x14）
     * @param correlation 历史记录帧需带回序号，其它填 0
     */
    fun buildAck(replyType: Int, correlation: Int = 0): ByteArray = frame(WIRE_CONTROL) {
        this[2] = 0x12
        this[3] = replyType.toByte()
        this[4] = 0
        this[5] = correlation.coerceIn(0, 255).toByte()
    }

    /** 构造分段上报请求帧（类型 0x5F，子命令 0x14）。 */
    fun buildChunkRequest(): ByteArray = frame(WIRE_CONTROL) {
        this[2] = 0x14
    }

    /** 十六进制字符串（大写、无分隔），用于日志与单元测试比对。 */
    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    /**
     * 分配一帧并把类型、校验填好，body 只负责写 [2..17]。
     *
     * @param wireType 第 18 字节的报文类型
     * @param body     填充逻辑
     */
    private fun frame(wireType: Int, body: ByteArray.() -> Unit): ByteArray {
        val f = ByteArray(FRAME_SIZE)
        f[0] = HEAD_0.toByte()
        f[1] = HEAD_1.toByte()
        f[18] = wireType.toByte()
        f.body()
        return f.withChecksum()
    }

    /**
     * 写入校验位：`sum(bytes[2..18]) and 0x1F`。
     *
     * 只保留低 5 位，因此不同载荷可能撞出同一个校验值 —— 这是协议设计如此，
     * 校验只能用来排除明显噪声帧，不能当作帧类型判据。
     */
    private fun ByteArray.withChecksum(): ByteArray {
        var sum = 0
        for (i in 2..18) sum += u8(this[i])
        this[19] = (sum and 0x1F).toByte()
        return this
    }

    private fun le16(raw: ByteArray, o: Int): Int = u8(raw[o]) or (u8(raw[o + 1]) shl 8)

    private fun le32(raw: ByteArray, o: Int): Long =
        u8(raw[o]).toLong() or
            (u8(raw[o + 1]).toLong() shl 8) or
            (u8(raw[o + 2]).toLong() shl 16) or
            (u8(raw[o + 3]).toLong() shl 24)

    private fun putLe16(raw: ByteArray, o: Int, v: Int) {
        raw[o] = (v and 0xFF).toByte()
        raw[o + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putLe32(raw: ByteArray, o: Int, v: Long) {
        raw[o] = (v and 0xFF).toByte()
        raw[o + 1] = ((v ushr 8) and 0xFF).toByte()
        raw[o + 2] = ((v ushr 16) and 0xFF).toByte()
        raw[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun u8(v: Byte): Int = v.toInt() and 0xFF
}
