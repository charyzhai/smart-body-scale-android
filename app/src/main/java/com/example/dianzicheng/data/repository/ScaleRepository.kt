package com.example.dianzicheng.data.repository

import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.MeasurementEntity
import com.example.dianzicheng.data.local.ScaleDao
import com.example.dianzicheng.data.local.toDomain
import com.example.dianzicheng.data.local.toEntity
import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.*
import kotlin.math.abs

/**
 * 体重秤数据仓库，封装 Room DAO 操作，提供领域层可用的接口。
 *
 * 职责：
 * 1. 提供测量记录和家庭成员的增删查 Flow/挂起函数接口
 * 2. 保存测量时自动执行成员智能匹配与 BIA 指标重新计算
 * 3. 在保存成功后更新成员参考体重，提升下次匹配精准度
 */
class ScaleRepository(private val dao: ScaleDao) {

    /**
     * 获取所有测量历史记录（Flow，按时间倒序）。
     * 历史页订阅此 Flow，数据库更新后自动推送最新列表。
     */
    fun getHistory(): Flow<List<BodyMeasurement>> =
        dao.getAllMeasurements().map { entities -> entities.map { it.toDomain() } }

    /**
     * 一次性获取所有测量记录列表（挂起函数）。
     * 用于 Health Connect 批量同步、WebDAV 备份等非实时场景。
     */
    suspend fun getAllMeasurements(): List<BodyMeasurement> =
        dao.getAllMeasurementsList().map { it.toDomain() }

    /**
     * 获取所有家庭成员列表（Flow）。
     * ScaleViewModel 和 ProfileViewModel 均订阅此 Flow 以保持成员列表同步。
     */
    fun getMembers(): Flow<List<FamilyMember>> =
        dao.getAllMembers().map { entities -> entities.map { it.toDomain() } }

    /**
     * 保存或覆盖更新一条测量记录，并触发成员匹配逻辑。
     *
     * 优先级（成员绑定）：
     * 1. 传入的 [targetMember]（用户手动选择）
     * 2. 记录中已有的 memberId 所对应的成员
     * 3. 通过 [findBestMember] 智能匹配最近的成员
     * 4. 以上均失败：不绑定成员，只保存体重数据
     *
     * 若成功绑定成员，会用该成员的真实参数（性别、身高、年龄）重新计算体脂率等 BIA 指标，
     * 并将本次体重更新到成员的 [FamilyMember.referenceWeightKg]，供下次匹配使用。
     *
     * @param measurement 原始测量数据（体重、阻抗等）
     * @param targetMember 用户手动指定的目标成员，null 表示自动匹配
     * @param existingId 已有的会话 ID，传入时用于覆盖同一次称量的旧记录（防止重复条目）
     * @return Pair<最终保存的测量数据, 实际绑定的成员（null 表示未绑定）>
     */
    suspend fun saveMeasurement(
        measurement: BodyMeasurement,
        targetMember: FamilyMember? = null,
        existingId: String? = null
    ): Pair<BodyMeasurement, FamilyMember?> {
        val members = dao.getMembersList().map { it.toDomain() }

        // 按优先级确定实际绑定的成员
        val memberToBind = targetMember
            ?: members.firstOrNull { it.id == measurement.memberId }
            ?: findBestMember(measurement.weightKg, members)

        // 若有匹配成员，用其真实参数重新计算 BIA 指标；否则保留原始数据
        val recalculated = if (memberToBind != null) {
            com.example.dianzicheng.domain.BodyAlgorithm.calculate(
                weightKg = measurement.weightKg,
                impedanceOhm = measurement.impedanceOhm,
                sex = memberToBind.sex,
                heightCm = memberToBind.heightCm,
                birthDateEpochMs = memberToBind.birthDateEpochMs
            ).copy(
                // 使用传入的 existingId 保证同次称量幂等写入，否则生成新 UUID
                id = existingId ?: measurement.id.ifEmpty { UUID.randomUUID().toString() },
                // 保留原始称量时间戳，不被 BodyAlgorithm 内的 System.currentTimeMillis() 覆盖
                measuredAtEpochMs = measurement.measuredAtEpochMs,
                memberId = memberToBind.id,
                memberNameSnapshot = memberToBind.name
            )
        } else {
            // 未匹配到成员时：优先使用系统已有成员身高或默认 170cm 兜底计算有效 BMI，杜绝 BMI 为 0.0
            val fallbackHeightCm = members.firstOrNull()?.heightCm ?: 170.0
            val heightM = (fallbackHeightCm.coerceIn(50.0, 250.0)) / 100.0
            val fallbackBmi = if (measurement.bmi > 0.0) measurement.bmi
                else if (measurement.weightKg > 0.0) (measurement.weightKg / (heightM * heightM)).coerceIn(1.0, 100.0)
                else 0.0
            measurement.copy(
                id = existingId ?: measurement.id.ifEmpty { UUID.randomUUID().toString() },
                bmi = fallbackBmi
            )
        }

        // 写入数据库（REPLACE 策略：同 ID 覆盖旧记录）
        dao.insertMeasurement(recalculated.toEntity())

        // 用本次体重更新成员参考体重，供下次智能匹配参考
        memberToBind?.let { member ->
            dao.insertMember(member.copy(referenceWeightKg = measurement.weightKg).toEntity())
        }

        return Pair(recalculated, memberToBind)
    }

