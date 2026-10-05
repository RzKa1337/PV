package com.solartracker.pro.core.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Downloader and update flow against a real local HTTP server. */
class DownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val apk = Random(42).nextBytes(300_000)
    private val apkName = "SolarTrackerPRO-v0.5.0-universal.apk"
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String?>>>()

    /** Number of upcoming APK requests that send only part of the file and then cut the connection. */
    private val failuresLeft = AtomicInteger(0)
    private val serverErrorsLeft = AtomicInteger(0)
    private var corruptApk = false
    private var sums = ""

    private fun HttpExchange.reply(code: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        responseBody.use { it.write(body) }
    }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:${server.address.port}"
        sums = "${Checksums.sha256(apk.inputStream())}  $apkName\n"
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            requests += path to mapOf(
                "Authorization" to ex.requestHeaders.getFirst("Authorization"),
                "Range" to ex.requestHeaders.getFirst("Range"),
                "Accept" to ex.requestHeaders.getFirst("Accept"),
            )
            when (path) {
                "/api/repos/RzKa1337/PV/releases" -> {
                    if (ex.requestHeaders.getFirst("Authorization") != "Bearer good-token") {
                        ex.reply(404, """{"message":"Not Found"}""".toByteArray())
                    } else {
                        ex.reply(200, releasesJson().toByteArray())
                    }
                }
                "/api/assets/1" -> ex.reply(302, ByteArray(0), mapOf("Location" to "$base/storage/apk?sig=x"))
                "/api/assets/2" -> ex.reply(302, ByteArray(0), mapOf("Location" to "/storage/sums"))
                "/storage/sums" -> ex.reply(200, sums.toByteArray())
                "/storage/apk" -> serveApk(ex)
                "/missing" -> ex.reply(404, ByteArray(0))
                else -> ex.reply(500, ByteArray(0))
            }
        }
        server.start()
    }

    private fun serveApk(ex: HttpExchange) {
        if (serverErrorsLeft.getAndDecrement() > 0) return ex.reply(503, ByteArray(0))
        val data = if (corruptApk) apk.copyOf().also { it[1000] = (it[1000] + 1).toByte() } else apk
        val range = ex.requestHeaders.getFirst("Range")
        val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
        val code = if (range != null) 206 else 200
        val body = data.copyOfRange(start, data.size)
        if (failuresLeft.getAndDecrement() > 0) {
            // Promise the full length, send a third, drop the connection.
            ex.sendResponseHeaders(code, body.size.toLong())
            runCatching { ex.responseBody.write(body, 0, body.size / 3); ex.responseBody.flush() }
            ex.close()
            return
        }
        ex.reply(code, body)
    }

    private fun releasesJson() = """
        [{"tag_name":"v0.5.0","draft":false,"prerelease":false,"assets":[
          {"id":1,"name":"$apkName","size":${apk.size},"url":"$base/api/assets/1"},
          {"id":2,"name":"SHA256SUMS","size":${sums.length},"url":"$base/api/assets/2"}]},
         {"tag_name":"v0.4.0","assets":[]}]
    """

    @After
    fun stop() = server.stop(0)

    private val noSleep = RetryPolicy(maxAttempts = 4, initialDelay = Duration.ofMillis(1))
    private val sleeps = mutableListOf<Long>()
    private fun downloader(log: UpdateLog? = null) = Downloader(UrlConnectionHttpClient(), noSleep, { sleeps += it }, log)

    private fun engine(log: UpdateLog = UpdateLog()) =
        UpdateEngine(GitHubClient(UrlConnectionHttpClient(), apiBase = "$base/api"), downloader(log), log, tmp.root.resolve("updates"))

    private val config = UpdateConfig(token = "good-token")

    @Test
    fun download_resumesWithRangeAfterBrokenConnections() {
        failuresLeft.set(2)
        val progress = mutableListOf<DownloadProgress>()
        val file = downloader().download("$base/storage/apk", emptyMap(), tmp.root.resolve("a.apk"), apk.size.toLong()) { progress += it }
        assertTrue(file.readBytes().contentEquals(apk))
        assertFalse(tmp.root.resolve("a.apk.part").exists())
        val ranges = requests.filter { it.first == "/storage/apk" }.map { it.second["Range"] }
        assertEquals(3, ranges.size)
        assertNull(ranges[0])
        assertTrue(ranges[1]!!.startsWith("bytes=") && ranges[1] != "bytes=0-")
        assertEquals(listOf(1L, 2L), sleeps) // exponential back-off between attempts
        assertEquals(apk.size.toLong(), progress.last().downloadedBytes)
        assertEquals(1f, progress.last().fraction!!, 0f)
        assertEquals(3, progress.last().attempt)
        // Progress never goes backwards within one attempt.
        progress.groupBy { it.attempt }.values.forEach { p -> assertEquals(p.map { it.downloadedBytes }.sorted(), p.map { it.downloadedBytes }) }
    }

    @Test
    fun download_retriesServerErrorsButNotClientErrors() {
        serverErrorsLeft.set(2)
        val file = downloader().download("$base/storage/apk", emptyMap(), tmp.root.resolve("b.apk"))
        assertTrue(file.readBytes().contentEquals(apk))

        sleeps.clear()
        try {
            downloader().download("$base/missing", emptyMap(), tmp.root.resolve("c.apk"))
            fail()
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("404"))
        }
        assertTrue("4xx must not be retried", sleeps.isEmpty())

        serverErrorsLeft.set(10)
        try {
            downloader().download("$base/storage/apk", emptyMap(), tmp.root.resolve("d.apk"))
            fail()
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("4 próbach"))
        }
        assertFalse(tmp.root.resolve("d.apk").exists())
    }

    @Test
    fun download_canBeCancelled() {
        try {
            downloader().download("$base/storage/apk", emptyMap(), tmp.root.resolve("e.apk"), isCancelled = { true })
            fail()
        } catch (_: CancelledException) {
        }
        assertFalse(tmp.root.resolve("e.apk").exists())
    }

    @Test
    fun redirect_dropsTokenForOtherHosts() {
        val headers = GitHubClient(UrlConnectionHttpClient()).headers("secret", "application/octet-stream")
        // 127.0.0.1 → localhost is a different host name: the token must not be forwarded.
        server.createContext("/cross") { requests += "/cross" to mapOf("Authorization" to it.requestHeaders.getFirst("Authorization")); it.reply(302, ByteArray(0), mapOf("Location" to "http://localhost:${server.address.port}/storage/sums")) }
        UrlConnectionHttpClient().getFollowingRedirects("$base/cross", headers).use { assertEquals(200, it.code) }
        val (first, second) = requests.takeLast(2)
        assertEquals("Bearer secret", first.second["Authorization"])
        assertEquals("/storage/sums", second.first)
        assertNull(second.second["Authorization"])
    }

    @Test
    fun engine_checksDownloadsAndVerifies() {
        val log = UpdateLog()
        val engine = engine(log)
        val result = engine.check(config, UpdateState(), SemanticVersion(0, 4, 0), listOf("arm64-v8a"), Instant.now())
        val candidate = (result as UpdateCheckResult.Available).candidate
        assertEquals("0.5.0", candidate.version.toString())

        failuresLeft.set(1)
        val progress = mutableListOf<DownloadProgress>()
        val verified = engine.downloadAndVerify(candidate, config) { progress += it }
        assertTrue(verified.file.readBytes().contentEquals(apk))
        assertEquals(Checksums.sha256(apk.inputStream()), verified.sha256)
        assertTrue(progress.isNotEmpty())
        // Asset downloads use the API URL with octet-stream; the token goes only to the API host.
        val assetRequest = requests.first { it.first == "/api/assets/1" }
        assertEquals("application/octet-stream", assetRequest.second["Accept"])
        assertEquals("Bearer good-token", assetRequest.second["Authorization"])
        assertTrue(log.entries().any { it.message.startsWith("SHA-256 zgodna") })

        // Second call reuses the verified file without downloading it again.
        val before = requests.count { it.first == "/storage/apk" }
        engine.downloadAndVerify(candidate, config)
        assertEquals(before, requests.count { it.first == "/storage/apk" })
    }

    @Test
    fun engine_rejectsAndDeletesTamperedApk() {
        val log = UpdateLog()
        val engine = engine(log)
        val candidate = (engine.check(config, UpdateState(), SemanticVersion(0, 4, 0), emptyList(), Instant.now()) as UpdateCheckResult.Available).candidate
        corruptApk = true
        try {
            engine.downloadAndVerify(candidate, config)
            fail()
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("sumę kontrolną"))
        }
        assertFalse(tmp.root.resolve("updates/$apkName").exists())
        assertTrue(tmp.root.resolve("updates").listFiles()!!.isEmpty())
        assertTrue(log.entries().any { it.level == LogLevel.ERROR && it.message.contains("niezgodna") })
    }

    @Test
    fun engine_rejectsReleaseWithoutChecksumForApk() {
        sums = "${"0".repeat(64)}  other.apk\n"
        val engine = engine()
        val candidate = (engine.check(config, UpdateState(), SemanticVersion(0, 4, 0), emptyList(), Instant.now()) as UpdateCheckResult.Available).candidate
        try {
            engine.downloadAndVerify(candidate, config)
            fail()
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("nie zawiera sumy"))
        }
        assertTrue(requests.none { it.first == "/storage/apk" })
    }

    @Test
    fun engine_reportsPrivateRepoWithoutToken() {
        try {
            engine().check(UpdateConfig(), UpdateState(), SemanticVersion(0, 4, 0), emptyList(), Instant.now())
            fail()
        } catch (e: UpdateException) {
            assertTrue(e.message!!, e.message!!.contains("token"))
        }
        try {
            engine().check(UpdateConfig(owner = "bad/owner"), UpdateState(), SemanticVersion(0, 4, 0), emptyList(), Instant.now())
            fail()
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("właściciel"))
        }
    }

    @Test
    fun engine_honoursSkippedVersions() {
        val state = UpdatePolicy.skip(UpdateState(), "0.5.0")
        assertEquals(UpdateCheckResult.UpToDate, engine().check(config, state, SemanticVersion(0, 4, 0), emptyList(), Instant.now()))
    }
}
