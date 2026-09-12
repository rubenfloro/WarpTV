package com.example.warptv

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
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
import android.view.View
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
        const val BUTTON_BLUE = 0xFF2563A9.toInt()
        const val BUTTON_BLUE_FOCUSED = 0xFF38C8FF.toInt()
        const val BUTTON_BLUE_PRESSED = 0xFF0C4775.toInt()
        const val BUTTON_DISABLED = 0xFF64748B.toInt()
        const val BUTTON_TEXT = 0xFF062A3F.toInt()
        const val REQUEST_VPN = 1001
    }

    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var metrics: TextView
    private lateinit var diagnostics: TextView
    private lateinit var button: Button
    private lateinit var operatorPanel: LinearLayout
    private lateinit var operatorBlockStatus: TextView
    private lateinit var operatorRefreshButton: Button
    private val executor = Executors.newSingleThreadExecutor()
    private val metricsHandler = Handler(Looper.getMainLooper())
    private val store by lazy { ConfigStore(this) }
    private var backend: Backend? = null
    private var config: Config? = null
    private var pendingConnect = false
    private var lastRenderedState: Tunnel.State? = null
    private var diagnosticsQueryRunning = false
    private var operatorStatusQueryRunning = false
    private val tunnel = WarpRuntime.tunnel
    private val connectedDiagnosticsRunnable = Runnable { queryConnectedDiagnostics() }
    private val disconnectedDiagnosticsRunnable = Runnable { queryDisconnectedIp() }
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
        operatorBlockStatus = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(0, 12, 0, 12)
            maxLines = 3
            setHorizontallyScrolling(false)
            setTextColor(STATUS_NEUTRAL)
        }
        operatorRefreshButton = Button(this).apply {
            text = "ACTUALIZAR DATOS"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(buttonTextColor())
            setPadding(24, 0, 24, 0)
            isFocusable = true
            isFocusableInTouchMode = true
            background = buttonBackground()
            stateListAnimator = buttonInteractionAnimator(this)
            setOnClickListener { refreshOperatorBlockStatus() }
        }
        button = Button(this).apply {
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(buttonTextColor())
            setPadding(24, 0, 24, 0)
            isFocusable = true
            isFocusableInTouchMode = true
            background = buttonBackground()
            stateListAnimator = buttonInteractionAnimator(this)
            setOnClickListener { onMainButton() }
        }
        operatorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 12, 36, 12)
            background = operatorPanelBackground()
        }
        operatorPanel.addView(operatorBlockStatus, LinearLayout.LayoutParams(0, -1, 1f).apply {
            marginEnd = 24
        })
        operatorPanel.addView(operatorRefreshButton, LinearLayout.LayoutParams(440, 88))
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(details, LinearLayout.LayoutParams(-1, -2))
        root.addView(metrics, LinearLayout.LayoutParams(-1, -2))
        root.addView(diagnostics, LinearLayout.LayoutParams(-1, -2))
        root.addView(button, LinearLayout.LayoutParams(640, 128).apply { bottomMargin = 28 })
        root.addView(operatorPanel, LinearLayout.LayoutParams(-1, 156))
        setContentView(root)
        button.post { button.requestFocus() }
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
        // Android TV may restore the last focused view; the VPN action is always the default.
        button.post { if (!isFinishing) button.requestFocus() }
        refreshState()
        metricsHandler.removeCallbacks(metricsTicker)
        metricsHandler.post(metricsTicker)
    }

    override fun onPause() {
        metricsHandler.removeCallbacks(metricsTicker)
        metricsHandler.removeCallbacks(connectedDiagnosticsRunnable)
        metricsHandler.removeCallbacks(disconnectedDiagnosticsRunnable)
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
            diagnostics.text = ""
            operatorPanel.visibility = View.GONE
        } else if (state == Tunnel.State.UP) {
            WarpRuntime.ensureConnectionStarted()
            status.setTextColor(STATUS_GREEN)
            status.text = "● VPN CONECTADA"
            button.text = "APAGAR VPN"
            details.text = "WireGuard / WARP activo"
            metrics.text = formatMetrics(WarpRuntime.connectionStartMillis(), 0L, 0L)
            if (lastRenderedState != Tunnel.State.UP) scheduleConnectedDiagnostics()
            showOperatorBlockStatus()
        } else {
            WarpRuntime.clearConnectionStart()
            status.setTextColor(STATUS_RED)
            status.text = "● VPN DESCONECTADA"
            button.text = if (config == null) "CONFIGURAR WARP" else "ENCENDER VPN"
            if (config == null) details.text = "Configuración pendiente" else details.text = "VPN apagada por el usuario"
            metrics.text = ""
            metricsHandler.removeCallbacks(connectedDiagnosticsRunnable)
            val baseline = WarpDiagnostics.loadBaselineIp(this)
            diagnostics.text = formatDisconnectedDiagnostics(baseline)
            if (lastRenderedState == null || lastRenderedState == Tunnel.State.UP || baseline == null) {
                scheduleDisconnectedDiagnostics()
            }
            showOperatorBlockStatus()
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
        }
    }

    private fun buttonBackground(): StateListDrawable {
        fun fill(color: Int, strokeColor: Int, strokeWidth: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f
            setColor(color)
            setStroke(strokeWidth, strokeColor)
        }

        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), fill(BUTTON_DISABLED, 0xFF94A3B8.toInt(), 2))
            addState(intArrayOf(android.R.attr.state_pressed), fill(BUTTON_BLUE_PRESSED, 0xFFBFEFFF.toInt(), 4))
            addState(intArrayOf(android.R.attr.state_focused), fill(BUTTON_BLUE_FOCUSED, 0xFFFFFFFF.toInt(), 5))
            addState(intArrayOf(), fill(BUTTON_BLUE, 0xFF164B7A.toInt(), 2))
        }
    }

    private fun buttonTextColor(): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(android.R.attr.state_focused),
                intArrayOf()
            ),
            intArrayOf(
                0xFFE2E8F0.toInt(),
                BUTTON_TEXT,
                0xFFFFFFFF.toInt(),
                0xFFFFFFFF.toInt()
            )
        )
    }

    private fun buttonInteractionAnimator(view: View): StateListAnimator {
        fun animation(scale: Float, translationY: Float, elevation: Float): AnimatorSet {
            return AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, "scaleX", scale),
                    ObjectAnimator.ofFloat(view, "scaleY", scale),
                    ObjectAnimator.ofFloat(view, "translationY", translationY),
                    ObjectAnimator.ofFloat(view, "elevation", elevation)
                )
                duration = 110L
                interpolator = android.view.animation.DecelerateInterpolator()
            }
        }

        return StateListAnimator().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), animation(1f, 0f, 0f))
            addState(intArrayOf(android.R.attr.state_pressed), animation(0.96f, 4f, 2f))
            addState(intArrayOf(android.R.attr.state_focused), animation(1.03f, -3f, 10f))
            addState(intArrayOf(), animation(1f, 0f, 0f))
        }
    }

    private fun operatorPanelBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f
            setColor(0xFF101820.toInt())
            setStroke(2, 0xFF263746.toInt())
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

    private fun scheduleConnectedDiagnostics() {
        if (diagnosticsQueryRunning || config == null) return
        metricsHandler.removeCallbacks(connectedDiagnosticsRunnable)
        val baseline = WarpDiagnostics.loadBaselineIp(this)
        diagnostics.text = formatConnectedWaitingDiagnostics(baseline)
        metricsHandler.postDelayed(connectedDiagnosticsRunnable, 2_000L)
    }

    private fun queryConnectedDiagnostics() {
        if (diagnosticsQueryRunning || config == null || currentState() != Tunnel.State.UP) return
        diagnosticsQueryRunning = true
        executor.execute {
            try {
                val trace = WarpDiagnostics.queryTrace()
                val baseline = WarpDiagnostics.loadBaselineIp(this)
                val text = formatConnectedDiagnostics(baseline, trace)
                runOnUiThread {
                    diagnosticsQueryRunning = false
                    if (!isFinishing && currentState() == Tunnel.State.UP) diagnostics.text = text
                    else if (!isFinishing) scheduleDisconnectedDiagnostics()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    diagnosticsQueryRunning = false
                    if (!isFinishing && currentState() == Tunnel.State.UP) {
                        diagnostics.text = formatConnectedWaitingDiagnostics(WarpDiagnostics.loadBaselineIp(this))
                    } else if (!isFinishing) scheduleDisconnectedDiagnostics()
                }
            }
        }
    }

    private fun scheduleDisconnectedDiagnostics() {
        if (diagnosticsQueryRunning || config == null) return
        metricsHandler.removeCallbacks(disconnectedDiagnosticsRunnable)
        metricsHandler.postDelayed(disconnectedDiagnosticsRunnable, 2_000L)
    }

    private fun queryDisconnectedIp() {
        if (diagnosticsQueryRunning || config == null || currentState() == Tunnel.State.UP) return
        diagnosticsQueryRunning = true
        executor.execute {
            try {
                val trace = WarpDiagnostics.queryTrace()
                trace.ip?.let { WarpDiagnostics.saveBaselineIp(this, it) }
                val text = formatDisconnectedDiagnostics(WarpDiagnostics.loadBaselineIp(this))
                runOnUiThread {
                    diagnosticsQueryRunning = false
                    if (!isFinishing && currentState() != Tunnel.State.UP) diagnostics.text = text
                }
            } catch (_: Exception) {
                runOnUiThread { diagnosticsQueryRunning = false }
            }
        }
    }

    private fun showOperatorBlockStatus() {
        operatorPanel.visibility = View.VISIBLE
        operatorRefreshButton.isEnabled = true
        operatorRefreshButton.alpha = if (operatorStatusQueryRunning) 0.65f else 1f
        if (operatorBlockStatus.text.isNullOrBlank()) refreshOperatorBlockStatus()
    }

    private fun refreshOperatorBlockStatus() {
        if (operatorStatusQueryRunning || config == null) return
        operatorStatusQueryRunning = true
        operatorRefreshButton.isEnabled = true
        operatorRefreshButton.alpha = 0.65f
        operatorRefreshButton.requestFocus()
        operatorBlockStatus.setTextColor(STATUS_NEUTRAL)
        operatorBlockStatus.text = "Bloqueos Fútbol: COMPROBANDO…\nIPs afectadas: —\nOperadores afectados: —"
        executor.execute {
            try {
                val result = OperatorBlockStatus.fetch()
                runOnUiThread {
                    operatorStatusQueryRunning = false
                    if (!isFinishing) {
                        renderOperatorBlockStatus(result)
                        operatorRefreshButton.alpha = 1f
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    operatorStatusQueryRunning = false
                    if (!isFinishing) {
                        operatorBlockStatus.setTextColor(STATUS_NEUTRAL)
                        operatorBlockStatus.text = "Bloqueos Fútbol: DATOS NO DISPONIBLES\nIPs afectadas: —\nOperadores afectados: —"
                        operatorRefreshButton.isEnabled = true
                        operatorRefreshButton.alpha = 1f
                    }
                }
            }
        }
    }

    private fun renderOperatorBlockStatus(result: OperatorBlockStatus.Result) {
        val incidents = result.blockedIpCount > 0 || result.affectedOperators.isNotEmpty()
        operatorBlockStatus.setTextColor(if (incidents) STATUS_RED else STATUS_GREEN)
        val operators = result.affectedOperators.joinToString(", ").ifBlank { "Ninguno" }
        operatorBlockStatus.text =
            "Bloqueos Fútbol: ${if (incidents) "INCIDENCIAS DETECTADAS" else "SIN INCIDENCIAS"}\n" +
                "IPs afectadas: ${result.blockedIpCount}\n" +
                "Operadores afectados: $operators"
        operatorRefreshButton.isEnabled = true
        operatorRefreshButton.alpha = 1f
    }

    private fun captureBaselineIp() {
        runCatching { WarpDiagnostics.queryTrace().ip }
            .getOrNull()
            ?.let { WarpDiagnostics.saveBaselineIp(this, it) }
    }

    private fun formatConnectedWaitingDiagnostics(baselineIp: String?): String {
        return "IP Pública sin VPN: ${baselineIp ?: "No disponible"}\n" +
            "IP Pública con WARP: comprobando…\n" +
            "Localización Cloudflare: comprobando…"
    }

    private fun formatConnectedDiagnostics(
        baselineIp: String?,
        trace: WarpDiagnostics.TraceResult
    ): String {
        return "IP Pública sin VPN: ${baselineIp ?: "No disponible"}\n" +
            "IP Pública con WARP: ${trace.ip ?: "No disponible"}\n" +
            "Localización Cloudflare: ${trace.colo ?: "No disponible"}"
    }

    private fun formatDisconnectedDiagnostics(baselineIp: String?): String {
        return "IP Pública sin VPN: ${baselineIp ?: "No disponible"}"
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
        metricsHandler.removeCallbacks(connectedDiagnosticsRunnable)
        metricsHandler.removeCallbacks(disconnectedDiagnosticsRunnable)
        executor.shutdownNow()
        super.onDestroy()
    }

}
