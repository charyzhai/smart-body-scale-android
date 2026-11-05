package com.example.dianzicheng

import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 男女多组样本的全指标跑批校验。
 *
 * 目的不是验证某一个公式，而是确认「身体数据分析」卡片上同时出现的 11 个指标
 * 彼此自洽、落在生理范围内，且性别差异方向正确。测试会打印一张对照表，
 * 跑 `gradlew testDebugUnitTest --tests "*MetricSampleProbeTest*"` 后可在
 * test-results 的 system-out 里看到完整数值。
 */
class MetricSampleProbeTest {

    private data class Sample(
        val label: String,
        val weightKg: Double,
        val impedanceOhm: Double,
        val sex: Sex,
        val heightCm: Double,
        val age: Int,
    )

    private fun birthDateYearsAgo(years: Int): Long = Calendar.getInstance().apply {
        add(Calendar.YEAR, -years)
        add(Calendar.DAY_OF_MONTH, -20) // 避开「今天正好生日」导致年龄差 1
    }.timeInMillis

    private val samples = listOf(
        Sample("男 22岁 178cm 62.0kg 700Ω", 62.0, 700.0, Sex.MALE, 178.0, 22),
        Sample("男 30岁 175cm 70.0kg 520Ω", 70.0, 520.0, Sex.MALE, 175.0, 30),
        Sample("男 36岁 185cm 77.45kg 461Ω", 77.45, 461.0, Sex.MALE, 185.0, 36),
        Sample("男 45岁 172cm 85.0kg 470Ω", 85.0, 470.0, Sex.MALE, 172.0, 45),
        Sample("男 55岁 170cm 92.0kg 440Ω", 92.0, 440.0, Sex.MALE, 170.0, 55),
        Sample("男 30岁 175cm 70.0kg 无阻抗", 70.0, 0.0, Sex.MALE, 175.0, 30),
        Sample("女 20岁 165cm 50.0kg 680Ω", 50.0, 680.0, Sex.FEMALE, 165.0, 20),
        Sample("女 28岁 162cm 55.0kg 600Ω", 55.0, 600.0, Sex.FEMALE, 162.0, 28),
        Sample("女 35岁 165cm 60.0kg 560Ω", 60.0, 560.0, Sex.FEMALE, 165.0, 35),
        Sample("女 45岁 158cm 62.0kg 540Ω", 62.0, 540.0, Sex.FEMALE, 158.0, 45),
        Sample("女 55岁 160cm 75.0kg 500Ω", 75.0, 500.0, Sex.FEMALE, 160.0, 55),
        Sample("女 30岁 160cm 58.0kg 无阻抗", 58.0, 0.0, Sex.FEMALE, 160.0, 30),
    )

    private fun run(s: Sample): BodyMeasurement = BodyAlgorithm.calculate(
        weightKg = s.weightKg,
        impedanceOhm = s.impedanceOhm,
        sex = s.sex,
        heightCm = s.heightCm,
        birthDateEpochMs = birthDateYearsAgo(s.age)
    )

