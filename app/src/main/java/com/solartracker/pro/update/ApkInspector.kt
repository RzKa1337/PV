package com.solartracker.pro.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.solartracker.pro.core.update.ApkIdentity
import java.io.File
import java.security.MessageDigest

/** Reads package name, version and signing certificates of the installed app and of an APK file. */
object ApkInspector {

    private val flags: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    fun installed(context: Context): ApkIdentity {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }
        return info.identity()
    }

    fun archive(context: Context, apk: File): ApkIdentity? {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        }
        return info?.identity()
    }

    private fun PackageInfo.identity() = ApkIdentity(
        packageName = packageName,
        versionCode = PackageInfoCompat.getLongVersionCode(this),
        versionName = versionName,
        signerSha256 = signers().map { sha256(it.toByteArray()) }.toSet(),
    )

    private fun PackageInfo.signers(): List<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            signatures?.toList().orEmpty()
        }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
