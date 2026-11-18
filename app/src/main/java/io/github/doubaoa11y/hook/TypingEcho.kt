package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.util.WeakHashMap

/** 独立 TTS 的统一回声。只读操作成功后确认的变化，无法查询的编辑器保守不补读。 */
object TypingEcho {
    private val main = Handler(Looper.getMainLooper())
    private val composing = WeakHashMap<InputConnection, Composing>()
    private var session = 0L
    private var deletion: PendingDelete? = null
    data class Snapshot(val text: String, val selectionStart: Int = -1, val selectionEnd: Int = -1)
    private data class Composing(val start: Int, val inserted: String, val confirmedBody: String) {
        fun validIn(body: String): Boolean = body == confirmedBody && start >= 0 &&
            start + inserted.length <= body.length && body.regionMatches(start, inserted, 0, inserted.length)
    }
    private data class PendingDelete(val session: Long, val connection: InputConnection, val before: Snapshot)
    private val enabled: Boolean get() = A11yState.recording && !Config.b("speakByReader") && ViewA11yHooks.service?.currentInputEditorInfo != null
    fun observing(): Boolean = enabled && (Config.b("ttsInputEcho") || Config.b("ttsDeleteEcho") || Config.b("ttsVoiceEcho"))
    fun snapshot(ic: InputConnection): Snapshot? {
        if (!observing()) return null
        return try {
            val request = ExtractedTextRequest().apply { hintMaxChars = 8192; hintMaxLines = 100 }
            val value = ic.getExtractedText(request, 0) ?: return null
            if (value.startOffset != 0 || value.partialStartOffset >= 0) return null
            val text = value.text?.toString() ?: return null
            if (text.length > 8192) null else Snapshot(text, value.selectionStart, value.selectionEnd)
        } catch (_: Throwable) { null }
    }
    fun reset() { session++; composing.clear(); deletion = null; main.removeCallbacks(readDeletion) }
    fun successful(ic: InputConnection, name: String, argument: String, before: Snapshot?) {
        if (!enabled) return
        if (name == "finishComposingText") {
            val value = composing.remove(ic) ?: return
            if (snapshot(ic)?.text?.let { value.validIn(it) } != true) return
            if (!Voice.acceptSuccessfulWrite(value.inserted, true) && Config.b("ttsInputEcho")) Speaker.echo(value.inserted)
            return
        }
        val previous = if (name == "commitText") composing.remove(ic) else composing[ic]
        val old = before ?: return
        val after = snapshot(ic) ?: return
        val change = EchoTextChange.between(old.text, after.text)
        if (change.inserted.isEmpty()) {
            // 正文不变的 commit 只有匹配此前确认的同一组字段才可补读。
            // 先交给语音事务处理，避免语音组字+确认又被普通输入路径重复朗读。
            if (name == "commitText" && old.text == after.text && previous?.validIn(old.text) == true &&
                argument == previous.inserted && argument.isNotEmpty()) {
                if (!Voice.acceptSuccessfulWrite(previous.inserted, true) && Config.b("ttsInputEcho"))
                    Speaker.echo(previous.inserted)
            } else if (name == "commitText") Voice.acceptConfirmedCommit(argument)
            return
        }
        // 用确切替换位置与完整正文验证累计组字，不能靠 contains 命中别处旧文字。
        val record = confirmedComposition(old, after, argument, previous, change.inserted)
        val confirmed = record.inserted
        if (name == "setComposingText") composing[ic] = record
        if (Voice.acceptSuccessfulWrite(confirmed, name == "commitText")) return
        if (name == "commitText" && Config.b("ttsInputEcho")) Speaker.echo(confirmed)
    }
    private fun confirmedComposition(before: Snapshot, after: Snapshot, argument: String,
        previous: Composing?, actualDelta: String): Composing {
        if (previous?.validIn(before.text) == true &&
            before.text.replaceRange(previous.start, previous.start + previous.inserted.length, argument) == after.text)
            return Composing(previous.start, argument, after.text)
        val start = minOf(before.selectionStart, before.selectionEnd)
        val end = maxOf(before.selectionStart, before.selectionEnd)
        if (start >= 0 && end <= before.text.length &&
            before.text.replaceRange(start, end, argument) == after.text)
            return Composing(start, argument, after.text)
        // 无可验证的组字或选区位置时，只记实际新增的差异段。
        var at = 0
        while (at < before.text.length && at < after.text.length && before.text[at] == after.text[at]) at++
        if (at > 0 && at < after.text.length && Character.isLowSurrogate(after.text[at]) && Character.isHighSurrogate(after.text[at - 1])) at--
        return Composing(at, actualDelta, after.text)
    }
    fun deleted(ic: InputConnection, before: Snapshot?) {
        if (!enabled || !Config.b("ttsDeleteEcho") || before == null) return
        if (deletion?.session != session || deletion?.connection !== ic) deletion = PendingDelete(session, ic, before)
        main.removeCallbacks(readDeletion)
        main.postDelayed(readDeletion, 90)
    }
    private val readDeletion = Runnable {
        val pending = deletion ?: return@Runnable
        deletion = null
        if (!enabled || !Config.b("ttsDeleteEcho") || pending.session != session) return@Runnable
        val after = snapshot(pending.connection) ?: return@Runnable
        val change = EchoTextChange.between(pending.before.text, after.text)
        if (change.removed.isEmpty() || change.inserted.isNotEmpty()) return@Runnable
        val n = change.removed.codePointCount(0, change.removed.length)
        Speaker.echo(if (n <= 6) "已删除 ${change.removed.replace("\n", "换行")}" else "已删除 $n 个字")
    }
    fun voiceResult(text: String) {
        if (enabled && InputPrivacy.mayRead() && Config.b("ttsVoiceEcho")) Speaker.echo(text)
    }
}
