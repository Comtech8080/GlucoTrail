package com.aidexx.reader.protocol

data class AidexAdvertisementCrcDebug(
    val payloadLength: Int,
    val payloadHex: String,
    val sensorMinute: Int?,
    val seed: Long?,
    val calculatedCrc: Long?,
    val receivedCrc: Long?,
    val crcValid: Boolean?
)

object AidexAdvertisementParser {
    const val COMPANY_ID = 0x0059
    const val PAYLOAD_LENGTH = 20

    fun parse(serial: String, payload: ByteArray, rssi: Int): AidexReading? {
        if (payload.size < PAYLOAD_LENGTH) return null

        val body = payload.copyOfRange(0, 16)
        val receivedCrc = le32(payload, 16)

        val seed = (
            le32(payload, 0) +
                le32(payload, 4) +
                le32(payload, 8) +
                le32(payload, 12)
            ) % 0x7FA777L

        val calculatedCrc = AidexCrc.crc32Normal(body, seed)
        val crcValid = calculatedCrc == receivedCrc

        val minute = le16(payload, 0)
        val trend = payload[4].toInt()
        val currentPacked = le16(payload, 5)
        val current = currentPacked and 0x03FF
        val valid = (currentPacked and 0x8000) != 0
        val quality = payload[7].toInt() and 0xFF

        val prev1Packed = le16(payload, 8)
        val prev1 = if ((prev1Packed and 0x8000) != 0) prev1Packed and 0x03FF else null
        val prev2Packed = le16(payload, 11)
        val prev2 = if ((prev2Packed and 0x8000) != 0) prev2Packed and 0x03FF else null

        return AidexReading(
            serial = serial,
            sensorMinute = minute,
            glucoseMgDl = current,
            trendRaw = trend,
            quality = quality,
            valid = valid,
            previousMgDl = prev1,
            previous2MgDl = prev2,
            crcValid = crcValid,
            rssi = rssi
        )
    }

    fun debug(
        payload: ByteArray
    ): AidexAdvertisementCrcDebug {
        val payloadHex =
            payload.joinToString(
                separator = ""
            ) {
                "%02X".format(
                    it.toInt() and
                        0xFF
                )
            }

        if (
            payload.size <
            PAYLOAD_LENGTH
        ) {
            return AidexAdvertisementCrcDebug(
                payloadLength =
                    payload.size,
                payloadHex =
                    payloadHex,
                sensorMinute =
                    if (
                        payload.size >=
                        2
                    ) {
                        le16(
                            payload,
                            0
                        )
                    } else {
                        null
                    },
                seed =
                    null,
                calculatedCrc =
                    null,
                receivedCrc =
                    null,
                crcValid =
                    null
            )
        }

        val body =
            payload.copyOfRange(
                0,
                16
            )

        val receivedCrc =
            le32(
                payload,
                16
            )

        val seed =
            (
                le32(
                    payload,
                    0
                ) +
                    le32(
                        payload,
                        4
                    ) +
                    le32(
                        payload,
                        8
                    ) +
                    le32(
                        payload,
                        12
                    )
                ) %
                0x7FA777L

        val calculatedCrc =
            AidexCrc.crc32Normal(
                body,
                seed
            )

        return AidexAdvertisementCrcDebug(
            payloadLength =
                payload.size,
            payloadHex =
                payloadHex,
            sensorMinute =
                le16(
                    payload,
                    0
                ),
            seed =
                seed,
            calculatedCrc =
                calculatedCrc,
            receivedCrc =
                receivedCrc,
            crcValid =
                calculatedCrc ==
                    receivedCrc
        )
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
}
