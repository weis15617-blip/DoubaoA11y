package io.github.doubaoa11y.hook

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.doubaoa11y.ConfigSpec
import org.json.JSONArray
import java.io.File

/**
 * 上半屏手势剪贴板历史切换与“紧接着双击粘贴”：
 * - 通过系统 ClipboardManager 监听 + 键盘弹出同步 + 豆包剪贴板视图辅助同步，
 *   维护本地持久化的剪贴板历史列表（最新在前，重启不丢）。
 * - 执行“上一个剪贴板 / 下一个剪贴板”时，只读取对应条目并带序号全文朗读
 *   （如“1. 豆包输入法无障碍模块正式版”），同时标记为待粘贴状态。
 * - 若紧接着的下一个手势是单指双击，直接将当前获取的剪贴板写入编辑框并朗读“xxx已粘贴”；
 *   若执行了其他手势、打字、换输入框或收起键盘，自动清除待粘贴状态。
 */
object ClipboardNav {
    private const val FILE_NAME = "doubao_a11y_clipboard_history.json"
    private const val MAX_ITEMS = 80

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val items = ArrayList<String>()

    @Volatile
    private var ctx: Context? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var cursor = -1

    @Volatile
    private var pendingActive = false

    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    fun init(c: Context) {
        if (initialized) return
        initialized = true
        val app = c.applicationContext ?: c
        ctx = app
        loadFromDisk(app)
        syncPrimaryClip(app)
        try {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm != null) {
                val l = ClipboardManager.OnPrimaryClipChangedListener {
                    try {
                        syncPrimaryClip(app)
                    } catch (t: Throwable) {
                        L.hookError("ClipboardNav.onPrimaryClipChanged", t)
                    }
                }
                clipListener = l
                cm.addPrimaryClipChangedListener(l)
            }
        } catch (t: Throwable) {
            L.hookError("ClipboardNav.init", t)
        }
    }

    /** 键盘弹出或切换输入框时同步一次当前系统剪贴板，避免休眠期间复制的内容遗漏 */
    fun onKeyboardShown() {
        clearPending()
        val c = ctx ?: ServiceHooks.appContext ?: return
        syncPrimaryClip(c)
    }

    fun onKeyboardHidden() {
        clearPending()
    }

    fun onTyping() {
        clearPending()
    }

    /** 当前是否处于“刚切换完剪贴板，等待下一个手势双击粘贴”的状态 */
    fun hasPending(): Boolean = synchronized(lock) {
        pendingActive && cursor in items.indices
    }

    fun clearPending() {
        synchronized(lock) {
            pendingActive = false
            cursor = -1
        }
    }

    /** 上一个剪贴板（往更早/更前方向？按列表序号减 1，第 1 条最新） */
    fun prev(): Boolean = step(-1)

    /** 下一个剪贴板（序号加 1，往更旧条目浏览） */
    fun next(): Boolean = step(1)

    private fun step(dir: Int): Boolean {
        val c = ctx ?: ServiceHooks.appContext
        if (c != null) syncPrimaryClip(c)
        val speakMsg: String
        val hitBoundary: Boolean
        synchronized(lock) {
            if (items.isEmpty()) {
                pendingActive = false
                cursor = -1
                Sound.play("boundary")
                Speaker.unmute()
                Speaker.speak("剪贴板为空")
                return true
            }
            if (!pendingActive || cursor !in items.indices) {
                cursor = 0
                pendingActive = true
                hitBoundary = false
                speakMsg = formatItem(cursor, items[cursor])
            } else {
                val nextIdx = cursor + dir
                if (nextIdx < 0) {
                    cursor = 0
                    pendingActive = true
                    hitBoundary = true
                    speakMsg = "已到最新，${formatItem(0, items[0])}"
                } else if (nextIdx >= items.size) {
                    cursor = items.lastIndex
                    pendingActive = true
                    hitBoundary = true
                    speakMsg = "已到最后，${formatItem(cursor, items[cursor])}"
                } else {
                    cursor = nextIdx
                    pendingActive = true
                    hitBoundary = false
                    speakMsg = formatItem(cursor, items[cursor])
                }
            }
        }
        if (hitBoundary) Sound.play("boundary")
        Speaker.unmute()
        Speaker.echo(speakMsg)
        L.i("切换剪贴板（dir=$dir，边界=$hitBoundary）：第 ${cursor + 1} 条，长度=${items.getOrNull(cursor)?.length ?: 0}")
        return true
    }

    /**
     * 紧接着的单指双击：将当前选中的剪贴板条目直接写入编辑框并播报“xxx已粘贴”。
     * 完成后退出待粘贴状态。
     */
    fun pastePending(): Boolean {
        val text = synchronized(lock) {
            if (!pendingActive || cursor !in items.indices) return false
            val t = items[cursor]
            pendingActive = false
            cursor = -1
            t
        }
        val ic = try {
            ViewA11yHooks.service?.currentInputConnection
        } catch (_: Throwable) {
            null
        }
        if (ic == null) {
            Speaker.unmute()
            Speaker.speak("当前编辑框不支持粘贴")
            return false
        }
        TypingEcho.suppressInputFor(300L)
        val ok = try {
            ic.commitText(text, 1)
        } catch (t: Throwable) {
            L.hookError("ClipboardNav.pastePending", t)
            false
        }
        if (!ok) {
            Speaker.unmute()
            Speaker.speak("当前编辑框不支持粘贴")
            return false
        }
        val label = formatPasteLabel(text)
        Speaker.unmute()
        Speaker.echo(GestureSpeechFormat.pasted(label, text.length))
        EditOptions.refresh("剪贴板双击粘贴")
        L.i("剪贴板双击粘贴成功：长度=${text.length}")
        return true
    }

    /** 朗读格式：“1. 内容”（全文不截断） */
    internal fun formatItem(zeroBasedIndex: Int, text: String): String {
        val normalized = text.replace("\r\n", "\n").trim()
        val body = if (normalized.isEmpty()) "空白" else normalized.replace('\n', '，')
        return "${zeroBasedIndex + 1}. $body"
    }

    private fun formatPasteLabel(raw: String): String {
        val normalized = raw.replace("\r\n", "\n")
        val trimmed = normalized.trim()
        if (trimmed.isEmpty()) return "空白"
        return trimmed.replace('\n', '，')
    }

    /** 读取系统当前主剪贴板文本；若为新内容则置顶写入历史并落盘 */
    fun syncPrimaryClip(c: Context) {
        val text = try {
            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.coerceToText(c)?.toString()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
        if (text.isNullOrBlank()) return
        recordText(text, moveToTop = true)
    }

    /** 供外部或豆包剪贴板列表同步补充一条记录 */
    fun recordText(raw: String, moveToTop: Boolean = true) {
        val text = raw.replace("\r\n", "\n")
        if (text.isBlank()) return
        var changed = false
        synchronized(lock) {
            val existingIdx = items.indexOf(text)
            if (existingIdx == 0) return
            if (existingIdx > 0) {
                if (!moveToTop) return
                items.removeAt(existingIdx)
                items.add(0, text)
                if (pendingActive) {
                    if (cursor == existingIdx) cursor = 0
                    else if (cursor in 0 until existingIdx) cursor++
                }
                changed = true
            } else {
                if (moveToTop) {
                    items.add(0, text)
                    if (pendingActive && cursor >= 0) cursor++
                } else {
                    items.add(text)
                }
                while (items.size > MAX_ITEMS) items.removeAt(items.lastIndex)
                if (cursor >= items.size) cursor = items.lastIndex
                changed = true
            }
        }
        if (changed) saveToDiskAsync()
    }

    private fun loadFromDisk(c: Context) {
        try {
            val f = File(c.filesDir, FILE_NAME)
            if (!f.isFile) return
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            synchronized(lock) {
                items.clear()
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "")
                    if (s.isNotBlank() && s !in items) {
                        items.add(s)
                        if (items.size >= MAX_ITEMS) break
                    }
                }
            }
        } catch (t: Throwable) {
            L.hookError("ClipboardNav.loadFromDisk", t)
        }
    }

    private var lastSaveAt = 0L

    private fun saveToDiskAsync() {
        val c = ctx ?: return
        val snapshot = synchronized(lock) { ArrayList(items) }
        val now = SystemClock.uptimeMillis()
        lastSaveAt = now
        main.postDelayed({
            if (lastSaveAt != now) return@postDelayed
            Thread({
                try {
                    val arr = JSONArray()
                    for (s in snapshot) arr.put(s)
                    val tmp = File(c.filesDir, "$FILE_NAME.tmp")
                    val dst = File(c.filesDir, FILE_NAME)
                    tmp.writeText(arr.toString(), Charsets.UTF_8)
                    if (!tmp.renameTo(dst)) {
                        tmp.copyTo(dst, overwrite = true)
                        tmp.delete()
                    }
                } catch (t: Throwable) {
                    L.hookError("ClipboardNav.saveToDisk", t)
                }
            }, "DoubaoA11yClipSave").start()
        }, 200L)
    }
}
