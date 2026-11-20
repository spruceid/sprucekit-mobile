package com.spruceid.mobile.sdk.ble

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic

/** Stubs [Log] for JVM tests of code that logs through [BleLogger]. Undo with `unmockkAll()`. */
internal fun mockAndroidLog() {
    mockkStatic(Log::class)
    every { Log.v(any(), any()) } returns 0
    every { Log.d(any(), any()) } returns 0
    every { Log.i(any(), any()) } returns 0
    every { Log.w(any(), any<String>()) } returns 0
    every { Log.w(any(), any<String>(), any()) } returns 0
    every { Log.e(any(), any()) } returns 0
    every { Log.e(any(), any(), any()) } returns 0
}