    /**
     * 将已有测量记录重新绑定到指定成员，并以该成员参数重新计算 BIA 指标后写库。
     *
     * 使用场景：
     * 1. 称重结果卡片上手动「重选/绑定成员」；
     * 2. 历史列表里点成员名改绑（自动匹配错人时的补救手段）。
     *
     * 重算口径与 [recalculateMeasurement] 完全一致：用目标成员的性别、身高、生日
     * 重新跑一遍 [BodyAlgorithm.calculate]，**全部 12 项派生指标**（BMI / 体脂率 /
     * 水分 / 蛋白质 / 骨量 / 肌肉 / 骨骼肌 / 基础代谢 / 皮下脂肪 / 内脏脂肪 /
     * 身体年龄）都会刷新；体重、阻抗、称重时间、记录 ID 保持不变。
     *
     * @param measurement 要重新绑定的原始测量记录
     * @param member 用户选择的目标成员
     * @return 重新计算后的测量记录
     */
    suspend fun bindMeasurementToMember(measurement: BodyMeasurement, member: FamilyMember): BodyMeasurement {
        val recalculated = rebindMeasurement(measurement, member)
        dao.insertMeasurement(recalculated.toEntity())

        // 同步成员参考体重，供下次自动匹配使用。
        // 仅当这条记录就是该成员的最新一条时才更新：改绑几个月前的历史记录时，
        // 若拿旧体重覆盖参考值，会让下一次智能匹配直接认错人。
        val latest = dao.getLatestMeasurementForMember(member.id)
        if (shouldRefreshReferenceWeight(latest?.id, measurement.id)) {
            dao.insertMember(member.copy(referenceWeightKg = measurement.weightKg).toEntity())
        }

        return recalculated
    }

