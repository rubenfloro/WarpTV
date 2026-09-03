package com.example.warptv

import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Registers a fresh WireGuard public key with Cloudflare WARP and builds a WireGuard Config. */
object WarpRegistrar {
    private const val API_URL = "https://api.cloudflareclient.com/v0a737/reg"

    data class Result(val config: Config, val accountId: String?, val deviceId: String?)

    fun register(): Result {
        val keyPair = KeyPair()
        val publicKey = keyPair.publicKey.toBase64()
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val payload = JSONObject().apply {
            put("key", publicKey)
            put("install_id", "")
            put("warp_enabled", true)
            put("tos", tos)
            put("type", "Android")
            put("locale", Locale.getDefault().toLanguageTag())
            put("model", "Android TV")
        }

        val response = postJson(payload.toString())
        val root = JSONObject(response)
        val cfg = root.getJSONObject("config")
        val iface = cfg.getJSONObject("interface").getJSONObject("addresses")
        val peers = cfg.getJSONArray("peers")
        require(peers.length() > 0) { "WARP no devolvió ningún peer" }
        val peer = peers.getJSONObject(0)
        val endpoint = peer.getJSONObject("endpoint").getString("host")
        val peerPublic = peer.getString("public_key")
        val v4 = iface.getString("v4")
        val v6 = iface.optString("v6", "")

        val addresses = buildString {
            append(v4).append("/32")
            if (v6.isNotBlank() && v6 != "null") append(", ").append(v6).append("/128")
        }

        val configText = buildString {
            append("[Interface]\n")
            append("PrivateKey = ").append(keyPair.privateKey.toBase64()).append('\n')
            append("Address = ").append(addresses).append('\n')
            append("DNS = 1.1.1.1, 1.0.0.1\n")
            append("MTU = 1280\n\n")
            append("[Peer]\n")
            append("PublicKey = ").append(peerPublic).append('\n')
            append("AllowedIPs = 0.0.0.0/0, ::/0\n")
            append("Endpoint = ").append(endpoint).append('\n')
        }

        return Result(
            Config.parse(configText.byteInputStream()),
            root.optJSONObject("account")?.optString("id"),
            root.optString("id", null)
        )
    }

    private fun postJson(body: String): String {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WarpTV/0.1 Android")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
            } ?: ""
            if (code !in 200..299) throw IllegalStateException("WARP HTTP $code: $text")
            text
        } finally {
            connection.disconnect()
        }
    }
}
