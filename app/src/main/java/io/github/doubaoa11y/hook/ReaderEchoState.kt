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

/**
 * 选区增量差分：比较上一次与本次选区区间，只提取新扩选或刚缩回的那一段文字。
 * 无论编辑框是否把反向选区 (anchor > end) 规范化成 (lo <= hi)，都按区间端点变化计算。
 */
object SelectionDelta {
    enum class Kind { NONE, EXPANDED, SHRUNK, CLEARED }
    data class Result(val kind: Kind, val text: String)

    fun between(text: String, prevStart: Int, prevEnd: Int, newStart: Int, newEnd: Int): Result {
        val len = text.length
        val oldLo = minOf(prevStart, prevEnd).coerceIn(0, len)
        val oldHi = maxOf(prevStart, prevEnd).coerceIn(0, len)
        val newLo = minOf(newStart, newEnd).coerceIn(0, len)
        val newHi = maxOf(newStart, newEnd).coerceIn(0, len)

        if (newLo == newHi) {
            return if (oldLo != oldHi) Result(Kind.CLEARED, "") else Result(Kind.NONE, "")
        }
        if (oldLo == newLo && oldHi == newHi) {
            return Result(Kind.NONE, "")
        }
        if (oldLo == oldHi) {
            return Result(Kind.EXPANDED, sliceSafe(text, newLo, newHi))
        }
        // 跨越锚点选到了另一侧（新旧区间没有正长度交集）
        if (newHi <= oldLo || newLo >= oldHi) {
            return Result(Kind.EXPANDED, sliceSafe(text, newLo, newHi))
        }
        // 左端移动、右端不变
        if (oldHi == newHi) {
            return if (newLo < oldLo) {
                Result(Kind.EXPANDED, sliceSafe(text, newLo, oldLo))
            } else {
                Result(Kind.SHRUNK, sliceSafe(text, oldLo, newLo))
            }
        }
        // 右端移动、左端不变
        if (oldLo == newLo) {
            return if (newHi > oldHi) {
                Result(Kind.EXPANDED, sliceSafe(text, oldHi, newHi))
            } else {
                Result(Kind.SHRUNK, sliceSafe(text, newHi, oldHi))
            }
        }
        // 两端同时变化且有交集（如全选或外部跳选）
        val kind = if (newHi - newLo >= oldHi - oldLo) Kind.EXPANDED else Kind.SHRUNK
        return Result(kind, sliceSafe(text, newLo, newHi))
    }

    private fun sliceSafe(text: String, from: Int, to: Int): String {
        val len = text.length
        var a = from.coerceIn(0, len)
        var b = to.coerceIn(a, len)
        if (a > 0 && a < len && Character.isLowSurrogate(text[a]) && Character.isHighSurrogate(text[a - 1])) a--
        if (b > a && b < len && Character.isLowSurrogate(text[b]) && Character.isHighSurrogate(text[b - 1])) b++
        return text.substring(a, b)
    }
}

/**
 * 选区/编辑动作的限时防重标记：动作自己明确播报过结果后，
 * 消费并跳过紧随其后的一次 onUpdateSelection 内部回声，避免同一动作重复播报。
 */
class SelectionSpeechGate {
    private var skipUntil = 0L

    fun markSkipNext(nowMs: Long, ttlMs: Long = 1000L) {
        skipUntil = nowMs + ttlMs.coerceAtLeast(0L)
    }

    fun consumeSkipNext(nowMs: Long): Boolean {
        val until = skipUntil
        if (until <= 0L) return false
        skipUntil = 0L
        return nowMs < until
    }

    fun reset() {
        skipUntil = 0L
    }
}

/**
 * 方案 A 下的读屏对齐缓冲：
 * - 天坦读屏全屏屏蔽生效或独立 TTS 时不走延迟（0ms 立即播报）；
 * - 解说/保益/点明下，单字移动与单字选字让位给读屏，多字跳转与文本变更动作使用短落地缓冲避开竞态；
 * - 取消选择 / 选区收拢 / 单字句段跳转可能落入解说 100ms 的 8192 位差<=1 播报（含文字解释），
 *   用 [cancelSelectionDelayMs] 在其开口后干净覆盖。
 */
object SelectionOverrideTiming {
    private const val PKG_TALKMAN = "com.nirenr.talkman"

    fun selectionDelayMs(readerPackage: String): Long = 30L

    fun cancelSelectionDelayMs(readerPackage: String): Long =
        if (readerPackage == PKG_TALKMAN || readerPackage.isEmpty()) 140L else 45L

    fun textChangeDelayMs(readerPackage: String): Long = 55L

    fun fallbackMs(afterSelMs: Long): Long = afterSelMs + 90L
}

/** 手势反馈统一文案格式：涉及文字的操作一律先念文字/数量，再念“已xx”。 */
object GestureSpeechFormat {
    fun selected(text: String): String = if (text.isEmpty()) "" else "${text}已选中"
    fun deselected(text: String): String = if (text.isEmpty()) "" else "${text}已取消"
    fun deleted(text: String, count: Int): String = when {
        text.isNotEmpty() && count <= 6 -> "${text}已删除"
        count > 0 -> "${count}个字已删除"
        else -> "已删除"
    }
    fun copied(text: String, count: Int): String = when {
        text.isEmpty() -> "已复制"
        count <= 24 -> "${text}已复制"
        else -> "${text.take(24)}等${count}个字已复制"
    }
    fun cut(text: String, count: Int): String = when {
        text.isEmpty() -> "已剪切"
        count <= 24 -> "${text}已剪切"
        else -> "${text.take(24)}等${count}个字已剪切"
    }
    fun pasted(text: String, count: Int): String = when {
        text.isEmpty() -> "已粘贴"
        count <= 24 -> "${text}已粘贴"
        else -> "${text.take(24)}等${count}个字已粘贴"
    }
    fun cleared(count: Int): String = if (count > 0) "${count}个字已清空" else "已清空"
}