    /**
     * 用当前成员档案与当前版体成分算法，**一次性重算全部历史记录**。
     *
     * 使用场景：体成分公式修正后，把用旧公式落库的历史记录刷新成新口径，
     * 避免趋势图上出现「升级那天断崖式跳变」。由设置页的按钮手动触发，幂等可重复执行。
     *
     * 处理规则（对应历史数据里存在的两类「不该重算」的记录）：
     * - `memberId` 为空：没有性别 / 身高 / 生日，无法可信重算 BIA，原样保留；
     * - `impedanceOhm <= 0` 或体重 < 3kg：本来就没有阻抗数据，重算也只会得到 0，原样保留。
     *
     * 重算时**只覆盖派生指标**（BMI / 体脂率 / 水分 / 蛋白质 / 骨量 / 肌肉量），
     * 原始测量量（体重、阻抗、称重时间、记录 ID、成员绑定、成员姓名快照）全部保留。
     *
     * 注意：使用的是成员**当前**档案；若成员在称重之后改过身高，重算结果会按新身高计算。
     *
     * @return 重算统计，见 [RecalcSummary]
     */
    suspend fun recalculateAllMeasurements(): RecalcSummary {
        val members = dao.getMembersList().map { it.toDomain() }.associateBy { it.id }
        val all = dao.getAllMeasurementsList()

        val summary = recalcEntities(all, members)
        AppLogger.i("ScaleRepository", "历史体成分重算完成：${summary.describe()}")
        return summary
    }

    /**
     * 只重算某一位成员的历史记录。
     *
     * 使用场景：在「我的 - 成员管理」里修改了成员的性别 / 身高 / 年龄。
     * 这三项是体成分公式的直接输入，改完若不重算，该成员的历史数值就会停留在旧档案口径
     * （例如把年龄从 25 改成 40，身体年龄、内脏脂肪、基础代谢全都该变）。
     * 由 [ProfileViewModel.updateMember] 自动调用，无需用户再手动点一次「重新计算」。
     *
     * 只扫描该成员的记录，不会牵连其他成员，即使家里有多人也不受影响。
     *
     * @param memberId 成员 ID
     * @return 重算统计；成员不存在时返回全 0
     */
    suspend fun recalculateMeasurementsForMember(memberId: String): RecalcSummary {
        val member = dao.getMembersList().map { it.toDomain() }.firstOrNull { it.id == memberId }
            ?: return RecalcSummary(0, 0, 0, 0, 0)
        val mine = dao.getAllMeasurementsList().filter { it.memberId == memberId }
        val summary = recalcEntities(mine, mapOf(member.id to member))
        AppLogger.i("ScaleRepository", "成员「${member.name}」历史重算完成：${summary.describe()}")
        return summary
    }

    /**
     * 重算一批记录的公共实现，供全量重算与单人重算共用，保证两处口径完全一致。
     *
     * @param entities 待重算的记录
     * @param membersById 成员 ID → 成员档案
     */
    private suspend fun recalcEntities(
        entities: List<MeasurementEntity>,
        membersById: Map<String, FamilyMember>
    ): RecalcSummary {
        var updated = 0
        var skippedNoMember = 0
        var skippedNoImpedance = 0
        var failed = 0

        for (entity in entities) {
            val member = entity.memberId?.let { membersById[it] }
            if (member == null) {
                skippedNoMember++
                continue
            }
            if (!isRecalculable(entity)) {
                skippedNoImpedance++
                continue
            }
            val fresh = try {
                recalculateMeasurement(entity, member)
            } catch (e: Exception) {
                AppLogger.e("ScaleRepository", "重算历史记录 ${entity.id} 失败: ${e.message}")
                null
            }
            if (fresh == null) {
                failed++
                continue
            }
            dao.insertMeasurement(fresh)
            updated++
        }

        return RecalcSummary(
            total = entities.size,
            updated = updated,
            skippedNoMember = skippedNoMember,
            skippedNoImpedance = skippedNoImpedance,
            failed = failed
        )
    }

    /**
     * 根据当前体重，从数据库中查找最匹配的家庭成员。
     * 是 [findBestMember] 的挂起函数包装，供外部（如 ScaleViewModel）调用。
     */
    suspend fun getBestMember(weightKg: Double): FamilyMember? {
        val members = dao.getMembersList().map { it.toDomain() }
        return findBestMember(weightKg, members)
    }

