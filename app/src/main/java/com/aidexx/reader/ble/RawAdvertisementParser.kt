package com.aidexx.reader.ble

data class RawManufacturerData(
    val companyId: Int,
    val payload: ByteArray
)

data class RawAdvertisement(
    val localName: String?,
    val serviceUuids16: Set<Int>,
    val manufacturerData: List<RawManufacturerData>
)

object RawAdvertisementParser {
    fun parse(bytes: ByteArray): RawAdvertisement {
        var offset = 0
        var name: String? = null
        val services = linkedSetOf<Int>()
        val manufacturers = mutableListOf<RawManufacturerData>()

        while (offset < bytes.size) {
            val length = bytes[offset].toInt() and 0xFF
            if (length == 0) break

            val structureEnd = offset + 1 + length
            if (structureEnd > bytes.size || length < 1) break

            val type = bytes[offset + 1].toInt() and 0xFF
            val valueStart = offset + 2
            val valueEnd = structureEnd

            when (type) {
                0x02, 0x03 -> {
                    var p = valueStart
                    while (p + 1 < valueEnd) {
                        val uuid = (bytes[p].toInt() and 0xFF) or
                            ((bytes[p + 1].toInt() and 0xFF) shl 8)
                        services += uuid
                        p += 2
                    }
                }

                0x08, 0x09 -> {
                    if (valueEnd > valueStart) {
                        name = bytes.copyOfRange(valueStart, valueEnd)
                            .toString(Charsets.UTF_8)
                            .trimEnd('\u0000')
                    }
                }

                0xFF -> {
                    if (valueEnd - valueStart >= 2) {
                        val companyId = (bytes[valueStart].toInt() and 0xFF) or
                            ((bytes[valueStart + 1].toInt() and 0xFF) shl 8)
                        manufacturers += RawManufacturerData(
                            companyId = companyId,
                            payload = bytes.copyOfRange(valueStart + 2, valueEnd)
                        )
                    }
                }
            }

            offset = structureEnd
        }

        return RawAdvertisement(
            localName = name,
            serviceUuids16 = services,
            manufacturerData = manufacturers
        )
    }
}
