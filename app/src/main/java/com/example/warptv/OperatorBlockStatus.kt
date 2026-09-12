package com.example.warptv

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Reads the current operator-blocking lists published by hayahora.futbol. */
object OperatorBlockStatus {
    private const val BASE_URL = "https://hayahora.futbol/estado/"

    private val operatorFiles = linkedMapOf(
        "Movistar" to "blocked-movistar.txt",
        "DIGI" to "blocked-digi.txt",
        "Vodafone" to "blocked-vodafone.txt",
        "Orange" to "blocked-orange.txt",
        "MasMóvil" to "blocked-masmovil.txt"
    )

    data class Result(
        val blockedIpCount: Int,
        val affectedOperators: List<String>
    )

    fun fetch(): Result {
        val paths = listOf("blocked-any.txt") + operatorFiles.values
        val pool = Executors.newFixedThreadPool(paths.size)
        return try {
            val futures = paths.associateWith { path ->
                pool.submit<Set<String>> { fetchLines(path) }
            }
            val lists = futures.mapValues { it.value.get() }
            Result(
                blockedIpCount = lists.getValue("blocked-any.txt").size,
                affectedOperators = operatorFiles.mapNotNull { (operator, file) ->
                    if (lists.getValue(file).isNotEmpty()) operator else null
                }
            )
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fetchLines(path: String): Set<String> {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            useCaches = false
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "WarpTV/1.0 Android")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                reader.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toSet()
            }
        } finally {
            connection.disconnect()
        }
    }
}
