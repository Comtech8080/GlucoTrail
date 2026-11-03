package com.aidexx.reader.updates

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun acceptsOnlyRepositoryHttpsAssets() {
        assertTrue(UpdatePolicy.assetUrl("https://github.com/Comtech8080/GlucoTrail/releases/download/v1/a.apk"))
        for (url in listOf("http://github.com/Comtech8080/GlucoTrail/releases/download/v1/a.apk",
            "https://github.com.evil.test/Comtech8080/GlucoTrail/releases/download/v1/a.apk",
            "https://github.com/other/app/releases/download/v1/a.apk",
            "https://user@github.com/Comtech8080/GlucoTrail/releases/download/v1/a.apk")) {
            assertFalse(url, UpdatePolicy.assetUrl(url))
        }
    }
    @Test fun parsesStandardChecksumAndRejectsWrongFilename() {
        val digest = "ab".repeat(32)
        assertEquals(digest, UpdatePolicy.checksum("${digest.uppercase()}  GlucoTrail-v1.apk\n", "GlucoTrail-v1.apk"))
        assertEquals(digest, UpdatePolicy.checksum(digest, "GlucoTrail-v1.apk"))
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.checksum("$digest  other.apk", "GlucoTrail-v1.apk") }
        assertThrows(IllegalStateException::class.java) { UpdatePolicy.checksum("1234", "GlucoTrail-v1.apk") }
    }
    @Test fun identityRejectsDowngradesTamperedMetadataAndWrongSigners() {
        UpdatePolicy.validateIdentity("app", "app", 98, 99, 99, setOf("a"), setOf("a"))
        fun rejected(pkg: String = "app", code: Long = 99, advertised: Long = 99, signers: Set<String> = setOf("a")) {
            assertThrows(IllegalArgumentException::class.java) {
                UpdatePolicy.validateIdentity("app", pkg, 98, advertised, code, setOf("a"), signers)
            }
        }
        rejected(pkg = "other")
        rejected(code = 98, advertised = 98)
        rejected(code = 97, advertised = 97)
        rejected(advertised = 100)
        rejected(signers = setOf("b"))
        rejected(signers = emptySet())
        rejected(signers = setOf("a", "b"))
    }
}
