package com.example.warptv

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var button: Button
    private val executor = Executors.newSingleThreadExecutor()
    private val store by lazy { ConfigStore(this) }
    private var backend: Backend? = null
    private var config: Config? = null
    private var pendingConnect = false

    private val tunnel = object : Tunnel {
        override fun getName() = "warp-tv"
        override fun onStateChange(newState: Tunnel.State) { runOnUiThread { render(newState) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        backend = GoBackend(this)
        config = runCatching {
            store.load()?.let { Config.parse(it.byteInputStream()) }
        }.getOrNull()
        render(currentState())
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(80, 48, 80, 48)
            setBackgroundColor(0xFF101216.toInt())
        }
        val title = TextView(this).apply {
            text = "WARP TV"; textSize = 42f; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        }
        status = TextView(this).apply {
            textSize = 28f; gravity = Gravity.CENTER; setPadding(0, 24, 0, 12)
            setTextColor(0xFFFFFFFF.toInt())
        }
        details = TextView(this).apply {
            textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 30)
            setTextColor(0xFFB8BDC7.toInt())
        }
        button = Button(this).apply {
            textSize = 24f; isFocusable = true; isFocusableInTouchMode = true
            setOnClickListener { onMainButton() }
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(details, LinearLayout.LayoutParams(-1, -2))
        root.addView(button, LinearLayout.LayoutParams(560, 110))
        setContentView(root)
        button.requestFocus()
    }

    private fun onMainButton() {
        if (currentState() == Tunnel.State.UP) {
            executor.execute {
                runCatching { backend?.setState(tunnel, Tunnel.State.DOWN, null) }
                    .onFailure { showError(it) }
            }
            return
        }
        if (config == null) {
            generateConfig()
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            pendingConnect = true
            startActivityForResult(prepare, REQUEST_VPN)
        } else connect()
    }

    private fun generateConfig() {
        button.isEnabled = false
        status.text = "GENERANDO CONFIGURACIÓN…"
        details.text = "Registrando este dispositivo con WARP"
        executor.execute {
            try {
                val result = WarpRegistrar.register()
                val text = result.config.toWgQuickString()
                store.save(text)
                config = result.config
                runOnUiThread {
                    button.isEnabled = true
                    details.text = "Configuración lista"
                    render(currentState())
                }
            } catch (e: Exception) { showError(e) }
        }
    }

    private fun connect() {
        val cfg = config ?: return
        executor.execute {
            try { backend?.setState(tunnel, Tunnel.State.UP, cfg) }
            catch (e: Exception) { showError(e) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK && pendingConnect) connect()
            else if (resultCode != RESULT_OK) {
                status.text = "AUTORIZACIÓN VPN CANCELADA"
                details.text = "Pulsa el botón para volver a intentarlo"
            }
            pendingConnect = false
        }
    }

    private fun currentState(): Tunnel.State = try { backend?.getState(tunnel) ?: Tunnel.State.DOWN } catch (_: Exception) { Tunnel.State.DOWN }

    private fun render(state: Tunnel.State) {
        if (state == Tunnel.State.UP) {
            status.text = "● CONECTADA"
            button.text = "APAGAR VPN"
            details.text = "WireGuard / WARP activo"
        } else {
            status.text = "○ DESCONECTADA"
            button.text = if (config == null) "CONFIGURAR WARP" else "ENCENDER VPN"
            if (config == null) details.text = "Configuración pendiente" else details.text = "VPN apagada por el usuario"
        }
    }

    private fun showError(t: Throwable) {
        runOnUiThread {
            button.isEnabled = true
            status.text = "ERROR"
            details.text = (t.message ?: t.javaClass.simpleName).take(180)
            button.text = if (config == null) "REINTENTAR" else "ENCENDER VPN"
        }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    companion object { private const val REQUEST_VPN = 1001 }
}
