package com.amika.aipro

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingVoiceLang: String = "fa-IR"

    // هسته صوتی آفلاین فارسی (Sherpa-ONNX + Piper)
    private var offlinePersianTts: OfflineTts? = null
    private var activeAudioTrack: AudioTrack? = null
    private val ttsExecutor = Executors.newSingleThreadExecutor()
    private val searchExecutor = Executors.newCachedThreadPool()
    private val downloadExecutor = Executors.newCachedThreadPool()
    @Volatile
    private var ytdlInitialized = false
    private val ttsSessionId = AtomicInteger(0)

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.parseColor("#04070e")
        window.navigationBarColor = android.graphics.Color.parseColor("#04070e")

        tts = TextToSpeech(this, this)
        ttsExecutor.execute { initOfflinePersianTts() }
        downloadExecutor.execute { initDownloaderEngine() }

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    } catch (_: Exception) {}
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    try { request?.grant(request.resources) } catch (_: Exception) {}
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    fileUploadCallback = null
                    Toast.makeText(this@MainActivity, "خطا در باز کردن انتخابگر فایل", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        webView.addJavascriptInterface(NativeBridge(), "AmikaNative")
        webView.loadUrl("file:///android_asset/index.html")
    }

    // ══════════════════════════════════════════════════════════════════
    // 1. Offline Persian TTS (Sherpa-ONNX + Piper)
    // ══════════════════════════════════════════════════════════════════
    private fun copyAssetFolder(assetPath: String, destDir: File) {
        val list = assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            destDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destDir.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            destDir.mkdirs()
            for (item in list) {
                copyAssetFolder("$assetPath/$item", File(destDir, item))
            }
        }
    }

    @Synchronized
    private fun initOfflinePersianTts(): OfflineTts? {
        if (offlinePersianTts != null) return offlinePersianTts
        return try {
            val targetDir = File(filesDir, "tts-fa")
            val modelFile = File(targetDir, "fa_IR-amir-medium.onnx")
            val tokensFile = File(targetDir, "tokens.txt")
            val dataDir = File(targetDir, "espeak-ng-data")

            if (!modelFile.exists() || !tokensFile.exists() || !dataDir.exists()) {
                copyAssetFolder("tts-fa", targetDir)
            }

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        tokens = tokensFile.absolutePath,
                        dataDir = dataDir.absolutePath
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )
            offlinePersianTts = OfflineTts(assetManager = null, config = config)
            offlinePersianTts
        } catch (_: Exception) {
            null
        }
    }

    private fun stopAudioTrack() {
        try {
            activeAudioTrack?.pause()
            activeAudioTrack?.flush()
            activeAudioTrack?.stop()
        } catch (_: Exception) {}
        try { activeAudioTrack?.release() } catch (_: Exception) {}
        activeAudioTrack = null
    }

    private fun speakPersianOffline(text: String) {
        val mySession = ttsSessionId.incrementAndGet()
        stopAudioTrack()

        ttsExecutor.execute {
            try {
                val engine = initOfflinePersianTts()
                if (engine == null) {
                    runOnUiThread {
                        webView.evaluateJavascript("window.__onNativeTtsError && window.__onNativeTtsError()", null)
                    }
                    return@execute
                }

                val sentences = text
                    .split(Regex("(?<=[.!؟?!\\n؛])\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val sampleRate = engine.sampleRate()
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                activeAudioTrack = track
                track.play()

                for (sentence in sentences) {
                    if (ttsSessionId.get() != mySession) break
                    val audio = engine.generate(text = sentence, sid = 0, speed = 1.0f)
                    if (ttsSessionId.get() != mySession) break

                    val samples = audio.samples
                    val pcm = ShortArray(samples.size) { i ->
                        (samples[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                    }
                    var offset = 0
                    val chunkSize = 2048
                    while (offset < pcm.size && ttsSessionId.get() == mySession) {
                        val len = minOf(chunkSize, pcm.size - offset)
                        track.write(pcm, offset, len)
                        offset += len
                    }
                }

                stopAudioTrack()
                if (ttsSessionId.get() == mySession) {
                    runOnUiThread {
                        webView.evaluateJavascript("window.__onNativeTtsDone && window.__onNativeTtsDone()", null)
                    }
                }
            } catch (_: Exception) {
                stopAudioTrack()
                if (ttsSessionId.get() == mySession) {
                    runOnUiThread {
                        webView.evaluateJavascript("window.__onNativeTtsError && window.__onNativeTtsError()", null)
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. Direct Free Native Web Search (DuckDuckGo + Google News RSS + Wikipedia)
    // ══════════════════════════════════════════════════════════════════
    private fun httpGetString(urlStr: String, timeoutMs: Int = 7000): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7")
        }
        return try {
            if (conn.responseCode !in 200..299) ""
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun cleanHtmlText(raw: String): String {
        return raw
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun performDirectWebSearch(query: String): JSONArray {
        val results = JSONArray()
        val seenUrls = HashSet<String>()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val isPersian = Regex("[\\u0600-\\u06FF]").containsMatchIn(query)

        fun addSource(title: String, url: String, snippet: String, date: String = "") {
            val cleanUrl = url.trim()
            if (cleanUrl.isBlank() || !seenUrls.add(cleanUrl)) return
            val obj = JSONObject()
            obj.put("title", cleanHtmlText(title).take(140))
            obj.put("url", cleanUrl)
            obj.put("snippet", cleanHtmlText(snippet).take(650))
            obj.put("date", date.trim())
            results.put(obj)
        }

        // Source A: Wikipedia Summary (instant factual knowledge in fa / en)
        try {
            val wikiLang = if (isPersian) "fa" else "en"
            val wikiSearchUrl = "https://$wikiLang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srlimit=2&format=json"
            val wikiJson = httpGetString(wikiSearchUrl, 5000)
            if (wikiJson.isNotBlank()) {
                val searchArr = JSONObject(wikiJson).optJSONObject("query")?.optJSONArray("search")
                if (searchArr != null && searchArr.length() > 0) {
                    val top = searchArr.getJSONObject(0)
                    val title = top.optString("title", "")
                    val snippet = top.optString("snippet", "")
                    if (title.isNotBlank()) {
                        val pageUrl = "https://$wikiLang.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
                        addSource("ویکی‌پدیا: $title", pageUrl, snippet, "")
                    }
                }
            }
        } catch (_: Exception) {}

        // Source B: DuckDuckGo HTML Search (general web results, zero API key)
        try {
            val ddgHtml = httpGetString("https://html.duckduckgo.com/html/?q=$encoded", 7500)
            if (ddgHtml.isNotBlank()) {
                val linkRegex = Regex(
                    "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
                    RegexOption.IGNORE_CASE
                )
                val snipRegex = Regex(
                    "<a[^>]*class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>|<div[^>]*class=\"result__snippet\"[^>]*>([\\s\\S]*?)</div>",
                    RegexOption.IGNORE_CASE
                )
                val links = linkRegex.findAll(ddgHtml).toList()
                val snips = snipRegex.findAll(ddgHtml).toList()
                val count = minOf(links.size, 4)
                var fetchedPages = 0
                for (i in 0 until count) {
                    var href = links[i].groupValues[1]
                    val title = links[i].groupValues[2]
                    if (href.contains("uddg=")) {
                        val extracted = href.substringAfter("uddg=").substringBefore("&")
                        href = try { URLDecoder.decode(extracted, "UTF-8") } catch (_: Exception) { href }
                    }
                    if (href.startsWith("//")) href = "https:$href"
                    var snip = if (i < snips.size) {
                        snips[i].groupValues[1].ifBlank { snips[i].groupValues[2] }
                    } else ""
                    if (href.startsWith("http")) {
                        // استخراج متن واقعی از ۲ صفحه اول برای پاسخ‌های دقیق‌تر
                        if (fetchedPages < 2 && !href.endsWith(".pdf", ignoreCase = true)) {
                            try {
                                val pageHtml = httpGetString(href, 4500)
                                if (pageHtml.isNotBlank()) {
                                    val pMatches = Regex("<p[^>]*>([\s\S]*?)</p>", RegexOption.IGNORE_CASE)
                                        .findAll(pageHtml)
                                        .map { cleanHtmlText(it.groupValues[1]) }
                                        .filter { it.length > 55 }
                                        .take(4)
                                        .joinToString(" ")
                                    if (pMatches.length > 90) {
                                        snip = (cleanHtmlText(snip) + " | متن صفحه: " + pMatches).take(650)
                                        fetchedPages++
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        addSource(title, href, snip, "")
                    }
                }
            }
        } catch (_: Exception) {}

        // Source C: Google News RSS (live breaking Persian & English news)
        try {
            val newsUrl = if (isPersian) {
                "https://news.google.com/rss/search?q=$encoded&hl=fa&gl=IR&ceid=IR:fa"
            } else {
                "https://news.google.com/rss/search?q=$encoded&hl=en-US&gl=US&ceid=US:en"
            }
            val rssXml = httpGetString(newsUrl, 6500)
            if (rssXml.isNotBlank()) {
                val itemRegex = Regex("<item>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
                val items = itemRegex.findAll(rssXml).take(3).toList()
                for (it in items) {
                    val block = it.groupValues[1]
                    val title = Regex("<title>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1) ?: ""
                    val link = Regex("<link>([\\s\\S]*?)</link>", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1) ?: ""
                    val desc = Regex("<description>([\\s\\S]*?)</description>", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1) ?: ""
                    val pubDate = Regex("<pubDate>([\\s\\S]*?)</pubDate>", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1) ?: ""
                    val cleanTitle = title.replace("<![CDATA[", "").replace("]]>", "")
                    val cleanDesc = desc.replace("<![CDATA[", "").replace("]]>", "")
                    if (link.startsWith("http")) {
                        addSource(cleanTitle, link, cleanDesc.ifBlank { cleanTitle }, pubDate.take(16))
                    }
                }
            }
        } catch (_: Exception) {}

        return results
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. Organized Storage in Download/AmikaAI/{Documents, Code, Images}
    // ══════════════════════════════════════════════════════════════════
    private fun resolveAmikaSubfolder(filename: String, mimeType: String): String {
        val lower = filename.lowercase(Locale.US)
        return when {
            mimeType.startsWith("image/") || lower.endsWith(".png") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif") -> "Images"
            lower.endsWith(".py") || lower.endsWith(".js") || lower.endsWith(".ts") ||
                lower.endsWith(".html") || lower.endsWith(".css") || lower.endsWith(".kt") ||
                lower.endsWith(".java") || lower.endsWith(".cpp") || lower.endsWith(".c") ||
                lower.endsWith(".cs") || lower.endsWith(".php") || lower.endsWith(".sql") ||
                lower.endsWith(".sh") || lower.endsWith(".xml") || lower.endsWith(".yml") -> "Code"
            else -> "Documents"
        }
    }

    private fun saveBytesToAmikaFolder(filename: String, bytes: ByteArray, mimeType: String) {
        val safeName = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "amika_file" }
        val subFolder = resolveAmikaSubfolder(safeName, mimeType)
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/AmikaAI/$subFolder"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType.substringBefore(";").ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("ایجاد فایل در حافظه ناموفق بود")
            contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
        } else {
            val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(baseDir, "AmikaAI/$subFolder")
            if (!targetDir.exists()) targetDir.mkdirs()
            File(targetDir, safeName).writeBytes(bytes)
        }

        runOnUiThread {
            Toast.makeText(
                this@MainActivity,
                "ذخیره شد در Downloads/AmikaAI/$subFolder/$safeName",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    // ══════════════════════════════════════════════════════════════════
    // 4. Universal Downloader Engine (yt-dlp + FFmpeg + aria2c + DownloadManager)
    // ══════════════════════════════════════════════════════════════════
    @Synchronized
    private fun initDownloaderEngine(): Boolean {
        if (ytdlInitialized) return true
        return try {
            YoutubeDL.getInstance().init(applicationContext)
            try { FFmpeg.getInstance().init(applicationContext) } catch (_: Exception) {}
            try { Aria2c.getInstance().init(applicationContext) } catch (_: Exception) {}
            ytdlInitialized = true
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun emitDownloadEvent(taskId: String, status: String, progress: Int, message: String) {
        val sId = JSONObject.quote(taskId)
        val sSt = JSONObject.quote(status)
        val sMsg = JSONObject.quote(message)
        runOnUiThread {
            webView.evaluateJavascript(
                "window.__onNativeDownloadEvent && window.__onNativeDownloadEvent($sId, $sSt, $progress, $sMsg)",
                null
            )
        }
    }

    private fun saveFileStreamToAmikaFolder(sourceFile: File, subFolder: String): String {
        val safeName = sourceFile.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "amika_download" }
        val ext = safeName.substringAfterLast('.', "").lowercase(Locale.US)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/AmikaAI/$subFolder"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("ایجاد فایل در حافظه دستگاه ناموفق بود")
            contentResolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out, 65536) }
            }
        } else {
            val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(baseDir, "AmikaAI/$subFolder")
            if (!targetDir.exists()) targetDir.mkdirs()
            val destFile = File(targetDir, safeName)
            sourceFile.inputStream().use { input ->
                destFile.outputStream().use { out -> input.copyTo(out, 65536) }
            }
        }
        return "Downloads/AmikaAI/$subFolder/$safeName"
    }

    private fun isDirectFileUrl(url: String): Boolean {
        val clean = url.substringBefore("?").substringBefore("#").lowercase(Locale.US)
        val directExts = listOf(
            ".apk", ".zip", ".rar", ".7z", ".tar", ".gz", ".pdf", ".doc", ".docx",
            ".xls", ".xlsx", ".ppt", ".pptx", ".mp3", ".wav", ".flac", ".ogg", ".m4a",
            ".mp4", ".mkv", ".avi", ".mov", ".webm", ".jpg", ".jpeg", ".png", ".webp",
            ".gif", ".exe", ".iso", ".txt", ".json", ".csv", ".epub"
        )
        return directExts.any { clean.endsWith(it) }
    }

    private fun startAndroidDownloadManager(taskId: String, url: String) {
        try {
            val fileName = URLUtil.guessFileName(url, null, null)
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .ifBlank { "amika_file_${System.currentTimeMillis()}" }
            val subFolder = resolveAmikaSubfolder(fileName, "")
            val subPath = "AmikaAI/$subFolder/$fileName"

            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("در حال دانلود توسط Amika AI Pro...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, subPath)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            emitDownloadEvent(
                taskId,
                "done",
                100,
                "دانلود مستقیم آغاز شد (نوار پیشرفت در اعلان گوشی) ← Downloads/$subPath"
            )
            runOnUiThread {
                Toast.makeText(this@MainActivity, "در حال دانلود در Downloads/$subPath", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            emitDownloadEvent(taskId, "error", 0, "خطای دانلود مستقیم: ${e.message}")
        }
    }

    private fun runUniversalDownload(taskId: String, rawUrl: String, mode: String) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            emitDownloadEvent(taskId, "error", 0, "لینک معتبر نیست (باید با http یا https شروع شود).")
            return
        }

        if (mode == "direct" || (mode != "audio" && isDirectFileUrl(url))) {
            emitDownloadEvent(taskId, "progress", 15, "تشخیص فایل مستقیم · ارسال به DownloadManager اندروید...")
            startAndroidDownloadManager(taskId, url)
            return
        }

        emitDownloadEvent(taskId, "progress", 5, "در حال آماده‌سازی هسته yt-dlp + FFmpeg + aria2c...")
        if (!initDownloaderEngine()) {
            emitDownloadEvent(taskId, "progress", 20, "تلاش با دانلودر بومی اندروید...")
            startAndroidDownloadManager(taskId, url)
            return
        }

        val tempDir = File(cacheDir, "amika_dl_$taskId")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        try {
            val request = YoutubeDLRequest(url)
            request.addOption("-o", File(tempDir, "%(title).90s.%(ext)s").absolutePath)
            request.addOption("--no-mtime")
            request.addOption("--no-playlist")
            request.addOption("--restrict-filenames")

            if (mode == "audio") {
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
            } else {
                request.addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                request.addOption("--merge-output-format", "mp4")
            }

            // استفاده از aria2c برای افزایش سرعت دانلود
            request.addOption("--downloader", "libaria2c.so")
            request.addOption("--external-downloader-args", "aria2c:\"-x 8 -k 1M --summary-interval=1\"")

            emitDownloadEvent(taskId, "progress", 12, "در حال دریافت اطلاعات لینک و شروع دانلود...")

            YoutubeDL.getInstance().execute(request, taskId) { progress, etaInSeconds, line ->
                val pct = progress.toInt().coerceIn(0, 99)
                val info = if (etaInSeconds > 0) "در حال دانلود ($pct%) · زمان باقی‌مانده: ${etaInSeconds}ث"
                else (line?.take(90) ?: "در حال پردازش ($pct%)...")
                emitDownloadEvent(taskId, "progress", pct, info)
            }

            val downloadedFiles = tempDir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
            if (downloadedFiles.isEmpty()) {
                throw Exception("فایلی از این لینک دریافت نشد.")
            }

            val savedPaths = mutableListOf<String>()
            val targetSub = if (mode == "audio") "Audio" else "Videos"
            for (f in downloadedFiles) {
                val path = saveFileStreamToAmikaFolder(f, targetSub)
                savedPaths.add(path)
            }
            tempDir.deleteRecursively()

            val msg = "دانلود با موفقیت تکمیل شد ✓\nمسیر ذخیره: ${savedPaths.joinToString(" , ")}"
            emitDownloadEvent(taskId, "done", 100, msg)
            runOnUiThread {
                Toast.makeText(this@MainActivity, "ذخیره شد در ${savedPaths.first()}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            val errMsg = e.message ?: "خطای ناشناخته"
            // اگر yt-dlp لینک را نشناخت، به‌صورت خودکار با DownloadManager امتحان کن
            if (errMsg.contains("Unsupported URL", ignoreCase = true)) {
                emitDownloadEvent(taskId, "progress", 30, "ارسال خودکار به دانلودر مستقیم اندروید...")
                startAndroidDownloadManager(taskId, url)
            } else {
                emitDownloadEvent(taskId, "error", 0, "خطا در دانلود: ${errMsg.take(260)}")
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        webView.evaluateJavascript("window.__onNativeTtsDone && window.__onNativeTtsDone()", null)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        webView.evaluateJavascript("window.__onNativeTtsError && window.__onNativeTtsError()", null)
                    }
                }
            })
        }
    }

    private fun releaseMediaPlayer() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun startUniversalDownload(url: String, mode: String, taskId: String) {
            downloadExecutor.execute {
                runUniversalDownload(taskId, url, mode)
            }
        }

        @JavascriptInterface
        fun cancelUniversalDownload(taskId: String) {
            downloadExecutor.execute {
                try {
                    YoutubeDL.getInstance().destroyProcessById(taskId)
                    emitDownloadEvent(taskId, "error", 0, "دانلود توسط کاربر لغو شد.")
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun updateDownloaderCore() {
            downloadExecutor.execute {
                try {
                    emitDownloadEvent("core_update", "progress", 35, "در حال بررسی و آپدیت هسته yt-dlp...")
                    initDownloaderEngine()
                    val status = YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                    emitDownloadEvent("core_update", "done", 100, "وضعیت آپدیت هسته yt-dlp: $status")
                } catch (e: Exception) {
                    emitDownloadEvent("core_update", "error", 0, "خطا در آپدیت هسته: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun nativeWebSearch(query: String, callbackId: String) {
            searchExecutor.execute {
                val payload = try {
                    performDirectWebSearch(query).toString()
                } catch (_: Exception) {
                    "[]"
                }
                val safeId = JSONObject.quote(callbackId)
                val safePayload = JSONObject.quote(payload)
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.__onNativeSearchResult && window.__onNativeSearchResult($safeId, $safePayload)",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun speak(text: String, langCode: String) {
            runOnUiThread {
                releaseMediaPlayer()
                try { tts?.stop() } catch (_: Exception) {}

                if (langCode.startsWith("fa")) {
                    speakPersianOffline(text)
                    return@runOnUiThread
                }

                ttsSessionId.incrementAndGet()
                stopAudioTrack()

                if (!isTtsReady) {
                    webView.evaluateJavascript("window.__onNativeTtsError && window.__onNativeTtsError()", null)
                    return@runOnUiThread
                }
                val locale = when {
                    langCode.startsWith("ar") -> Locale("ar")
                    langCode.startsWith("de") -> Locale.GERMAN
                    langCode.startsWith("fr") -> Locale.FRENCH
                    else -> Locale.US
                }
                val res = tts?.setLanguage(locale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    webView.evaluateJavascript("window.__onNativeTtsError && window.__onNativeTtsError()", null)
                    return@runOnUiThread
                }
                tts?.setSpeechRate(0.98f)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "amika_tts")
            }
        }

        @JavascriptInterface
        fun playUrlAudio(audioUrl: String) {
            Thread {
                try {
                    val conn = (URL(audioUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                    }
                    if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                    val tmpFile = File(cacheDir, "tts_chunk_${System.currentTimeMillis()}.mp3")
                    conn.inputStream.use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    conn.disconnect()

                    runOnUiThread {
                        try {
                            releaseMediaPlayer()
                            val mp = MediaPlayer().apply {
                                setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .build()
                                )
                                setDataSource(tmpFile.absolutePath)
                                setOnCompletionListener {
                                    tmpFile.delete()
                                    releaseMediaPlayer()
                                    webView.evaluateJavascript("window.__onNativeUrlAudioDone && window.__onNativeUrlAudioDone()", null)
                                }
                                setOnErrorListener { _, _, _ ->
                                    tmpFile.delete()
                                    releaseMediaPlayer()
                                    webView.evaluateJavascript("window.__onNativeUrlAudioError && window.__onNativeUrlAudioError()", null)
                                    true
                                }
                                prepare()
                                start()
                            }
                            mediaPlayer = mp
                        } catch (_: Exception) {
                            tmpFile.delete()
                            webView.evaluateJavascript("window.__onNativeUrlAudioError && window.__onNativeUrlAudioError()", null)
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        webView.evaluateJavascript("window.__onNativeUrlAudioError && window.__onNativeUrlAudioError()", null)
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun stopSpeak() {
            ttsSessionId.incrementAndGet()
            runOnUiThread {
                try { tts?.stop() } catch (_: Exception) {}
                stopAudioTrack()
                releaseMediaPlayer()
            }
        }

        @JavascriptInterface
        fun startListening(langCode: String) {
            runOnUiThread {
                pendingVoiceLang = if (langCode.isNotBlank()) langCode else "fa-IR"
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        202
                    )
                } else {
                    beginSpeechRecognition(pendingVoiceLang)
                }
            }
        }

        @JavascriptInterface
        fun stopListening() {
            runOnUiThread {
                try {
                    speechRecognizer?.stopListening()
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun saveTextFile(filename: String, content: String, mimeType: String) {
            searchExecutor.execute {
                try {
                    saveBytesToAmikaFolder(filename, content.toByteArray(Charsets.UTF_8), mimeType)
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "خطا در ذخیره فایل: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun saveImage(filename: String, dataOrUrl: String) {
            searchExecutor.execute {
                try {
                    val bytes: ByteArray
                    val mime: String
                    if (dataOrUrl.startsWith("data:", ignoreCase = true)) {
                        val header = dataOrUrl.substringBefore(",")
                        val b64 = dataOrUrl.substringAfter(",")
                        mime = header.substringAfter("data:").substringBefore(";").ifBlank { "image/png" }
                        bytes = Base64.decode(b64, Base64.DEFAULT)
                    } else {
                        val conn = (URL(dataOrUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 15000
                            readTimeout = 20000
                            instanceFollowRedirects = true
                        }
                        if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
                        mime = conn.contentType?.substringBefore(";") ?: "image/png"
                        bytes = conn.inputStream.use { it.readBytes() }
                        conn.disconnect()
                    }
                    val ext = when {
                        mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
                        mime.contains("webp") -> ".webp"
                        else -> ".png"
                    }
                    val finalName = if (filename.contains(".")) filename else "$filename$ext"
                    saveBytesToAmikaFolder(finalName, bytes, mime)
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "خطا در ذخیره تصویر: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun beginSpeechRecognition(langCode: String) {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    webView.evaluateJavascript("window.__onNativeSttStart && window.__onNativeSttStart()", null)
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    webView.evaluateJavascript("window.__onNativeSttError && window.__onNativeSttError($error)", null)
                }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull() ?: ""
                    val quoted = JSONObject.quote(text)
                    webView.evaluateJavascript("window.__onNativeSttResult && window.__onNativeSttResult($quoted)", null)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            webView.evaluateJavascript("window.__onNativeSttError && window.__onNativeSttError(-1)", null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 202 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            beginSpeechRecognition(pendingVoiceLang)
        } else if (requestCode == 202) {
            Toast.makeText(this, "اجازه دسترسی به میکروفون داده نشد", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "(function(){ return window.__handleAndroidBack ? window.__handleAndroidBack() : false; })()"
        ) { result ->
            if (result != "true") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        ttsSessionId.incrementAndGet()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        stopAudioTrack()
        try { offlinePersianTts?.release() } catch (_: Exception) {}
        releaseMediaPlayer()
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        ttsExecutor.shutdownNow()
        searchExecutor.shutdownNow()
        downloadExecutor.shutdownNow()
        super.onDestroy()
    }
}
