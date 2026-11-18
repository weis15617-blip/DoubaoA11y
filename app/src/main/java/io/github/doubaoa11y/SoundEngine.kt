package io.github.doubaoa11y

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import java.io.File

/** 用户确认的自制内置音频；用户覆盖仅保存在模块私有目录。 */
object SoundEngine {
    val events = linkedMapOf("key" to "触摸按键", "gesture" to "手势成功", "boundary" to "边界或操作失败",
        "double" to "双击或编辑选项", "scroll" to "翻页、切键盘或候选", "voice" to "语音开始")
    val slots = listOf("key", "boundary", "gesture", "double", "scroll", "voice")
    val assets = listOf("light_tap.ogg", "end_cue.ogg", "swipe_cue.ogg", "double_tap.ogg", "page_turn.ogg", "record_start.wav")
    val keys = mapOf("key" to "sfxKey", "boundary" to "sfxFail", "gesture" to "sfxGesture", "double" to "sfxDouble", "scroll" to "sfxScroll", "voice" to "sfxVoice")
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var pool: SoundPool? = null
    private var usage = -1
    private val ids = HashMap<String, Int>()
    private val ready = HashSet<Int>()
    private var stream = 0
    private var pending: Triple<Int, Float, Long>? = null
    private var last = 0L
    val labels = listOf("轻触声", "结束提示声", "滑动提示声", "双击声", "翻页声", "起录提示声")
    fun file(c: Context, id: String): File {
        require(io.github.doubaoa11y.ui.ZipBackup.validSlot(id))
        return File(c.filesDir, "sounds/$id.audio")
    }
    fun imports(c: Context) = io.github.doubaoa11y.ui.ZipBackup.audioFiles(File(c.filesDir, "sounds"))
    fun names(c: Context): org.json.JSONObject = try {
        org.json.JSONObject(c.getSharedPreferences(ConfigSpec.SETTINGS_PREFS, 0).getString("soundNames", "{}") ?: "{}")
    } catch (_: org.json.JSONException) { org.json.JSONObject() }
    fun label(c: Context, id: String): String {
        if (id.startsWith("builtin.")) return labels[slots.indexOf(id.removePrefix("builtin."))]
        return names(c).optString(id).ifBlank { if (id in slots) "导入的" + labels[slots.indexOf(id)] else "导入音频" }
    }
    fun selection(c: Context, event: String): String {
        val p = c.getSharedPreferences(ConfigSpec.SETTINGS_PREFS, 0)
        val key = keys.getValue(event)
        val saved = p.getString(key + "Id", "").orEmpty()
        if (saved.isNotEmpty()) {
            if (saved.removePrefix("builtin.") in slots && saved.startsWith("builtin.")) return saved
            if (io.github.doubaoa11y.ui.ZipBackup.validSlot(saved) && file(c, saved).isFile) return saved
            return "builtin.$event"
        }
        val old = slots.getOrNull(p.getInt(key, slots.indexOf(event))) ?: event
        return if (file(c, old).isFile) old else "builtin.$old"
    }
    fun play(c: Context, event: String, preview: Boolean = false, rawSlot: Boolean = false): Boolean {
        if (!rawSlot && event !in events) return false
        val prefs = c.getSharedPreferences(ConfigSpec.SETTINGS_PREFS, Context.MODE_PRIVATE)
        if (!preview && (!prefs.getBoolean("moduleOn", true) || !prefs.getBoolean("soundOn", false))) return false
        val volume = prefs.getInt("soundVolume", 50).coerceIn(0, 100) / 100f
        if (volume == 0f) return true
        val selected = if (rawSlot) event else selection(c, event)
        val slot = selected.removePrefix("builtin.").takeIf { it in slots } ?: event.takeIf { it in slots } ?: "key"
        main.post {
            try {
                if (!preview && (!prefs.getBoolean("moduleOn", true) || !prefs.getBoolean("soundOn", false))) { stopNow(); return@post }
                val now = android.os.SystemClock.uptimeMillis()
                if (!preview && now - last < 35) return@post
                last = now
                stopNow()
                val wantUsage = if (prefs.getBoolean("ttsA11yStream", false)) AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY else AudioAttributes.USAGE_MEDIA
                val custom = if (selected.startsWith("builtin.")) null else file(c, selected)
                if (custom?.isFile == true) {
                    val p = MediaPlayer(); player = p
                    p.setAudioAttributes(AudioAttributes.Builder().setUsage(wantUsage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    p.setVolume(volume, volume); p.setDataSource(custom.absolutePath)
                    p.setOnPreparedListener { try { if (player === it) it.start() else it.release() } catch (_: Throwable) { if (player === p) stopNow() } }
                    p.setOnCompletionListener { if (player === it) stopNow() }
                    p.setOnErrorListener { _, _, _ -> if (player === p) { stopNow(); builtin(c, slot, wantUsage, volume) }; true }
                    p.prepareAsync()
                    main.postDelayed({ if (player === p) stopNow() }, 5500)
                } else builtin(c, slot, wantUsage, volume)
            } catch (_: Throwable) { stopNow() }
        }
        return true
    }
    private fun builtin(c: Context, slot: String, wantUsage: Int, volume: Float) {
        try {
            if (pool == null || usage != wantUsage) {
                pool?.release(); ids.clear(); ready.clear(); pending = null
                usage = wantUsage
                pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build().also { current ->
                    current.setOnLoadCompleteListener { p, id, status -> main.post {
                        if (pool === p && status == 0) {
                            ready.add(id)
                            pending?.takeIf { it.first == id }?.let { request ->
                                pending = null
                                if (android.os.SystemClock.uptimeMillis() - request.third < 500) stream = p.play(id, request.second, request.second, 1, 0, 1f)
                            }
                        }
                    } }
                }
            }
            val p = pool ?: return
            val id = ids.getOrPut(slot) { c.assets.openFd("sfx/${assets[slots.indexOf(slot)]}").use { p.load(it, 1) } }
            if (id in ready) stream = p.play(id, volume, volume, 1, 0, 1f)
            else pending = Triple(id, volume, android.os.SystemClock.uptimeMillis())
        } catch (_: Throwable) { pending = null }
    }
    private fun stopNow() {
        pending = null
        try { player?.release() } catch (_: Throwable) { }; player = null
        try { if (stream != 0) pool?.stop(stream) } catch (_: Throwable) { }; stream = 0
    }
    fun stop() { if (Looper.myLooper() == Looper.getMainLooper()) stopNow() else main.post { stopNow() } }
}
