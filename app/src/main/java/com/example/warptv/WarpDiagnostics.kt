package com.example.warptv

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Reads public connection details through Cloudflare's diagnostic trace endpoint. */
object WarpDiagnostics {
    private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val PREFS = "warp_diagnostics"
    private const val BASELINE_IP_KEY = "baseline_public_ip"

    data class TraceResult(
        val ip: String?,
        val colo: String?
    )

    fun loadBaselineIp(context: Context): String? = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(BASELINE_IP_KEY, null)

    fun saveBaselineIp(context: Context, ip: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(BASELINE_IP_KEY, ip)
            .apply()
    }

    fun queryTrace(): TraceResult {
        val connection = (URL(TRACE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            useCaches = false
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "WarpTV/1.0 Android")
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Cloudflare trace HTTP $code")
            val body = BufferedReader(
                InputStreamReader(connection.inputStream, Charsets.UTF_8)
            ).use { it.readText() }
            val values = body.lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
                .toMap()
            TraceResult(
                ip = values["ip"].orEmpty().takeIf { it.isNotBlank() },
                colo = values["colo"].orEmpty().takeIf { it.isNotBlank() }
            )
        } finally {
            connection.disconnect()
        }
    }
}
