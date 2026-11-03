package com.aidexx.reader.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.zone.ZoneRules

object AidexProtocol {
    val SERVICE_UUID = java.util.UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")
    val F001_UUID = java.util.UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")
    val F002_UUID = java.util.UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")
    val F003_UUID = java.util.UUID.fromString("0000f003-0000-1000-8000-00805f9b34fb")
    val F005_UUID = java.util.UUID.fromString("0000f005-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val START_INFO_COMMAND = byteArrayOf(0x10, 0xC1.toByte(), 0xF3.toByte())
    val ASK_START_TIME_COMMAND = byteArrayOf(0x21, 0xB3.toByte(), 0xD5.toByte())
    val CONFIG_31_COMMAND = byteArrayOf(0x31, 0x01, 0x8A.toByte(), 0x3B)
    val FINALIZE_35_COMMAND = byteArrayOf(0x35, 0x01, 0x4E, 0xF7.toByte())
    val FINALIZE_11_COMMAND = byteArrayOf(0x11, 0xE0.toByte(), 0xE3.toByte())
    val FINALIZE_34_COMMAND = byteArrayOf(0x34, 0x01, 0x7F, 0xC4.toByte())

    fun encryptedCommand(key1: ByteArray, iv: ByteArray, clearWithCrc: ByteArray): ByteArray =
        AidexCrypto.aesCfbEncrypt(key1, iv, clearWithCrc)

    fun decryptAndValidate(key1: ByteArray, iv: ByteArray, encrypted: ByteArray): ByteArray? {
        val clear = AidexCrypto.aesCfbDecrypt(key1, iv, encrypted)
        if (clear.size < 3) return null
        val data = clear.copyOfRange(0, clear.size - 2)
        val expected = (clear[clear.size - 2].toInt() and 0xFF) or
            ((clear[clear.size - 1].toInt() and 0xFF) shl 8)
        return if (AidexCrc.crc16CcittFalse(data) == expected) clear else null
    }

    fun makeTimeCommand(now: Instant = Instant.now(), zoneId: ZoneId = ZoneId.systemDefault()): ByteArray {
        val zoned = now.atZone(zoneId)
        val rules: ZoneRules = zoneId.rules
        val standardOffsetSeconds = rules.getStandardOffset(now).totalSeconds
        val actualOffsetSeconds = rules.getOffset(now).totalSeconds
        val dstOffsetSeconds = actualOffsetSeconds - standardOffsetSeconds

        val timezoneQuarterHours = standardOffsetSeconds / (15 * 60)
        val dstQuarterHours = dstOffsetSeconds / (15 * 60)

        val payload = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x20)
            putShort(zoned.year.toShort())
            put(zoned.monthValue.toByte())
            put(zoned.dayOfMonth.toByte())
            put(zoned.hour.toByte())
            put(zoned.minute.toByte())
            put(zoned.second.toByte())
            put(timezoneQuarterHours.toByte())
            put(dstQuarterHours.toByte())
        }.array()
        return AidexCrc.withCrc16(payload)
    }

    fun responseType(clearWithCrc: ByteArray): Int? {
        if (clearWithCrc.size < 4) return null
        return (clearWithCrc[0].toInt() and 0xFF) or ((clearWithCrc[1].toInt() and 0xFF) shl 8)
    }

    fun serialFromName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val prefixes = listOf("AiDEX X-", "LinX-", "Lumi-", "Smart-")
        val prefix = prefixes.firstOrNull { name.startsWith(it) } ?: return null
        val suffix = name.removePrefix(prefix)
        val serial = if (suffix.length == 10) suffix else suffix.takeLast(10)
        return serial.uppercase().takeIf(::isValidSerial)
    }

    fun isValidSerial(serial: String): Boolean =
        serial.length == 10 && serial.all { it.isLetterOrDigit() }
}
