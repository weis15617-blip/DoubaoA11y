package io.github.doubaoa11y.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.LinearLayout
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.SpeechEngine

/** TTS 在模块自身进程运行，不需要在豆包进程里绕过包可见性。 */
object VoicePage {
    fun fill(a: SettingsActivity, root: LinearLayout) {
        a.addKeys(root, "speakByReader")
        val first = root.childCount
        val selected = a.prefs.getString("ttsEngine", "").orEmpty()
        root.addView(a.button("语音库：${selected.ifEmpty { "系统默认" }}") { chooseEngine(a) })
        root.addView(a.button("发音人：${a.prefs.getString("ttsVoice", "").orEmpty().ifEmpty { "默认" }}") { chooseVoice(a) })
        a.addKeys(root, "ttsRate", "ttsPitch", "ttsVolume")

        root.addView(a.button("试听语音效果") {
            SpeechEngine.prepare(a, a.prefs)
            Handler(Looper.getMainLooper()).postDelayed({
                if (!a.isFinishing && !a.isDestroyed) {
                    val ok = SpeechEngine.speak(a, a.prefs, "这是豆包无障碍模块的语音库测试。")
                    if (!ok) a.toast("语音库尚未就绪或不可用，请稍后再试；键盘提示会回退读屏")
                }
            }, 1000)
        })
        a.addKeys(root, "ttsA11yStream")
        a.addKeys(root, "ttsInputEcho", "ttsDeleteEcho", "ttsVoiceEcho", "ttsPunctuationNames")
        fun enable(view: android.view.View, value: Boolean) {
            view.isEnabled = value
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i), value)
        }
        for (i in first until root.childCount) enable(root.getChildAt(i), !a.prefs.getBoolean("speakByReader", true))
    }

    private fun chooseEngine(a: SettingsActivity) {
        val engines = try {
            a.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .map { it.serviceInfo.packageName to it.loadLabel(a.packageManager).toString() }.distinctBy { it.first }
        } catch (_: Throwable) { emptyList() }
        val list = listOf("" to "系统默认") + engines
        AlertDialog.Builder(a).setTitle("语音库").setItems(list.map { it.second }.toTypedArray()) { _, i ->
            a.prefs.edit().putString("ttsEngine", list[i].first).putString("ttsVoice", "").apply()
            // 选引擎不擅自切换播报方式；由用户明确关闭“使用屏幕阅读器播报”。
            a.send(null); a.rebuild()
        }.setNegativeButton("取消", null).show()
    }

    private fun chooseVoice(a: SettingsActivity) {
        val handler = Handler(Looper.getMainLooper())
        var tts: TextToSpeech? = null
        var finished = false
        fun close() { try { tts?.shutdown() } catch (_: Throwable) { }; tts = null }
        val timeout = Runnable {
            if (!finished) { finished = true; close(); if (!a.isFinishing) a.toast("读取发音人超时") }
        }
        handler.postDelayed(timeout, 5000)
        try {
            val listener = TextToSpeech.OnInitListener { status ->
                handler.post {
                    if (finished) return@post
                    finished = true
                    handler.removeCallbacks(timeout)
                    if (a.isFinishing || a.isDestroyed) { close(); return@post }
                    val voices = if (status == TextToSpeech.SUCCESS) try { tts?.voices?.sortedBy { it.name }.orEmpty() } catch (_: Throwable) { emptyList() } else emptyList()
                    close()
                    val names = listOf("默认") + voices.map { "${it.locale.displayName} ${it.name}" }
                    AlertDialog.Builder(a).setTitle("发音人").setItems(names.toTypedArray()) { _, i ->
                        a.prefs.edit().putString("ttsVoice", if (i == 0) "" else voices[i - 1].name).apply()
                        a.send(null); a.rebuild()
                    }.setNegativeButton("取消", null).show()
                }
            }
            val pkg = a.prefs.getString("ttsEngine", "").orEmpty()
            tts = if (pkg.isEmpty()) TextToSpeech(a.applicationContext, listener) else TextToSpeech(a.applicationContext, listener, pkg)
        } catch (_: Throwable) {
            finished = true; handler.removeCallbacks(timeout); close(); a.toast("无法读取发音人")
        }
    }
}