    /**
     * 成员智能匹配算法，从成员列表中找到体重最接近的成员。
     *
     * 匹配策略（按优先级）：
     * 1. 只有 1 个成员时，直接返回该成员（单用户家庭无歧义）
     * 2. 多成员中若全为新成员（referenceWeightKg <= 0.0），优先绑定第一个未测成员
     * 3. 在已测过体重的成员中，寻找体重最接近的成员（容差放宽至 10.0kg，适应日常体重正常浮动与衣服波动）
     * 4. 若与已测成员差值较大，但存在未测过体重的新增成员，优先归属于未测成员
     * 5. 兜底返回最接近的已有成员，避免数据彻底孤立
     *
     * @param weightKg 本次称量体重（公斤）
     * @param members 候选成员列表
     * @return 最匹配的成员，无法匹配时为 null
     */
    private fun findBestMember(weightKg: Double, members: List<FamilyMember>): FamilyMember? {
        if (members.isEmpty()) return null

        // 1. 单成员：直接自动绑定
        if (members.size == 1) {
            return members.first()
        }

        // 2. 多成员中若全为新成员（referenceWeightKg <= 0.0），优先绑定第一个未测成员
        val measuredMembers = members.filter { it.referenceWeightKg > 0.0 }
        if (measuredMembers.isEmpty()) {
            return members.first()
        }

        // 3. 在已测过体重的成员中，寻找体重最接近的成员（容差放宽至 10.0kg，适应日常体重正常浮动与衣服波动）
        val best = measuredMembers.minByOrNull { abs(it.referenceWeightKg - weightKg) }
        if (best != null && abs(best.referenceWeightKg - weightKg) <= 10.0) {
            return best
        }

        // 4. 若与已测成员差值较大，但存在未测过体重的新增成员，优先归属于未测成员
        val unmeasured = members.firstOrNull { it.referenceWeightKg <= 0.0 }
        if (unmeasured != null) {
            return unmeasured
        }

        // 5. 兜底返回最接近的已有成员，避免数据彻底孤立
        return best ?: members.first()
    }

    /** 删除一条测量记录 */
    suspend fun deleteMeasurement(measurement: BodyMeasurement) {
        dao.deleteMeasurement(measurement.toEntity())
    }

    /** 按 ID 删除测量记录，用于作废无效会话 */
    suspend fun deleteMeasurementById(id: String) {
        dao.deleteMeasurementById(id)
    }
}

/**
 * 历史体成分重算的统计结果。
 *
 * @param total             扫描到的记录总数
 * @param updated           实际重算并写回的记录数
 * @param skippedNoMember   因未绑定成员而跳过的记录数（无性别/身高/生日，无法可信重算）
 * @param skippedNoImpedance 因缺少有效阻抗（或体重过小）而跳过的记录数
 * @param failed            重算过程中抛异常的记录数
 */
data class RecalcSummary(
    val total: Int,
    val updated: Int,
    val skippedNoMember: Int,
    val skippedNoImpedance: Int,
    val failed: Int,
) {
    /** 未处理（跳过 + 失败）的记录数。 */
    val untouched: Int get() = skippedNoMember + skippedNoImpedance + failed

    /** 面向用户的一句话结果，用于 Toast / 状态栏提示。 */
    fun describe(): String = buildString {
        append("已重算 $updated 条记录")
        if (untouched > 0) {
            append("，未处理 $untouched 条")
            val reasons = buildList {
                if (skippedNoMember > 0) add("$skippedNoMember 条未绑定成员")
                if (skippedNoImpedance > 0) add("$skippedNoImpedance 条无阻抗数据")
                if (failed > 0) add("$failed 条失败")
            }
            if (reasons.isNotEmpty()) append("（${reasons.joinToString("、")}）")
        }
    }
}

/**
 * 判断一条历史记录是否具备重算条件。
 *
 * 只有既有有效体重、又测到过阻抗的记录才有 BIA 指标可谈；
 * 其余记录重算只会得到 0，没有意义，直接跳过保留原值。
 *
 * @param entity 数据库中的测量记录
 */