/**
 * 极速连滑防漏读检测器（仅用于解说/保益/点明等由读屏念单字的场景）：
 * - 正常逐字滑动（相邻两次间隔 > [maxGapMs]）每次都让读屏稳定念出，绝不抢麦补念；
 * - 只有相邻两次滑动间隔 <= [maxGapMs]（落入读屏 100ms 8192 去抖窗口，读屏会因位差 >1 静默漏念）
 *   才在停手后补念最后一格。
 */
class RapidMoveDetector(private val maxGapMs: Long = 150L) {
    private var lastStepAt = 0L
    private var rapidCount = 0
    private var pendingText = ""

    fun record(nowMs: Long, text: String): Boolean {
        val consecutiveRapid = lastStepAt > 0L && nowMs >= lastStepAt && (nowMs - lastStepAt) <= maxGapMs
        lastStepAt = nowMs
        if (consecutiveRapid) {
            rapidCount = if (rapidCount < 2) 2 else rapidCount + 1
            pendingText = text
            return true
        }
        rapidCount = 1
        pendingText = ""
        return false
    }

    fun consumePending(): Pair<Int, String> {
        val count = rapidCount
        val text = pendingText
        rapidCount = 0
        pendingText = ""
        lastStepAt = 0L
        return if (count >= 2 && text.isNotEmpty()) count to text else 0 to ""
    }

    fun reset() {
        lastStepAt = 0L
        rapidCount = 0
        pendingText = ""
    }
}

/**
 * 候选栏翻页合并器：
 * 豆包触发加载更多候选时，快照可能返回“全量累计列表”（如 0..39），也可能只返回“新一页或滑动窗口”（如 20..39）。
 * 统一按引擎序号（或文本去重）保留之前已加载的页，确保翻到下一页后向左滑能无缝回到上一页。
 */
object CandidatePages {
    data class Merged(val items: List<String>, val engineIdx: List<Int>, val firstNew: Int)

    fun merge(
        prevItems: List<String>,
        prevEng: List<Int>,
        incomingItems: List<String>,
        incomingEng: List<Int>,
        hasEngineIdx: Boolean = true,
    ): Merged {
        if (prevItems.isEmpty()) {
            return Merged(incomingItems, incomingEng, if (incomingItems.isEmpty()) -1 else 0)
        }
        if (incomingItems.isEmpty()) {
            return Merged(prevItems, prevEng, -1)
        }
        // 全量累计列表：新快照直接以旧列表开头且更长
        if (incomingItems.size > prevItems.size && incomingItems.subList(0, prevItems.size) == prevItems) {
            return Merged(incomingItems, incomingEng, prevItems.size)
        }
        val outItems = ArrayList<String>(prevItems.size + incomingItems.size)
        val outEng = ArrayList<Int>(prevItems.size + incomingItems.size)
        outItems.addAll(prevItems)
        outEng.addAll(prevEng)
        var firstNew = -1
        if (hasEngineIdx && prevEng.size == prevItems.size && incomingEng.size == incomingItems.size) {
            val seen = HashSet<Int>(prevEng)
            for (i in incomingItems.indices) {
                val e = incomingEng[i]
                if (seen.add(e)) {
                    if (firstNew < 0) firstNew = outItems.size
                    outItems.add(incomingItems[i])
                    outEng.add(e)
                }
            }
            if (firstNew >= 0) return Merged(outItems, outEng, firstNew)
        }
        // 降级：没有引擎序号或序号重叠但出现了全新候选词时，按词去重追加
        val seenText = HashSet<String>(prevItems)
        for (i in incomingItems.indices) {
            val w = incomingItems[i]
            if (seenText.add(w)) {
                if (firstNew < 0) firstNew = outItems.size
                outItems.add(w)
                outEng.add(incomingEng.getOrElse(i) { outItems.size - 1 })
            }
        }
        return Merged(outItems, outEng, firstNew)
    }
}

/**
 * 双指单击/双击与滑/拖判定纯逻辑策略：
 * - 双指双击窗口以“第 1 拍抬手到第 2 拍按下”的空中间隔衡量，避免第 2 拍按住耗时吃掉窗口；
 * - 拖动开启且当前方向绑定了拖动动作时，先挂起普通滑动，短按抬手走普通滑动、按住超过进入阈值走拖动，绝不同时触发两次。
 */
object TwoFingerTapPolicy {
    fun doubleGapMs(sensitivity: Int): Long = when (sensitivity) {
        0 -> 440L
        2 -> 340L
        else -> 380L
    }

    fun isDoubleTap(
        pending: Boolean,
        firstUpAt: Long,
        secondDownAt: Long,
        dx: Float,
        dy: Float,
        radius: Float,
        gapMs: Long,
    ): Boolean {
        if (!pending || firstUpAt <= 0L) return false
        val dt = secondDownAt - firstUpAt
        if (dt < 0L || dt > gapMs) return false
        return kotlin.math.abs(dx) <= radius && kotlin.math.abs(dy) <= radius
    }

    fun shouldDeferSlideForDrag(dragEnabled: Boolean, dragAction: Int): Boolean =
        dragEnabled && dragAction != 0

    fun dragEnterDelayMs(dragRepeatMs: Int): Long = dragRepeatMs.coerceIn(180, 320).toLong()
}
