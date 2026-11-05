package com.example.dianzicheng.domain

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import com.example.dianzicheng.data.local.AppLogger

/**
 * 身体成分计算算法（BIA - 生物电阻抗分析）。
 *
 * ## 计算链
 *
 * 所有指标都从「体重 + 体脂率」向下推，保证内部自洽：
 *
 * ```
 * BMI = 体重 / 身高²
 *   │
 * 体脂率 ──► 去脂体重 FFM = 体重 × (1 − 体脂率)
 *   │            │
 *   │            ├─► 水分  = 0.730 × FFM     （FFM 水合常数）
 *   │            ├─► 蛋白质 = 0.194 × FFM     （四组分模型蛋白占比）
 *   │            ├─► 骨量  = 0.052 × FFM     （骨矿物质占比）
 *   │            └─► 肌肉量 = FFM − 骨量
 * ```
 *
 * ### 关于三项组分之和只有 97.6% FFM
 *
 * 水 0.730 + 蛋白 0.194 + 骨 0.052 = 0.976，并不等于 1.000。这不是遗漏：
 * 四组分模型（Wang ZM et al., 1992, *Am J Clin Nutr*）把 FFM 拆成
 * 水 73.8% / 蛋白 19.4% / 矿物质 6.8%，而其中的矿物质又分为骨矿物质 5.2%
 * 与软组织矿物质 1.6%。本类只输出「骨量」这一项（对应骨矿物质），
 * 剩下的 2.4% = 非骨矿物质 1.6% + 糖原等其他固形物 0.8%，
 * 体脂秤行业惯例不单列，故三项之和必然略小于 FFM。这是口径设计，不是算错。
 *
 * ## 采用的公式与出处
 *
 * 1. **体脂率（人群基准）**：Deurenberg 等，1991，*Br J Nutr*
 *    `体脂率 = 1.20×BMI + 0.23×年龄 − 10.8×性别(男=1/女=0) − 5.4`
 *    标准估计误差 ±4.1 个百分点（R²≈0.79），是不依赖设备的公认基准式。
 *
 * 2. **体脂率（阻抗实测）**：Lukaski & Bolonchuk，1988，*Am J Clin Nutr*
 *    `TBW(L) = 0.372×H²/Z + 3.05×性别 + 0.142×体重 − 0.069×年龄`
 *    `FFM = TBW / 0.73`，`体脂率 = (体重 − FFM) / 体重 × 100`
 *
 * 3. **为什么两个公式都要用**：Lukaski 式是基于**手—脚四电极**全身测量的回归方程，
 *    而本类服务的体脂秤是**双脚站立式**（leg-to-leg）。Nuñez 等（1997，*Med Sci
 *    Sports Exerc*）实测指出，两者测的并非同一段身体：同一受试者双脚压力电极的阻抗
 *    比足底凝胶电极系统性高约 15Ω，且 leg-to-leg 与体成分的相关性明显低于 arm-to-leg；
 *    Tanita 等厂商的换算公式是**专有对数回归**，没有公开标准式。
 *    也就是说：把脚踩秤的原始阻抗直接代进 Lukaski 式，会得到系统性偏高的体脂率
 *    （实测偏差可达 +10 个百分点以上）。
 *    因此这里采用「双式互校」策略：
 *    - 两个公式结果相差 ≤ [BIA_AGREEMENT_PP] 个百分点时，说明这次阻抗测量落在两台
 *      公式都成立的人群区间内，取两者均值以提高精度；
 *    - 相差过大时判定为阻抗方程外推失效（肥胖、极端体型、脚掌接触不良、刚运动完），
 *      退回 Deurenberg 人群基准式，避免给出离谱读数。
 *
 * ## 约定
 *
 * - 阻抗无效（`impedanceOhm <= 0`，如穿袜称重）或体重 < 3kg 时，所有 BIA 指标返回
 *   0.0，不做估算回退，避免误导用户。
 * - BMI 与阻抗无关，始终计算。
 */
object BodyAlgorithm {

    /** 两个体脂公式结果的最大容许偏差（百分点），超出即认为阻抗方程外推失效。 */
    private const val BIA_AGREEMENT_PP = 8.0

    /** 去脂体重（FFM）的水合常数：健康成人 FFM 含水约 73%。 */
    private const val FFM_HYDRATION = 0.73

