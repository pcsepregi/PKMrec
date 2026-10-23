package me.csepregi.pkmrec.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    @Test
    fun `parses versions with and without prefix`() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion(0, 1, 0), AppVersion.parse("0.1.0-debug"))
        assertNull(AppVersion.parse("latest"))
    }

    @Test
    fun `compares versions numerically`() {
        assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.9.9")!!)
        assertTrue(AppVersion.parse("1.0.0")!! > AppVersion.parse("0.99.99")!!)
        assertTrue(AppVersion.parse("0.1.1")!! > AppVersion.parse("0.1.0")!!)
    }

    @Test
    fun `picks apk and checksum assets from a release`() {
        val release = GitHubRelease(
            tagName = "v0.2.0",
            body = "Notes",
            assets = listOf(
                GitHubAsset("PKMrec-0.2.0.apk.sha256", 80, "https://example.com/sha"),
                GitHubAsset("PKMrec-0.2.0.apk", 1000, "https://example.com/apk"),
            ),
        )
        val info = GitHubReleaseClient.parseRelease(release)!!
        assertEquals(AppVersion(0, 2, 0), info.version)
        assertEquals("https://example.com/apk", info.apkUrl)
        assertEquals("https://example.com/sha", info.sha256Url)
    }

    @Test
    fun `ignores drafts, prereleases and releases without apk`() {
        assertNull(GitHubReleaseClient.parseRelease(GitHubRelease("v1.0.0", draft = true)))
        assertNull(GitHubReleaseClient.parseRelease(GitHubRelease("v1.0.0", prerelease = true)))
        assertNull(GitHubReleaseClient.parseRelease(GitHubRelease("v1.0.0")))
    }

    @Test
    fun `reads sha256sum file format`() {
        val hash = "a".repeat(64)
        assertEquals(hash, GitHubReleaseClient.parseSha256File("$hash  PKMrec-0.2.0.apk\n"))
        assertNull(GitHubReleaseClient.parseSha256File("not a hash"))
    }
}
