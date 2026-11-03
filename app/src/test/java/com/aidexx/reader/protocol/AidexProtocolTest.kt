package com.aidexx.reader.protocol

import org.junit.Assert.*
import org.junit.Test

class AidexProtocolTest {
    @Test
    fun activeAdvertisement_decodesKnownPacket() {
        val payload = hex("3F0000000A7E81647B81647C816400005FD30083")
        val r = AidexAdvertisementParser.parse("TESTSERIAL", payload, -61)!!
        assertTrue(r.crcValid)
        assertEquals(63, r.sensorMinute)
        assertEquals(382, r.glucoseMgDl)
        assertEquals(10, r.trendRaw)
        assertEquals(100, r.quality)
        assertTrue(r.valid)
        assertEquals(379, r.previousMgDl)
        assertEquals(380, r.previous2MgDl)
    }

    @Test
    fun serialDerivation_matchesExpectedVector() {
        val serial = "22222ABCDE"
        assertEquals("7d0979a63e2c6e19e3341165a9d80360", AidexCrypto.serialIv(serial).hex())
        assertEquals("79961e50b10ee728ae8ed960297b786e", AidexCrypto.keyRequest(serial).hex())
    }

    @Test
    fun startCommand_hasValidCrc16() {
        val cmd = AidexProtocol.START_INFO_COMMAND
        assertEquals(0x10, cmd[0].toInt() and 0xFF)
        val crc = AidexCrc.crc16CcittFalse(byteArrayOf(cmd[0]))
        val included = (cmd[1].toInt() and 0xFF) or ((cmd[2].toInt() and 0xFF) shl 8)
        assertEquals(crc, included)
    }


    @Test
    fun rawAdvertisement_duplicateManufacturerRecords() {
        val glucose = "3F0000000A7E81647B81647C816400005FD30083"
        val name = "AiDEX X-22222BRCZ9".toByteArray(Charsets.UTF_8)
        val bytes = mutableListOf<Byte>()

        // Flags
        bytes += byteArrayOf(0x02, 0x01, 0x06).toList()
        // Incomplete list of 16-bit UUIDs: 0x181F
        bytes += byteArrayOf(0x03, 0x02, 0x1F, 0x18).toList()

        val glucoseBytes = hex(glucose)
        bytes += (glucoseBytes.size + 3).toByte()
        bytes += 0xFF.toByte()
        bytes += 0x59.toByte()
        bytes += 0x00.toByte()
        bytes += glucoseBytes.toList()

        bytes += (name.size + 1).toByte()
        bytes += 0x09.toByte()
        bytes += name.toList()

        // A second manufacturer AD structure with the same company ID.
        val shortPayload = hex("00FFFFDA36")
        bytes += (shortPayload.size + 3).toByte()
        bytes += 0xFF.toByte()
        bytes += 0x59.toByte()
        bytes += 0x00.toByte()
        bytes += shortPayload.toList()

        val raw = com.aidexx.reader.ble.RawAdvertisementParser.parse(bytes.toByteArray())
        assertEquals("AiDEX X-22222BRCZ9", raw.localName)
        assertTrue(0x181F in raw.serviceUuids16)

        val manufacturerPayloads = raw.manufacturerData
            .filter { it.companyId == 0x0059 }
            .map { it.payload }

        assertEquals(2, manufacturerPayloads.size)
        val reading = manufacturerPayloads
            .mapNotNull { AidexAdvertisementParser.parse("22222BRCZ9", it, -61) }
            .firstOrNull { it.crcValid }

        assertNotNull(reading)
        assertEquals(382, reading!!.glucoseMgDl)
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
