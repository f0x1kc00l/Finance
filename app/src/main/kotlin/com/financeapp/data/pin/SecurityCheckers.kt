package com.financeapp.data.pin

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Basic root and emulator detection.
 *
 * Threat model context:
 * - These checks are NOT a silver bullet. A sophisticated attacker with physical
 *   access and a Magisk-hidden root can bypass most software checks.
 * - Goal: raise the cost of attack. Stops casual/automated extraction attempts.
 * - A server-verified Play Integrity verdict would be the real control; this app has no
 *   backend, so that is listed as a recommendation in the README rather than stubbed here.
 * - NEVER use this as the sole security gate for PIN verification — it's a
 *   supplementary signal only.
 *
 * We deliberately log only opaque results (true/false), never which check fired,
 * to make it harder for an attacker reading logcat to know what to spoof.
 */
object DeviceSecurityChecker {

    private const val TAG = "DeviceSecurity" // opaque tag — no "root" in log

    private val knownRootApps = arrayOf(
        "com.topjohnwu.magisk",
        "com.noshufou.android.su",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.zachspong.temprootremovejb",
        "com.ramdroid.appquarantine"
    )

    private val rootBinaries = arrayOf("su", "busybox", "magisk")

    private val rootPaths = arrayOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su"
    )

    /** Returns true if the device shows signs of root. */
    fun isRooted(context: Context): Boolean {
        val result = checkBuildTags() || checkRootPaths() || checkRootPackages(context)
        // Only log the boolean result, never the reason
        if (result) Log.w(TAG, "Device integrity check: anomaly detected")
        return result
    }

    /** Returns true if running on an emulator. */
    fun isEmulator(): Boolean {
        val result = Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || Build.MANUFACTURER.contains("Genymotion")
            || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
            || "google_sdk" == Build.PRODUCT
        if (result) Log.w(TAG, "Emulator environment detected")
        return result
    }

    /** Combined check for PIN-screen risk assessment. */
    fun isDeviceCompromised(context: Context): Boolean = isRooted(context) || isEmulator()

    private fun checkBuildTags(): Boolean =
        Build.TAGS != null && Build.TAGS.contains("test-keys")

    private fun checkRootPaths(): Boolean =
        rootPaths.any { File(it).exists() }

    private fun checkRootPackages(context: Context): Boolean {
        val pm = context.packageManager
        return knownRootApps.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
        }
    }
}
