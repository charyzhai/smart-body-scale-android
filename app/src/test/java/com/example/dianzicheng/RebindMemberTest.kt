package com.example.dianzicheng

import com.example.dianzicheng.data.repository.rebindMeasurement
import com.example.dianzicheng.data.repository.shouldRefreshReferenceWeight
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 历史记录「更改测量成员」的单元测试。
 *
 * 核心约束：
 * 1. 改绑只改「归属 + 派生指标」，体重/阻抗/称重时间/记录 ID 这些客观测量量一律不动；
 * 2. 派生指标必须真的按**新成员**的性别、身高、年龄重算，而不是沿用旧成员的数值；
 * 3. 成员的参考体重只在改绑「该成员最新一条记录」时才刷新，避免旧记录污染自动匹配。
 */
class RebindMemberTest {

    /** 36 岁、185cm 的男性成员；生日往前多退 30 天，避免运行日恰逢生日导致年龄差 1。 */
    private fun maleMember() = FamilyMember(
        id = "male-1",
        name = "爸爸",
        sex = Sex.MALE,
        heightCm = 185.0,
        birthDateEpochMs = birthYearsAgo(36),
        referenceWeightKg = 77.45
    )

    /** 同为 36 岁、165cm 的女性成员，用来验证换人后体脂口径随之切换。 */
    private fun femaleMember() = FamilyMember(
        id = "female-1",
        name = "妈妈",
        sex = Sex.FEMALE,
        heightCm = 165.0,
        birthDateEpochMs = birthYearsAgo(36),
        referenceWeightKg = 55.0
    )

    private fun birthYearsAgo(years: Int): Long =
        Calendar.getInstance().apply {
            add(Calendar.YEAR, -years)
            add(Calendar.DAY_OF_MONTH, -30)
        }.timeInMillis

    /** 真机那次称重：77.45kg / 461Ω，最初被自动匹配给了爸爸。 */
    private fun measurement(
        weightKg: Double = 77.45,
        impedanceOhm: Double = 461.0,
        memberId: String? = "male-1",
        memberName: String? = "爸爸"
    ) = BodyMeasurement(
        id = "session-777",
        measuredAtEpochMs = 1_790_000_000_000L,
        weightKg = weightKg,
        impedanceOhm = impedanceOhm,
        bmi = 22.63,
        bodyFatPct = 19.2,
        muscleKg = 59.3,
        waterPct = 59.0,
        proteinPct = 15.7,
        boneMassKg = 3.25,
        memberId = memberId,
        memberNameSnapshot = memberName
    )

    @Test
    fun testRebindSwapsOwnerButKeepsRawMeasurements() {
        val origin = measurement()
        val result = rebindMeasurement(origin, femaleMember())

        assertEquals("记录 ID 必须保持不变，否则会多出一条重复记录", "session-777", result.id)
        assertEquals("称重时间不能被改写", origin.measuredAtEpochMs, result.measuredAtEpochMs)
        assertEquals("体重是客观测量值，不能变", 77.45, result.weightKg, 1e-9)
        assertEquals("阻抗是客观测量值，不能变", 461.0, result.impedanceOhm, 1e-9)
        assertEquals("成员 ID 应换成目标成员", "female-1", result.memberId)
        assertEquals("成员姓名快照应换成目标成员", "妈妈", result.memberNameSnapshot)
    }

    @Test
    fun testRebindRecomputesDerivedMetricsWithNewMemberParams() {
        val origin = measurement()
        val asMale = rebindMeasurement(origin, maleMember())
        val asFemale = rebindMeasurement(origin, femaleMember())

        // Deurenberg 中性别项为 -10.8×sex：女性 sex=0 不减这 10.8，同龄同 BMI 下应高出约 10.8pp
        assertTrue(
            "改绑到女性后体脂率必须按女性公式重算（应显著高于男性口径：${asMale.bodyFatPct} vs ${asFemale.bodyFatPct}）",
            asFemale.bodyFatPct > asMale.bodyFatPct + 5.0
        )
        // 身高从 185 换成 165，BMI 必须跟着变，否则说明仍用了旧成员的身高
        assertNotEquals("BMI 必须按新成员身高重算", asMale.bmi, asFemale.bmi, 0.5)
        assertEquals("BMI 应等于 77.45 / 1.65²", 28.45, asFemale.bmi, 0.1)

        // 派生量必须与各自成员参数自洽：脂肪 + 去脂 = 体重
        val ffm = 77.45 * (1 - asFemale.bodyFatPct / 100)
        assertEquals("肌肉 + 骨量应等于去脂体重", ffm, asFemale.muscleKg + asFemale.boneMassKg, 0.05)
    }

    @Test
    fun testRebindFillsAllExtendedMetrics() {
        val result = rebindMeasurement(measurement(), femaleMember())

        assertTrue("基础代谢应被算出", result.basalMetabolismKcal > 800)
        assertTrue("骨骼肌量应被算出", result.skeletalMuscleKg > 0)
        assertTrue("皮下脂肪率应有值", result.subcutaneousFatPct > 0)
        assertTrue("内脏脂肪应在 1~30 之间", result.visceralFatLevel in 1.0..30.0)
        assertTrue("身体年龄应有值", result.bodyAge > 0)
    }

    @Test
    fun testRebindRecordWithoutImpedanceStillWorks() {
        // 只踩秤没测阻抗的记录，改绑后不该崩，也不该凭空造出体脂
        val result = rebindMeasurement(measurement(impedanceOhm = 0.0), femaleMember())

        assertEquals("没有阻抗就不该有体脂率", 0.0, result.bodyFatPct, 1e-9)
        assertEquals("没有阻抗就不该有肌肉量", 0.0, result.muscleKg, 1e-9)
        assertEquals("BMI 仍应算出来", 28.45, result.bmi, 0.1)
        assertEquals("归属仍应更新", "female-1", result.memberId)
    }

    @Test
    fun testReferenceWeightRefreshedOnlyForLatestRecord() {
        // 改绑的就是该成员最新一条：刷新参考体重
        assertTrue(shouldRefreshReferenceWeight("session-777", "session-777"))
        // 成员此前没有任何记录：也刷新
        assertTrue(shouldRefreshReferenceWeight(null, "session-777"))
        // 改绑的是几条之前的历史记录：不能覆盖当前参考体重
        assertFalse(
            "改绑旧记录不得覆盖成员当前参考体重，否则下次自动匹配会认错人",
            shouldRefreshReferenceWeight("session-newest", "session-777")
        )
    }
}