    @Test
    fun probeAllSamplesAndAssertConsistency() {
        println(
            "\n样本 | BMI | 体脂% | FFM | 水分% | 蛋白% | 骨量kg | 肌肉kg | 骨骼肌kg | " +
                "皮下% | 内脏级 | BMR | 身体年龄"
        )
        println("-".repeat(150))

        for (s in samples) {
            val r = run(s)
            val hasBia = r.bodyFatPct > 0.0
            println(
                "${s.label} | ${f(r.bmi, 1)} | ${f(r.bodyFatPct, 1)} | " +
                    "${f(s.weightKg * (1 - r.bodyFatPct / 100), 2)} | ${f(r.waterPct, 1)} | " +
                    "${f(r.proteinPct, 1)} | ${f(r.boneMassKg, 2)} | ${f(r.muscleKg, 2)} | " +
                    "${f(r.skeletalMuscleKg, 2)} | ${f(r.subcutaneousFatPct, 1)} | " +
                    "${f(r.visceralFatLevel, 1)} | ${f(r.basalMetabolismKcal, 0)} | ${r.bodyAge}"
            )

            // ── 通用校验 ────────────────────────────────────────────────
            assertTrue("${s.label}: BMI 异常 ${r.bmi}", r.bmi in 10.0..60.0)
            assertTrue("${s.label}: BMR 异常 ${r.basalMetabolismKcal}",
                r.basalMetabolismKcal in 600.0..4500.0)
            assertTrue("${s.label}: 骨骼肌异常 ${r.skeletalMuscleKg}", r.skeletalMuscleKg >= 0.0)

            if (!hasBia) {
                // 无阻抗：BIA 类指标必须为 0，BMR/骨骼肌仍要有值
                assertEquals("${s.label}: 无阻抗时体脂应为 0", 0.0, r.bodyFatPct, 1e-9)
                assertEquals(0.0, r.waterPct, 1e-9)
                assertEquals(0.0, r.muscleKg, 1e-9)
                assertEquals(0.0, r.subcutaneousFatPct, 1e-9)
                assertEquals(0.0, r.visceralFatLevel, 1e-9)
                assertEquals(0, r.bodyAge)
                assertTrue("${s.label}: 无阻抗时 BMR 仍应有值", r.basalMetabolismKcal > 600.0)
                assertTrue("${s.label}: 无阻抗时骨骼肌仍应有值", r.skeletalMuscleKg > 0.0)
                continue
            }

            val fatKg = s.weightKg * r.bodyFatPct / 100.0
            val ffm = s.weightKg - fatKg
            val waterKg = s.weightKg * r.waterPct / 100.0
            val proteinKg = s.weightKg * r.proteinPct / 100.0

            // 1) 脂肪 + 去脂体重 = 体重
            assertEquals("${s.label}: 脂肪+FFM 必须等于体重", s.weightKg, fatKg + ffm, 1e-9)
            // 2) 肌肉 + 骨量 = 去脂体重
            assertEquals("${s.label}: 肌肉+骨量 必须等于 FFM", ffm, r.muscleKg + r.boneMassKg, 0.05)
            // 3) 水 + 蛋白 + 骨量 = 0.976 FFM（四组分口径留白 2.4%）
            assertEquals("${s.label}: 三项组分和必须为 0.976 FFM",
                0.976 * ffm, waterKg + proteinKg + r.boneMassKg, ffm * 0.002)
            // 4) 骨骼肌不得超过肌肉量（骨骼肌是肌肉的子集）
            assertTrue("${s.label}: 骨骼肌 ${r.skeletalMuscleKg} 不应超过肌肉量 ${r.muscleKg}",
                r.skeletalMuscleKg <= r.muscleKg + 0.01)
            // 5) 皮下脂肪 < 总脂肪
            assertTrue("${s.label}: 皮下脂肪应小于体脂", r.subcutaneousFatPct < r.bodyFatPct)
            // 6) 内脏脂肪等级 1~30
            assertTrue("${s.label}: 内脏脂肪等级越界 ${r.visceralFatLevel}",
                r.visceralFatLevel in 1.0..30.0)
            // 7) 身体年龄 18~80 且偏离实际年龄不超过 20 岁
            assertTrue("${s.label}: 身体年龄越界 ${r.bodyAge}", r.bodyAge in 18..80)
            assertTrue("${s.label}: 身体年龄 ${r.bodyAge} 与实际 ${s.age} 偏离过大",
                kotlin.math.abs(r.bodyAge - s.age) <= 20)
            // 8) BMR 与体重正相关量级合理：每公斤 15~30 kcal
            assertTrue("${s.label}: BMR/体重 比例异常 ${r.basalMetabolismKcal / s.weightKg}",
                r.basalMetabolismKcal / s.weightKg in 15.0..30.0)
        }
    }

    /** 同 BMI、同年龄下，女性体脂率必须高于男性（Deurenberg 性别项 10.8pp）。 */
    @Test
    fun femaleFatMustExceedMaleAtSameBmi() {
        val male = BodyAlgorithm.calculate(70.0, 520.0, Sex.MALE, 175.0, birthDateYearsAgo(35))
        val female = BodyAlgorithm.calculate(70.0, 520.0, Sex.FEMALE, 175.0, birthDateYearsAgo(35))
        assertEquals("同 BMI 下 BMI 应相同", male.bmi, female.bmi, 1e-9)
        assertTrue("女性体脂 ${female.bodyFatPct} 必须高于男性 ${male.bodyFatPct}",
            female.bodyFatPct > male.bodyFatPct + 9.0)
        println("\n同体格男女对照：男体脂=${f(male.bodyFatPct, 1)}%  女体脂=${f(female.bodyFatPct, 1)}%  " +
            "女内脏=${f(female.visceralFatLevel, 1)}级 男内脏=${f(male.visceralFatLevel, 1)}级")
    }

    private fun f(v: Double, digits: Int): String = String.format("%.${digits}f", v)
}