    /**
     * 人体阻抗的生理合理区间（欧姆）。
     *
     * 双脚站立式体脂秤（leg-to-leg）成人典型读数 350~800Ω，极瘦者可达 900Ω 以上、
     * 重度肥胖者约 300Ω，儿童可到 1000Ω。超出 50~2500Ω 几乎只能来自协议解析错位
     * （字节序搞反会把 461Ω 读成 117761Ω，丢位会读成 46Ω），此时必须当作无阻抗处理，
     * 否则会算出离谱的体脂率。
     */
    private const val MIN_VALID_IMPEDANCE = 50.0
    private const val MAX_VALID_IMPEDANCE = 2500.0

    /** 皮下脂肪占总体脂的比例：内脏脂肪约占 10~20%（男性偏高、女性偏低），此处取 80/20。 */
    private const val SUBCUTANEOUS_OF_FAT = 0.80

    /** 蛋白质占 FFM 的比例（四组分模型：水 73.8% / 蛋白 19.4% / 矿物质 6.8%）。 */
    private const val PROTEIN_OF_FFM = 0.194

    /** 骨矿物质占 FFM 的比例，对应成人「骨量」3.0~3.5kg 的常见区间。 */
    private const val BONE_OF_FFM = 0.052

    /**
     * 计算单次称重的完整身体成分数据。
     *
     * @param weightKg         体重（公斤）
     * @param impedanceOhm     体内阻抗（欧姆），<= 0 表示无有效阻抗数据
     * @param sex              性别（MALE / FEMALE），影响体脂率计算系数
     * @param heightCm         身高（厘米），用于计算 BMI 与 BIA 辅助参数
     * @param birthDateEpochMs 出生日期（Unix 毫秒时间戳），用于推算年龄
     * @return 完整的 [BodyMeasurement] 对象；id 为空字符串，由 repository 层赋值
     */
    fun calculate(
        weightKg: Double,
        impedanceOhm: Double,
        sex: Sex,
        heightCm: Double,
        birthDateEpochMs: Long
    ): BodyMeasurement {
        // 限制身高在生理合理范围（50~250cm），杜绝 heightCm <= 0 时的除以零或 NaN
        val safeHeightCm = heightCm.coerceIn(50.0, 250.0)
        val heightM = safeHeightCm / 100.0
        val bmi = if (weightKg > 0.0) (weightKg / (heightM * heightM)).coerceIn(1.0, 100.0) else 0.0
        val age = calculateAge(birthDateEpochMs)
        val sexFactor = if (sex == Sex.MALE) 1 else 0

        // 仅当测得有效阻抗（生理区间内且体重有效）时才做 BIA 分解。
        // 加入区间校验：协议解析错位产生的 0、几百千欧等异常值必须当作"未测出"，
        // 否则 Lukaski 式会把 Z 错误地代入，得出负 TBW 或 FFM>体重 之类的怪值。
        val hasImpedance = impedanceOhm in MIN_VALID_IMPEDANCE..MAX_VALID_IMPEDANCE &&
            weightKg >= 3.0

        // ── 体脂率（%）─────────────────────────────────────────────────────
        val fat = if (hasImpedance) {
            // ① 人群基准式（Deurenberg 1991）
            val bmiBasedFat = 1.20 * bmi + 0.23 * age - 10.8 * sexFactor - 5.4
            // ② 阻抗实测式（Lukaski 1988 → TBW → FFM）
            val impedanceBasedFat = impedanceBodyFat(weightKg, impedanceOhm, safeHeightCm, age, sexFactor)

            val picked = if (impedanceBasedFat != null &&
                abs(impedanceBasedFat - bmiBasedFat) <= BIA_AGREEMENT_PP
            ) {
                // 两式互证通过：取均值提升精度
                (impedanceBasedFat + bmiBasedFat) / 2.0
            } else {
                // 阻抗方程外推失效，退回人群基准式
                bmiBasedFat
            }
            picked.coerceIn(3.0, 60.0)
        } else {
            0.0
        }

        // ── 去脂体重（公斤）────────────────────────────────────────────────
        val fatFreeMassKg = if (fat > 0.0) weightKg * (1.0 - fat / 100.0) else 0.0

        // ── 体内水分率（%）──────────────────────────────────────────────────
        // 标准做法：TBW = 0.73 × FFM，再除以体重得到占体重的百分比
        val water = if (fat > 0.0)
            (FFM_HYDRATION * fatFreeMassKg / weightKg * 100.0).coerceIn(35.0, 75.0)
        else 0.0

        // ── 骨量（公斤）──────────────────────────────────────────────────────
        val bone = if (fat > 0.0) (BONE_OF_FFM * fatFreeMassKg).coerceIn(1.5, 5.5) else 0.0

        // ── 蛋白质率（%）──────────────────────────────────────────────────────
        // 蛋白质质量 = 0.194 × FFM，同样换算成占体重的百分比
        val protein = if (fat > 0.0)
            (PROTEIN_OF_FFM * fatFreeMassKg / weightKg * 100.0).coerceIn(10.0, 24.0)
        else 0.0

        // ── 肌肉量（公斤）──────────────────────────────────────────────────
        // 去脂体重扣除骨量即为肌肉量（与主流体脂秤口径一致），保证不低于 0
        val muscle = if (fat > 0.0) max(fatFreeMassKg - bone, 0.0) else 0.0

        // ── 基础代谢率（kcal/天）────────────────────────────────────────────
        // 有阻抗 → Katch-McArdle（370 + 21.6×FFM，直接吃去脂体重）；
        // 无阻抗 → Mifflin-St Jeor（人群式，只需体重/身高/年龄/性别）。
        val bmr = if (weightKg >= 3.0) {
            val raw = if (hasImpedance) 370.0 + 21.6 * fatFreeMassKg
            else mifflinBmr(weightKg, safeHeightCm, age, sexFactor)
            raw.coerceIn(600.0, 4500.0)
        } else 0.0

        // ── 骨骼肌量（公斤）─────────────────────────────────────────────────
        // Janssen 2000（MRI 校验的 BIA 人群方程），不依赖阻抗，无阻抗时同样成立。
        // 上界压到 FFM 的 85%：极端肥胖时方程会给出接近甚至超过 FFM 的值。
        val skeletalMuscle = if (weightKg >= 3.0) {
            val janssen = 0.244 * weightKg + 7.8 * heightM - 0.098 * age + 6.6 * sexFactor - 3.3
            val upperBound = if (fatFreeMassKg > 0.0) fatFreeMassKg * 0.85 else weightKg * 0.6
            janssen.coerceIn(0.0, upperBound)
        } else 0.0

        // ── 皮下脂肪率（%）───────────────────────────────────────────────────
        val subcutaneousFat = if (fat > 0.0) (fat * SUBCUTANEOUS_OF_FAT).coerceIn(0.0, 55.0) else 0.0

        // ── 内脏脂肪等级（1~30）─────────────────────────────────────────────
        val visceralFat = if (fat > 0.0) visceralFatLevel(fat, age, bmi, sexFactor) else 0.0

        // ── 身体年龄（岁）───────────────────────────────────────────────────
        val bodyAge = if (fat > 0.0) estimateBodyAge(fat, age, sexFactor) else 0

        return BodyMeasurement(
            id = "", // id 由 ScaleRepository 根据 sessionId 赋值，此处留空
            measuredAtEpochMs = System.currentTimeMillis(),
            weightKg = weightKg,
            impedanceOhm = impedanceOhm,
            bmi = bmi,
            bodyFatPct = fat,
            muscleKg = muscle,
            waterPct = water,
            proteinPct = protein,
            boneMassKg = bone,
            basalMetabolismKcal = bmr,
            skeletalMuscleKg = skeletalMuscle,
            subcutaneousFatPct = subcutaneousFat,
            visceralFatLevel = visceralFat,
            bodyAge = bodyAge,
            memberId = null,           // 成员绑定由 ScaleRepository 处理
            memberNameSnapshot = null
        )
    }

