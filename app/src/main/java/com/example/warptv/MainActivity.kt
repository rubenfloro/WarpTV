package com.example.warptv

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private companion object {
        const val STATUS_RED = 0xFFFF5B61.toInt()
        const val STATUS_GREEN = 0xFF2ECC71.toInt()
        const val STATUS_NEUTRAL = 0xFFB8BDC7.toInt()
        const val BUTTON_BLUE = 0xFF55B9EA.toInt()
        const val BUTTON_BLUE_FOCUSED = 0xFF7DD3FC.toInt()
        const val BUTTON_BLUE_PRESSED = 0xFF2D90C4.toInt()
        const val BUTTON_DISABLED = 0xFF64748B.toInt()
        const val BUTTON_TEXT = 0xFF062A3F.toInt()
        const val REQUEST_VPN = 1001
    }

    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var metrics: TextView
    private lateinit var diagnostics: TextView
    private lateinit var button: Button
    private lateinit var diagnosticsButton: Button
    private val executor = Executors.newSingleThreadExecutor()
    private val metricsHandler = Handler(Looper.getMainLooper())
    private val store by lazy { ConfigStore(this) }
    private var backend: Backend? = null
    private var config: Config? = null
    private var pendingConnect = false
    private var lastRenderedState: Tunnel.State? = null
    private var diagnosticsRefreshRunning = false
    private val tunnel = WarpRuntime.tunnel
    private val metricsTicker = object : Runnable {
        override fun run() {
            refreshMetrics()
            metricsHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        backend = WarpRuntime.getBackend(this)
        WarpRuntime.setStateListener { newState -> runOnUiThread { render(newState) } }
        config = runCatching {
            store.load()?.let { storedText ->
                // Migrate configurations created by earlier builds to the selected DNS.
                val updatedText = enforcePreferredDns(storedText)
                if (updatedText != storedText) store.save(updatedText)
                Config.parse(updatedText.byteInputStream())
            }
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
            setTextColor(STATUS_NEUTRAL)
        }
        details = TextView(this).apply {
            textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 30)
            setTextColor(STATUS_NEUTRAL)
        }
        metrics = TextView(this).apply {
            textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 30)
            setTextColor(STATUS_NEUTRAL)
        }
        diagnostics = TextView(this).apply {
            textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 18)
            setTextColor(STATUS_NEUTRAL)
        }
        diagnosticsButton = Button(this).apply {
            text = "ACTUALIZAR DIAGNÓSTICO"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(BUTTON_TEXT)
            setPadding(24, 0, 24, 0)
            isFocusable = true
            isFocusableInTouchMode = true
            background = buttonBackground()
            setOnClickListener { refreshDiagnostics() }
        }
        button = Button(this).apply {
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(BUTTON_TEXT)
            setPadding(24, 0, 24, 0)
            isFocusable = true
            isFocusableInTouchMode = true
            background = buttonBackground()
            setOnClickListener { onMainButton() }
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(details, LinearLayout.LayoutParams(-1, -2))
        root.addView(metrics, LinearLayout.LayoutParams(-1, -2))
        root.addView(diagnostics, LinearLayout.LayoutParams(-1, -2))
        root.addView(diagnosticsButton, LinearLayout.LayoutParams(560, 96))
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
        status.setTextColor(STATUS_NEUTRAL)
        status.text = "GENERANDO CONFIGURACIÓN…"
        details.text = "Registrando este dispositivo con WARP"
        executor.execute {
            try {
                captureBaselineIp()
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

    override fun onResume() {
        super.onResume()
        refreshState()
        metricsHandler.removeCallbacks(metricsTicker)
        metricsHandler.post(metricsTicker)
    }

    override fun onPause() {
        metricsHandler.removeCallbacks(metricsTicker)
        super.onPause()
    }

    private fun connect() {
        val cfg = config ?: return
        executor.execute {
            try {
                // Capture the current non-VPN IP immediately before enabling the tunnel.
                captureBaselineIp()
                backend?.setState(tunnel, Tunnel.State.UP, cfg)
            }
            catch (e: Exception) { showError(e) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK && pendingConnect) connect()
            else if (resultCode != RESULT_OK) {
                status.setTextColor(STATUS_RED)
                status.text = "AUTORIZACIÓN VPN CANCELADA"
                details.text = "Pulsa el botón para volver a intentarlo"
            }
            pendingConnect = false
        }
    }

    private fun render(state: Tunnel.State) {
        if (config == null) {
            WarpRuntime.clearConnectionStart()
            status.setTextColor(STATUS_RED)
            status.text = "● NECESARIO CONFIGURAR VPN"
            button.text = "CONFIGURAR WARP"
            details.text = "Configuración pendiente"
            metrics.text = ""
            diagnostics.text = "El diagnóstico estará disponible después de configurar WARP"
            diagnosticsButton.isEnabled = false
        } else if (state == Tunnel.State.UP) {
            WarpRuntime.ensureConnectionStarted()
            status.setTextColor(STATUS_GREEN)
            status.text = "● VPN CONECTADA"
            button.text = "APAGAR VPN"
            details.text = "WireGuard / WARP activo"
            metrics.text = formatMetrics(WarpRuntime.connectionStartMillis(), 0L, 0L)
            diagnosticsButton.isEnabled = !diagnosticsRefreshRunning
            if (lastRenderedState != Tunnel.State.UP) refreshDiagnostics()
        } else {
            WarpRuntime.clearConnectionStart()
            status.setTextColor(STATUS_RED)
            status.text = "● VPN DESCONECTADA"
            button.text = if (config == null) "CONFIGURAR WARP" else "ENCENDER VPN"
            if (config == null) details.text = "Configuración pendiente" else details.text = "VPN apagada por el usuario"
            metrics.text = ""
            diagnosticsButton.isEnabled = !diagnosticsRefreshRunning
            if (!diagnosticsRefreshRunning) {
                val baseline = WarpDiagnostics.loadBaselineIp(this) ?: "No disponible"
                diagnostics.text = "IP sin VPN: $baseline\nPulsa ACTUALIZAR DIAGNÓSTICO para consultar la conexión"
            }
        }
        lastRenderedState = state
    }

    private fun showError(t: Throwable) {
        runOnUiThread {
            button.isEnabled = true
            status.setTextColor(STATUS_RED)
            status.text = "ERROR"
            details.text = (t.message ?: t.javaClass.simpleName).take(180)
            metrics.text = ""
            button.text = if (config == null) "REINTENTAR" else "ENCENDER VPN"
            diagnosticsButton.isEnabled = config != null
        }
    }

    private fun buttonBackground(): StateListDrawable {
        fun fill(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f
            setColor(color)
        }

        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), fill(BUTTON_DISABLED))
            addState(intArrayOf(android.R.attr.state_pressed), fill(BUTTON_BLUE_PRESSED))
            addState(intArrayOf(android.R.attr.state_focused), fill(BUTTON_BLUE_FOCUSED))
            addState(intArrayOf(), fill(BUTTON_BLUE))
        }
    }

    private fun refreshState() {
        executor.execute {
            val state = currentState()
            runOnUiThread { if (!isFinishing) render(state) }
        }
    }

    private fun refreshMetrics() {
        executor.execute {
            val state = currentState()
            if (state != Tunnel.State.UP || config == null) {
                runOnUiThread { if (!isFinishing) metrics.text = "" }
                return@execute
            }

            WarpRuntime.ensureConnectionStarted()
            val stats = runCatching { backend?.getStatistics(tunnel) }.getOrNull()
            val rxBytes = stats?.totalRx() ?: 0L
            val txBytes = stats?.totalTx() ?: 0L
            val startMillis = WarpRuntime.connectionStartMillis()
            val text = formatMetrics(startMillis, rxBytes, txBytes)
            runOnUiThread {
                if (!isFinishing && currentState() == Tunnel.State.UP) metrics.text = text
            }
        }
    }

    private fun refreshDiagnostics() {
        if (diagnosticsRefreshRunning || config == null) return
        diagnosticsRefreshRunning = true
        diagnosticsButton.isEnabled = false
        diagnostics.text = "Comprobando IP, WARP y latencia…"
        executor.execute {
            try {
                val connected = currentState() == Tunnel.State.UP
                val trace = WarpDiagnostics.queryTrace()
                if (!connected) trace.ip?.let { WarpDiagnostics.saveBaselineIp(this, it) }
                val baseline = WarpDiagnostics.loadBaselineIp(this)
                val handshake = if (connected) latestHandshakeEpochMillis() else 0L
                val text = formatDiagnostics(trace, baseline, handshake, connected)
                runOnUiThread {
                    diagnosticsRefreshRunning = false
                    if (!isFinishing) {
                        diagnostics.text = text
                        diagnosticsButton.isEnabled = config != null
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    diagnosticsRefreshRunning = false
                    if (!isFinishing) {
                        diagnostics.text = "No se pudo actualizar el diagnóstico\n${e.message ?: "Comprueba la conexión a Internet"}"
                        diagnosticsButton.isEnabled = config != null
                    }
                }
            }
        }
    }

    private fun captureBaselineIp() {
        runCatching { WarpDiagnostics.queryTrace().ip }
            .getOrNull()
            ?.let { WarpDiagnostics.saveBaselineIp(this, it) }
    }

    private fun latestHandshakeEpochMillis(): Long {
        val stats = runCatching { backend?.getStatistics(tunnel) }.getOrNull() ?: return 0L
        return stats.peers().mapNotNull { peerKey ->
            val peerStats = stats.peer(peerKey) ?: return@mapNotNull null
            // Supports both the record accessor in current WireGuard and the older public field.
            runCatching {
                (peerStats.javaClass.getMethod("latestHandshakeEpochMillis").invoke(peerStats) as Number).toLong()
            }.recoverCatching {
                (peerStats.javaClass.getField("latestHandshakeEpochMillis").get(peerStats) as Number).toLong()
            }.getOrNull()
        }.maxOrNull() ?: 0L
    }

    private fun formatDiagnostics(
        trace: WarpDiagnostics.TraceResult,
        baselineIp: String?,
        handshakeEpochMillis: Long,
        connected: Boolean
    ): String {
        val warpText = when {
            trace.warpVerified -> "Sí"
            trace.warpStatus != null -> "No (${trace.warpStatus})"
            else -> "No disponible"
        }
        val colo = trace.colo ?: "No disponible"
        val observedIpLabel = if (connected) "IP con WARP" else "IP actual sin VPN"
        return "IP sin VPN: ${baselineIp ?: "No disponible"}\n" +
            "$observedIpLabel: ${trace.ip ?: "No disponible"}\n" +
            "WARP verificado: $warpText    Punto Cloudflare: $colo\n" +
            "Handshake: ${formatHandshake(handshakeEpochMillis)}    Latencia: ${trace.latencyMillis} ms"
    }

    private fun formatHandshake(epochMillis: Long): String {
        if (epochMillis <= 0L) return "No disponible"
        val ageSeconds = ((System.currentTimeMillis() - epochMillis).coerceAtLeast(0L)) / 1000L
        return when {
            ageSeconds < 60L -> "hace ${ageSeconds}s"
            ageSeconds < 3_600L -> "hace ${ageSeconds / 60L} min"
            else -> "hace ${ageSeconds / 3_600L} h"
        }
    }

    private fun formatMetrics(startMillis: Long, rxBytes: Long, txBytes: Long): String {
        val elapsed = if (startMillis > 0L) {
            (System.currentTimeMillis() - startMillis).coerceAtLeast(0L)
        } else 0L
        return "Tiempo: ${formatDuration(elapsed)}\n" +
            "↓ Descarga: ${formatBytes(rxBytes)}    ↑ Subida: ${formatBytes(txBytes)}"
    }

    private fun formatDuration(milliseconds: Long): String {
        var seconds = milliseconds / 1000L
        val days = seconds / 86_400L
        seconds %= 86_400L
        val hours = seconds / 3_600L
        seconds %= 3_600L
        val minutes = seconds / 60L
        seconds %= 60L
        return if (days > 0L) {
            String.format(Locale.getDefault(), "%dd %02dh %02dm %02ds", days, hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02dh %02dm %02ds", hours, minutes, seconds)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return String.format(Locale.getDefault(), "%.2f MB", bytes / 1_000_000.0)
    }

    private fun enforcePreferredDns(configText: String): String {
        val dnsLine = Regex("(?m)^DNS\\s*=.*$")
        return if (dnsLine.containsMatchIn(configText)) {
            dnsLine.replace(configText, "DNS = ${WarpRegistrar.PREFERRED_DNS}")
        } else {
            Regex("(?m)^MTU\\s*=.*$").replace(configText) { match ->
                "${match.value}\nDNS = ${WarpRegistrar.PREFERRED_DNS}"
            }
        }
    }

    private fun currentState(): Tunnel.State {
        val backendState = runCatching { backend?.getState(tunnel) ?: Tunnel.State.DOWN }
            .getOrDefault(Tunnel.State.DOWN)
        if (backendState == Tunnel.State.UP) return Tunnel.State.UP
        return if (isVpnActiveForThisApp()) Tunnel.State.UP else Tunnel.State.DOWN
    }

    private fun isVpnActiveForThisApp(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.allNetworks.any { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@any false
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val ownerUid = capabilities.ownerUid
                ownerUid < 0 || ownerUid == applicationInfo.uid
            } else {
                true
            }
        }
    }

    override fun onDestroy() {
        WarpRuntime.setStateListener(null)
        metricsHandler.removeCallbacks(metricsTicker)
        executor.shutdownNow()
        super.onDestroy()
    }

}
