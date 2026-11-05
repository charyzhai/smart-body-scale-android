package com.example.dianzicheng.data.ble

/**
 * 薄荷 B1（`AFU-BH-TZ-B1`）测量流程状态机。
 *
 * ## 为什么必须有状态机
 *
 * 这台秤不是「连上就推数据」的傻瓜秤，它按固定剧本走，每一步都要手机端推动，
 * 任何一步缺失都会卡住（实测日志已经证实）：
 *
 * ```
 * 订阅通知 ─► 校时(0x5F/16) ─► 同步帧1(0x62) ─► 同步帧2(0x62)
 *                                                    │
 *                       首个 0x51 实时体重 ◄──────────┘
 *                                 │
 *                        下发资料帧 INITIAL(0x61/3F)
 *                                 │
 *                       0x51 带锁定位 ─► 回 ack(0x51)
 *                                 │
 *                    静默 2s ─► 再同步 + 资料帧 4B/5B + 索取分段
 *                                 │
 *                 0x52 阻抗 ─► 回 ack(0x52) ─► 资料帧 FINAL
 *                                 │
 *                          15s 后结束本轮
 * ```
 *
 * ## 真机上「不推动」的后果
 *
 * 2026-10-29 的真机日志里：用户称到 77.45 kg 并锁定后，秤端立刻推送了
 * `0x54` 结果帧（77.45 kg / 461 Ω），但因为 App 从未回 ack 也从未发过握手帧，
 * 秤端把同一条记录**连续重发了 200 多次长达 2 分钟**，直到连接断开。
 * 也就是说数据一直都在，只是没人应答。
 *
 * ## 线程模型
 *
 * 本类不持有任何 Android 对象，也不自己起线程：
 * [send] 由调用方压入 BLE 串行写队列，[tick] 由调用方每 250ms 调一次，
 * [onFrame] 在 GATT 回调投递到主线程后调用。因此它可以在纯 JVM 单元测试里跑通。
 *
 * @param age      年龄（岁）
 * @param male     是否男性
 * @param targetKg 目标体重（kg），仅用于秤端进度显示
 * @param userSlot 用户槽位，单用户固定 0
 * @param send     下发指令（payload, 日志标签）
 * @param onLog    日志回调
 */
