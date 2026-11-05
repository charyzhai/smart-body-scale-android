package com.example.dianzicheng

import com.example.dianzicheng.data.ble.AfuBhProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 薄荷 B1（AFU-BH-TZ-B1）协议编解码测试。
 *
 * 真机帧来自 2026-10-29 的实际日志；下行指令帧的期望值与该型号
 * 已在真机验证的开源实现（OHealth Device Bridge，MIT）逐字节一致，
 * 任何改动导致对不上都说明帧结构被改坏了。
 */
class AfuBhProtocolTest {

    private fun hex(text: String): ByteArray =
        text.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun testLiveWeightFramesFromRealDevice() {
        val cases = listOf(
            "AF 45 3A 20 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 01" to 8250,
            "AF 45 00 00 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 07" to 0,
            "AF 45 F4 E2 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 1D" to 58100,
            "AF 45 B0 30 09 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 08" to 78000
        )
        for ((raw, grams) in cases) {
            val frame = hex(raw)
            assertTrue("校验必须通过：$raw", AfuBhProtocol.isValid(frame))
            val parsed = AfuBhProtocol.parse(frame)
            assertTrue("$raw 应解析为实时体重", parsed is AfuBhProtocol.LiveWeight)
            parsed as AfuBhProtocol.LiveWeight
            assertEquals("体重错误：$raw", grams, parsed.weightGrams)
            // 这四帧都是未锁定状态（bit31 = 0）
            assertFalse("$raw 不应报锁定", parsed.locked)
        }
    }

    @Test
    fun testLockedLiveWeightFrame() {
        // 真机 19:55:41.603 的锁定帧：77.45 kg
        val frame = hex("AF 45 8A 2E 09 80 02 80 01 88 38 4B 00 00 00 00 00 00 51 00")
        val parsed = AfuBhProtocol.parse(frame) as AfuBhProtocol.LiveWeight
        assertEquals(77450, parsed.weightGrams)
        assertTrue(parsed.locked)
    }

    @Test
    fun testStoredRecordCarriesWeightAndImpedance() {
        // 真机 19:55:49 起被无限重发的那条结果记录：77.45 kg / 461 Ω
        val frame = hex("AF 45 00 BE 33 E3 6A 8A 2E 09 80 00 01 CD 01 00 00 00 54 02")
        val parsed = AfuBhProtocol.parse(frame)
        assertTrue(parsed is AfuBhProtocol.StoredRecord)
        parsed as AfuBhProtocol.StoredRecord
        assertEquals(77450, parsed.weightGrams)
        assertEquals(461, parsed.resistanceOhm)
        assertEquals(0, parsed.sequence)
        assertTrue(parsed.locked)
        assertEquals(1793274814L, parsed.timestampEpochSeconds)
    }

    @Test
    fun testChecksumRejectsCorruptedFrame() {
        val good = hex("AF 45 8A 2E 09 80 02 80 01 88 38 4B 00 00 00 00 00 00 51 00")
        assertTrue(AfuBhProtocol.isValid(good))
        val bad = good.copyOf().also { it[19] = ((it[19].toInt() + 1) and 0x1F).toByte() }
        assertFalse(AfuBhProtocol.isValid(bad))
        assertEquals(null, AfuBhProtocol.parse(bad))
    }

    @Test
    fun testWrongHeaderOrLengthRejected() {
        assertFalse(AfuBhProtocol.isValid(hex("AC 45 8A 2E 09 80 02 80 01 88 38 4B 00 00 00 00 00 00 51 00")))
        assertFalse(AfuBhProtocol.isValid(hex("AF 45 8A 2E 09 80 02 80 01 88 38 4B 00 00 00 00 00 51 00")))
    }

    @Test
    fun testTimeSyncFrameMatchesReference() {
        val frame = AfuBhProtocol.buildTimeSync(1786810554L)
        assertEquals("AF4516BA90806A20000000000000000000005F09", AfuBhProtocol.toHex(frame))
        assertTrue(AfuBhProtocol.isValid(frame))
    }

    @Test
    fun testAckFrameCarriesSequence() {
        val frame = AfuBhProtocol.buildAck(AfuBhProtocol.WIRE_STORED, 5)
        assertEquals("AF45125400050000000000000000000000005F0A", AfuBhProtocol.toHex(frame))
        assertTrue(AfuBhProtocol.isValid(frame))
    }

    @Test
    fun testProfileFrameLayout() {
        val frame = AfuBhProtocol.buildProfile(
            AfuBhProtocol.ProfileVariant.INITIAL, 1786810580L, 1, 29, true, 50.0
        )
        assertEquals("AF453FD490806A0001AFB6171D01881303006107", AfuBhProtocol.toHex(frame))
        assertTrue(AfuBhProtocol.isValid(frame))
    }

    @Test
    fun testHandshakeFramesAreSelfConsistent() {
        for (frame in listOf(AfuBhProtocol.buildSyncFirst(), AfuBhProtocol.buildSyncSecond())) {
            assertTrue("握手帧校验必须自洽", AfuBhProtocol.isValid(frame))
            val parsed = AfuBhProtocol.parse(frame)
            assertNotNull(parsed)
            assertTrue(parsed is AfuBhProtocol.Unknown)
            assertEquals(AfuBhProtocol.WIRE_SYNC, (parsed as AfuBhProtocol.Unknown).wireType)
        }
    }
}
