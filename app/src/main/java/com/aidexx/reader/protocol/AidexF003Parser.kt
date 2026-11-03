package com.aidexx.reader.protocol

/**
 * AiDEX X proprietary current-glucose F003 packet.
 *
 * IMPORTANT:
 * F003 characteristic values are encrypted with the per-connection KEY1.
 * Callers must first AES-CFB decrypt the notification and validate its CRC16.
 *
 * Clear packet layout:
 *
 * bytes 0..1   one16 / message prefix
 * byte  2      status/meta
 * byte  3      signed trend in 0.1 mg/dL/min
 * bytes 4..5   sensor minute, little endian
 * bytes 6..7   packed glucose/status
 * bytes 8..14  additional current-sensor data
 * bytes 15..16 CRC16-CCITT-FALSE over bytes 0..14
 */
object AidexF003Parser {
    const val CLEAR_PACKET_LENGTH =
        17

    fun parseClearWithCrc(
        serial: String,
        clearWithCrc: ByteArray,
        rssi: Int =
            0,
        receivedAtMs: Long =
            System.currentTimeMillis()
    ): AidexReading? {
        if (
            clearWithCrc.size !=
            CLEAR_PACKET_LENGTH
        ) {
            return null
        }

        val data =
            clearWithCrc.copyOfRange(
                0,
                15
            )

        val expectedCrc =
            le16(
                clearWithCrc,
                15
            )

        val calculatedCrc =
            AidexCrc
                .crc16CcittFalse(
                    data
                )

        if (
            calculatedCrc !=
            expectedCrc
        ) {
            return null
        }

        val minute =
            le16(
                data,
                4
            )

        val trend =
            data[3]
                .toInt()

        val packed =
            le16(
                data,
                6
            )

        val glucose =
            packed and
                0x03FF

        val warmup =
            (
                packed and
                    0x0400
            ) !=
                0

        val valid =
            (
                packed and
                    0x8000
            ) !=
                0 &&
                glucose in
                18..800

        return AidexReading(
            serial =
                serial.uppercase(),
            sensorMinute =
                minute,
            glucoseMgDl =
                glucose,
            trendRaw =
                trend,
            quality =
                if (
                    valid &&
                    !warmup
                ) {
                    100
                } else {
                    0
                },
            valid =
                valid,
            previousMgDl =
                null,
            previous2MgDl =
                null,
            crcValid =
                true,
            rssi =
                rssi,
            receivedAtMs =
                receivedAtMs
        )
    }

    private fun le16(
        bytes: ByteArray,
        offset: Int
    ): Int =
        (
            bytes[offset]
                .toInt() and
                0xFF
        ) or
            (
                (
                    bytes[
                        offset +
                            1
                    ]
                        .toInt() and
                        0xFF
                ) shl
                    8
            )
}
