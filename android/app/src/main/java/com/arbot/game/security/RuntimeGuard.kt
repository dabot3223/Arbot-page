package com.arbot.game.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import com.arbot.game.BuildConfig

/**
 * Cheap local tamper signals for release builds. These are easy to patch out,
 * so they are only a speed bump: the authoritative checks are server-side
 * (Play Integrity verdicts, device signatures and replay verification).
 */
object RuntimeGuard {
    fun isCompromised(context: Context): Boolean {
        if (BuildConfig.DEBUG) return false
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return debuggable || Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }
}
