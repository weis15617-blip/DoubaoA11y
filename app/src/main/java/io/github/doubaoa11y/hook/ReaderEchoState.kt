package io.github.doubaoa11y.hook

/** 只比较已成功操作前后的文本；相同字符的两次真实插入仍是两次事件。 */
object EchoTextChange {
    data class Change(val removed: String, val inserted: String)
    fun between(before: String, after: String): Change {
        var first = 0
        while (first < before.length && first < after.length && before[first] == after[first]) first++
        // 不把代理对拆成两个不合法的字符。
        if (first > 0 && first < before.length && Character.isLowSurrogate(before[first]) && Character.isHighSurrogate(before[first - 1])) first--
        var oldEnd = before.length
        var newEnd = after.length
        while (oldEnd > first && newEnd > first && before[oldEnd - 1] == after[newEnd - 1]) { oldEnd--; newEnd-- }
        if (oldEnd > first && oldEnd < before.length && Character.isLowSurrogate(before[oldEnd]) && Character.isHighSurrogate(before[oldEnd - 1])) { oldEnd++; newEnd++ }
        return Change(before.substring(first, oldEnd), after.substring(first, newEnd))
    }
}

/** 每次录音是独立事务，只对同一录音的已写入结果去重，不按字符串/时间吞掉正常重复输入。 */
class VoiceEchoGate {
    private var generation = 0L
    private var delivered = false
    fun begin(): Long { generation++; delivered = false; return generation }
    fun accept(token: Long): Boolean {
        if (token != generation || delivered) return false
        delivered = true
        return true
    }
    fun cancel() { generation++; delivered = true }
}

/** 候选的输入来源与组字会话，没有“几秒后自动过期”的猜测。 */
class CandidateOriginState {
    enum class Input { KEY, HANDWRITING, COMPOSING }
    private var pending: Input? = null
    private var open = false
    private var residual = false
    private var moved = false
    fun input(source: Input) { pending = source; moved = false }
    fun cursorMoved() { clear(); moved = true }
    fun clear() { pending = null; open = false; residual = false; moved = false }
    fun committed() { residual = residual || open; pending = null; open = false }
    fun snapshot(hasPinyin: Boolean, hasCandidates: Boolean, engineTyping: Boolean?): Boolean {
        if (!hasCandidates) { open = false; return false }
        if (moved) return false
        val evidence = hasPinyin || pending == Input.HANDWRITING || pending == Input.COMPOSING ||
            (pending == Input.KEY && engineTyping == true) || ((open || residual) && engineTyping == true)
        if (!evidence) {
            open = false
            if (engineTyping == false) { residual = false; if (pending == Input.KEY) pending = null }
            return false
        }
        open = true
        residual = false
        return true
    }
    fun fromHandwriting(): Boolean = pending == Input.HANDWRITING
    fun isOpen(): Boolean = open && !moved
}
