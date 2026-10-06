package com.solartracker.pro

import android.os.SystemClock
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** Shared helpers for UiAutomator tests on a freshly booted CI emulator. */
object UiTestSupport {
    /**
     * Waits for [selector] up to [timeoutMs]. A fresh emulator sometimes shows a system "isn't responding"
     * dialog (e.g. for System UI) that hides the app; such dialogs are dismissed with "Wait".
     */
    fun findDismissingAnr(device: UiDevice, selector: BySelector, tag: String, timeoutMs: Long = 60_000): UiObject2? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            device.wait(Until.findObject(selector), 5_000)?.let { return it }
            device.findObject(By.res("android:id/aerr_wait"))?.let {
                Log.w(tag, "dismissing system 'not responding' dialog")
                it.click()
            }
        }
        return null
    }
}
