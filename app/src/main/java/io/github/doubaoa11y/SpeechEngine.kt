package io.github.doubaoa11y

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** 在模块自身进程运行 TTS，避免改写豆包进程的 PackageManager 查询。 */
object SpeechEngine {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var config = ""
    private var generation = 0
    private var starting = false
    private var ready = false
    private var failedAt = 0L

    @Synchronized
    fun prepare(ctx: Context, p: SharedPreferences) {
        val key = listOf("ttsEngine", "ttsVoice").joinToString("|") { p.getString(it, "").orEmpty() } +
            listOf("ttsRate", "ttsPitch", "ttsVolume").joinToString("|") { p.getInt(it, 100).toString() } + p.getBoolean("ttsA11yStream", false)
        if (key == config && (ready || starting || android.os.SystemClock.uptimeMillis() - failedAt < 5000)) return
        reset()
        config = key
        starting = true
        val token = ++generation
        val pkg = p.getString("ttsEngine", "").orEmpty()
        main.post {
            synchronized(this) {
                if (token != generation) return@post
                try {
                    val listener = TextToSpeech.OnInitListener { status ->
                        main.post {
                            synchronized(this) {
                                if (token != generation) return@post
                                starting = false
                                ready = status == TextToSpeech.SUCCESS && engine != null
                                if (!ready) failedAt = android.os.SystemClock.uptimeMillis()
                                if (ready) configure(p)
                            }
                        }
                    }
                    engine = if (pkg.isEmpty()) TextToSpeech(ctx.applicationContext, listener)
                        else TextToSpeech(ctx.applicationContext, listener, pkg)
                    engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) { }
                        override fun onDone(id: String?) { }
                        @Deprecated("兼容旧 TTS 引擎")
                        override fun onError(id: String?) {
                            synchronized(this@SpeechEngine) {
                                if (token == generation) { ready = false; failedAt = android.os.SystemClock.uptimeMillis() }
                            }
                            android.util.Log.w("DoubaoA11y", "语音库异步播放失败，后续提示回退读屏")
                        }
                    })
                    main.postDelayed({
                        synchronized(this) {
                            if (token == generation && starting) {
                                reset()
                                failedAt = android.os.SystemClock.uptimeMillis()
                            }
                        }
                    }, 5000)
                } catch (t: Throwable) {
                    starting = false
                    ready = false
                    failedAt = android.os.SystemClock.uptimeMillis()
                    android.util.Log.w("DoubaoA11y", "初始化模块语音库失败", t)
                }
            }
        }
    }

    private fun configure(p: SharedPreferences) {
        try {
            val e = engine ?: return
            e.setSpeechRate((p.getInt("ttsRate", 100) / 100f).coerceIn(0.5f, 2f))
            e.setPitch((p.getInt("ttsPitch", 100) / 100f).coerceIn(0.5f, 2f))
            val name = p.getString("ttsVoice", "").orEmpty()
            val v = e.voices?.firstOrNull { it.name == name }
            if (v != null) e.voice = v else e.language = Locale.SIMPLIFIED_CHINESE
            e.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(if (p.getBoolean("ttsA11yStream", false)) AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY else AudioAttributes.USAGE_MEDIA).build())
        } catch (t: Throwable) {
            ready = false
            failedAt = android.os.SystemClock.uptimeMillis()
            android.util.Log.w("DoubaoA11y", "配置模块语音库失败", t)
        }
    }

    @Synchronized
    fun speak(ctx: Context, p: SharedPreferences, text: String): Boolean {
        prepare(ctx, p)
        if (!ready || text.isBlank()) return false
        return try {
            val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (p.getInt("ttsVolume", 100) / 100f).coerceIn(0f, 1f)) }
            val ok = engine?.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, b, "module-${generation}-${System.nanoTime()}") == TextToSpeech.SUCCESS
            if (!ok) { ready = false; failedAt = android.os.SystemClock.uptimeMillis() }
            ok
        } catch (t: Throwable) {
            ready = false
            failedAt = android.os.SystemClock.uptimeMillis()
            false
        }
    }

    @Synchronized
    fun stop() { try { engine?.stop() } catch (_: Throwable) { } }

    @Synchronized
    private fun reset() {
        generation++
        starting = false
        ready = false
        try { engine?.stop(); engine?.shutdown() } catch (_: Throwable) { }
        engine = null
    }
}
