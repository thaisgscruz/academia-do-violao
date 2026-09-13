package com.mapatonal.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Html
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : Activity() {

    private lateinit var webView: WebView

    @Volatile
    private var tunerRunning = false

    private var pendingTunerStart = false
    private var microphonePermissionRequestInProgress = false
    private var audioRecord: AudioRecord? = null
    private var tunerThread: Thread? = null
    private val latestSearchRequest = AtomicInteger(0)
    private val cifraCache = ConcurrentHashMap<String, String>()
    private val lyricsCache = ConcurrentHashMap<String, String>()
    private var lyricsOverlay: LinearLayout? = null
    private var embeddedLyricsWebView: WebView? = null

    private var systemTopInset = 0
    private var systemBottomInset = 0
    private var pageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)
        applySystemInsets()
        configureWebView()
        setSystemBars(light = false)

        webView.addJavascriptInterface(AndroidBridge(), "Android")
        webView.loadUrl("file:///android_asset/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoaded = true
                pushInsetsToWebContent()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                if (url.startsWith("file:///android_asset/")) return false
                openAllowedExternalUrl(url)
                return true
            }
        }
    }

    private fun applySystemInsets() {
        webView.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safeInsets = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                systemTopInset = safeInsets.top
                systemBottomInset = safeInsets.bottom
            } else {
                @Suppress("DEPRECATION")
                systemTopInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                systemBottomInset = insets.systemWindowInsetBottom
            }

            pushInsetsToWebContent()
            insets
        }
        webView.requestApplyInsets()
    }

    private fun pushInsetsToWebContent() {
        if (!pageLoaded || !::webView.isInitialized) return

        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val topCssPx = (systemTopInset.coerceAtLeast(0) / density).roundToInt()
        val bottomCssPx = (systemBottomInset.coerceAtLeast(0) / density).roundToInt()

        webView.post {
            webView.evaluateJavascript(
                """document.documentElement.style.setProperty('--android-top-inset', '${topCssPx}px');
                   document.documentElement.style.setProperty('--android-bottom-inset', '${bottomCssPx}px');""".trimIndent(),
                null
            )
        }
    }

    private fun setSystemBars(light: Boolean) {
        val background = Color.parseColor(if (light) "#F5F7F9" else "#0D1117")
        window.statusBarColor = background
        window.navigationBarColor = background

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                val appearance = if (light) {
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                } else {
                    0
                }
                controller.setSystemBarsAppearance(
                    appearance,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            flags = if (light) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    } else {
                        0
                    }
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                    } else {
                        -1
                    }
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun setLightTheme(light: Boolean) {
            runOnUiThread { setSystemBars(light) }
        }

        @JavascriptInterface
        fun prepareTuner() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    sendJs("window.onTunerPermission", "granted")
                    startNativeTuner()
                    return@runOnUiThread
                }

                val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                if (preferences.getBoolean(KEY_MICROPHONE_PERMISSION_REQUESTED, false)) {
                    sendJs("window.onTunerPermission", "denied")
                    return@runOnUiThread
                }

                preferences.edit()
                    .putBoolean(KEY_MICROPHONE_PERMISSION_REQUESTED, true)
                    .apply()
                requestMicrophonePermission(startAfterGrant = true)
            }
        }

        @JavascriptInterface
        fun startTuner() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    startNativeTuner()
                } else {
                    requestMicrophonePermission(startAfterGrant = true)
                }
            }
        }

        @JavascriptInterface
        fun stopTuner() {
            stopNativeTuner()
        }

        @JavascriptInterface
        fun startVoiceSearch() {
            runOnUiThread {
                try {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga o nome da música ou artista")
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_VOICE_SEARCH)
                } catch (_: Exception) {
                    sendJs(
                        "window.onVoiceSearchError",
                        "Reconhecimento de voz indisponível neste aparelho."
                    )
                }
            }
        }

        @JavascriptInterface
        fun searchSongs(query: String) {
            val normalizedQuery = query.trim()
            if (normalizedQuery.isBlank()) return
            val requestNumber = latestSearchRequest.incrementAndGet()

            thread(name = "song-search") {
                try {
                    val encoded = URLEncoder.encode(normalizedQuery, Charsets.UTF_8.name())
                    val response = httpGet(
                        "https://api.deezer.com/search?q=$encoded&limit=12",
                        userAgent = APP_USER_AGENT
                    )

                    val raw = JSONObject(response.body).optJSONArray("data") ?: JSONArray()
                    val clean = JSONArray()
                    val seenIds = mutableSetOf<Long>()

                    for (i in 0 until raw.length()) {
                        val item = raw.getJSONObject(i)
                        val artist = item.optJSONObject("artist")
                        val album = item.optJSONObject("album")
                        val trackId = item.optLong("id")
                        if (trackId <= 0 || !seenIds.add(trackId)) continue

                        clean.put(
                            JSONObject().apply {
                                put("id", trackId)
                                put("title", item.optString("title"))
                                put("artist", artist?.optString("name") ?: "")
                                put("album", album?.optString("title") ?: "")
                            }
                        )
                    }

                    val querySlug = slugify(normalizedQuery)
                    val alreadyMatchedArtist = (0 until clean.length()).any { index ->
                        slugify(clean.getJSONObject(index).optString("artist")).contains(querySlug)
                    }

                    if (!alreadyMatchedArtist && querySlug.length >= 4 &&
                        requestNumber == latestSearchRequest.get()
                    ) {
                        try {
                            val artistResponse = httpGet(
                                "https://api.deezer.com/search/artist?q=$encoded&limit=2",
                                userAgent = APP_USER_AGENT
                            )
                            val artists = JSONObject(artistResponse.body)
                                .optJSONArray("data") ?: JSONArray()
                            val matchingArtist = (0 until artists.length())
                                .map { artists.getJSONObject(it) }
                                .firstOrNull { item ->
                                    val candidate = slugify(item.optString("name"))
                                    candidate.startsWith(querySlug) || candidate == querySlug
                                }

                            if (matchingArtist != null && requestNumber == latestSearchRequest.get()) {
                                val artistId = matchingArtist.optLong("id")
                                if (artistId > 0) {
                                    val topResponse = httpGet(
                                        "https://api.deezer.com/artist/$artistId/top?limit=8",
                                        userAgent = APP_USER_AGENT
                                    )
                                    val tracks = JSONObject(topResponse.body)
                                        .optJSONArray("data") ?: JSONArray()
                                    val preferred = JSONArray()

                                    for (index in 0 until tracks.length()) {
                                        val track = tracks.getJSONObject(index)
                                        val trackId = track.optLong("id")
                                        if (trackId <= 0 || !seenIds.add(trackId)) continue
                                        preferred.put(JSONObject()
                                            .put("id", trackId)
                                            .put("title", track.optString("title"))
                                            .put("artist", track.optJSONObject("artist")
                                                ?.optString("name") ?: matchingArtist.optString("name"))
                                            .put("album", track.optJSONObject("album")
                                                ?.optString("title") ?: ""))
                                    }
                                    for (index in 0 until clean.length()) {
                                        preferred.put(clean.getJSONObject(index))
                                    }
                                    while (clean.length() > 0) clean.remove(clean.length() - 1)
                                    for (index in 0 until minOf(preferred.length(), 12)) {
                                        clean.put(preferred.getJSONObject(index))
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Preserva a busca principal quando a consulta de artista não responde.
                        }
                    }

                    if (requestNumber != latestSearchRequest.get()) return@thread

                    sendJs(
                        "window.onSongSearchResults",
                        JSONObject()
                            .put("query", normalizedQuery)
                            .put("results", clean)
                            .toString()
                    )
                } catch (e: Exception) {
                    if (requestNumber != latestSearchRequest.get()) return@thread

                    sendJs(
                        "window.onSongSearchError",
                        JSONObject()
                            .put("query", normalizedQuery)
                            .put("message", e.message ?: "Falha de conexão")
                            .toString()
                    )
                }
            }
        }

        @JavascriptInterface
        fun loadTrack(trackId: String) {
            val safeId = trackId.filter { it.isDigit() }
            if (safeId.isBlank()) return

            thread(name = "track-detail") {
                try {
                    val response = httpGet(
                        "https://api.deezer.com/track/$safeId",
                        userAgent = APP_USER_AGENT
                    )
                    val raw = JSONObject(response.body)
                    val artist = raw.optJSONObject("artist")
                    val album = raw.optJSONObject("album")

                    val detail = JSONObject().apply {
                        put("id", raw.optLong("id"))
                        put("title", raw.optString("title"))
                        put("artist", artist?.optString("name") ?: "")
                        put("album", album?.optString("title") ?: "")
                        put("bpm", raw.optDouble("bpm", 0.0))
                        put("duration", raw.optInt("duration", 0))
                    }

                    sendJs("window.onTrackDetail", detail.toString())
                } catch (e: Exception) {
                    sendJs("window.onTrackDetailError", e.message ?: "Falha de conexão")
                }
            }
        }

        @JavascriptInterface
        fun lookupSongKey(title: String, artist: String) {
            val safeTitle = title.trim()
            val safeArtist = artist.trim()

            if (safeTitle.isBlank() || safeArtist.isBlank()) {
                sendJs(
                    "window.onKeyLookupResult",
                    JSONObject()
                        .put("found", false)
                        .put("message", "Título ou artista ausente.")
                        .toString()
                )
                return
            }

            thread(name = "cifra-key") {
                val result = findKeyOnCifraClub(safeTitle, safeArtist)
                    .put("title", safeTitle)
                    .put("artist", safeArtist)
                sendJs("window.onKeyLookupResult", result.toString())
            }
        }

        @JavascriptInterface
        fun lookupLyrics(title: String, artist: String) {
            val cleanTitle = title.trim()
            val cleanArtist = artist.trim()
            if (cleanTitle.isBlank() || cleanArtist.isBlank()) return

            thread(name = "song-lyrics") {
                val cacheKey = "${slugify(cleanArtist)}|${slugify(cleanTitle)}"
                val directUrl = "https://www.letras.mus.br/${slugify(cleanArtist)}/${slugify(cleanTitle)}/"
                val query = URLEncoder.encode(
                    "site:letras.mus.br \"$cleanTitle\" \"$cleanArtist\"",
                    Charsets.UTF_8.name()
                )
                val result = JSONObject()
                    .put("title", cleanTitle)
                    .put("artist", cleanArtist)
                    .put("googleUrl", "https://www.google.com/search?q=$query")

                val cachedUrl = lyricsCache[cacheKey]
                if (cachedUrl != null) {
                    result.put("found", true).put("url", cachedUrl)
                } else {
                    var foundUrl: String? = null
                    for ((artistSlug, songSlug) in sourceSlugCandidates(cleanTitle, cleanArtist)) {
                        try {
                            val response = httpGet(
                                "https://www.letras.mus.br/$artistSlug/$songSlug/",
                                CIFRA_USER_AGENT
                            )
                            val finalUri = Uri.parse(response.finalUrl)
                            val finalHost = finalUri.host.orEmpty().lowercase(Locale.ROOT)
                            val validHost = finalHost == "letras.mus.br" || finalHost.endsWith(".letras.mus.br")
                            if (validHost && finalUri.pathSegments.size >= 2) {
                                foundUrl = response.finalUrl
                                break
                            }
                        } catch (_: Exception) {
                            // Tenta uma variação normalizada antes de oferecer a busca manual.
                        }
                    }

                    if (foundUrl != null) {
                        lyricsCache[cacheKey] = foundUrl
                        result.put("found", true).put("url", foundUrl)
                    } else {
                        result.put("found", false).put("url", directUrl)
                    }
                }

                sendJs("window.onLyricsLookupResult", result.toString())
            }
        }

        @JavascriptInterface
        fun openLyricsPage(url: String) {
            runOnUiThread { openEmbeddedLyricsPage(url) }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread { openAllowedExternalUrl(url) }
        }
    }

    private data class HttpResponse(
        val body: String,
        val finalUrl: String,
        val status: Int
    )

    private fun httpGet(url: String, userAgent: String): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 7_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.6")
            setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
        }

        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                throw IllegalStateException("Servidor respondeu HTTP $status")
            }

            HttpResponse(
                body = body,
                finalUrl = connection.url.toString(),
                status = status
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun findKeyOnCifraClub(title: String, artist: String): JSONObject {
        val artistSlug = slugify(artist)
        val songSlug = slugify(title)
        val cacheKey = "$artistSlug|$songSlug"
        val directUrl = "https://www.cifraclub.com.br/$artistSlug/$songSlug/"
        val googleUrl = buildGoogleCifraSearchUrl(title, artist)

        if (artistSlug.isBlank() || songSlug.isBlank()) {
            return JSONObject()
                .put("found", false)
                .put("message", "Não foi possível formar a busca da cifra.")
                .put("googleUrl", googleUrl)
        }

        val cached = cifraCache[cacheKey]
        if (cached != null) return JSONObject(cached).put("googleUrl", googleUrl)

        for ((candidateArtist, candidateSong) in sourceSlugCandidates(title, artist)) {
            try {
                val response = httpGet(
                    "https://www.cifraclub.com.br/$candidateArtist/$candidateSong/",
                    CIFRA_USER_AGENT
                )
                val key = extractCifraKey(response.body)

                if (!key.isNullOrBlank()) {
                    val found = JSONObject()
                        .put("found", true)
                        .put("key", key)
                        .put("source", "Cifra Club")
                        .put("url", response.finalUrl)
                        .put("googleUrl", googleUrl)
                    cifraCache[cacheKey] = found.toString()
                    return found
                }
            } catch (_: Exception) {
                // A próxima combinação remove versões, participações ou sufixos comuns.
            }
        }

        return JSONObject()
            .put("found", false)
            .put(
                "message",
                "A cifra não foi localizada automaticamente. Use a busca no Google para confirmar o tom."
            )
            .put("googleUrl", googleUrl)
            .put("cifraUrl", directUrl)
    }

    private fun buildGoogleCifraSearchUrl(title: String, artist: String): String {
        val query = "site:cifraclub.com.br \"$title\" \"$artist\" cifra"
        return "https://www.google.com/search?q=" +
            URLEncoder.encode(query, Charsets.UTF_8.name())
    }

    private fun slugify(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace("&", " ")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        return normalized
    }

    private fun sourceSlugCandidates(title: String, artist: String): List<Pair<String, String>> {
        val cleanedTitle = title
            .replace(Regex("""(?i)\s*[\[(](?:ao vivo|live|ac[uú]stico|acoustic|remaster(?:ed)?|feat\.?|part\.?|vers[aã]o)[^)\]]*[\])]"""), "")
            .replace(Regex("(?i)\\s*[-–]\\s*(?:ao vivo|live|ac[uú]stico|acoustic|remaster(?:ed)?|vers[aã]o).*$"), "")
            .replace(Regex("(?i)\\s+(?:feat\\.?|ft\\.?|part\\.?|com)\\s+.*$"), "")
            .trim()
        val primaryArtist = artist
            .split(Regex("(?i)\\s*(?:,|;|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+part\\.?\\s+|\\s+&\\s+)"))
            .firstOrNull()
            .orEmpty()
            .trim()

        return listOf(
            slugify(artist) to slugify(title),
            slugify(artist) to slugify(cleanedTitle),
            slugify(primaryArtist) to slugify(cleanedTitle)
        ).filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .distinct()
            .take(3)
    }

    private fun extractCifraKey(html: String): String? {
        val plainText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html).toString()
        }
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")

        val match = Regex(
            """(?i)\bTom\s*:\s*([A-G](?:#|b)?m?)(?![A-Za-z0-9#])"""
        ).find(plainText)

        if (match != null) return match.groupValues.getOrNull(1)

        val metadataPatterns = listOf(
            Regex("""(?i)[\"'](?:tonality|original_key|originalKey|song_key|songKey)[\"']\s*:\s*[\"']([A-G](?:#|b)?m?)[\"']"""),
            Regex("""(?i)data-(?:key|tone|tonality)\s*=\s*[\"']([A-G](?:#|b)?m?)[\"']""")
        )
        for (pattern in metadataPatterns) {
            val found = pattern.find(html)?.groupValues?.getOrNull(1)
            if (!found.isNullOrBlank()) return found
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openEmbeddedLyricsPage(rawUrl: String) {
        val uri = Uri.parse(rawUrl)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (uri.scheme != "https" || !(host == "letras.mus.br" || host.endsWith(".letras.mus.br"))) {
            return
        }

        closeEmbeddedLyricsPage()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            setPadding(0, systemTopInset, 0, systemBottomInset)
        }
        val closeButton = Button(this).apply {
            text = "← Voltar para Academia do Violão"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#171D25"))
            setOnClickListener { closeEmbeddedLyricsPage() }
        }
        val sourceView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val target = Uri.parse(url ?: return true)
                    val targetHost = target.host.orEmpty().lowercase(Locale.ROOT)
                    if (target.scheme == "https" &&
                        (targetHost == "letras.mus.br" || targetHost.endsWith(".letras.mus.br"))) {
                        return false
                    }
                    openAllowedExternalUrl(url)
                    return true
                }
            }
        }
        container.addView(closeButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        container.addView(sourceView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        addContentView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        lyricsOverlay = container
        embeddedLyricsWebView = sourceView
        sourceView.loadUrl(rawUrl)
    }

    private fun closeEmbeddedLyricsPage(): Boolean {
        val overlay = lyricsOverlay ?: return false
        embeddedLyricsWebView?.stopLoading()
        embeddedLyricsWebView?.destroy()
        embeddedLyricsWebView = null
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        lyricsOverlay = null
        return true
    }

    private fun openAllowedExternalUrl(rawUrl: String) {
        try {
            val uri = Uri.parse(rawUrl)
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()

            val allowed = uri.scheme == "https" && (
                host == "www.google.com" ||
                    host == "google.com" ||
                    host == "www.cifraclub.com.br" ||
                    host == "cifraclub.com.br" ||
                    host == "letras.mus.br" ||
                    host.endsWith(".letras.mus.br")
                )

            if (!allowed) return

            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            // Sem navegador compatível: nenhuma ação.
        }
    }

    private fun sendJs(functionName: String, payload: String) {
        runOnUiThread {
            val quoted = JSONObject.quote(payload)
            webView.evaluateJavascript("$functionName($quoted)", null)
        }
    }

    private fun requestMicrophonePermission(startAfterGrant: Boolean) {
        pendingTunerStart = pendingTunerStart || startAfterGrant
        if (microphonePermissionRequestInProgress) return

        microphonePermissionRequestInProgress = true
        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO
        )
    }

    @SuppressLint("MissingPermission")
    private fun startNativeTuner() {
        if (tunerRunning) return

        val sampleRate = 44_100
        val frameSize = 4096
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            sendJs("window.onTunerError", "Configuração de áudio indisponível.")
            return
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, frameSize * 2)
            )
        } catch (_: Exception) {
            sendJs("window.onTunerError", "Não foi possível configurar o microfone.")
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            sendJs("window.onTunerError", "Não foi possível iniciar o microfone.")
            return
        }

        audioRecord = recorder
        tunerRunning = true
        try {
            recorder.startRecording()
        } catch (_: Exception) {
            tunerRunning = false
            audioRecord = null
            recorder.release()
            sendJs("window.onTunerError", "Não foi possível iniciar a captação de áudio.")
            return
        }

        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            tunerRunning = false
            audioRecord = null
            recorder.release()
            sendJs("window.onTunerError", "O microfone não iniciou a gravação.")
            return
        }

        tunerThread = thread(name = "guitar-tuner") {
            val buffer = ShortArray(frameSize)

            while (tunerRunning) {
                val read = try {
                    recorder.read(buffer, 0, buffer.size)
                } catch (_: Exception) {
                    break
                }

                if (read <= 1024) continue

                val frequency = estimateGuitarFrequency(buffer, read, sampleRate)
                if (frequency !in 70.0..400.0) continue

                val midi = (
                    69 + 12 * (ln(frequency / 440.0) / ln(2.0))
                    ).roundToInt()

                val noteNames = arrayOf(
                    "C", "C♯", "D", "D♯", "E", "F",
                    "F♯", "G", "G♯", "A", "A♯", "B"
                )

                val note = noteNames[((midi % 12) + 12) % 12]
                val octave = midi / 12 - 1
                val target = 440.0 * 2.0.pow((midi - 69) / 12.0)
                val cents = 1200.0 * (ln(frequency / target) / ln(2.0))

                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onTunerUpdate(" +
                            String.format(Locale.US, "%.2f", frequency) + "," +
                            String.format(Locale.US, "%.1f", cents) + "," +
                            JSONObject.quote("$note$octave") +
                            ")",
                        null
                    )
                }
            }
        }
    }

    private fun stopNativeTuner() {
        tunerRunning = false

        val recorder = audioRecord
        audioRecord = null

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        try {
            tunerThread?.join(350)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        tunerThread = null
    }

    private fun estimateGuitarFrequency(
        samples: ShortArray,
        size: Int,
        sampleRate: Int
    ): Double {
        var mean = 0.0
        for (i in 0 until size) {
            mean += samples[i].toDouble()
        }
        mean /= size

        val centered = DoubleArray(size)
        var squared = 0.0

        for (i in 0 until size) {
            val value = samples[i] - mean
            centered[i] = value
            squared += value * value
        }

        val rms = sqrt(squared / size)
        if (rms < 120.0) return 0.0

        val minFrequency = 70.0
        val maxFrequency = 400.0
        val minLag = (sampleRate / maxFrequency).toInt()
        val maxLag = minOf((sampleRate / minFrequency).toInt(), size / 2)

        var bestLag = -1
        var bestScore = 0.0
        var previousScore = -1.0
        var previousPreviousScore = -1.0

        for (lag in minLag..maxLag) {
            var cross = 0.0
            var energyA = 0.0
            var energyB = 0.0

            var i = 0
            while (i < size - lag) {
                val a = centered[i]
                val b = centered[i + lag]
                cross += a * b
                energyA += a * a
                energyB += b * b
                i += 2
            }

            val denominator = sqrt(energyA * energyB)
            val score = if (denominator > 0.0) cross / denominator else 0.0

            if (
                lag > minLag &&
                previousScore > previousPreviousScore &&
                previousScore >= score &&
                previousScore > 0.72 &&
                lag > minLag + 1
            ) {
                bestLag = lag - 1
                bestScore = previousScore
                break
            }

            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }

            previousPreviousScore = previousScore
            previousScore = score
        }

        if (bestLag <= 0 || bestScore < 0.55) return 0.0
        return sampleRate.toDouble() / bestLag.toDouble()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_RECORD_AUDIO) {
            microphonePermissionRequestInProgress = false
            val shouldStartTuner = pendingTunerStart
            pendingTunerStart = false

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                sendJs("window.onTunerPermission", "granted")
                if (shouldStartTuner) startNativeTuner()
            } else {
                sendJs("window.onTunerPermission", "denied")
                sendJs(
                    "window.onTunerError",
                    "Permissão do microfone negada."
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VOICE_SEARCH) return

        if (resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.firstOrNull()?.trim().orEmpty()
            if (spokenText.isNotBlank()) {
                sendJs("window.onVoiceSearchResult", spokenText)
                return
            }
        }
        sendJs("window.onVoiceSearchError", "Não foi possível reconhecer a pesquisa.")
    }

    override fun onPause() {
        stopNativeTuner()
        super.onPause()
    }

    override fun onDestroy() {
        stopNativeTuner()
        closeEmbeddedLyricsPage()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Android")
            webView.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (closeEmbeddedLyricsPage()) {
            return
        } else if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val REQUEST_VOICE_SEARCH = 1002
        private const val PREFERENCES_NAME = "academia_do_violao"
        private const val KEY_MICROPHONE_PERMISSION_REQUESTED = "microphone_permission_requested"
        private const val APP_USER_AGENT = "AcademiaDoViolao/1.7.0"
        private const val CIFRA_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
