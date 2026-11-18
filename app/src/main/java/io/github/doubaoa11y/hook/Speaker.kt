package io.github.doubaoa11y.hook

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import io.github.doubaoa11y.ConfigSpec

/** 模块自己的提示可选独立 TTS；失败立刻走原有读屏路径，不打断或隐藏读屏。 */
object Speaker {
    private var ctx: Context? = null
    private var am: AccessibilityManager? = null
    private var pkg = ""
    private var muteUntil = 0L
    private var ttsRequested = false
    fun prepareTts() {
        if (!A11yState.recording || Config.b("speakByReader")) return
        ttsRequested = true
        try { ctx?.contentResolver?.call(io.github.doubaoa11y.SettingsProvider.URI, "warmup", null, null) } catch (_: Throwable) { }
    }
    fun init(c: Context) {
        ctx = c.applicationContext ?: c
        am = c.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        pkg = c.packageName
    }
    fun mute(ms: Long) { muteUntil = SystemClock.uptimeMillis() + ms }
    fun unmute() { muteUntil = 0L }
    fun readerWillSpeak(): Boolean = Config.b("speakByReader")
    fun hushReader() { /* 不压制用户读屏，宿主应用的选区提示由读屏自身决定。 */ }
    fun echo(text: String) = speak(text)
    fun speak(text: String) {
        if (!A11yState.recording || text.isBlank() || SystemClock.uptimeMillis() < muteUntil) return
        if (!Config.b("speakByReader")) {
            ttsRequested = true
            val ok = try {
                val spoken = if (Config.b("ttsPunctuationNames")) Names.punctuationText(text) else text
                val b = Bundle().apply { putString("text", spoken.take(4000)) }
                ctx?.contentResolver?.call(io.github.doubaoa11y.SettingsProvider.URI, "speak", null, b)?.getBoolean("accepted") == true
            } catch (t: Throwable) { L.hookError("Speaker.tts", t); false }
            if (ok) return
        }
        readerSay(text)
    }
    private fun readerSay(text: String) {
        val m = am ?: return
        try {
            if (!m.isEnabled) return
            val ev = event(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            ev.packageName = pkg
            ev.className = "android.inputmethodservice.Keyboard\$Key"
            ev.contentDescription = text
            ev.eventTime = SystemClock.uptimeMillis()
            m.sendAccessibilityEvent(ev)
        } catch (t: Throwable) { L.hookError("Speaker.reader", t) }
    }
    fun stop() {
        if (ttsRequested) {
            try { ctx?.contentResolver?.call(io.github.doubaoa11y.SettingsProvider.URI, "stop", null, null) } catch (_: Throwable) { }
            ttsRequested = false
        }
        val m = am ?: return
        try {
            if (!m.isEnabled) return
            val ev = event(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
            ev.packageName = pkg
            ev.className = "android.inputmethodservice.Keyboard\$Key"
            ev.eventTime = SystemClock.uptimeMillis()
            m.sendAccessibilityEvent(ev)
        } catch (t: Throwable) { L.hookError("Speaker.stop", t) }
    }
    private fun event(type: Int): AccessibilityEvent = if (Build.VERSION.SDK_INT >= 30) AccessibilityEvent(type)
        else @Suppress("DEPRECATION") AccessibilityEvent.obtain(type)
}
