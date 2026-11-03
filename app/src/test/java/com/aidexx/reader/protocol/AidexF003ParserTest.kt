package com.aidexx.reader.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AidexF003ParserTest {
    @Test
    fun parsesDecryptedCurrentPacketWithValidCrc() {
        val packet =
            ByteArray(
                17
            )

        packet[0] =
            1

        packet[3] =
            (-12)
                .toByte()

        val minute =
            1234

        packet[4] =
            (
                minute and
                    0xFF
            )
                .toByte()

        packet[5] =
            (
                minute shr
                    8
            )
                .toByte()

        val glucose =
            225

        val packed =
            glucose or
                0x8000

        packet[6] =
            (
                packed and
                    0xFF
            )
                .toByte()

        packet[7] =
            (
                packed shr
                    8
            )
                .toByte()

        val crc =
            AidexCrc
                .crc16CcittFalse(
                    packet.copyOfRange(
                        0,
                        15
                    )
                )

        packet[15] =
            (
                crc and
                    0xFF
            )
                .toByte()

        packet[16] =
            (
                crc shr
                    8
            )
                .toByte()

        val reading =
            AidexF003Parser
                .parseClearWithCrc(
                    serial =
                        "22222BRCZ9",
                    clearWithCrc =
                        packet,
                    receivedAtMs =
                        1_234_567L
                )

        assertNotNull(
            reading
        )

        requireNotNull(
            reading
        )

        assertEquals(
            minute,
            reading.sensorMinute
        )

        assertEquals(
            glucose,
            reading.glucoseMgDl
        )

        assertEquals(
            -12,
            reading.trendRaw
        )

        assertTrue(
            reading.valid
        )

        assertTrue(
            reading.crcValid
        )
    }
}
