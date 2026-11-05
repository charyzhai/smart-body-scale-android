package com.example.dianzicheng

import com.example.dianzicheng.data.local.MeasurementEntity
import com.example.dianzicheng.data.repository.isRecalculable
import com.example.dianzicheng.data.repository.recalculateMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 历史记录一次性重算（设置页「重新计算历史体成分」按钮）的单元测试。
 *
 * 重点验证两件事：
 * 1. **只刷新派生指标**——体重、阻抗、称重时间、记录 ID、成员绑定与姓名快照必须原样保留，
 *    否则重算会把用户的原始测量数据改成算法输出的值，属于不可接受的数据损坏；
 * 2. **跳过规则**——未绑定成员或没有阻抗的记录不应被重算（否则会把有效值刷成 0）。
 */
class RecalcHistoryTest {

    /** 构造一条用旧公式落库的历史记录（体脂 9.6% 明显偏低）。 */
    private fun legacyEntity(
        id: String = "session-001",
        weightKg: Double = 77.45,
        impedanceOhm: Double = 461.0,
        measuredAt: Long = 1_790_000_000_000L,
        memberId: String? = "member-1",
        memberName: String? = "本人",
    ) = MeasurementEntity(
        id = id,
        measuredAtEpochMs = measuredAt,
        weightKg = weightKg,
        impedanceOhm = impedanceOhm,
        bmi = 22.6,
        bodyFatPct = 9.6,
        muscleKg = 66.4,
        waterPct = 66.0,
        proteinPct = 17.5,
        boneMassKg = 3.64,
        memberId = memberId,
        memberNameSnapshot = memberName
    )

    /** 36 岁男性，身高 185cm。生日取「36 年前再往前 30 天」，避免运行日期正好是生日导致年龄差 1。 */
    private fun maleMember(): FamilyMember {
        val birth = Calendar.getInstance().apply {
            add(Calendar.YEAR, -36)
            add(Calendar.DAY_OF_MONTH, -30)
        }.timeInMillis
        return FamilyMember(
            id = "member-1",
            name = "本人",
            sex = Sex.MALE,
            heightCm = 185.0,
            birthDateEpochMs = birth,
            referenceWeightKg = 77.45
        )
    }

    @Test
    fun testRawMeasurementsArePreserved() {
        val entity = legacyEntity()
        val result = recalculateMeasurement(entity, maleMember())

        assertEquals("记录 ID 必须保持不变（否则会新增重复记录）", entity.id, result.id)
        assertEquals("称重时间必须保持不变", entity.measuredAtEpochMs, result.measuredAtEpochMs)
        assertEquals("体重必须保持不变", entity.weightKg, result.weightKg, 1e-9)
        assertEquals("阻抗必须保持不变", entity.impedanceOhm, result.impedanceOhm, 1e-9)
        assertEquals("成员绑定必须保持不变", entity.memberId, result.memberId)
        assertEquals("成员姓名快照必须保持不变", entity.memberNameSnapshot, result.memberNameSnapshot)
    }

    @Test
    fun testDerivedMetricsAreRefreshedToStandardFormula() {
        val result = recalculateMeasurement(legacyEntity(), maleMember())

        assertEquals("BMI 应仍按 77.45 / 1.85² 计算", 22.63, result.bmi, 0.1)
        assertEquals("体脂率应改为标准公式结果（旧值 9.6% 必须被替换）", 19.2, result.bodyFatPct, 0.6)
        assertEquals("水分率应按 0.73 × FFM 计算", 59.0, result.waterPct, 0.8)
        assertEquals("骨量应按 0.052 × FFM 计算", 3.25, result.boneMassKg, 0.1)
        assertEquals("肌肉量应按 FFM − 骨量 计算", 59.3, result.muscleKg, 0.8)
        assertEquals("蛋白质率应按 0.194 × FFM 计算", 15.7, result.proteinPct, 0.5)
    }

    @Test
    fun testFatAndFatFreeMassStillSumToWeight() {
        val result = recalculateMeasurement(legacyEntity(), maleMember())
        val fatKg = result.weightKg * result.bodyFatPct / 100.0
        val ffmKg = result.weightKg - fatKg
        // 肌肉 + 骨量 应等于去脂体重（同一套派生链，不允许出现自相矛盾）
        assertEquals("肌肉量 + 骨量 必须等于去脂体重", ffmKg, result.muscleKg + result.boneMassKg, 0.05)
    }

    @Test
    fun testRecordsWithoutImpedanceAreSkipped() {
        assertFalse("无阻抗记录不应重算（否则会把有效指标刷成 0）", isRecalculable(legacyEntity(impedanceOhm = 0.0)))
        assertFalse("负阻抗（异常值）不应重算", isRecalculable(legacyEntity(impedanceOhm = -1.0)))
    }

    @Test
    fun testTooLightRecordsAreSkipped() {
        assertFalse("体重小于 3kg 的记录不应重算", isRecalculable(legacyEntity(weightKg = 2.5)))
    }

    @Test
    fun testNormalRecordIsRecalculable() {
        assertTrue("有体重且有阻抗的记录应参与重算", isRecalculable(legacyEntity()))
    }
}