class AfuBhSession(
    private val age: Int,
    private val male: Boolean,
    private val targetKg: Double,
    private val userSlot: Int = 0,
    private val send: (ByteArray, String) -> Unit,
    private val onLog: (String) -> Unit = {}
) {

    /** 当前流程阶段。 */
    enum class State {
        /** 握手帧尚未发完。 */
        HANDSHAKE,

        /** 已就绪，等待用户上秤。 */
        IDLE,

        /** 正在实时测量。 */
        LIVE,

        /** 体重已锁定，等待静默判定。 */
        WAIT_LOCK,

        /** 收尾：等阻抗与结果帧。 */
        COMMIT,

        /** 本轮已结束。 */
        FINISHED
    }

    var state: State = State.HANDSHAKE
        private set

    /** 是否已回过 0x51 锁定应答，防止同一轮重复回包。 */
    private var lockAcked = false

    /** 本轮测量起点（Unix 秒），0 表示尚未开始。 */
    private var weighStartedEpoch = 0L

    /** 最后一次「测量相关」帧（0x51/0x52）到达时刻，单调时钟毫秒。 */
    private var lastMeasureFrameAt = 0L

    /** 进入 COMMIT 的时刻，单调时钟毫秒。 */
    private var commitAt = 0L

    /** 最近一次实时体重（克）。 */
    private var lastWeightGrams = 0

    /** 本轮锁定时的体重（克），用于识别「下秤后重新上秤」。 */
    private var lockedWeightGrams = 0

    /** 本轮是否已把阻抗交给上层。 */
    private var impedanceSeen = false

    /** 会话是否已结束。 */
    val isFinished: Boolean get() = state == State.FINISHED

    /**
     * 开始一轮会话：立即下发校时与两帧同步。
     *
     * 三帧必须串行发送（BLE 同一时刻只允许一个写在途），
     * 因此这里只是把帧压进调用方的写队列。
     *
     * @param epochSeconds 当前 Unix 时间戳（秒）
     * @param nowMs        当前单调时钟（毫秒）
     */
    fun start(epochSeconds: Long, nowMs: Long) {
        if (state != State.HANDSHAKE) return
        lastMeasureFrameAt = nowMs
        send(AfuBhProtocol.buildTimeSync(epochSeconds), "校时")
        send(AfuBhProtocol.buildSyncFirst(), "同步帧1")
        send(AfuBhProtocol.buildSyncSecond(), "同步帧2")
        state = State.IDLE
        onLog("握手帧已排队：校时 / 同步1 / 同步2")
    }

    /**
     * 处理一帧已解析报文，按需回 ack、下发资料帧。
     *
     * @param frame        解析结果
     * @param nowMs        当前单调时钟（毫秒）
     * @param epochSeconds 当前 Unix 时间戳（秒）
     */
    fun onFrame(frame: AfuBhProtocol.Frame, nowMs: Long, epochSeconds: Long) {
        when (frame) {
            is AfuBhProtocol.LiveWeight -> {
                lastMeasureFrameAt = nowMs
                handleLiveWeight(frame, epochSeconds)
            }

            is AfuBhProtocol.Impedance -> {
                lastMeasureFrameAt = nowMs
                handleImpedance(frame, epochSeconds)
            }

            is AfuBhProtocol.StoredRecord -> handleStoredRecord(frame, nowMs)

            is AfuBhProtocol.ReportChunk -> {
                send(AfuBhProtocol.buildAck(0x14), "ack 分段上报")
                onLog("收到分段上报 #${frame.index}，已应答")
            }

            is AfuBhProtocol.ControlAck -> onLog(
                "秤端应答 cmd=0x%02X op=0x%02X".format(frame.replyCommand, frame.replyOperation)
            )

            is AfuBhProtocol.Unknown -> onLog("未处理报文类型 0x%02X".format(frame.wireType))
        }
    }

    /**
     * 由外部定时器每 250ms 调一次，推进与时间相关的状态迁移。
     *
     * @param nowMs        当前单调时钟（毫秒）
     * @param epochSeconds 当前 Unix 时间戳（秒）
     */
    fun tick(nowMs: Long, epochSeconds: Long) {
        when (state) {
            State.WAIT_LOCK -> if (nowMs - lastMeasureFrameAt >= IDLE_MS) {
                state = State.COMMIT
                commitAt = nowMs
                onLog("锁定后静默 ${IDLE_MS}ms，进入收尾：重新同步并索取阻抗")
                sync(epochSeconds)
                send(profile(AfuBhProtocol.ProfileVariant.GET_WEIGH_AGAIN_1, epochSeconds), "资料帧 4B")
                send(profile(AfuBhProtocol.ProfileVariant.GET_WEIGH_AGAIN_2, epochSeconds), "资料帧 5B")
                send(AfuBhProtocol.buildChunkRequest(), "索取分段上报")
            }

            State.COMMIT -> if (nowMs - commitAt >= COMMIT_MS) {
                state = State.FINISHED
                onLog("本轮测量收尾完成（体重 ${lastWeightGrams / 1000.0} kg，阻抗${if (impedanceSeen) "已取得" else "未取得"}）")
            }

            else -> Unit
        }
    }

    /**
     * 判断某个历史/结果帧是否是「刚刚这次称重」产生的。
     *
     * 秤端时钟并不准（实测与手机差约 2 分钟），所以容差放到 30 分钟；
     * 真正的判据是「体重与刚锁定值接近」。
     *
     * @param frame        0x54 记录帧
     * @param epochSeconds 当前 Unix 秒
     */
    fun isFreshRecord(frame: AfuBhProtocol.StoredRecord, epochSeconds: Long): Boolean {
        if (frame.weightGrams <= 0) return false
        val timeSane = kotlin.math.abs(epochSeconds - frame.timestampEpochSeconds) <= FRESH_WINDOW_SECONDS
        val weightSane = lockedWeightGrams > 0 && kotlin.math.abs(frame.weightGrams - lockedWeightGrams) <= 3000
        return timeSane && (weightSane || weighStartedEpoch == 0L)
    }

    // ------------------------------------------------------------------
    // 内部处理
    // ------------------------------------------------------------------

    private fun handleLiveWeight(frame: AfuBhProtocol.LiveWeight, epochSeconds: Long) {
        // 上一轮已结束，但用户又踩上秤（体重与上次锁定值差距明显）→ 自动开新一轮
        if (state == State.FINISHED) {
            val steppedOnAgain = frame.weightGrams > 3000 &&
                (lockedWeightGrams == 0 || kotlin.math.abs(frame.weightGrams - lockedWeightGrams) > 3000)
            if (!steppedOnAgain) return
            onLog("检测到重新上秤，开始新一轮测量")
            state = State.IDLE
            lockAcked = false
            impedanceSeen = false
            weighStartedEpoch = 0L
        }

        if (state == State.COMMIT) return

        if (weighStartedEpoch == 0L) {
            weighStartedEpoch = epochSeconds
        }
        if (state == State.IDLE) {
            state = State.LIVE
            send(profile(AfuBhProtocol.ProfileVariant.INITIAL, epochSeconds), "资料帧 INITIAL(0x3F)")
        }

        if (frame.weightGrams <= 0) return
        lastWeightGrams = frame.weightGrams

        if (frame.locked && !lockAcked) {
            lockAcked = true
            lockedWeightGrams = frame.weightGrams
            state = State.WAIT_LOCK
            send(AfuBhProtocol.buildAck(AfuBhProtocol.WIRE_LIVE_WEIGHT), "ack 体重锁定")
            onLog("体重已锁定 ${frame.weightGrams / 1000.0} kg，已回 ack(0x51)")
        }
    }

    private fun handleImpedance(frame: AfuBhProtocol.Impedance, epochSeconds: Long) {
        send(AfuBhProtocol.buildAck(AfuBhProtocol.WIRE_IMPEDANCE), "ack 阻抗")
        if (!frame.valid) {
            onLog("收到阻抗帧但标记为无效（阻抗=${frame.resistanceOhm}Ω），已应答")
            return
        }
        impedanceSeen = true
        onLog("收到阻抗 ${frame.resistanceOhm}Ω，已回 ack(0x52)")
        if (state == State.COMMIT || state == State.WAIT_LOCK) {
            send(profile(AfuBhProtocol.ProfileVariant.FINAL, epochSeconds), "资料帧 FINAL(0x68)")
            sync(epochSeconds)
        }
    }

    private fun handleStoredRecord(frame: AfuBhProtocol.StoredRecord, nowMs: Long) {
        // 关键：不论是否采用这条数据，都必须回 ack，否则秤端会无限重发同一条记录
        send(AfuBhProtocol.buildAck(AfuBhProtocol.WIRE_STORED, frame.sequence), "ack 结果记录#${frame.sequence}")
        if (!frame.locked) return
        if (frame.weightGrams <= 0) return
        if (state == State.FINISHED) return

        lastWeightGrams = frame.weightGrams
        if (frame.resistanceOhm > 0) impedanceSeen = true
        if (lockedWeightGrams == 0) lockedWeightGrams = frame.weightGrams
        if (state != State.WAIT_LOCK && state != State.COMMIT) {
            state = State.WAIT_LOCK
            lastMeasureFrameAt = nowMs
        }
        onLog(
            "收到秤端结果记录：${frame.weightGrams / 1000.0} kg" +
                (if (frame.resistanceOhm > 0) " / ${frame.resistanceOhm}Ω" else " / 无阻抗") +
                "（序号 ${frame.sequence}），已回 ack"
        )
    }

    private fun sync(epochSeconds: Long) {
        send(AfuBhProtocol.buildTimeSync(epochSeconds), "校时")
        send(AfuBhProtocol.buildSyncFirst(), "同步帧1")
        send(AfuBhProtocol.buildSyncSecond(), "同步帧2")
    }

    private fun profile(variant: AfuBhProtocol.ProfileVariant, epochSeconds: Long): ByteArray =
        AfuBhProtocol.buildProfile(variant, epochSeconds, userSlot, age, male, targetKg)

    companion object {
        /** 锁定后无测量帧多久算「称完了」。 */
        const val IDLE_MS = 2000L

        /** 收尾阶段最长等待时间。 */
        const val COMMIT_MS = 15000L

        /** 结果帧时间戳与手机的允许偏差（秒）。 */
        const val FRESH_WINDOW_SECONDS = 1800L
    }
}
