package com.solartracker.pro

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.solartracker.pro.core.update.ApkIdentityCheck
import com.solartracker.pro.core.update.SemanticVersion
import com.solartracker.pro.update.ApkInspector
import com.solartracker.pro.update.UpdateRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Checks the APK verification against the real Android package manager. */
@RunWith(AndroidJUnit4::class)
class UpdateVerificationInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedAppAndItsOwnApkHaveTheSameIdentity() {
        val installed = ApkInspector.installed(context)
        Log.i("UpdateTest", "installed: $installed")
        assertEquals("com.solartracker.pro", installed.packageName)
        assertEquals(BuildConfig.VERSION_CODE.toLong(), installed.versionCode)
        assertEquals(1, installed.signerSha256.size)
        assertEquals(64, installed.signerSha256.first().length)

        // The APK file of the installed app, read as a downloaded update would be.
        val apk = File(context.applicationInfo.sourceDir)
        val archive = ApkInspector.archive(context, apk)
        Log.i("UpdateTest", "archive: $archive")
        assertEquals(installed, archive)

        // Same version → refused; the same APK claiming a newer version passes all other checks.
        val version = SemanticVersion.parse(BuildConfig.VERSION_NAME)!!
        assertTrue(ApkIdentityCheck.problem(installed, archive, version)!!.contains("nie jest nowsze"))
        val newer = archive!!.copy(versionCode = archive.versionCode + 1, versionName = "99.0.0")
        assertNull(ApkIdentityCheck.problem(installed, newer, SemanticVersion(99, 0, 0)))
        // A different signing key is refused.
        val otherKey = newer.copy(signerSha256 = setOf("0".repeat(64)))
        assertTrue(ApkIdentityCheck.problem(installed, otherKey, SemanticVersion(99, 0, 0))!!.contains("innym kluczem"))
    }

    @Test
    fun garbageFileIsNotAnApk() {
        val fake = File(context.cacheDir, "fake.apk").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val identity = ApkInspector.archive(context, fake)
        assertNull(identity)
        assertNotNull(ApkIdentityCheck.problem(ApkInspector.installed(context), identity, SemanticVersion(1, 0, 0)))
        fake.delete()
    }

    @Test
    fun recoveryWorksWithRealAppDirectories() {
        val recovery = UpdateRecovery(context)
        recovery.abandon()
        assertEquals(UpdateRecovery.StartResult.Normal, recovery.onAppStart(BuildConfig.VERSION_CODE.toLong()))
        recovery.prepare(BuildConfig.VERSION_CODE.toLong() + 1, "99.0.0", BuildConfig.VERSION_NAME)
        assertEquals(UpdateRecovery.StartResult.Normal, recovery.onAppStart(BuildConfig.VERSION_CODE.toLong()))
        recovery.abandon()
    }
}
