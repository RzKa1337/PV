package com.solartracker.pro.data

import com.solartracker.pro.core.analytics.ManualProductionEntry
import com.solartracker.pro.core.solar.GeoLocation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class ProductionRepositoryTest {
    private val dir: File = Files.createTempDirectory("production").toFile()
    private val warsaw = GeoLocation(52.23, 21.01, 100.0)
    private var now = Instant.parse("2026-10-12T10:00:00Z")
    private val requests = mutableListOf<String>()
    private var online = true

    /** A minimal archive answer: one hour per requested day is enough for the parser. */
    private fun repository() = ProductionRepository(dir, { now }) { url ->
        requests += url
        if (!online) throw IOException("offline")
        val t = LocalDate.parse(Regex("start_date=([0-9-]+)").find(url)!!.groupValues[1]).toEpochDay() * 86_400
        """{"timezone":"Europe/Warsaw","hourly":{"time":[$t],"direct_normal_irradiance":[500],"diffuse_radiation":[100],"temperature_2m":[12]}}"""
    }

    private fun d(day: Int) = LocalDate.of(2026, 10, day)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun entries_surviveANewRepositoryInstance() = runBlocking {
        assertTrue(repository().entries().isEmpty())
        val entries = listOf(ManualProductionEntry(d(8), d(11), 31.5), ManualProductionEntry(d(5), d(5), 7.25))
        assertTrue(repository().save(entries))
        assertEquals(entries, repository().entries())
        assertTrue(repository().save(emptyList()))
        assertTrue(repository().entries().isEmpty())
    }

    @Test
    fun entries_brokenFileGivesAnEmptyListNotACrash() = runBlocking {
        File(dir, "entries.txt").writeBytes(byteArrayOf(0, -1, 7, 10, 59, 59))
        assertTrue(repository().entries().isEmpty())
    }

    @Test
    fun weather_isDownloadedOnceAndReusedWhileItCoversTheDays() = runBlocking {
        val repo = repository()
        assertNotNull(repo.weather(warsaw, d(1), d(11)))
        assertEquals(1, requests.size)
        assertTrue("start_date=2026-10-01&end_date=2026-10-11" in requests.single() && "latitude=52.2300" in requests.single())
        assertNotNull(repository().weather(warsaw, d(3), d(9)))   // inside the cached range, new instance
        assertEquals(1, requests.size)
        assertNotNull(repo.weather(warsaw, d(1), d(11)))
        assertEquals(1, requests.size)
    }

    @Test
    fun weather_isDownloadedAgainForMoreDaysAnotherPlaceOrAnOldCopy() = runBlocking {
        val repo = repository()
        repo.weather(warsaw, d(5), d(9))
        repo.weather(warsaw, d(1), d(9))                      // earlier start
        assertEquals(2, requests.size)
        repo.weather(GeoLocation(50.06, 19.94, 200.0), d(1), d(9)) // Kraków
        assertEquals(3, requests.size)
        assertTrue("latitude=50.0600" in requests.last())
        now = now.plus(ProductionRepository.CACHE_MAX_AGE).plus(Duration.ofMinutes(1))
        repo.weather(GeoLocation(50.06, 19.94, 200.0), d(1), d(9))
        assertEquals(4, requests.size)
    }

    @Test
    fun weather_offlineUsesAnOldCopyOnlyWhenItCoversTheDays() = runBlocking {
        val repo = repository()
        repo.weather(warsaw, d(1), d(9))
        online = false
        now = now.plus(Duration.ofDays(5))
        assertNotNull("old copy is better than none", repo.weather(warsaw, d(2), d(8)))
        assertNull("the copy does not have these days", repo.weather(warsaw, d(1), d(11)))
        assertNull("the copy is for another place", repo.weather(GeoLocation(50.06, 19.94, 200.0), d(2), d(8)))
    }

    @Test
    fun weather_nothingCachedAndOfflineIsNull() = runBlocking {
        online = false
        assertNull(repository().weather(warsaw, d(1), d(9)))
    }
}
