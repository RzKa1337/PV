package com.solartracker.pro.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant

class UpdateCoreTest {

    private fun v(s: String) = SemanticVersion.parse(s)!!

    @Test
    fun semanticVersion_parsesAndOrders() {
        assertEquals(SemanticVersion(0, 4, 0), v("v0.4.0"))
        assertEquals(SemanticVersion(1, 2, 3, listOf("beta", "1")), v("1.2.3-beta.1+build.7"))
        assertNull(SemanticVersion.parse("0.4"))
        assertNull(SemanticVersion.parse("latest"))
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0")
            .map(::v)
        assertEquals(ordered, ordered.shuffled(java.util.Random(1)).sorted())
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("1.0.0-rc.1").isPreRelease)
        assertEquals("1.2.3-beta.1", v("v1.2.3-beta.1").toString())
    }

    private fun asset(name: String, id: Long = name.hashCode().toLong()) =
        ReleaseAsset(id, name, 100, "https://api.github.com/repos/o/r/releases/assets/$id", "https://github.com/o/r/releases/download/x/$name")

    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false, assets: List<ReleaseAsset>? = null) =
        GitHubRelease(tag, tag, "", draft, pre, null, "", assets ?: listOf(asset("SolarTrackerPRO-$tag-universal.apk"), asset(Checksums.FILE_NAME)))

    @Test
    fun parser_readsGitHubReleaseJson() {
        val json = """
            [{"tag_name":"v0.5.0","name":"Solar Tracker PRO v0.5.0","body":"zmiany","draft":false,"prerelease":false,
              "published_at":"2026-10-05T10:00:00Z","html_url":"https://github.com/RzKa1337/PV/releases/tag/v0.5.0",
              "assets":[{"id":11,"name":"SolarTrackerPRO-v0.5.0-universal.apk","size":12345,
                         "url":"https://api.github.com/repos/RzKa1337/PV/releases/assets/11",
                         "browser_download_url":"https://github.com/RzKa1337/PV/releases/download/v0.5.0/a.apk","extra":{"x":1}},
                        {"id":12,"name":"SHA256SUMS","size":99,"url":"https://api.github.com/repos/RzKa1337/PV/releases/assets/12"}]},
             {"tag_name":"v0.6.0-beta.1","draft":true,"prerelease":true,"assets":[]},
             {"name":"no tag"}]
        """.trimIndent()
        val list = GitHubReleaseParser.parseList(json)
        assertEquals(2, list.size)
        val r = list[0]
        assertEquals(v("0.5.0"), r.version)
        assertEquals("zmiany", r.body)
        assertEquals(2, r.assets.size)
        assertEquals(12345L, r.assets[0].size)
        assertEquals("https://api.github.com/repos/RzKa1337/PV/releases/assets/11", r.assets[0].apiUrl)
        assertTrue(list[1].draft && list[1].preRelease)
        try {
            GitHubReleaseParser.parseList("""{"message":"Not Found"}""")
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Not Found"))
        }
        try {
            GitHubReleaseParser.parseList("<html>")
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun selector_choosesNewestForChannel() {
        val releases = listOf(release("v0.4.0"), release("v0.5.0"), release("v0.6.0-beta.1", pre = true), release("v0.7.0", draft = true), release("nightly"))
        val stable = UpdateSelector.select(releases, v("0.4.0"), UpdateChannel.STABLE, listOf("arm64-v8a"))
        assertEquals(v("0.5.0"), (stable as UpdateCheckResult.Available).candidate.version)
        val beta = UpdateSelector.select(releases, v("0.4.0"), UpdateChannel.BETA, listOf("arm64-v8a"))
        assertEquals(v("0.6.0-beta.1"), (beta as UpdateCheckResult.Available).candidate.version)
        // A pre-release version tag not flagged as pre-release is still beta only.
        val unflagged = listOf(release("v0.6.0-rc.1"))
        assertEquals(UpdateCheckResult.UpToDate, UpdateSelector.select(unflagged, v("0.5.0"), UpdateChannel.STABLE, emptyList()))
        assertEquals(UpdateCheckResult.UpToDate, UpdateSelector.select(releases, v("0.5.0"), UpdateChannel.STABLE, emptyList()))
        assertEquals(UpdateCheckResult.UpToDate, UpdateSelector.select(releases, v("0.4.0"), UpdateChannel.STABLE, emptyList(), setOf("0.5.0")))
        // Never a downgrade.
        assertEquals(UpdateCheckResult.UpToDate, UpdateSelector.select(releases, v("1.0.0"), UpdateChannel.BETA, emptyList()))
    }

    @Test
    fun selector_requiresApkAndChecksums() {
        val noSums = listOf(release("v0.5.0", assets = listOf(asset("a-universal.apk"))))
        val r1 = UpdateSelector.select(noSums, v("0.4.0"), UpdateChannel.STABLE, emptyList())
        assertTrue(r1 is UpdateCheckResult.NotInstallable)
        val noApk = listOf(release("v0.5.0", assets = listOf(asset(Checksums.FILE_NAME), asset("notes.txt"))))
        assertTrue(UpdateSelector.select(noApk, v("0.4.0"), UpdateChannel.STABLE, emptyList()) is UpdateCheckResult.NotInstallable)
    }

    @Test
    fun selector_picksApkForAbi() {
        val assets = listOf(asset("x-universal.apk"), asset("x-armeabi-v7a.apk"), asset("x-arm64-v8a.apk"), asset("SHA256SUMS"))
        assertEquals("x-arm64-v8a.apk", UpdateSelector.selectApk(assets, listOf("arm64-v8a", "armeabi-v7a"))!!.name)
        assertEquals("x-armeabi-v7a.apk", UpdateSelector.selectApk(assets, listOf("armeabi-v7a"))!!.name)
        assertEquals("x-universal.apk", UpdateSelector.selectApk(assets, listOf("x86_64"))!!.name)
        assertEquals("app.apk", UpdateSelector.selectApk(listOf(asset("app.apk")), listOf("x86"))!!.name)
        assertNull(UpdateSelector.selectApk(listOf(asset("a-x86.apk"), asset("b-x86_64.apk")), listOf("arm64-v8a")))
    }

    @Test
    fun checksums_parseAndVerify() {
        val hello = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        val text = "# sums\n${hello.uppercase()}  SolarTrackerPRO-v0.5.0-universal.apk\n$hello *dir/SHA_other.bin\nnot a line\n"
        val map = Checksums.parse(text)
        assertEquals(hello, map["SolarTrackerPRO-v0.5.0-universal.apk"])
        assertEquals(hello, map["SHA_other.bin"])
        assertEquals(2, map.size)
        assertEquals(hello, Checksums.sha256("hello".byteInputStream()))
        assertTrue(Checksums.matches(hello.uppercase(), hello))
        assertFalse(Checksums.matches(hello, hello.replaceFirst('2', '3')))
    }

    @Test
    fun policy_intervalsSnoozeSkipBad() {
        val now = Instant.parse("2026-10-05T12:00:00Z")
        val config = UpdateConfig()
        assertTrue(UpdatePolicy.isCheckDue(config, UpdateState(), now))
        val checked = UpdateState(lastCheck = now.minus(Duration.ofHours(23)))
        assertFalse(UpdatePolicy.isCheckDue(config, checked, now))
        assertTrue(UpdatePolicy.isCheckDue(config.copy(interval = CheckInterval.HOURS_12), checked, now))
        assertFalse(UpdatePolicy.isCheckDue(config.copy(interval = CheckInterval.MANUAL), UpdateState(), now))
        assertFalse(UpdatePolicy.isCheckDue(config.copy(enabled = false), UpdateState(), now))

        var state = UpdatePolicy.snooze(UpdateState(), "0.5.0", now, Duration.ofDays(1))
        assertEquals(setOf("0.5.0"), UpdatePolicy.ignoredVersions(state, now.plus(Duration.ofHours(1))))
        assertEquals(emptySet<String>(), UpdatePolicy.ignoredVersions(state, now.plus(Duration.ofDays(2))))
        state = UpdatePolicy.markBad(UpdatePolicy.skip(state, "0.6.0"), "0.7.0")
        assertEquals(setOf("0.6.0", "0.7.0"), UpdatePolicy.ignoredVersions(state, now.plus(Duration.ofDays(2))))
    }

    @Test
    fun config_validation() {
        assertTrue(UpdateConfig().validate().isEmpty())
        assertEquals(2, UpdateConfig(owner = "a/b", repo = "").validate().size)
        assertEquals(1, UpdateConfig(token = "ghp_ab cd").validate().size)
    }

    @Test
    fun retry_exponentialBackoffCapped() {
        val r = RetryPolicy(maxAttempts = 6, initialDelay = Duration.ofSeconds(2), maxDelay = Duration.ofSeconds(10))
        assertEquals(listOf(2000L, 4000L, 8000L, 10000L, 10000L), (1..5).map { r.delayBefore(it).toMillis() })
    }

    @Test
    fun log_isBoundedAndRoundTrips() {
        var t = Instant.parse("2026-10-05T10:00:00Z")
        val log = UpdateLog(capacity = 3) { t.also { t = t.plusSeconds(1) } }
        log.info("a")
        log.warn("b|c\nd")
        log.error("e")
        log.info("f")
        assertEquals(listOf("b/c d", "e", "f"), log.entries().map { it.message })
        val restored = UpdateLog(capacity = 2)
        restored.restore(log.serialize() + "\ngarbage")
        assertEquals(listOf(LogLevel.ERROR, LogLevel.INFO), restored.entries().map { it.level })
        assertEquals(Instant.parse("2026-10-05T10:00:03Z"), restored.entries().last().time)
    }
}

class ApkIdentityCheckTest {
    private val installed = ApkIdentity("com.solartracker.pro", 5, "0.5.0", setOf("aa"))
    private val good = ApkIdentity("com.solartracker.pro", 6, "0.6.0", setOf("aa"))
    private val v060 = SemanticVersion(0, 6, 0)

    @Test
    fun acceptsSameAppSameKeyNewerVersion() {
        org.junit.Assert.assertNull(ApkIdentityCheck.problem(installed, good, v060))
    }

    @Test
    fun rejectsEverythingElse() {
        val cases = mapOf(
            null to "poprawnym APK",
            good.copy(packageName = "evil.app") to "Inna aplikacja",
            good.copy(signerSha256 = setOf("bb")) to "innym kluczem",
            good.copy(signerSha256 = setOf("aa", "bb")) to "innym kluczem",
            good.copy(signerSha256 = emptySet()) to "nie jest podpisane",
            good.copy(versionCode = 5) to "nie jest nowsze",
            good.copy(versionCode = 3) to "nie jest nowsze",
            good.copy(versionName = "0.6.1") to "nie zgadza się",
            good.copy(versionName = null) to "nie zgadza się",
        )
        cases.forEach { (apk, reason) ->
            val problem = ApkIdentityCheck.problem(installed, apk, v060)
            assertTrue("$apk → $problem", problem != null && problem.contains(reason))
        }
        assertTrue(ApkIdentityCheck.problem(installed.copy(signerSha256 = emptySet()), good, v060)!!.contains("zainstalowanej"))
    }
}
