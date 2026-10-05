package com.solartracker.pro.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val filesDir by lazy { tmp.newFolder("files") }
    private val noBackup by lazy { tmp.newFolder("no_backup") }
    private val settings by lazy { File(filesDir, "datastore/${UpdateRecovery.SETTINGS_FILE}").apply { parentFile!!.mkdirs() } }
    private fun recovery() = UpdateRecovery(filesDir, noBackup) // a new instance = a new process

    @Test
    fun healthyUpdateKeepsSettingsAndClearsBackup() {
        settings.writeText("user-settings-v4")
        recovery().prepare(5, "0.5.0", "0.4.0")
        // Still the old version (installation pending/cancelled): nothing happens.
        assertEquals(UpdateRecovery.StartResult.Normal, recovery().onAppStart(4))
        val start = recovery().onAppStart(5)
        assertTrue(start is UpdateRecovery.StartResult.OnProbation)
        settings.writeText("user-settings-v5-migrated")
        val ended = recovery().markHealthy(5)
        assertEquals("0.5.0", ended!!.version)
        assertEquals("0.4.0", ended.fromVersion)
        assertEquals("user-settings-v5-migrated", settings.readText())
        assertEquals(UpdateRecovery.StartResult.Normal, recovery().onAppStart(5))
        assertNull(recovery().markHealthy(5))
    }

    @Test
    fun crashLoopRestoresSettingsBackup() {
        settings.writeText("good")
        recovery().prepare(5, "0.5.0", "0.4.0")
        assertTrue(recovery().onAppStart(5) is UpdateRecovery.StartResult.OnProbation)
        settings.writeText("broken by new version")
        recovery().recordCrash(5)
        assertTrue("one crash is tolerated", recovery().onAppStart(5) is UpdateRecovery.StartResult.OnProbation)
        recovery().recordCrash(5)
        val result = recovery().onAppStart(5)
        assertTrue(result is UpdateRecovery.StartResult.RolledBack)
        assertEquals(2, (result as UpdateRecovery.StartResult.RolledBack).probation.crashes)
        assertEquals("good", settings.readText())
        // Rolled back once; afterwards it is a normal start again.
        assertEquals(UpdateRecovery.StartResult.Normal, recovery().onAppStart(5))
    }

    @Test
    fun missingSettingsAfterUpdateAreRestored() {
        settings.writeText("good")
        recovery().prepare(5, "0.5.0", "0.4.0")
        settings.delete()
        assertTrue(recovery().onAppStart(5) is UpdateRecovery.StartResult.SettingsRestored)
        assertEquals("good", settings.readText())
    }

    @Test
    fun crashesOfOtherVersionsAreIgnoredAndAbandonClears() {
        settings.writeText("good")
        recovery().prepare(5, "0.5.0", "0.4.0")
        recovery().recordCrash(4)
        recovery().recordCrash(4)
        assertEquals(UpdateRecovery.StartResult.Normal, recovery().onAppStart(4))
        recovery().abandon()
        assertTrue(recovery().onAppStart(5) is UpdateRecovery.StartResult.Normal)
        assertFalse(File(noBackup, "update-backup/${UpdateRecovery.SETTINGS_FILE}").exists())
    }

    @Test
    fun olderMarkerIsDropped() {
        recovery().prepare(5, "0.5.0", "0.4.0")
        assertEquals(UpdateRecovery.StartResult.Normal, recovery().onAppStart(6))
        assertNull(recovery().markHealthy(5))
    }

    @Test
    fun noSettingsFileBeforeUpdate() {
        recovery().prepare(5, "0.5.0", "0.4.0")
        assertTrue(recovery().onAppStart(5) is UpdateRecovery.StartResult.OnProbation)
    }
}