    /**
     * Mifflin-St Jeor 基础代谢率方程（1990，ADA 2005 推荐为成人首选）。
     *
     * ```
     * 男：BMR = 10×体重 + 6.25×身高(cm) − 5×年龄 + 5
     * 女：BMR = 10×体重 + 6.25×身高(cm) − 5×年龄 − 161
     * ```
     * 不使用 Harris-Benedict（1919）：该式基于上世纪初样本，现代人群实测系统性高估 5~15%。
     */
    private fun mifflinBmr(weightKg: Double, heightCm: Double, age: Int, sexFactor: Int): Double =
        10.0 * weightKg + 6.25 * heightCm - 5.0 * age + if (sexFactor == 1) 5.0 else -161.0

    /**
     * 内脏脂肪等级估算（1~30，等级 ≤9 为正常）。
     *
     * ⚠️ 说明：Tanita / Omron 的内脏脂肪等级由专有回归式给出，未公开；市面开源实现
     * 普遍采用「体脂率 + 年龄 + BMI 加权」的经验映射，本方法同样如此：
     * 以 30 岁、BMI 22 的**同性别标准体脂**为锚点（男 20%→9 级、女 28%→5 级），
     * 三个自变量各自线性加权。因此该值只能用于**纵向趋势对比**，不能当作医学测量结果；
     * 真正准确的内脏脂肪需要 CT/MRI 或生物电阻抗分段测量（躯干阻抗），双脚秤物理上拿不到。
     */
    private fun visceralFatLevel(fat: Double, age: Int, bmi: Double, sexFactor: Int): Double {
        // 基准锚点必须按性别分开：女性正常体脂本就比男性高 8 个百分点左右，
        // 若统一用「体脂 20%」为基准，正常女性会被整体推高 3~4 级、误报成内脏脂肪偏高。
        // 男：30 岁基准 9 级；女：30 岁基准 5 级（Tanita 量程中女性普遍低于男性）。
        val base = if (sexFactor == 1) 9.0 else 5.0
        val standardFat = (if (sexFactor == 1) 20.0 else 28.0) + (age - 30) * 0.3
        val raw = base +
            (fat - standardFat) * 0.45 +
            (age - 30) * 0.12 +
            (bmi - 22.0) * 0.35
        return raw.coerceIn(1.0, 30.0)
    }

