package com.tinyggrok.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    @Test
    fun `later versions are newer, numerically`() {
        assertTrue(isNewerVersion("0.0.33", "0.0.34"))
        assertTrue(isNewerVersion("0.0.33", "v0.0.34"))
        assertTrue(isNewerVersion("0.0.9", "0.0.10"))     // text order would say otherwise
        assertTrue(isNewerVersion("0.0.33", "0.1.0"))
        assertTrue(isNewerVersion("0.0.33", "0.0.33.1"))
    }

    @Test
    fun `the same or an older version is not offered`() {
        assertFalse(isNewerVersion("0.0.33", "0.0.33"))
        assertFalse(isNewerVersion("0.0.33", "v0.0.33"))
        assertFalse(isNewerVersion("0.0.33", "0.0.32"))
        assertFalse(isNewerVersion("0.0.33", "0.0.33.0"))
    }

    @Test
    fun `a tag that is not a version never pushes an update`() {
        assertFalse(isNewerVersion("0.0.33", "nightly"))
        assertFalse(isNewerVersion("0.0.33", "0.0.34-beta"))
        assertFalse(isNewerVersion("0.0.33", ""))
    }

    /** Trimmed from the real GitHub response for v0.0.33. */
    private val release = """
        {"tag_name":"v0.0.34","name":"v0.0.34 — something","draft":false,"prerelease":false,
         "html_url":"https://github.com/gprot42/android-tiny-ggrok/releases/tag/v0.0.34",
         "assets":[
           {"name":"tiny-ggrok-0.0.34-arm64-v8a-release.apk","size":1,"browser_download_url":"https://example.invalid/arm64.apk"},
           {"name":"tiny-ggrok-0.0.34-universal-release.apk","size":13934909,
            "browser_download_url":"https://github.com/gprot42/android-tiny-ggrok/releases/download/v0.0.34/tiny-ggrok-0.0.34-universal-release.apk"}
         ]}
    """.trimIndent()

    @Test
    fun `the universal build is the one offered`() {
        val update = parseLatestRelease(release)
        assertNotNull(update)
        assertEquals("0.0.34", update!!.versionName)
        assertTrue(update.downloadUrl.endsWith("tiny-ggrok-0.0.34-universal-release.apk"))
        assertEquals(13934909L, update.sizeBytes)
    }

    @Test
    fun `a release without an installable file offers nothing`() {
        assertNull(parseLatestRelease("""{"tag_name":"v0.0.34","draft":false,"assets":[]}"""))
        assertNull(parseLatestRelease(release.replace("\"draft\":false", "\"draft\":true")))
    }
}
