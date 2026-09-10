package com.example.warptv

import android.content.Context
import android.content.SharedPreferences
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel

/** Keeps the backend and tunnel identity stable while the app process is alive. */
object WarpRuntime {
    private const val CONNECTED_AT_KEY = "connected_at_epoch_millis"
    private var backend: Backend? = null
    private var preferences: SharedPreferences? = null
    private var stateListener: ((Tunnel.State) -> Unit)? = null

    val tunnel: Tunnel = object : Tunnel {
        override fun getName() = "warp-tv"

        override fun onStateChange(newState: Tunnel.State) {
            val listener = synchronized(this@WarpRuntime) {
                when (newState) {
                    Tunnel.State.UP -> ensureConnectionStartedLocked()
                    Tunnel.State.DOWN -> preferences?.edit()?.remove(CONNECTED_AT_KEY)?.apply()
                    else -> Unit
                }
                stateListener
            }
            listener?.invoke(newState)
        }
    }

    @Synchronized
    fun getBackend(context: Context): Backend {
        val appContext = context.applicationContext
        if (preferences == null) {
            preferences = appContext.getSharedPreferences("warp_runtime", Context.MODE_PRIVATE)
        }
        return backend ?: GoBackend(appContext).also { backend = it }
    }

    @Synchronized
    fun setStateListener(listener: ((Tunnel.State) -> Unit)?) {
        stateListener = listener
    }

    @Synchronized
    fun ensureConnectionStarted() {
        ensureConnectionStartedLocked()
    }

    @Synchronized
    fun connectionStartMillis(): Long = preferences?.getLong(CONNECTED_AT_KEY, 0L) ?: 0L

    @Synchronized
    fun clearConnectionStart() {
        preferences?.edit()?.remove(CONNECTED_AT_KEY)?.apply()
    }

    private fun ensureConnectionStartedLocked() {
        val prefs = preferences ?: return
        if (prefs.getLong(CONNECTED_AT_KEY, 0L) == 0L) {
            prefs.edit().putLong(CONNECTED_AT_KEY, System.currentTimeMillis()).apply()
        }
    }
}
