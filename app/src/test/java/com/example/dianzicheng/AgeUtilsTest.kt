package com.example.dianzicheng

import com.example.dianzicheng.data.local.MeasurementEntity
import com.example.dianzicheng.data.repository.recalculateMeasurement
import com.example.dianzicheng.domain.AgeUtils
import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 年龄换算与「改完年龄后重算」的单元测试。
 *
 * 背景：界面按「岁」给用户填写，数据库按「出生日期」存储，算法按「周岁」参与计算。
 * 三处口径必须完全一致，否则会出现「填 35 岁、卡片显示 34 岁、算法却按 36 岁算」
 * 这种连锁错位，而体脂率、基础代谢、内脏脂肪、身体年龄全都依赖年龄。
 */
class AgeUtilsTest {

    /** 构造某一天中午的时间戳，避开时区/夏令时导致的跨日误差 */
    private fun epochOf(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    @Test
    fun testAgeRoundTripIsStable() {
        val now = epochOf(2026, 11, 4)
        listOf(1, 18, 25, 30, 36, 45, 60, 99, 120).forEach { age ->
            val birth = AgeUtils.birthDateFromAge(age, now)
            assertEquals("填 $age 岁，换算成生日再算回来必须仍是 $age 岁", age, AgeUtils.ageFromBirthDate(birth, now))
        }
    }

    @Test
    fun testBirthdayNotYetPassedThisYear() {
        val now = epochOf(2026, 11, 4)
        // 1990-12-31 出生，今年生日还没到 → 35 周岁
        assertEquals(35, AgeUtils.ageFromBirthDate(epochOf(1990, 12, 31), now))
        // 1990-01-01 出生，今年生日已过 → 36 周岁
        assertEquals(36, AgeUtils.ageFromBirthDate(epochOf(1990, 1, 1), now))
    }

    @Test
    fun testBirthdayTodayCountsAsNewAge() {
        val now = epochOf(2026, 11, 4)
        // 生日当天即算长一岁
        assertEquals(36, AgeUtils.ageFromBirthDate(epochOf(1990, 11, 4), now))
    }

    @Test
    fun testInvalidBirthDateReturnsNullAndFallback() {
        val now = epochOf(2026, 11, 4)
        assertNull("未设置生日（0L 哨兵值）应返回 null，由调用方决定兜底", AgeUtils.ageFromBirthDate(0L, now))
        assertNull("1900 年前的日期明显异常，应判为无效", AgeUtils.ageFromBirthDate(epochOf(1850, 1, 1), now))
        assertNull("未来生日应返回 null", AgeUtils.ageFromBirthDate(epochOf(2030, 1, 1), now))
        assertEquals(
            "界面展示遇到非法生日应回落到默认年龄，不能显示 0 岁",
            AgeUtils.DEFAULT_AGE,
            AgeUtils.displayAge(0L, now)
        )
    }

    /**
     * 回归测试：1970 年前出生的成员，时间戳为负数，不能被当成「未填写」而回落 30 岁。
     * 60 岁以上的长辈用这台秤时，此前体脂率/基础代谢/身体年龄全部按 30 岁算。
     */
    @Test
    fun testPre1970BirthDateIsTreatedAsReal() {
        val now = epochOf(2026, 11, 4)
        val birth1966 = epochOf(1966, 5, 20)
        assertTrue("1966 年出生的时间戳必须是负数（验证测试前提）", birth1966 < 0L)
        assertEquals("1966 年生的人应算出 60 岁，而不是回落到 30 岁", 60, AgeUtils.ageFromBirthDate(birth1966, now))

        val result = BodyAlgorithm.calculate(
            weightKg = 65.0, impedanceOhm = 500.0, sex = Sex.FEMALE,
            heightCm = 158.0, birthDateEpochMs = birth1966
        )
        // 按 60 岁代入 Deurenberg：1.20×26.04 + 0.23×60 − 0 − 5.4 ≈ 39.6%；
        // 若年龄被误判成 30 岁只会得到 32.7%，两者差 6.9pp，足以区分
        assertEquals("60 岁成员必须按 60 岁算体脂率", 39.6, result.bodyFatPct, 0.3)
    }

    /** 同一份测量数据，只把年龄从 25 改到 35，体脂率必须按 Deurenberg 的 0.23×年龄 变化 */
    @Test
    fun testChangingAgeChangesDerivedMetrics() {
        val entity = MeasurementEntity(
            id = "session-001",
            measuredAtEpochMs = 1_790_000_000_000L,
            weightKg = 77.45,
            impedanceOhm = 461.0,
            bmi = 22.63, bodyFatPct = 19.2, muscleKg = 59.3, waterPct = 59.0,
            proteinPct = 15.7, boneMassKg = 3.25,
            memberId = "m1", memberNameSnapshot = "本人"
        )
        val now = System.currentTimeMillis()

        fun memberOf(age: Int) = FamilyMember(
            id = "m1", name = "本人", sex = Sex.MALE, heightCm = 185.0,
            birthDateEpochMs = AgeUtils.birthDateFromAge(age, now), referenceWeightKg = 77.45
        )

        val at25 = recalculateMeasurement(entity, memberOf(25))
        val at35 = recalculateMeasurement(entity, memberOf(35))

        assertEquals(
            "年龄 +10 岁，体脂率应上升约 2.3pp（Deurenberg 0.23×10）；若没变化说明年龄没进公式",
            2.3, at35.bodyFatPct - at25.bodyFatPct, 0.3
        )
        assertTrue("基础代谢会随年龄变化", at25.basalMetabolismKcal != at35.basalMetabolismKcal)
        assertTrue("身体年龄应随档案年龄上移", at35.bodyAge > at25.bodyAge)
    }

    /** 直接用算法层核对口径一致性，防止界面显示的年龄与算法使用的年龄再次分叉 */
    @Test
    fun testAlgorithmUsesSameAgeAsDisplay() {
        val age = 42
        val birth = AgeUtils.birthDateFromAge(age)
        assertEquals(age, AgeUtils.displayAge(birth))

        // 与该年龄的参照样本对比：同龄成员算出的体脂率应与手动用 42 岁代入 Deurenberg 一致
        val result = BodyAlgorithm.calculate(
            weightKg = 70.0, impedanceOhm = 0.0, sex = Sex.MALE,
            heightCm = 175.0, birthDateEpochMs = birth
        )
        val bmi = 70.0 / (1.75 * 1.75)
        val expected = 1.20 * bmi + 0.23 * 42 - 10.8 * 1 - 5.4
        // 无阻抗时骨骼肌等仍可算，但体脂率为 0（BIA 需要阻抗），故这里只校验 BMI
        assertEquals(bmi, result.bmi, 1e-6)
        assertTrue("无阻抗时体脂率为 0，不应凭空产生", result.bodyFatPct == 0.0)
        // 有阻抗时同一生日应产出与 42 岁 Deurenberg 一致的结果
        val withZ = BodyAlgorithm.calculate(
            weightKg = 70.0, impedanceOhm = 700.0, sex = Sex.MALE,
            heightCm = 175.0, birthDateEpochMs = birth
        )
        assertEquals("体脂率应与按 42 岁代入公式一致", expected.coerceIn(3.0, 60.0), withZ.bodyFatPct, 0.5)
    }
}
