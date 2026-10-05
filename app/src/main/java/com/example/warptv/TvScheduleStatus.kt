package com.example.warptv

import android.os.Build
import android.text.Html
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** Reads the next televised match for the three requested teams. */
object TvScheduleStatus {
    private data class TeamPage(
        val displayName: String,
        val aliases: List<String>,
        val url: String
    )

    data class Result(val lines: List<String>)

    private val pages = listOf(
        TeamPage(
            displayName = "Real Madrid",
            aliases = listOf("Real Madrid"),
            url = "https://www.futbolenlatv.es/equipo/real-madrid"
        ),
        TeamPage(
            displayName = "At. Madrid",
            aliases = listOf("At. Madrid", "Atlético de Madrid"),
            url = "https://www.futbolenlatv.es/equipo/at-madrid"
        ),
        TeamPage(
            displayName = "Barcelona",
            aliases = listOf("FC Barcelona", "Barcelona"),
            url = "https://www.futbolenlatv.es/equipo/fc-barcelona"
        )
    )

    private val datePattern = Regex("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b")
    private val timePattern = Regex("\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b")
    private val teamAnchorPattern = Regex(
        """<a\b[^>]*href\s*=\s*[\"'][^\"']*/equipo/[^\"']*[\"'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun fetch(): Result {
        val pool = Executors.newFixedThreadPool(pages.size)
        return try {
            val futures = pages.map { page -> pool.submit { fetchPage(page) } }
            Result(futures.map { it.get() })
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fetchPage(page: TeamPage): String {
        return runCatching {
            val html = download(page.url)
            parseNextMatch(page, html)
        }.getOrElse {
            "${page.displayName}: información no disponible"
        }
    }

    private fun download(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            useCaches = false
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "WarpTV/4.0 Android")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseNextMatch(page: TeamPage, html: String): String {
        val lines = htmlToLines(html)
        val teamNames = extractTeamNames(html)
        val dateIndexes = lines.mapIndexedNotNull { index, line ->
            if (datePattern.containsMatchIn(line)) index else null
        }
        val firstDateIndex = dateIndexes.firstOrNull()
            ?: throw IllegalStateException("No hay partidos publicados")
        val nextDateIndex = dateIndexes.firstOrNull { it > firstDateIndex } ?: lines.size
        val block = lines.subList(firstDateIndex, nextDateIndex)
        val date = datePattern.find(block.joinToString(" "))?.value
            ?: throw IllegalStateException("Fecha no disponible")
        val time = timePattern.find(block.take(8).joinToString(" "))?.value ?: "Hora por confirmar"
        val opponent = teamNames.firstOrNull { name ->
            block.any { line -> containsName(line, name) } &&
                page.aliases.none { alias -> sameTeam(name, alias) }
        } ?: "Rival por confirmar"
        val competition = findCompetition(block)
        val channels = findChannels(block)
        return "${page.displayName} - $opponent ($competition) $date $time - CANALES: $channels"
    }

    private fun htmlToLines(html: String): List<String> {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html).toString()
        }
        return decoded
            .replace('\u00A0', ' ')
            .split('\n')
            .map { it.replace(Regex("\\s+"), " ").trim().trim('|') }
            .filter { it.isNotBlank() }
    }

    private fun extractTeamNames(html: String): List<String> {
        return teamAnchorPattern.findAll(html).mapNotNull { match ->
            val content = match.groupValues[1]
            val text = htmlToLines(content).joinToString(" ")
                .removePrefix("Image: ")
                .trim()
            val alt = Regex("""alt\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
                .find(content)?.groupValues?.get(1).orEmpty()
            text.ifBlank { alt.removePrefix("Image: ").trim() }
        }.filter { it.length > 2 }.distinct().toList()
    }

    private fun containsName(line: String, name: String): Boolean {
        return line.contains(name, ignoreCase = true)
    }

    private fun sameTeam(first: String, second: String): Boolean {
        return normalize(first) == normalize(second) ||
            normalize(first).contains(normalize(second)) ||
            normalize(second).contains(normalize(first))
    }

    private fun normalize(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace("fc ", "")
            .replace("atlético", "at")
            .replace("at. ", "at ")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun findCompetition(block: List<String>): String {
        val text = block.joinToString(" ")
        return when {
            text.contains("La Liga EA Sports", ignoreCase = true) -> "LA LIGA"
            text.contains("Champions League", ignoreCase = true) -> "CHAMPIONS LEAGUE"
            text.contains("Copa del Rey", ignoreCase = true) -> "COPA DEL REY"
            text.contains("Supercopa de España", ignoreCase = true) -> "SUPERCOPA"
            text.contains("Europa League", ignoreCase = true) -> "EUROPA LEAGUE"
            else -> "COMPETICIÓN POR CONFIRMAR"
        }
    }

    private fun findChannels(block: List<String>): String {
        val channels = block.mapNotNull { line ->
            when {
                line.contains("M+ LALIGA HDR", ignoreCase = true) -> "M+ LALIGA HDR"
                line.contains("M+ LALIGA", ignoreCase = true) -> "M+ LALIGA"
                line.contains("M+ Liga de Campeones", ignoreCase = true) -> "M+ Liga de Campeones"
                line.contains("Movistar Plus+", ignoreCase = true) -> "Movistar Plus+"
                line.contains("Orange Fútbol 1", ignoreCase = true) -> "Orange Fútbol 1"
                line.contains("LaLiga TV Bar", ignoreCase = true) -> "LaLiga TV Bar"
                line.contains("DAZN LaLiga", ignoreCase = true) -> "DAZN LaLiga"
                line.contains("DAZN", ignoreCase = true) -> "DAZN"
                line.contains("Canal por confirmar", ignoreCase = true) -> "Canal por confirmar"
                else -> null
            }
        }.distinct()
        return when {
            channels.isEmpty() -> "Canal por confirmar"
            channels.size == 1 -> channels.first()
            channels.size == 2 -> "${channels[0]} y ${channels[1]}"
            else -> channels.dropLast(1).joinToString(", ") + " y " + channels.last()
        }
    }
}
