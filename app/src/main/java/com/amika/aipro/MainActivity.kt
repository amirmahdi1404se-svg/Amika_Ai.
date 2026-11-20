package com.amika.aipro

import android.Manifest
import android.annotation.SuppressLint
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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

        // آماده‌سازی مدل آفلاین فارسی در پس‌زمینه
        ttsExecutor.execute { initOfflinePersianTts() }

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

    private fun copyAssetFolder(assetPath: String, destDir: File) {
        val list = assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            destDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destDir.outputStream().use { output ->
                    input.copyTo(output)
                }
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
        try {
            activeAudioTrack?.release()
        } catch (_: Exception) {}
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

                // تقسیم متن به جمله‌های کوتاه برای پخش سریع و روان
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
        fun speak(text: String, langCode: String) {
            runOnUiThread {
                releaseMediaPlayer()
                try { tts?.stop() } catch (_: Exception) {}

                // برای فارسی مستقیم از موتور آفلاین داخل APK استفاده می‌شود
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
            runOnUiThread {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, filename)
                            put(MediaStore.Downloads.MIME_TYPE, mimeType.substringBefore(";"))
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(content.toByteArray(Charsets.UTF_8))
                            }
                            Toast.makeText(this@MainActivity, "در پوشه Downloads ذخیره شد: $filename", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!dir.exists()) dir.mkdirs()
                        File(dir, filename).writeText(content, Charsets.UTF_8)
                        Toast.makeText(this@MainActivity, "در پوشه Downloads ذخیره شد: $filename", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "خطا در ذخیره فایل: ${e.message}", Toast.LENGTH_SHORT).show()
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
        super.onDestroy()
    }
}
