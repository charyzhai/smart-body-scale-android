package com.example.dianzicheng.domain

/** 性别枚举，用于体脂算法中的参数区分 */
enum class Sex { FEMALE, MALE }

/**
 * 家庭成员信息。
 *
 * @param id                唯一标识符（UUID 字符串）
 * @param name              成员姓名
 * @param sex               性别，用于体脂率、肌肉量等 BIA 计算
 * @param heightCm          身高（厘米），用于计算 BMI 和体脂率
 * @param birthDateEpochMs  出生日期（Unix 毫秒时间戳），用于计算年龄
 * @param referenceWeightKg 最近一次测量体重（公斤），用于多人智能匹配时的参考依据；
 *                          首次称重前为 0.0
 */
data class FamilyMember(
    val id: String,
    val name: String,
    val sex: Sex,
    val heightCm: Double,
    val birthDateEpochMs: Long,
    val referenceWeightKg: Double,
)

/**
 * 单次体重测量结果，包含体重和通过 BIA（生物电阻抗分析）计算的身体成分。
 *
 * 注意：当 [impedanceOhm] 为 0 时，表示本次未测得有效阻抗（未赤脚称重），
 * 此时 [bodyFatPct]、[muscleKg]、[waterPct]、[proteinPct]、[boneMassKg] 均为 0.0。
 *
 * @param id                  唯一标识符（同一次称量的 sessionId，避免重复保存）
 * @param measuredAtEpochMs   称重时间（Unix 毫秒时间戳）
 * @param weightKg            体重（公斤）
 * @param impedanceOhm        体内阻抗（欧姆），0.0 表示本次未测出
 * @param bmi                 体质指数 = 体重 / 身高²
 * @param bodyFatPct          体脂率（%），Deurenberg 1991 与 Lukaski 1988 双式互校，仅当有有效阻抗时计算
 * @param muscleKg            肌肉量（公斤）= 去脂体重 − 骨量，仅当有有效阻抗时计算
 * @param waterPct            体内水分率（%）= 0.73 × 去脂体重 / 体重，仅当有有效阻抗时计算
 * @param proteinPct          蛋白质率（%）= 0.194 × 去脂体重 / 体重，仅当有有效阻抗时计算
 * @param boneMassKg          骨量（公斤）= 0.052 × 去脂体重，仅当有有效阻抗时计算
 * @param basalMetabolismKcal 基础代谢率（kcal/天）：有阻抗用 Katch-McArdle，无阻抗用 Mifflin-St Jeor
 * @param skeletalMuscleKg    骨骼肌量（公斤），Janssen 2000 人群方程，不依赖阻抗
 * @param subcutaneousFatPct  皮下脂肪率（%）= 0.80 × 体脂率，仅当有有效阻抗时计算
 * @param visceralFatLevel    内脏脂肪等级（1~30，≤9 正常），经验映射，仅供趋势参考
 * @param bodyAge             身体年龄（岁），由体脂率相对同龄参考值的偏离折算，0 表示未计算
 * @param memberId            关联的家庭成员 ID，null 表示未绑定成员
 * @param memberNameSnapshot  记录保存时的成员姓名快照（防止成员改名后历史数据混乱）
 */
data class BodyMeasurement(
    val id: String,
    val measuredAtEpochMs: Long,
    val weightKg: Double,
    val impedanceOhm: Double,
    val bmi: Double,
    val bodyFatPct: Double,
    val muscleKg: Double,
    val waterPct: Double,
    val proteinPct: Double,
    val boneMassKg: Double,
    val memberId: String?,
    val memberNameSnapshot: String?,
    val basalMetabolismKcal: Double = 0.0,
    val skeletalMuscleKg: Double = 0.0,
    val subcutaneousFatPct: Double = 0.0,
    val visceralFatLevel: Double = 0.0,
    val bodyAge: Int = 0,
)