    /**
     * 身体年龄估算（岁）。
     *
     * 同样是无公开标准的厂商级指标，这里采用可解释的经验映射：
     * 以「30 岁时男性标准体脂 20%、女性 28%，此后每岁增加 0.3 个百分点」为参考曲线
     * （成年后体脂随年龄上升约每十年 3 个百分点），
     * 实测体脂每偏离参考值 1 个百分点，身体年龄偏离 1 岁。
     * 结果限制在 [18, 80] 并取整。仅作趋势参考，误差可达 ±5 岁。
     */
    private fun estimateBodyAge(fat: Double, age: Int, sexFactor: Int): Int {
        val baseFat = if (sexFactor == 1) 20.0 else 28.0
        val standardFat = baseFat + (age - 30) * 0.3
        val estimated = age + (fat - standardFat)
        return estimated.coerceIn(18.0, 80.0).toInt()
    }

    /**
     * 用阻抗估算体脂率（Lukaski & Bolonchuk 1988）。
     *
     * ```
     * TBW(L) = 0.372 × H²/Z + 3.05 × 性别 + 0.142 × 体重 − 0.069 × 年龄
     * FFM    = TBW / 0.73
     * 体脂率 = (体重 − FFM) / 体重 × 100
     * ```
     *
     * @return 体脂率（%）；参数异常导致无意义结果时返回 null
     */
    private fun impedanceBodyFat(
        weightKg: Double,
        impedanceOhm: Double,
        heightCm: Double,
        age: Int,
        sexFactor: Int
    ): Double? {
        if (impedanceOhm !in MIN_VALID_IMPEDANCE..MAX_VALID_IMPEDANCE) return null
        val tbwLiters = 0.372 * (heightCm * heightCm / impedanceOhm) +
            3.05 * sexFactor +
            0.142 * weightKg -
            0.069 * age
        if (tbwLiters <= 0.0) return null
        val fatFreeMass = tbwLiters / FFM_HYDRATION
        // FFM 不得达到或超过总体重（否则体脂率 <= 0，说明数据不可信）
        if (fatFreeMass <= 0.0 || fatFreeMass >= weightKg) return null
        return (weightKg - fatFreeMass) / weightKg * 100.0
    }

    /**
     * 根据出生日期时间戳计算当前年龄（整数岁）。
     *
     * 以月/日逐项比较判定生日是否已过，避免闰年 DAY_OF_YEAR 偏差。
     *
     * Bug #27 修复：出生日期为 0L（未填写）时不再当作 1970-01-01 计算，
     * 现返回中性默认年龄 30 岁，减少未填生日时的计算偏差，并记录警告日志。
     *
     * 注意：**负数时间戳是合法的**（1970 年前出生），不能据此判定未填写，
     * 判定逻辑统一由 [AgeUtils.ageFromBirthDate] 负责。
     *
     * @param birthDateEpochMs 出生日期（Unix 毫秒时间戳），0 表示未设置，负数表示 1970 年前
     * @return 年龄（1~120 范围内，防止异常数据导致崩溃）
     */
    private fun calculateAge(birthDateEpochMs: Long): Int {
        // 换算口径统一收在 AgeUtils：界面展示的年龄、成员管理里填写的年龄、这里参与
        // 计算的年龄必须是同一套规则，否则会出现「界面显示 35 岁、算法按 36 岁算」的错位。
        val age = AgeUtils.ageFromBirthDate(birthDateEpochMs)
        if (age == null) {
            AppLogger.w(
                "BodyAlgorithm",
                "出生日期非法（birthDateEpochMs=$birthDateEpochMs），使用默认年龄 ${AgeUtils.DEFAULT_AGE} 岁参与体成分计算"
            )
            return AgeUtils.DEFAULT_AGE
        }
        return age
    }
}
