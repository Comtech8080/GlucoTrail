package com.aidexx.reader.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AidexHistoryProtocolTest {
    @Test
    fun lastIdCommandMatchesKnownCommand() {
        assertArrayEquals(
            byteArrayOf(
                0x22,
                0xD0.toByte(),
                0xE5.toByte()
            ),
            AidexHistoryProtocol
                .ASK_LAST_ID_COMMAND
        )
    }

    @Test
    fun historyCommandAddsExpectedLayoutAndCrc() {
        val cmd =
            AidexHistoryProtocol
                .makeHistoryCommand(
                    0x1234
                )

        assertEquals(
            5,
            cmd.size
        )

        assertEquals(
            0x23,
            cmd[0]
                .toInt() and
                0xFF
        )

        assertEquals(
            0x34,
            cmd[1]
                .toInt() and
                0xFF
        )

        assertEquals(
            0x12,
            cmd[2]
                .toInt() and
                0xFF
        )

        val expectedCrc =
            AidexCrc
                .crc16CcittFalse(
                    cmd.copyOfRange(
                        0,
                        3
                    )
                )

        val actualCrc =
            (
                cmd[3]
                    .toInt() and
                    0xFF
            ) or
                (
                    (
                        cmd[4]
                            .toInt() and
                            0xFF
                    ) shl 8
                )

        assertEquals(
            expectedCrc,
            actualCrc
        )
    }

    @Test
    fun parsesLastIdPacket() {
        val data =
            byteArrayOf(
                0x22,
                0x01,
                0x01,
                0x00,
                0x01,
                0x00,
                0xA5.toByte(),
                0x04
            )

        val packet =
            AidexCrc.withCrc16(
                data
            )

        assertEquals(
            0x04A5,
            AidexHistoryProtocol
                .parseLastId(
                    packet
                )
        )
    }

    @Test
    fun rejectsWrongLastIdMarker() {
        val packet =
            AidexCrc.withCrc16(
                byteArrayOf(
                    0x22,
                    0x01,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x20,
                    0x00
                )
            )

        assertNull(
            AidexHistoryProtocol
                .parseLastId(
                    packet
                )
        )
    }

    @Test
    fun parsesPackedHistoryValues() {
        /*
         * ID 100 = valid 180 mg/dL
         * ID 101 = valid warmup 181 mg/dL
         * ID 102 = invalid 182 mg/dL
         */
        fun packed(
            glucose: Int,
            warmup: Boolean,
            valid: Boolean
        ): Int =
            (
                glucose and
                    0x03FF
            ) or
                if (
                    warmup
                ) {
                    0x0400
                } else {
                    0
                } or
                if (
                    valid
                ) {
                    0x8000
                } else {
                    0
                }

        val raw1 =
            packed(
                180,
                false,
                true
            )

        val raw2 =
            packed(
                181,
                true,
                true
            )

        val raw3 =
            packed(
                182,
                false,
                false
            )

        val data =
            byteArrayOf(
                0x23,
                0x01,
                100,
                0,
                (
                    raw1 and
                        0xFF
                ).toByte(),
                (
                    raw1 ushr 8
                ).toByte(),
                (
                    raw2 and
                        0xFF
                ).toByte(),
                (
                    raw2 ushr 8
                ).toByte(),
                (
                    raw3 and
                        0xFF
                ).toByte(),
                (
                    raw3 ushr 8
                ).toByte()
            )

        val batch =
            AidexHistoryProtocol
                .parseHistoryBatch(
                    AidexCrc.withCrc16(
                        data
                    )
                )!!

        assertEquals(
            100,
            batch.startRelativeId
        )

        assertEquals(
            3,
            batch.values.size
        )

        assertEquals(
            180,
            batch.values[0]
                .glucoseMgDl
        )
        assertTrue(
            batch.values[0]
                .valid
        )
        assertFalse(
            batch.values[0]
                .warmup
        )

        assertTrue(
            batch.values[1]
                .warmup
        )

        assertFalse(
            batch.values[2]
                .valid
        )
    }
}
