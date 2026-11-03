package com.aidexx.reader.protocol

data class AidexPackedHistoryValue(
    val relativeId: Int,
    val glucoseMgDl: Int,
    val warmup: Boolean,
    val valid: Boolean,
    val raw: Int
)

data class AidexHistoryBatch(
    val startRelativeId: Int,
    val values: List<AidexPackedHistoryValue>
)

object AidexHistoryProtocol {
    /**
     * Juggluco's AiDEX X implementation uses the encrypted private F002
     * protocol for history:
     *
     * 0x22 + CRC16            -> ask for last history position
     * 0x23 + uint16 LE + CRC  -> ask for history beginning at relative ID
     *
     * The returned cleartext packets are CRC16-validated by AidexProtocol
     * before these parsers are called.
     */
    val ASK_LAST_ID_COMMAND =
        byteArrayOf(
            0x22,
            0xD0.toByte(),
            0xE5.toByte()
        )

    fun makeHistoryCommand(
        relativeId: Int
    ): ByteArray {
        val id =
            relativeId
                .coerceIn(
                    1,
                    0xFFFF
                )

        return AidexCrc.withCrc16(
            byteArrayOf(
                0x23,
                (
                    id and
                        0xFF
                ).toByte(),
                (
                    id ushr 8
                ).toByte()
            )
        )
    }

    fun parseLastId(
        clearWithCrc: ByteArray
    ): Int? {
        if (
            AidexProtocol
                .responseType(
                    clearWithCrc
                ) != 0x122
        ) {
            return null
        }

        /*
         * 0x122 carries a 32-bit marker 0x00010001 beginning at byte 2.
         * The last history position is the uint16 immediately before CRC16.
         */
        if (
            clearWithCrc.size <
            10
        ) {
            return null
        }

        val marker =
            u32(
                clearWithCrc,
                2
            )

        if (
            marker !=
            0x00010001L
        ) {
            return null
        }

        return u16(
            clearWithCrc,
            clearWithCrc.size -
                4
        )
    }

    fun parseHistoryBatch(
        clearWithCrc: ByteArray
    ): AidexHistoryBatch? {
        if (
            AidexProtocol
                .responseType(
                    clearWithCrc
                ) != 0x123
        ) {
            return null
        }

        /*
         * 0x123:
         * bytes 0-1 response code
         * bytes 2-3 starting history ID, little-endian
         * bytes 4..n-3 packed HistoryGlucose uint16 values
         * final two bytes CRC16
         */
        if (
            clearWithCrc.size <
            6
        ) {
            return null
        }

        val payloadBytes =
            clearWithCrc.size -
                6

        if (
            payloadBytes < 0 ||
            payloadBytes % 2 != 0
        ) {
            return null
        }

        val start =
            u16(
                clearWithCrc,
                2
            )

        val count =
            payloadBytes /
                2

        val values =
            ArrayList<AidexPackedHistoryValue>(
                count
            )

        repeat(
            count
        ) {
                index ->
            val raw =
                u16(
                    clearWithCrc,
                    4 +
                        index *
                        2
                )

            values +=
                AidexPackedHistoryValue(
                    relativeId =
                        start +
                            index,
                    glucoseMgDl =
                        raw and
                            0x03FF,
                    warmup =
                        raw and
                            0x0400 !=
                            0,
                    valid =
                        raw and
                            0x8000 !=
                            0,
                    raw =
                        raw
                )
        }

        return AidexHistoryBatch(
            startRelativeId =
                start,
            values =
                values
        )
    }

    private fun u16(
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
                    bytes[offset + 1]
                        .toInt() and
                        0xFF
                ) shl 8
            )

    private fun u32(
        bytes: ByteArray,
        offset: Int
    ): Long =
        (
            bytes[offset]
                .toLong() and
                0xFFL
        ) or
            (
                (
                    bytes[offset + 1]
                        .toLong() and
                        0xFFL
                ) shl 8
            ) or
            (
                (
                    bytes[offset + 2]
                        .toLong() and
                        0xFFL
                ) shl 16
            ) or
            (
                (
                    bytes[offset + 3]
                        .toLong() and
                        0xFFL
                ) shl 24
            )
}
