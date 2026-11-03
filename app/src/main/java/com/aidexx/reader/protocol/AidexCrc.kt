package com.aidexx.reader.protocol

object AidexCrc {
    fun crc8Maxim(data: ByteArray): Int {
        var crc = 0
        for (value in data) {
            crc = crc xor (value.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0x8C else crc ushr 1
            }
        }
        return crc and 0xFF
    }

    fun crc16CcittFalse(data: ByteArray): Int {
        var crc = 0xFFFF
        for (value in data) {
            crc = crc xor ((value.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    fun withCrc16(data: ByteArray): ByteArray {
        val crc = crc16CcittFalse(data)
        return data + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
    }

    fun crc32Normal(data: ByteArray, initial: Long): Long {
        var crc = initial and 0xFFFFFFFFL
        for (value in data) {
            crc = crc xor ((value.toLong() and 0xFFL) shl 24)
            repeat(8) {
                crc = if ((crc and 0x80000000L) != 0L) {
                    ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL
                } else {
                    (crc shl 1) and 0xFFFFFFFFL
                }
            }
        }
        return crc and 0xFFFFFFFFL
    }
}
