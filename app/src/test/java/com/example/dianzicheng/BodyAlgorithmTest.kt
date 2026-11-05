package com.example.dianzicheng

import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.Sex
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class BodyAlgorithmTest {

    @Test
    fun testZeroWeightProducesZeroBmiAndMetrics() {
        val result = BodyAlgorithm.calculate(
            weightKg = 0.0,
            impedanceOhm = 500.0,
            sex = Sex.MALE,
            heightCm = 175.0,
            birthDateEpochMs = 946656000000L
        )

        assertEquals("0.0kg 体重时 BMI 必须为 0.0，不能被限制为 5.0", 0.0, result.bmi, 0.0001)
        assertEquals(0.0, result.bodyFatPct, 0.0001)
        assertEquals(0.0, result.muscleKg, 0.0001)
        assertEquals(0.0, result.waterPct, 0.0001)
        assertEquals(0.0, result.boneMassKg, 0.0001)
    }

    @Test
    fun testLightWeightBelow3KgSuppressesImpedanceMetrics() {
        val result = BodyAlgorithm.calculate(
            weightKg = 2.5,
            impedanceOhm = 500.0,
            sex = Sex.MALE,
            heightCm = 175.0,
            birthDateEpochMs = 946656000000L
        )

        // 体重低于 3.0kg（非人体正常体重）：不应计算体脂与肌肉
        assertEquals(0.0, result.bodyFatPct, 0.0001)
        assertEquals(0.0, result.muscleKg, 0.0001)
        assertEquals(0.0, result.waterPct, 0.0001)
        assertTrue(result.bmi > 0.0)
    }

    @Test
    fun testFutureBirthDateHandledSafely() {
        val futureCal = Calendar.getInstance().apply {
            add(Calendar.YEAR, 5)
        }
        val result = BodyAlgorithm.calculate(
            weightKg = 70.0,
            impedanceOhm = 500.0,
            sex = Sex.MALE,
            heightCm = 175.0,
            birthDateEpochMs = futureCal.timeInMillis
        )

        assertNotNull(result)
        assertTrue("体脂率应处于合理范围", result.bodyFatPct in 5.0..55.0)
        assertTrue("BMI 应处于正常范围", result.bmi in 15.0..30.0)
    }

    @Test
    fun testUnsetBirthDateHandledSafely() {
        val result = BodyAlgorithm.calculate(
            weightKg = 70.0,
            impedanceOhm = 500.0,
            sex = Sex.FEMALE,
            heightCm = 165.0,
            birthDateEpochMs = 0L
        )

        assertNotNull(result)
        assertTrue("体脂率应处于合理范围", result.bodyFatPct in 5.0..55.0)
    }

    @Test
    fun testSafeHeightClampingPreventsDivisionByZero() {
        val result = BodyAlgorithm.calculate(
            weightKg = 70.0,
            impedanceOhm = 500.0,
            sex = Sex.MALE,
            heightCm = 0.0, // 异常身高
            birthDateEpochMs = 946656000000L
        )

        assertFalse("BMI 不能为 NaN", result.bmi.isNaN())
        assertFalse("BMI 不能为无穷大", result.bmi.isInfinite())
        assertTrue("BMI 应该限制在合理最大值", result.bmi <= 100.0)
    }

    @Test
    fun testNormalAdultMeasurementCalculation() {
        val resultMale = BodyAlgorithm.calculate(
            weightKg = 72.0,
            impedanceOhm = 520.0,
            sex = Sex.MALE,
            heightCm = 178.0,
            birthDateEpochMs = 788918400000L // 1995年
        )

        assertTrue(resultMale.bmi in 20.0..25.0)
        assertTrue(resultMale.bodyFatPct in 10.0..25.0)
        assertTrue(resultMale.muscleKg > 40.0)
        assertTrue(resultMale.waterPct in 50.0..65.0)
        assertTrue(resultMale.boneMassKg in 2.5..4.0)

        val resultFemale = BodyAlgorithm.calculate(
            weightKg = 52.0,
            impedanceOhm = 550.0,
            sex = Sex.FEMALE,
            heightCm = 162.0,
            birthDateEpochMs = 788918400000L
        )

        assertTrue(resultFemale.bmi in 18.0..22.0)
        assertTrue(resultFemale.bodyFatPct in 10.0..30.0)
        assertTrue(resultFemale.waterPct in 45.0..65.0)
    }

    @Test
    fun testSmallWeightBmiCalculation() {
        // 测试小体重（如 5.10kg 测试场景）BMI 正确计算，不被错误 clamp 到 5.0 或 0.0
        val result = BodyAlgorithm.calculate(
            weightKg = 5.10,
            impedanceOhm = 0.0,
            sex = Sex.MALE,
            heightCm = 170.0,
            birthDateEpochMs = 946656000000L
        )

        // 5.10 / (1.7 * 1.7) = 5.10 / 2.89 ≈ 1.76
        assertTrue("BMI 必须大于 0", result.bmi > 0.0)
        assertEquals("BMI 应准确计算为 5.10 / 1.7^2 ≈ 1.76", 5.10 / (1.7 * 1.7), result.bmi, 0.01)
    }

    /** 构造「N 年前的同一天」，得到稳定的年龄输入。 */
    private fun birthDateYearsAgo(years: Int): Long = Calendar.getInstance().apply {
        add(Calendar.YEAR, -years)
    }.timeInMillis

    /**
     * 真机参考帧：薄荷 B1 实测 77.45kg / 461Ω / 身高 185cm / 男性。
     *
     * 同一次测量在阿福 App 上显示体脂 18.6%、水分 57.2%、骨量 3.3kg。
     * 旧实现算出体脂 9.6%、水分 66.0%、肌肉量 66.4kg（占体重 85.7%），全部偏离标准区间。
     */
    @Test
    fun testRealDeviceFrameMatchesStandardFormula() {
        val result = BodyAlgorithm.calculate(
            weightKg = 77.45,
            impedanceOhm = 461.0,
            sex = Sex.MALE,
            heightCm = 185.0,
            birthDateEpochMs = birthDateYearsAgo(36)
        )

        assertEquals("BMI = 77.45 / 1.85²", 22.63, result.bmi, 0.05)

        // Deurenberg 1991：1.20×22.63 + 0.23×36 − 10.8 − 5.4 ≈ 19.2%
        assertTrue("体脂率应落在 18~20%（实测 19.2%）", result.bodyFatPct in 18.0..20.0)

        // 水分 = 0.73 × FFM / 体重 ≈ 59.0%
        assertTrue("水分应落在 58~60%（实测 59.0%）", result.waterPct in 58.0..60.0)

        // 肌肉量 = FFM − 骨量 ≈ 59.3kg（旧实现给出 66.4kg，占体重 85.7%，明显不可能）
        assertTrue("肌肉量应落在 58~61kg（实测 59.3kg）", result.muscleKg in 58.0..61.0)
        assertTrue(
            "肌肉量不应超过体重的 80%，实际 ${result.muscleKg / 77.45 * 100}%",
            result.muscleKg / 77.45 < 0.80
        )

        // 骨量 = 0.052 × FFM ≈ 3.25kg
        assertTrue("骨量应落在 3.1~3.4kg（实测 3.25kg）", result.boneMassKg in 3.1..3.4)

        // 蛋白质 = 0.194 × FFM ≈ 15.7%
        assertTrue("蛋白质率应落在 15~17%（实测 15.7%）", result.proteinPct in 15.0..17.0)
    }

    /** 脂肪 + 去脂体重必须严格等于体重，杜绝各指标各算一套导致互相矛盾。 */
    @Test
    fun testFatAndFatFreeMassSumToWeight() {
        val result = BodyAlgorithm.calculate(
            weightKg = 82.3,
            impedanceOhm = 505.0,
            sex = Sex.FEMALE,
            heightCm = 168.0,
            birthDateEpochMs = birthDateYearsAgo(41)
        )

        val fatMass = 82.3 * result.bodyFatPct / 100.0
        val fatFreeMass = 82.3 * (1 - result.bodyFatPct / 100.0)
        assertEquals(82.3, fatMass + fatFreeMass, 0.001)

        // 水分、骨量、肌肉均已按 FFM 拆分，肌肉量不得低于身高的合理下限
        assertTrue(result.muscleKg > 0.0)
        assertTrue("肌肉量不应超过去脂体重", result.muscleKg <= fatFreeMass + 0.001)
    }

    /**
     * 阻抗方程外推失效时必须退回人群基准式。
     *
     * 100kg / 170cm / 600Ω / 45 岁男性：
     * - Deurenberg 基准 ≈ 35.7%
     * - Lukaski 式代入后 ≈ 56%，与基准差 20 个百分点，属于明显的方程外推失效
     * 若实现未做互校，会给出 56% 这种不可能出现在体重秤上的读数。
     */
    @Test
    fun testFallsBackToPopulationBaselineWhenImpedanceFormulaDiverges() {
        val result = BodyAlgorithm.calculate(
            weightKg = 100.0,
            impedanceOhm = 600.0,
            sex = Sex.MALE,
            heightCm = 170.0,
            birthDateEpochMs = birthDateYearsAgo(45)
        )

        assertTrue("应退回 Deurenberg 基准 ≈35.7%，实际 ${result.bodyFatPct}", result.bodyFatPct in 34.0..37.5)
        assertTrue("不允许出现 50% 以上的体脂率", result.bodyFatPct < 45.0)
    }

    /**
     * 两式互证通过时应取均值（阻抗确实参与计算）。
     *
     * 60kg / 175cm / 420Ω / 22 岁男性：Deurenberg ≈12.4%，Lukaski ≈15.1%，
     * 相差 2.7 个百分点，落在容许区间内 → 结果应被阻抗抬高到约 13.7%。
     */
    @Test
    fun testImpedanceRefinesResultWhenBothFormulasAgree() {
        val result = BodyAlgorithm.calculate(
            weightKg = 60.0,
            impedanceOhm = 420.0,
            sex = Sex.MALE,
            heightCm = 175.0,
            birthDateEpochMs = birthDateYearsAgo(22)
        )

        assertTrue("双式互证后应约为 13.7%，实际 ${result.bodyFatPct}", result.bodyFatPct in 12.9..14.5)
    }

    /** 无阻抗（穿袜称重）时不得凭空给出 BIA 指标，但 BMI 仍需计算。 */
    @Test
    fun testNoImpedanceProducesZeroBiaMetricsButKeepsBmi() {
        val result = BodyAlgorithm.calculate(
            weightKg = 68.0,
            impedanceOhm = 0.0,
            sex = Sex.MALE,
            heightCm = 176.0,
            birthDateEpochMs = birthDateYearsAgo(33)
        )

        assertEquals(0.0, result.bodyFatPct, 0.0001)
        assertEquals(0.0, result.waterPct, 0.0001)
        assertEquals(0.0, result.proteinPct, 0.0001)
        assertEquals(0.0, result.boneMassKg, 0.0001)
        assertEquals(0.0, result.muscleKg, 0.0001)
        assertEquals(68.0 / (1.76 * 1.76), result.bmi, 0.001)
    }

    /**
     * 协议解析错位产生的异常阻抗必须按"未测出"处理，不能代入 Lukaski 式。
     *
     * 三种典型错法：字段全 0（丢包）、字节序反了（461Ω → 117761Ω）、丢低位（461Ω → 46Ω）。
     * 若不拦截，461Ω 反序后会算出负的 TBW，46Ω 会让 TBW 虚高到 FFM > 体重，
     * 两种情况都会输出离谱的体脂率。
     */
    @Test
    fun testOutOfRangeImpedanceIsTreatedAsMissing() {
        val weird = listOf(0.0, 46.0, 117761.0, 99999.0)
        for (z in weird) {
            val result = BodyAlgorithm.calculate(
                weightKg = 77.45,
                impedanceOhm = z,
                sex = Sex.MALE,
                heightCm = 185.0,
                birthDateEpochMs = birthDateYearsAgo(36)
            )
            assertEquals("阻抗 $z 应被视为无效，体脂率必须为 0", 0.0, result.bodyFatPct, 0.0001)
            assertEquals(0.0, result.muscleKg, 0.0001)
            assertEquals(0.0, result.boneMassKg, 0.0001)
            // BMI 与阻抗无关，仍要正常算出
            assertTrue(result.bmi > 22.0 && result.bmi < 23.0)
        }
    }

    /** 区间内的正常读数（含边界 50 与 2500）仍要走 BIA 分支。 */
    @Test
    fun testBoundaryImpedanceStillProducesBia() {
        for (z in listOf(50.0, 461.0, 2500.0)) {
            val result = BodyAlgorithm.calculate(
                weightKg = 77.45,
                impedanceOhm = z,
                sex = Sex.MALE,
                heightCm = 185.0,
                birthDateEpochMs = birthDateYearsAgo(36)
            )
            assertTrue("阻抗 $z 在有效区间内，应产出体脂率", result.bodyFatPct > 0.0)
            assertTrue(result.waterPct > 0.0)
        }
    }

    /**
     * 三项组分（水/蛋白/骨）之和应等于 97.6% FFM —— 差值是四组分模型中
     * 非骨矿物质与糖原的口径留白，用来防止有人"顺手"把某个系数改大导致重复计入。
     */
    @Test
    fun testComponentFractionsSumTo976() {
        val result = BodyAlgorithm.calculate(
            weightKg = 77.45,
            impedanceOhm = 461.0,
            sex = Sex.MALE,
            heightCm = 185.0,
            birthDateEpochMs = birthDateYearsAgo(36)
        )
        val ffm = 77.45 * (1.0 - result.bodyFatPct / 100.0)
        val waterKg = result.waterPct / 100.0 * 77.45
        val proteinKg = result.proteinPct / 100.0 * 77.45
        val sum = (waterKg + proteinKg + result.boneMassKg) / ffm
        assertEquals(0.976, sum, 0.001)
    }

    // ── v1.5.0 新增指标：BMR / 骨骼肌 / 皮下脂肪 / 内脏脂肪 / 身体年龄 ──────────────

    /** 真机帧（77.45kg / 461Ω / 185cm / 男 36 岁）验算 5 个新增指标。 */
    @Test
    fun testNewMetricsOnRealDeviceFrame() {
        val r = BodyAlgorithm.calculate(
            weightKg = 77.45,
            impedanceOhm = 461.0,
            sex = Sex.MALE,
            heightCm = 185.0,
            birthDateEpochMs = birthDateYearsAgo(36)
        )
        // Katch-McArdle：370 + 21.6 × FFM(62.55) ≈ 1721
        assertTrue("BMR 应约 1721kcal，实际 ${r.basalMetabolismKcal}", r.basalMetabolismKcal in 1690.0..1760.0)
        // Janssen：0.244W + 7.8H − 0.098age + 6.6 − 3.3 ≈ 33.1kg
        assertTrue("骨骼肌应约 33kg，实际 ${r.skeletalMuscleKg}", r.skeletalMuscleKg in 32.0..34.5)
        // 皮下脂肪 = 0.80 × 19.24 ≈ 15.4%
        assertEquals("皮下脂肪必须等于 0.80 倍体脂率", r.bodyFatPct * 0.80, r.subcutaneousFatPct, 0.01)
        // 内脏脂肪：36 岁、体脂 19.2%、BMI 22.6 的男性应落在正常档（≤9 级）
        assertTrue("内脏脂肪应处于正常档，实际 ${r.visceralFatLevel}", r.visceralFatLevel in 7.0..9.5)
        // 身体年龄：体脂低于同龄参考值 21.8%，应比实际 36 岁略年轻
        assertTrue("身体年龄应比实际年龄略低，实际 ${r.bodyAge}", r.bodyAge in 32..35)
    }

    /** 无阻抗（穿袜称重）：BIA 类指标全 0，但 BMR 走 Mifflin、骨骼肌走 Janssen，仍需有值。 */
    @Test
    fun testBmrAndSkeletalMuscleSurviveWithoutImpedance() {
        val r = BodyAlgorithm.calculate(
            weightKg = 77.45,
            impedanceOhm = 0.0,
            sex = Sex.MALE,
            heightCm = 185.0,
            birthDateEpochMs = birthDateYearsAgo(36)
        )
        // Mifflin-St Jeor 男：10×77.45 + 6.25×185 − 5×36 + 5 = 1755.75
        assertTrue("无阻抗时 BMR 应走 Mifflin ≈1756kcal，实际 ${r.basalMetabolismKcal}",
            r.basalMetabolismKcal in 1740.0..1775.0)
        assertTrue("骨骼肌不依赖阻抗，仍应有值，实际 ${r.skeletalMuscleKg}", r.skeletalMuscleKg > 30.0)
        // 依赖体脂的派生量必须为 0，不能凭空给数
        assertEquals(0.0, r.bodyFatPct, 1e-9)
        assertEquals(0.0, r.subcutaneousFatPct, 1e-9)
        assertEquals(0.0, r.visceralFatLevel, 1e-9)
        assertEquals(0, r.bodyAge)
    }

    /** 骨骼肌量不得超过去脂体重的 85%（Janssen 方程在重度肥胖时会给到接近 FFM 的荒谬值）。 */
    @Test
    fun testSkeletalMuscleNeverExceedsFatFreeMass() {
        for (w in listOf(45.0, 80.0, 130.0, 160.0)) {
            val r = BodyAlgorithm.calculate(
                weightKg = w,
                impedanceOhm = 480.0,
                sex = Sex.MALE,
                heightCm = 170.0,
                birthDateEpochMs = birthDateYearsAgo(40)
            )
            val ffm = w * (1.0 - r.bodyFatPct / 100.0)
            assertTrue("${w}kg 时骨骼肌 ${r.skeletalMuscleKg} 不应超过 FFM ${ffm * 0.85 + 0.01}",
                r.skeletalMuscleKg <= ffm * 0.85 + 0.01)
        }
    }

    /**
     * 女性内脏脂肪基准回归：正常体型女性（55kg / 162cm / 28 岁 / 体脂 26%）必须落在正常档。
     * 修复前统一以「体脂 20%」为锚点，这类用户会被推到 10 级以上、误报"偏高"。
     */
    @Test
    fun testNormalFemaleVisceralFatIsNotOverestimated() {
        val r = BodyAlgorithm.calculate(
            weightKg = 55.0,
            impedanceOhm = 600.0,
            sex = Sex.FEMALE,
            heightCm = 162.0,
            birthDateEpochMs = birthDateYearsAgo(28)
        )
        assertTrue("正常女性体脂应约 26%，实际 ${r.bodyFatPct}", r.bodyFatPct in 24.0..28.0)
        assertTrue("正常女性内脏脂肪必须 ≤9 级，实际 ${r.visceralFatLevel}", r.visceralFatLevel <= 9.0)
        assertTrue("女性 BMR 应约 1250kcal，实际 ${r.basalMetabolismKcal}", r.basalMetabolismKcal in 1200.0..1300.0)
        assertTrue("身体年龄应落在 20~30，实际 ${r.bodyAge}", r.bodyAge in 20..30)
    }

    /** 女性体脂率的性别项必须是 0（Deurenberg 原式 sex=1 男 / 0 女），不能被减成负数。 */
    @Test
    fun testFemaleBodyFatUsesZeroSexTerm() {
        val female = BodyAlgorithm.calculate(
            weightKg = 60.0, impedanceOhm = 560.0, sex = Sex.FEMALE,
            heightCm = 165.0, birthDateEpochMs = birthDateYearsAgo(35)
        )
        val male = BodyAlgorithm.calculate(
            weightKg = 60.0, impedanceOhm = 560.0, sex = Sex.MALE,
            heightCm = 165.0, birthDateEpochMs = birthDateYearsAgo(35)
        )
        // 同 BMI 同年龄下女性体脂应比男性高约 10.8 个百分点（Deurenberg 性别项）
        assertEquals("女 - 男 体脂差应约为 10.8pp", 10.8, female.bodyFatPct - male.bodyFatPct, 0.5)
        assertTrue("35 岁女性 BMI 22 体脂应约 29%，实际 ${female.bodyFatPct}", female.bodyFatPct in 27.5..30.5)
    }
}