internal fun isRecalculable(entity: MeasurementEntity): Boolean =
    entity.weightKg >= 3.0 && entity.impedanceOhm > 0.0

/**
 * 按成员当前档案重算一条历史记录的派生指标，返回可直接写回数据库的实体。
 *
 * **保留字段**：记录 ID、称重时间、体重、阻抗、成员 ID、成员姓名快照。
 * **刷新字段**：BMI、体脂率、水分率、蛋白质率、骨量、肌肉量。
 *
 * 抽成顶层纯函数是为了能在单元测试里脱离 Room 直接验证「原始量不被改写」。
 *
 * @param entity 原始测量记录
 * @param member 该记录绑定的成员（提供性别 / 身高 / 出生日期）
 * @return 重算后的实体，结构与 [entity] 的 ID 一致，可直接 `INSERT OR REPLACE`
 */
internal fun recalculateMeasurement(entity: MeasurementEntity, member: FamilyMember): MeasurementEntity =
    BodyAlgorithm.calculate(
        weightKg = entity.weightKg,
        impedanceOhm = entity.impedanceOhm,
        sex = member.sex,
        heightCm = member.heightCm,
        birthDateEpochMs = member.birthDateEpochMs
    ).copy(
        // 原始测量量一律以数据库中的值为准，算法内部的 now() / null 绑定不得覆盖
        id = entity.id,
        measuredAtEpochMs = entity.measuredAtEpochMs,
        memberId = entity.memberId,
        memberNameSnapshot = entity.memberNameSnapshot
    ).toEntity()

/**
 * 把一条测量记录改绑到另一位成员，并按目标成员的档案重算全部派生成分。
 *
 * 与 [recalculateMeasurement] 的区别只在「成员归属」：
 * - 前者保持原成员不变，只刷新算法口径；
 * - 本函数把 `memberId` / `memberNameSnapshot` 换成目标成员，再用其性别、身高、生日重算。
 *
 * **保留**：记录 ID、称重时间、体重、阻抗 —— 这是既不随人而变的客观测量值。
 * **刷新**：BMI、体脂率、水分、蛋白质、骨量、肌肉、骨骼肌、基础代谢、皮下脂肪、
 * 内脏脂肪、身体年龄 —— 这些全都依赖成员档案，换了人就必须重算。
 *
 * 抽成顶层纯函数，便于单测脱离 Room 验证「改绑只改归属与派生量，不动原始量」。
 *
 * @param measurement 原记录
 * @param member 目标成员
 * @return 改绑并重算后的记录，ID 与原记录相同，可直接 `INSERT OR REPLACE`
 */
internal fun rebindMeasurement(measurement: BodyMeasurement, member: FamilyMember): BodyMeasurement =
    BodyAlgorithm.calculate(
        weightKg = measurement.weightKg,
        impedanceOhm = measurement.impedanceOhm,
        sex = member.sex,
        heightCm = member.heightCm,
        birthDateEpochMs = member.birthDateEpochMs
    ).copy(
        id = measurement.id,
        measuredAtEpochMs = measurement.measuredAtEpochMs,
        memberId = member.id,
        memberNameSnapshot = member.name
    )

/**
 * 判断改绑后是否应该刷新成员的参考体重。
 *
 * 参考体重只服务于「下次称重自动认人」，必须代表成员**最近**的体重水平。
 * 因此只有当被改绑的这条记录就是该成员的最新记录时才更新；改绑一条几个月前的
 * 历史记录时若拿旧体重覆盖，下一次自动匹配会整体跑偏。成员尚无任何记录时也更新。
 *
 * @param latestMeasurementId 该成员当前最新记录的 ID，null 表示没有任何记录
 * @param rebindMeasurementId 本次改绑的记录 ID
 */
internal fun shouldRefreshReferenceWeight(latestMeasurementId: String?, rebindMeasurementId: String): Boolean =
    latestMeasurementId == null || latestMeasurementId == rebindMeasurementId
