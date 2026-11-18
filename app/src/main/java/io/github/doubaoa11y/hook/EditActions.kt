package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import io.github.doubaoa11y.ConfigSpec
import java.text.BreakIterator
import java.util.Locale

/**
 * 全屏手势用到的文本编辑动作。全部用当前输入框的 InputConnection 做，和编辑面板（EditPanel）互相独立，
 * 只在全屏手势里用；编辑面板照旧。
 *
 * 每个动作都自己朗读结果：移动就读越过的那个字，到边界读“已到开头”“已到末尾”。
 *
 * “选择文本（多选）”进入选择模式后，单指左右滑动（上一个/下一个字符）改成左右扩展选择，
 * 删除则删掉选中的一段，直到“取消选择”、上屏或者换输入框。
 */
object EditActions {
    private val main = Handler(Looper.getMainLooper())

    /** 是否在选择模式（多选）里：进入后方向动作变成扩展选择 */
    @Volatile
    private var selecting = false

    /** 选择模式的起点（绝对偏移），-1 表示还没开始选 */
    private var anchor = -1

    /**
     * 选择模式的**活动端**（绝对偏移）：真正跟着手指跑的那一头。
     *
     * 以前这里图省事，每次都用“选区里较大的那个偏移”当活动端，于是：
     * 光标停在文本末尾进入选择态、往左滑，选区是 [末尾-1, 末尾]，较大端永远是末尾，
     * 活动端看起来一直没动 ⇒ 怎么滑都选不出反方向（用户反馈的核心 bug）。
     * Android 的选区本来就分“固定端 selectionStart / 活动端 selectionEnd”，
     * 编辑框也可能把方向吃掉（有的实现 setSelection(lo,hi) 后 end 恒等于大的一端），
     * 所以活动端必须我们自己记：手指往哪边跑，它就是哪边。
     */
    private var activeEnd = -1

    /** 现在是不是在选择模式（多选）里：选择模式下左右滑动是扩选，不能拿去切候选 */
    val isSelecting: Boolean get() = selecting

    /**
     * 给手势层用的选择态自检：真的还在选才返回 true。
     *
     * 直接看 [isSelecting] 这个标志位是不够的——一旦它有残留（键盘收起没走到、编辑框自己取消了选区），
     * 手势层会一直按“扩选”处理，候选优先、左右滑切候选全都进不去，用户看到的就是“动作没了”。
     * 这里拿编辑框的真实选区核对一次，对不上就顺手清掉。
     */
    fun stillSelectingNow(): Boolean {
        if (!selecting) return false
        val c = ic() ?: return false
        val s = snapshot(c) ?: return false
        return stillSelecting(s)
    }

    /**
     * 键盘上直接按了删除键（豆包原生的 deleteBackward / deleteSurroundingText，不是走我们的手势）。
     * 选择态下删掉选中文本后，编辑框就没有选中了，这时要自动退出选择态（天坦也是这么做的：
     * 文本一变就 setSelectingMode(false)）。这里延迟一下，等删除真正生效、选区更新后再判断。
     */
    fun onExternalDelete() {
        if (!selecting) return
        main.postDelayed({
            val ic = ic() ?: return@postDelayed
            val s = snapshot(ic)
            // 编辑框里已经没有选中文本了（被删掉或收成光标），退出选择态
            if (s == null || !s.hasSelection) {
                clearSelectionState("删除键删掉选中文本")
            }
        }, 120)
    }

    /**
     * 键盘区（下半屏）按了删除键——全屏时摸到“删除”抬手、或者按住连续删除。
     *
     * 用户拿这个删掉选中的文本之后，编辑框里已经没有选区了，可选择态还挂着：
     * 左右滑还在扩选、编辑菜单轮盘还停在“剪切/复制/删除文本/取消选择”上，
     * 看着就像“没退出多选”（用户反馈）。这里等原生删除真的做完再核对一次。
     */
    fun onKeyDelete() {
        if (!selecting) {
            // 不在选择态：打字用的删除，编辑菜单轮盘照样回到“双击跳转行首行尾”
            EditOptions.keyTyped()
            return
        }
        main.postDelayed({
            val ic = ic()
            val s = ic?.let { snapshot(it) }
            if (s == null || !s.hasSelection) {
                clearSelectionState("键盘删除键删掉选中文本")
            }
        }, 150)
    }

    /**
     * 键盘上打了字：选择态已经没意义了（打出来的字会替换掉选中的那段），静默退出。
     * 不播报、不动光标——用户正在打字，别插话。
     */
    fun leaveSelectionQuiet() {
        if (!selecting) return
        clearSelectionState("键盘打字，静默退出")
    }

    /**
     * 退出选择态的唯一入口：标志位、固定端、活动端、编辑菜单轮盘一起收拾干净。
     *
     * 之前散在各处手写 `selecting = false; anchor = -1`，漏掉过 activeEnd 和
     * EditOptions.selectionEnded()，于是出现“选择态退了、轮盘还停在剪切/复制/取消选择上”
     * 和“活动端留着旧偏移、下次进来直接乱扩选”这两种残留。
     */
    private fun clearSelectionState(reason: String) {
        if (!selecting && anchor < 0 && activeEnd < 0) return
        selecting = false
        anchor = -1
        activeEnd = -1
        EditOptions.selectionEnded()
        L.i("退出选择态（$reason）")
    }

    /**
     * 选择态到底还算不算数：拿编辑框的真实状态核对一遍，别让标志位“卡住”。
     *
     * 用户反馈的场景：点过“全选文本/选择文本”，收起键盘再展开，左右滑想移动光标，
     * 结果模块还认为自己处在选择态，每一次左右滑都被当成扩选，光标一步不动。
     *
     * 返回 true = 这次方向动作应该扩选；返回 false = 选择态已经失效，调用方按普通移动继续。
     */
    private fun stillSelecting(s: Snapshot): Boolean {
        if (!selecting) return false
        val base = s.offset
        val len = s.text.length
        val anchorOk = anchor in base..(base + len)
        val activeOk = activeEnd in base..(base + len)
        // 刚进入选择模式、还没滑过：选区收缩成一点，和固定端重合，这是合法状态
        if (anchorOk && activeOk && activeEnd == anchor) return true
        // 编辑框里确实有选中：老老实实按选择态走（哪怕 anchor 丢了，靠编辑框的选区也能继续扩）
        if (s.hasSelection) return true
        // 没选中、锚点也对不上：这就是残留的选择态。再按扩选处理只会让光标一动不动
        clearSelectionState("选区状态与编辑框对不上")
        return false
    }

    /**
     * 键盘收起（或开始收起）：直接退出选择态，**不跨收起—展开保留**。
     *
     * 以前这里是“收起前记住、展开时恢复”，本意是好的，实际用起来是坑：
     * 用户点过全选/选择文本后收起键盘，再展开想用左右滑移动光标，模块仍处在选择态，
     * 左右滑全被当成扩选，光标动不了（用户反馈）。天坦输入法也是键盘一收就结束选择模式，
     * 这里照做——想接着选，展开后重新执行“选择文本”就行，成本很低。
     */
    fun onWindowHidden() {
        resetEcho()
        if (!selecting) return
        val ic = ic()
        val s = ic?.let { snapshot(it) }
        val hasSel = s?.hasSelection == true
        val end = activeEnd
        clearSelectionState("键盘收起，收起前有选中=$hasSel")
        // 收起前编辑框还留着选区：顺手收成光标，不然重新展开又是一片蓝色高亮（用户反馈）
        if (hasSel && ic != null) collapseSelection(ic, end, "键盘收起")
    }

    /**
     * 键盘展开：只做残留清理，绝不主动恢复选择态。
     *
     * 正常路径下 onWindowHidden 已经把选择态清掉了，这里兜的是“没经历收起就换了输入框/窗口重建”
     * 这类极端情况——真是残留，清掉；刚进入选择态的零长度状态由 stillSelecting 放行。
     */
    fun onWindowShown() {
        // 换了输入框、或者收起又展开：上一轮打字的候选会话到此结束，别再拦住左右滑
        Candidates.onCursorMove()
        resetEcho()
        if (!selecting) return
        val s = ic()?.let { snapshot(it) } ?: run { clearSelectionState("展开时拿不到编辑框"); return }
        if (!s.hasSelection && !(activeEnd == anchor && anchor >= 0)) {
            clearSelectionState("展开时发现选择态是残留的")
        }
    }

    private val stops = charArrayOf('。', '！', '？', '；', '…', '\n', '．', '.', '!', '?', ';')
    @Suppress("unused") private val unusedStops = stops  // 保留给未来句子边界兜底参考

    // ---------------- 天坦式光标移动 ----------------
    //
    // 移动光标完全照搬天坦输入法的 GranularityIterator 思路：不用方向键（方向键会被豆包编辑框
    // 解释成“跳到行首/行尾”，还会把 InputConnection 焦点搞丢，导致“光标离开输入法”“文字无法上屏”），
    // 而是用 BreakIterator 在文本里自己算出下一个/上一个字符（或句子、段落）的边界，再用
    // setSelection 直接把光标移过去，最后读越过的那段字。这样操作的是 InputConnection 的选区，
    // 与读屏、豆包原生编辑面板各走各的，天然不冲突、不错位。

    /** 默认的“句子结束”标点，和天坦输入法 SENTENCE_SYMBOL_DEFAULT 一致 */
    private fun sentenceSymbols() = ConfigSpec.sentenceList(Config.s("sentenceSymbols")).toSet()

    private fun isSentenceSymbol(c: Char): Boolean = sentenceSymbols().contains(c.toString())

    private fun ic(): InputConnection? = try {
        ViewA11yHooks.service?.currentInputConnection
    } catch (t: Throwable) {
        null
    }

    /**
     * 编辑框里现在一个字都没有。
     *
     * 给编辑菜单用（空框只显示“粘贴”“收起键盘”）。这是跨进程问编辑框，别在每次按键里调，
     * 只在弹键盘、上屏、删除、粘贴这些节点调（见 [EditOptions.refresh]）。
     */
    fun editorEmpty(): Boolean {
        val c = ic() ?: return true
        return try {
            val before = c.getTextBeforeCursor(1, 0)
            val after = c.getTextAfterCursor(1, 0)
            val sel = c.getSelectedText(0)
            before.isNullOrEmpty() && after.isNullOrEmpty() && sel.isNullOrEmpty()
        } catch (t: Throwable) {
            L.hookError("EditActions.editorEmpty", t)
            true
        }
    }

    /** 整段文字、组字区起点、光标 */
    private class Snapshot(val text: String, val offset: Int, val start: Int, val end: Int) {
        val caret: Int get() = if (start == end) start else minOf(start, end)
        val hasSelection: Boolean get() = start != end
        fun indexOf(i: Int): Int = (i - offset).coerceIn(0, text.length)
    }

    private fun snapshot(ic: InputConnection): Snapshot? { return try {
        val type = ViewA11yHooks.service?.currentInputEditorInfo?.inputType ?: 0
        val variation = type and android.text.InputType.TYPE_MASK_VARIATION
        val cls = type and android.text.InputType.TYPE_MASK_CLASS
        if ((cls == android.text.InputType.TYPE_CLASS_TEXT && variation in setOf(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == android.text.InputType.TYPE_CLASS_NUMBER && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)) return null
        val et: ExtractedText = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val t = et.text?.toString() ?: return null
        Snapshot(t, et.startOffset, et.selectionStart + et.startOffset, et.selectionEnd + et.startOffset)
    } catch (t: Throwable) {
        null
    }

    }

    // ---------------- 字 ----------------

    fun prevChar() = step(-1)
    fun nextChar() = step(1)

    /**
     * 单指左右滑移动光标：照搬天坦输入法的做法——用 BreakIterator 在文本里算出下一个/上一个
     * 字符的边界，再用 setSelection 把光标移过去，读越过的那段字。
     *
     * 不用方向键的原因：方向键发出去后由豆包编辑框自己解释，个别编辑框把 DPAD_LEFT/RIGHT
     * 当成“跳到行首/行尾”，还会把 InputConnection 焦点弄丢，于是“移动几个字后就只会报开头结尾、
     * 光标不动，文字也上不了屏”。setSelection 直接改选区，不碰焦点、不碰按键，稳定可靠。
     */
    private fun step(dir: Int) {
        Candidates.onCursorMove()
        val ic = ic() ?: return
        // 选择态先核对一遍：残留的选择态（比如收起键盘再展开）会让左右滑变成扩选，光标一动不动
        val cached = if (selecting) snapshot(ic) else null
        if (selecting) {
            if (cached == null) {
                // 拿不到文本快照就退回方向键带 Shift 扩选
                arrowShift(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                return
            }
            if (stillSelecting(cached)) {
                extendSelection(ic, cached, dir, Unit.CHAR)
                return
            }
            // 选择态已失效（残留）：往下走普通移动
        }
        val s = cached ?: snapshot(ic) ?: run {
            // 拿不到文本快照（罕见）：退回方向键兜底，至少光标能动
            send(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            return
        }
        val caret = s.indexOf(s.caret)
        val target = boundaryOf(Unit.CHAR, s.text, caret, dir)
        if (target < 0) {
            // 到头了：往右到头只念全文，往左到头念“共 N 个字”+全文（见 speakBoundary
            speakBoundary(dir, s)
            return
        }
        // setSelection 直接移动光标（选区收缩成一点）
        ic.setSelection(s.offset + target, s.offset + target)
        // 光标一动，应用自己会发“选区变了”，读屏立刻把那个字念出来；
        // 走语音库播报时先把读屏压一下，免得同一个字念两遍
        Speaker.hushReader()
        // 该念哪个字：见 charAtCaret（修“反向移动重复念同一个字”）
        val seg = charAtCaret(s.text, target, dir)
        // 走到第一个/最后一个字这一步：读屏经常在“刚好贴到边界”这一下不念（它认为接下来要报边界），
        // 用户听到的就是最后两个字之后直接跳“已到结尾”。这一步补读，别让那个字丢了
        speakSegment(seg, force = target <= 0 || target >= s.text.length)
    }

    /** 上一次朗读过的字在文本里的位置（-1 = 还没念过） */
    private var lastCharIdx = -1

    /** 换输入框、收起键盘时清掉，免得下一个输入框拿旧的序号去比 */
    fun resetEcho() {
        lastCharIdx = -1
        burst = 0
        burstText = ""
        main.removeCallbacks(burstRunner)
    }

    /**
     * 移动一格之后该念哪个字。
     *
     * 用户反复提的那个毛病：四个字“吃饭了吗？”，光标移到“吃”上（这时念的是“吃”），
     * 再往右移，老代码念的还是“跨过的那个字”——又是“吃”，听起来像光标没动。
     *
     * 现在的规则（正向反向都不重复）：
     * - 向左移：念光标前面那个字（移动后的 text[target]），所以 1→0 念“吃”；
     * - 向右移：念跨过的字 text[target-1]；如果这个字刚刚才念过，说明用户在往回走，
     *   改念光标后面那个字 text[target]，所以 0→1 念“饭”而不是再念一遍“吃”；
     * - 光标已经在最后一个字后面：念最后一个字。
     */
    private fun charAtCaret(text: String, target: Int, dir: Int): String {
        val len = text.length
        if (len == 0) return ""
        val crossed = if (dir > 0) target - 1 else target
        val idx = when {
            dir < 0 -> target.coerceIn(0, len - 1)
            crossed in 0 until len && crossed != lastCharIdx -> crossed
            target in 0 until len -> target
            else -> len - 1
        }
        lastCharIdx = idx
        return spoken(text[idx])
    }

    /** 还没读出去的那一个字；快速连滑时被后来的覆盖，只留最后停留的那个 */
    private var pendingEcho = ""

    private val echoRunner = Runnable {
        val t = pendingEcho
        pendingEcho = ""
        if (t.isNotEmpty()) Speaker.speak(t)
    }

    /**
     * 读一段越过的字：单个标点/空白/换行读名字，多个字符读原文（超长截断）。
     * 关掉“移动光标时朗读字词”后不读，交给读屏决定。
     *
     * fast：手的滑动比朗读快得多，逐字排队会越落越远（用户反馈“滑两下才念到上一个字”）。
     * 所以这里做 90ms 合并——连续移动只读最后停下的那一格，中间的让位。
     */
    private fun speakSegment(seg: String, force: Boolean = false) {
        if (!Config.b("fsSpeakChar")) return
        // 播报路线是读屏时，光标一动读屏自己就把这个字读出来了，模块再读一遍就是两遍。
        // 解说用户反馈的“移动光标播报两次”就是这里重复了（Bug2）。字词交给读屏，
        // 边界提示、字数这些读屏不会读的话仍然由 speakBoundary 播报，所以关掉朗读字词也不会丢提示。
        // force：刚好贴到首/尾那一格例外，读屏那一格常常不念，得由模块补
        if (!force && Speaker.readerWillSpeak()) {
            noteBurst(seg)
            return
        }
        val t = seg.trim()
        val text = if (t.isEmpty()) {
            // 纯空白/换行，读成“空格”/“换行”
            spoken(seg.firstOrNull())
        } else if (t.length <= 24) {
            t
        } else {
            t.substring(0, 24) + "，后面还有几个字"
        }
        if (force) {
            // 贴边这一格要立刻出声，不能等合并窗口，否则会和“已到开头/结尾”挤在一起
            main.removeCallbacks(echoRunner)
            pendingEcho = ""
            Speaker.speak(text)
            return
        }
        pendingEcho = text
        main.removeCallbacks(echoRunner)
        main.postDelayed(echoRunner, 90)
    }

    // ---------------- 连滑漏读兜底 ----------------

    /** 一串连续移动的次数、最后停留那一格的字、这一串的起始时间 */
    private var burst = 0
    private var burstText = ""
    private var burstAt = 0L

    private val burstRunner = Runnable {
        val n = burst
        val t = burstText
        val quick = SystemClock.uptimeMillis() - burstAt <= 1400L
        burst = 0
        burstText = ""
        if (n >= 3 && quick && t.isNotEmpty()) {
            L.i("连滑 $n 格，读屏可能漏念，模块补念最后一格「$t」")
            Speaker.echo(t)
        }
    }

    /**
     * 记一次“读屏会自己念、模块先不念”的移动。
     *
     * 读屏对“慢慢滑一格”是可靠的，但连滑时它会把几次播报合并、丢掉中间几个字
     * （用户反馈“左右滑动有概率部分文字漏读，语音库播报正常”）。
     * 所以数一下连滑次数，到 3 格以上就在停下来（160 毫秒没有新移动）之后补念最后一格。
     * 这样慢滑不重复、快滑不漏读。
     */
    private fun noteBurst(seg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - burstAt > 1400L) burst = 0
        if (burst == 0) burstAt = now
        burst++
        burstText = seg
        main.removeCallbacks(burstRunner)
        if (burst >= 3) main.postDelayed(burstRunner, 160)
    }

    /**
     * 光标已经到了开头/结尾还继续滑：把**整段文字完整念一遍**。两个方向的处理**不一样**（用户指定）：
     *
     * - 向右滑到结尾、再往右滑：**只念文字**，不报字数——用户已经知道自己在末尾了，报字数纯属多余；
     * - 向左滑到开头、再往左滑：念“共N个字 + 全文”，数字和正文之间不留逗号，不然读屏会在那里停一下。
     *
     * 换行读成停顿（`\n` → 逗号），`\r\n` 直接去掉，别让读屏念成“斜杠 n”或者直接吃掉。
     */
    private fun speakBoundary(dir: Int, s: Snapshot) {
        Sound.play("boundary")
        val text = s.text
        val n = text.length
        if (n == 0) {
            Speaker.speak("编辑框是空的")
            return
        }
        val whole = text.replace("\r\n", "").replace('\n', '，').trim()
        Speaker.unmute()
        val msg = when {
            whole.isEmpty() -> "共${n}个字"
            // 到结尾：只要文字
            dir > 0 -> whole
            // 到开头：字数 + 文字
            else -> "共${n}个字$whole"
        }
        Speaker.echo(msg)
        L.i("光标到边界：${if (dir < 0) "开头（字数+全文）" else "结尾（只念全文）"}，共 $n 个字")
    }

    /** 光标所在的那一句 / 那一段（按用户配置的标点断句） */
    private fun segmentAt(text: String, pos: Int, para: Boolean): String {
        val len = text.length
        if (len == 0) return ""
        val p = pos.coerceIn(0, len)
        fun isBreak(c: Char): Boolean = if (para) c == '\n' else isSentenceSymbol(c)
        var a = p
        while (a > 0 && !isBreak(text[a - 1])) a--
        var b = p
        while (b < len && !isBreak(text[b])) b++
        if (b < len) b++
        return text.substring(a, b).replace('\n', '，').trim()
    }

    /** 读一次移动后越过的字；拿不到就静默（读屏自己会读） */
    private fun speakCharAfterMove(ic: InputConnection, dir: Int) {
        val t = try {
            if (dir > 0) ic.getTextBeforeCursor(1, 0) else ic.getTextAfterCursor(1, 0)
        } catch (e: Throwable) {
            null
        }
        val c = t?.toString()
        // 到边界（拿不到字）时：只有编辑框一个字都没有才提示，有字就静默
        if (c.isNullOrEmpty()) {
            val empty = try {
                val s = snapshot(ic)
                s == null || s.text.isEmpty()
            } catch (e: Throwable) {
                false
            }
            if (empty) Speaker.speak(if (dir < 0) "已到开头" else "已到结尾")
            return
        }
        Speaker.speak(spoken(c.firstOrNull()))
    }

    /** 方向键带 Shift（选择模式下扩选用） */
    private fun arrowShift(code: Int) {
        val ic = ic() ?: return
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val t = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** 双击“默认”项：跳到行尾；已经在行尾就跳行首。播报“已到开头”/“已到结尾” */
    fun toggleEnd(): Boolean {
        Candidates.onCursorMove()
        val ic = ic() ?: return false
        val s = snapshot(ic) ?: run {
            // 拿不到文本快照就发 MOVE_END（跳到结尾），至少光标能动
            send(KeyEvent.KEYCODE_MOVE_END)
            Speaker.speak("已到结尾")
            return true
        }
        val caret = s.indexOf(s.caret)
        val atEnd = caret >= s.text.length
        // 和豆包编辑面板“开头/末尾”按钮一样发 MOVE_HOME/MOVE_END（带 CTRL 跳到全文）
        val meta = KeyEvent.META_CTRL_ON
        val code = if (atEnd) KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END
        val t = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
        Speaker.speak(if (atEnd) "已到开头" else "已到结尾")
        return true
    }

    // ---------------- 选择模式（多选） ----------------

    /**
     * 进入选择模式并从光标处开始选。已经在选择里再执行一次就是取消选择。
     * 照天坦输入法：只设选择态和固定端（anchor=光标位置），不立即选字，
     * 之后左右滑才真正扩选/缩选（extendSelection 里活动端从 anchor 开始移动）。
     */
    fun beginSelect() {
        if (selecting) {
            deselect()
            return
        }
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        Candidates.onCursorMove()
        selecting = true
        // 固定端 = 当前光标位置（绝对偏移）
        anchor = s.offset + s.indexOf(s.caret)
        // 活动端一开始和固定端重合；往哪边滑，它就往哪边跑（不预设方向）
        activeEnd = anchor
        // 光标收缩成一点，作为选区起点；左右滑再开始扩选
        ic.setSelection(anchor, anchor)
        Speaker.speak("已进入选择模式")
    }

    /** 取消选择：退出选择模式，光标回到选择起点 */
    fun deselect() {
        Candidates.onCursorMove()
        val a = anchor
        // 先把状态收干净：以前这里 `val ic = ic() ?: return` 会在拿不到输入连接时
        // 直接溜走，标志位留在选择态，用户看到的就是“退不出去”
        selecting = false
        anchor = -1
        activeEnd = -1
        EditOptions.selectionEnded()
        val ic = ic()
        if (ic == null) {
            L.w("取消选择时拿不到输入连接，只清了状态")
            Speaker.speak("已退出选择模式")
            return
        }
        // 编辑框里还留着选区就必须真的收掉：以前只 setSelection 一下就完事，
        // 编辑框不认（或者 snapshot 拿不到）时，退出多选后仍然是一大片蓝色高亮（用户反馈）
        val collapsed = collapseSelection(ic, a, "取消选择")
        Speaker.speak(if (collapsed) "已取消选择" else "已退出选择模式")
    }

    /**
     * 把编辑框里的选区收成一个光标。返回 true 表示真的收掉了（原来有选中）。
     *
     * 退出选择模式必须走这里，不能只管模块自己的标志位——编辑框那边如果还认为
     * “有一段被选中”，屏幕上就是一片蓝色高亮，读屏也会一直报“已选中 N 个字”。
     * 收不掉分两档兜底：先等 90 毫秒核实一次，还在就用不带 Shift 的方向键（Android
     * 编辑框收到方向键会把选区收成光标），再核实一次，实在不行才记日志放手。
     */
    private fun collapseSelection(ic: InputConnection, prefer: Int, why: String): Boolean {
        val s = snapshot(ic) ?: return false
        if (!s.hasSelection) return false
        val base = s.offset
        val lo = base + s.indexOf(minOf(s.start, s.end))
        val hi = base + s.indexOf(maxOf(s.start, s.end))
        val p = if (prefer in lo..hi) prefer else hi
        try {
            // 套一层 batchEdit：部分编辑框（Flutter、自绘输入框）不套 batch 就不认 setSelection 的选区变更
            ic.beginBatchEdit()
            ic.setSelection(p, p)
            ic.endBatchEdit()
        } catch (t: Throwable) {
            L.hookError("collapseSelection.setSelection", t)
        }
        main.postDelayed({
            val a = snapshot(ic)
            if (a != null && a.hasSelection) {
                L.w("退出选择态后编辑框还有选区（$why），改用方向键收掉")
                sendArrowCollapse(ic, a)
                main.postDelayed({
                    if (snapshot(ic)?.hasSelection == true) {
                        L.w("编辑框不给改选区（$why），只能等它自己取消")
                    } else {
                        L.i("方向键收掉了选区（$why）")
                    }
                }, 130)
            }
        }, 90)
        return true
    }

    /** 不带 Shift 的方向键：编辑框会把选区收成一个光标 */
    private fun sendArrowCollapse(ic: InputConnection, s: Snapshot) {
        val key = if (s.start > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        try {
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
        } catch (t: Throwable) {
            L.hookError("sendArrowCollapse", t)
        }
    }

    /**
     * 选择模式下往 dir 方向移动“活动端”，固定端（anchor）不动，可扩选也可缩选。
     * 完全照天坦输入法 moveTextCursor 里 isSelectingMode 分支的做法：
     * - 固定端 = 进入选择态时的 anchor；
     * - 活动端 = 当前 selectionEnd（没选中就是 anchor 本身），左右滑用 BreakIterator
     *   字符边界步进（不拆 surrogate pair / emoji），越过固定端就反向扩大；
     * - 全选后向左滑是缩选、向右滑到头报边界，不是只能一路扩大。
     */
    private fun extendSelection(ic: InputConnection, s: Snapshot, dir: Int, unit: Unit = Unit.CHAR) {
        val base = s.offset
        val len = s.text.length
        // 固定端：anchor 有效就用它，否则退回当前选区的起点
        val a = if (anchor in base..(base + len)) anchor else base + s.indexOf(minOf(s.start, s.end))
        // 活动端：用模块自己记的那一头（往左滑就是左边那头，往右滑就是右边那头）。
        // 如果编辑框里的实际选区跟它对不上（用户在外面点了一下、或别人改了选区），
        // 就以编辑框为准重建——取 selectionEnd，因为它才是活动端，而不是“较大的那个偏移”
        var active = if (activeEnd in base..(base + len)) activeEnd else a
        if (s.hasSelection) {
            val realLo = base + s.indexOf(minOf(s.start, s.end))
            val realHi = base + s.indexOf(maxOf(s.start, s.end))
            if (minOf(a, active) != realLo || maxOf(a, active) != realHi) {
                active = s.end.coerceIn(base, base + len)
            }
        }
        val rel = (active - base).coerceIn(0, len)

        if (unit == Unit.LINE) {
            // 按行扩选只能交给编辑框自己（要有排版信息才能算上一行/下一行）
            arrowShift(if (dir < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
            main.postDelayed({ reportSelection("按行扩选") }, 90)
            return
        }

        val next = boundaryOf(unit, s.text, rel, dir)
        if (next < 0) {
            Sound.play("boundary")
            // 活动端到头了：报字数和边界，别只说“已到开头”就没了
            val n = kotlin.math.abs(active - a)
            Speaker.speak((if (dir < 0) "已选到开头，共" else "已选到末尾，共") + "${n}个字")
            return
        }
        val newActive = base + next
        // 活动端正好回到固定端：选区长度变 0，等于把选中取消掉。
        // 保留选择态，手指接着往另一侧划就继续选那一侧（不是“反方向就取消”）
        if (newActive == a) {
            ic.setSelection(a, a)
            activeEnd = a
            Speaker.speak("已取消选中")
            return
        }
        val lo = minOf(a, newActive)
        val hi = maxOf(a, newActive)
        ic.setSelection(lo, hi)
        Speaker.hushReader()
        activeEnd = newActive
        val picked = s.text.substring((lo - base).coerceIn(0, len), (hi - base).coerceIn(0, len))
        // 编辑框处理 setSelection 有几十毫秒延迟，等一下再核对：
        // 有的编辑框（Flutter、自绘输入框）不认 setSelection 的选区、只把光标挪过去，
        // 那种就退回方向键带 Shift，不让用户以为选中了
        main.postDelayed({
            val after = snapshot(ic)
            if (after == null || !after.hasSelection) {
                L.w("setSelection 扩选没生效（$unit），改用方向键带 Shift")
                shiftFallback(unit, dir)
                main.postDelayed({ reportSelection("方向键扩选") }, 120)
            } else {
                reportSelection("扩选", picked)
            }
        }, 70)
    }

    /** 报一句选中了多少字、选的是什么；读屏会自己念选中内容时只补字数，避免重复朗读 */
    private fun reportSelection(how: String, expected: String? = null) {
        val ic = ic() ?: return
        val s = snapshot(ic)
        val picked: String
        if (s != null && s.hasSelection) {
            val lo = s.indexOf(minOf(s.start, s.end)).coerceIn(0, s.text.length)
            val hi = s.indexOf(maxOf(s.start, s.end)).coerceIn(0, s.text.length)
            picked = s.text.substring(lo, hi)
        } else {
            if (expected == null) {
                // setSelection 和方向键都选不上：这个编辑框不给改选区，只能走豆包编辑面板
                Speaker.speak("这个输入框选不中，请用编辑面板选择文本")
                L.w("选择态：两种方式都没能选中（$how）")
                return
            }
            picked = expected
        }
        val n = picked.length
        L.i("选择态（$how）：选中 $n 个字")
        if (Speaker.readerWillSpeak()) {
            // 读屏会自己念“已选择 xxx”，模块只补它不会念的字数
            Speaker.speak("共 $n 个字")
        } else {
            Speaker.speak("已选中 ${picked.takeLast(24)}，共 $n 个字")
        }
    }

    /** 方向键带 Shift 扩选：按粒度选对应的键 */
    private fun shiftFallback(unit: Unit, dir: Int) {
        val code = when (unit) {
            Unit.SENTENCE -> if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            Unit.PARAGRAPH -> if (dir < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            Unit.LINE -> if (dir < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            Unit.CHAR -> if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        }
        arrowShift(code)
    }

    /** 扩选用的移动单位 */
    private enum class Unit { CHAR, SENTENCE, PARAGRAPH, LINE }

    /**
     * 在文本里按单位找下一个边界：成功返回新位置（相对文本开头），到头返回 -1。
     *
     * 字用 BreakIterator（不拆 emoji / 代理对），句用用户配置的句号标点，段用换行——
     * 和天坦输入法的 GranularityIterator 一套思路，光标移动和扩选共用同一个边界函数，
     * 所以“按句移动”和“按句扩选”走的边界完全一致，不会一处能走一处走不动（Bug3）。
     */
    private fun boundaryOf(unit: Unit, text: String, pos: Int, dir: Int): Int {
        val len = text.length
        val p = pos.coerceIn(0, len)
        if (len == 0) return -1
        when (unit) {
            Unit.CHAR -> {
                val bi = BreakIterator.getCharacterInstance(Locale.getDefault())
                bi.setText(text)
                if (dir > 0) {
                    val n = bi.following(p)
                    return if (n == BreakIterator.DONE || n > len) -1 else n
                }
                val n = bi.preceding(p)
                return if (n == BreakIterator.DONE || n < 0) -1 else n
            }
            Unit.SENTENCE -> {
                val symbols = sentenceSymbols()
                fun symAt(i: Int) = i in 0 until len && symbols.contains(text[i].toString())
                fun startBoundary(i: Int): Boolean {
                    if (i < 0 || i >= len) return false
                    if (symAt(i)) return false
                    return i == 0 || symAt(i - 1)
                }
                fun endBoundary(i: Int): Boolean {
                    if (i <= 0 || i > len) return false
                    return symAt(i - 1) || i == len
                }
                return if (dir > 0) {
                    var i = p
                    while (i < len && symAt(i) && !startBoundary(i)) i++
                    if (i >= len) -1 else {
                        var j = i + 1
                        while (j < len && !endBoundary(j)) j++
                        j
                    }
                } else {
                    var i = p
                    while (i > 0 && symAt(i - 1) && !endBoundary(i)) i--
                    if (i <= 0) -1 else {
                        var j = i - 1
                        while (j > 0 && !startBoundary(j)) j--
                        j
                    }
                }
            }
            Unit.PARAGRAPH -> {
                fun nl(i: Int) = i in 0 until len && text[i] == '\n'
                fun startBoundary(i: Int): Boolean {
                    if (i < 0 || i >= len) return false
                    if (nl(i)) return false
                    return i == 0 || nl(i - 1)
                }
                fun endBoundary(i: Int): Boolean {
                    if (i <= 0 || i > len) return false
                    return nl(i - 1) || i == len
                }
                return if (dir > 0) {
                    var i = p
                    while (i < len && nl(i) && !startBoundary(i)) i++
                    if (i >= len) -1 else {
                        var j = i + 1
                        while (j < len && !endBoundary(j)) j++
                        j
                    }
                } else {
                    var i = p
                    while (i > 0 && nl(i - 1) && !endBoundary(i)) i--
                    if (i <= 0) -1 else {
                        var j = i - 1
                        while (j > 0 && !startBoundary(j)) j--
                        j
                    }
                }
            }
            Unit.LINE -> return -1
        }
    }

    /** 选择模式下删掉选中的一段（连选带删） */
    private fun deleteSelection(ic: InputConnection, s: Snapshot): Boolean {
        if (!s.hasSelection) return false
        val lo = s.offset + s.indexOf(minOf(s.start, s.end))
        val hi = s.offset + s.indexOf(maxOf(s.start, s.end))
        val text = s.text.substring((lo - s.offset).coerceIn(0, s.text.length), (hi - s.offset).coerceIn(0, s.text.length))
        clearSelectionState("删除选中文本")
        ic.setSelection(lo, hi)
        ic.commitText("", 1)
        main.postDelayed({ Speaker.speak("已删除 ${text.length} 个字") }, 70)
        return true
    }

    private fun spoken(c: Char?): String = when (c) {
        null, '\u0000' -> "空"
        ' ' -> "空格"
        '\n' -> "换行"
        else -> Names.spoken(c.toString())
    }

    // ---------------- 行 ----------------

    fun prevLine() = lineStep(KeyEvent.KEYCODE_DPAD_UP)
    fun nextLine() = lineStep(KeyEvent.KEYCODE_DPAD_DOWN)

    private fun lineStep(code: Int) {
        Candidates.onCursorMove()
        val ic = ic() ?: return
        val s = snapshot(ic)
        // 选择态：上下移行变成“往上一行/下一行扩选”
        if (selecting) {
            if (s == null) {
                arrowShift(code)
                return
            }
            if (stillSelecting(s)) {
                extendSelection(ic, s, if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1, Unit.LINE)
                return
            }
            // 选择态失效：继续走普通移行
        }
        if (s != null && s.hasSelection) {
            val p = s.indexOf(s.start)
            ic.setSelection(s.offset + p, s.offset + p)
        }
        arrow(code)
        // 上下移一行由编辑框自己算，要等一下位置才更新
        main.postDelayed({ readLineAtCaret() }, 90)
    }

    /**
     * 移行之后念光标所在的那一行。
     *
     * 以前走 readLine(quiet = true)，读屏模式下直接 return——读屏对“上下移一行”这种
     * 编辑框自己算出来的位移并不会主动念，用户听到的就是“移了一行，什么都没说”。
     */
    private fun readLineAtCaret() {
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        val caret = s.indexOf(s.caret)
        var a = caret
        while (a > 0 && s.text[a - 1] != '\n') a--
        var b = caret
        while (b < s.text.length && s.text[b] != '\n') b++
        val line = s.text.substring(a, b).trim()
        if (line.isEmpty()) {
            Speaker.echo("空行")
            return
        }
        Speaker.echo(if (line.length <= 200) line else line.take(200) + "，后面还有几个字")
    }

    fun lineStart() = jumpTo(true)
    fun lineEnd() = jumpTo(false)

    private fun jumpTo(start: Boolean) {
        Candidates.onCursorMove()
        val code = if (start) KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END
        send(code)
        Speaker.speak(if (start) "行首" else "行尾")
        main.postDelayed({ readLine(quiet = true) }, 80)
    }

    // ---------------- 句 ----------------

    fun prevSentence() = sentenceStep(-1)
    fun nextSentence() = sentenceStep(1)

    /**
     * 按句移动：照搬天坦输入法 SentenceTextSegmentIterator 的算法。
     * 句子的边界是用户配置的标点（sentenceSymbols，默认 。，；？！.!? 和换行），
     * 不是按固定字符硬切。这样“3.14”里的点、小数点都不会被当成句号。
     */
    private fun sentenceStep(dir: Int) {
        Candidates.onCursorMove()
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        // 选择态：按句扩选（以前只有按字能扩选，按句按段走普通移动，用户以为多选坏了，Bug3）
        if (selecting && stillSelecting(s)) {
            extendSelection(ic, s, dir, Unit.SENTENCE)
            return
        }
        val t = s.text
        val caret = s.indexOf(s.caret)
        val len = t.length
        if (len == 0) return
        val p = boundaryOf(Unit.SENTENCE, t, caret, dir)
        if (p < 0) {
            speakBoundary(dir, s)
            return
        }
        ic.setSelection(s.offset + p, s.offset + p)
        // 念“移动到的这一句”本身（用户反馈：按句移动没有声音，只有语音库模式偶尔能念到）
        Speaker.hushReader()
        val sentence = segmentAt(t, p, para = false)
        // 空句（连续标点、只有换行）不念“上一句/下一句”这种方向名——用户明确说这类提示是多余的，
        // 只会和真正要听的内容抢话。有内容就念内容
        if (sentence.isNotEmpty()) {
            Speaker.echo(if (sentence.length <= 200) sentence else sentence.take(200) + "，后面还有几个字")
        }
    }

    // ---------------- 段 ----------------

    fun prevParagraph() = paragraphStep(-1)
    fun nextParagraph() = paragraphStep(1)

    /** 段落按换行分：照搬天坦 ParagraphTextSegmentIterator——换行就是段的分界 */
    private fun paragraphStep(dir: Int) {
        Candidates.onCursorMove()
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        // 选择态：按段扩选
        if (selecting && stillSelecting(s)) {
            extendSelection(ic, s, dir, Unit.PARAGRAPH)
            return
        }
        val caret = s.indexOf(s.caret)
        val p = boundaryOf(Unit.PARAGRAPH, s.text, caret, dir)
        if (p < 0) {
            speakBoundary(dir, s)
            return
        }
        ic.setSelection(s.offset + p, s.offset + p)
        Speaker.hushReader()
        val para = segmentAt(s.text, p, para = true)
        // 空的（连着的空行）不念“上一段/下一段”，见同一版的说明
        if (para.isNotEmpty()) {
            Speaker.echo(if (para.length <= 200) para else para.take(200) + "，后面还有几个字")
        }
    }

    // ---------------- 整篇的开头、结尾 ----------------

    fun docStart() {
        Candidates.onCursorMove()
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        ic.setSelection(s.offset, s.offset)
        Speaker.speak("已到开头")
        main.postDelayed({ readLine(quiet = true) }, 80)
    }

    fun docEnd() {
        Candidates.onCursorMove()
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        val e = s.offset + s.text.length
        ic.setSelection(e, e)
        Speaker.speak("已到结尾")
        main.postDelayed({ readLine(quiet = true) }, 80)
    }

    // ---------------- 朗读 ----------------

    fun readLine() = readLine(quiet = false)

    private fun readLine(quiet: Boolean) {
        // 自动跟读（移动光标、移行之后）时，播报走读屏就交给读屏，模块不再重复一遍整行
        if (quiet && Speaker.readerWillSpeak()) return
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        val caret = s.indexOf(s.caret)
        var a = caret
        while (a > 0 && s.text[a - 1] != '\n') a--
        var b = caret
        while (b < s.text.length && s.text[b] != '\n') b++
        val line = s.text.substring(a, b).trim()
        if (line.isEmpty()) {
            if (!quiet) Speaker.speak("空行")
            return
        }
        Speaker.speak(line)
    }

    fun readAll() {
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        val t = s.text.trim()
        if (t.isEmpty()) return Speaker.speak("没有文字")
        if (t.length <= 300) Speaker.speak(t)
        else Speaker.speak(t.substring(0, 300) + "，后面还有 ${t.length - 300} 个字")
    }

    // ---------------- 编辑 ----------------

    /** 删除光标左边一个字，并把删掉的字读出来；选择模式下删掉选中的一段 */
    fun deleteLeft() {
        val ic = ic() ?: return
        val s = snapshot(ic)
        if (s != null && deleteSelection(ic, s)) return
        val before = charBefore()
        send(KeyEvent.KEYCODE_DEL)
        main.postDelayed({ Speaker.speak(if (before == null) "已删除" else "已删除 ${spoken(before)}") }, 70)
    }

    /** 删除光标右边一个字 */
    fun deleteRight() {
        val after = charAfter()
        send(KeyEvent.KEYCODE_FORWARD_DEL)
        main.postDelayed({ Speaker.speak(if (after == null) "已删除" else "已删除 ${spoken(after)}") }, 70)
    }

    private fun charBefore(): Char? = try {
        val ic = ic() ?: return null
        val s = snapshot(ic) ?: return null
        val p = s.indexOf(s.caret)
        if (p <= 0) null else s.text.getOrNull(p - 1)
    } catch (_: Throwable) {
        null
    }

    private fun charAfter(): Char? = try {
        val ic = ic() ?: return null
        val s = snapshot(ic) ?: return null
        val p = s.indexOf(s.caret)
        s.text.getOrNull(p)
    } catch (_: Throwable) {
        null
    }

    /** 朗读编辑框里的字（太长只读前面一段） */
    fun readEditor() = readAll()

    /** 朗读选中的文字；没选中就读光标所在行 */
    fun readSelection() {
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        if (!s.hasSelection) {
            readLine()
            return
        }
        val lo = (s.indexOf(minOf(s.start, s.end))).coerceIn(0, s.text.length)
        val hi = (s.indexOf(maxOf(s.start, s.end))).coerceIn(0, s.text.length)
        val t = s.text.substring(lo, hi).trim()
        if (t.isEmpty()) Speaker.speak("选中的是空的") else Speaker.speak("选中 ${t.take(200)}")
    }

    /** 撤销上一次编辑：走编辑框自己的 undo，不支持就退到“按住删除键”那种 */
    fun undo() {
        val ic = ic() ?: return
        val ok = try {
            ic.performContextMenuAction(android.R.id.undo)
        } catch (t: Throwable) {
            L.hookError("EditActions.undo", t)
            false
        }
        clearSelectionState("撤销")
        if (ok) {
            Speaker.speak("已撤销")
            main.postDelayed({ readLine(quiet = true) }, 90)
            main.postDelayed({ EditOptions.refresh("撤销") }, 140)
        } else {
            Speaker.speak("这个输入框不支持撤销")
        }
    }

    /** 清空文本：全选再删，一次清干净 */
    fun clearText() {
        val ic = ic() ?: return
        clearSelectionState("清空文本")
        val s = snapshot(ic)
        if (s == null || s.text.isEmpty()) {
            Speaker.speak("编辑框是空的")
            return
        }
        val n = s.text.length
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
        main.postDelayed({ Speaker.speak("已清空 $n 个字") }, 90)
        // 清空了：编辑菜单回到“粘贴 / 收起键盘”，默认停在粘贴
        main.postDelayed({ EditOptions.refresh("清空文本") }, 140)
    }

    /** 字符统计 */
    fun charCount() {
        val ic = ic() ?: return
        val s = snapshot(ic) ?: return
        val t = s.text
        if (t.isEmpty()) {
            Speaker.speak("编辑框是空的")
            return
        }
        val noSpace = t.count { !it.isWhitespace() }
        if (s.hasSelection) {
            val n = kotlin.math.abs(s.end - s.start)
            Speaker.speak("选中 $n 个字，全文 ${t.length} 个字")
        } else {
            Speaker.speak("共 ${t.length} 个字，不含空格 $noSpace 个")
        }
    }

    /** 输入提示：编辑框自己写的提示语（“请输入手机号”这种） */
    fun hint() {
        val ei = try {
            ViewA11yHooks.service?.currentInputEditorInfo
        } catch (_: Throwable) {
            null
        }
        val h = hintOf(ei)
        Speaker.speak(if (h.isNullOrBlank()) "这个输入框没有提示" else h)
    }

    private fun hintOf(ei: EditorInfo?): String? {
        if (ei == null) return null
        val direct = try {
            ei.hintText?.toString()
        } catch (_: Throwable) {
            null
        }
        if (!direct.isNullOrBlank()) return direct
        return try {
            // hintTextResId 是 @hide 字段，只能反射拿
            val f = EditorInfo::class.java.getField("hintTextResId")
            val id = f.getInt(ei)
            val res = ViewA11yHooks.service?.resources
            if (id != 0 && res != null) res.getText(id)?.toString() else null
        } catch (_: Throwable) {
            null
        }
    }

    fun enter() {
        send(KeyEvent.KEYCODE_ENTER)
        Speaker.speak("换行")
    }

    /** 全选：选中全部文字并进入选择态，之后左右滑可扩选/缩选、剪切复制删除 */
    fun selectAll() {
        val ic = ic() ?: return
        val before = snapshot(ic)
        // 先按“选上了”处理：校验要等一下才能做，用户这期间滑一下也是扩选，不会变成移动光标
        selecting = true
        anchor = before?.offset ?: 0
        // 零长度选择态的合法形态：活动端先跟固定端重合，等核对完选区再挪到末尾
        activeEnd = anchor
        val ok = try {
            ic.performContextMenuAction(android.R.id.selectAll)
        } catch (t: Throwable) {
            L.hookError("EditActions.selectAll", t)
            false
        }
        // 有的编辑框 performContextMenuAction 返回 false 但其实选上了：看结果说话
        main.postDelayed({
            val s = snapshot(ic)
            val selected = if (s != null && s.hasSelection) kotlin.math.abs(s.end - s.start) else 0
            val total = before?.text?.length ?: (s?.text?.length ?: 0)
            val really = s != null && s.hasSelection && selected > 0
            if (really || ok) {
                selecting = true
                anchor = if (s != null) s.offset + s.indexOf(minOf(s.start, s.end)) else 0
                // 全选后活动端放在末尾：向左滑是从末尾往回缩，向右滑才报“已选到末尾”
                activeEnd = if (s != null) s.offset + s.indexOf(maxOf(s.start, s.end)) else anchor
                Speaker.speak("已全选，共 $selected 个字")
                L.i("全选：选中 $selected/$total 个字")
            } else {
                clearSelectionState("全选没生效，编辑框没有选中内容")
                Speaker.speak("当前编辑框不支持全选")
                L.w("全选没生效：编辑框没有选中内容，返回值=$ok")
            }
        }, 90)
    }

    fun copy() {
        val ic = ic() ?: return
        val s = snapshot(ic)
        val selected = s?.let {
            if (!it.hasSelection) "" else it.text.substring(
                it.indexOf(minOf(it.start, it.end)).coerceIn(0, it.text.length),
                it.indexOf(maxOf(it.start, it.end)).coerceIn(0, it.text.length),
            )
        } ?: ""
        val ok = try {
            ic.performContextMenuAction(android.R.id.copy)
        } catch (t: Throwable) {
            L.hookError("EditActions.copy", t)
            false
        }
        main.postDelayed({
            // 复制成功与否不看返回值：有的编辑框返回 false 但已经复制好了，看剪贴板里是不是这段
            val clip = clipboardText()
            val copied = ok || (selected.isNotEmpty() && clip != null && (clip == selected || clip.contains(selected.take(40))))
            Speaker.speak(
                when {
                    copied -> "已复制"
                    selected.isEmpty() -> "没有选中的文字"
                    else -> "当前编辑框不支持复制"
                }
            )
        }, 100)
        if (ok) finishSelection()
    }

    fun cut() {
        val ic = ic() ?: return
        val before = snapshot(ic)?.text ?: ""
        val ok = try {
            ic.performContextMenuAction(android.R.id.cut)
        } catch (t: Throwable) {
            L.hookError("EditActions.cut", t)
            false
        }
        main.postDelayed({
            val after = snapshot(ic)?.text ?: before
            val cutDone = ok || after != before
            Speaker.speak(if (cutDone) "已剪切" else "当前编辑框不支持剪切")
            if (cutDone) finishSelection()
        }, 100)
    }

    /** 复制/剪切/删除/取消选择后退出选择态 */
    private fun finishSelection() {
        clearSelectionState("操作完成")
    }

    fun paste() {
        val ic = ic() ?: return
        val before = snapshot(ic)?.text ?: ""
        val ok = try {
            ic.performContextMenuAction(android.R.id.paste)
        } catch (t: Throwable) {
            L.hookError("EditActions.paste", t)
            false
        }
        // 粘贴到底成没成，看编辑框里的字有没有变——
        // 有的编辑框（豆包的聊天输入框、Flutter 应用）返回 false，其实已经粘进去了，
        // 老版本照着返回值读“当前编辑框不支持”，用户明明看到字进来了（Bug6）。
        checkPaste(ic, before, ok, 0)
    }

    private fun checkPaste(ic: InputConnection, before: String, ok: Boolean, round: Int) {
        main.postDelayed({
            val after = snapshot(ic)
            val now = after?.text ?: before
            val changed = after != null && now != before
            if (changed || ok) {
                val added = when {
                    now.length > before.length -> now.length - before.length
                    else -> 0
                }
                val sel = try {
                    ic.getSelectedText(0)?.toString()
                } catch (_: Throwable) {
                    null
                }
                val tail = if (now.length > before.length) now.takeLast(added.coerceAtMost(24)) else sel
                // 读屏模式下读屏对“整段文字被替换”这件事经常一声不吭（用户反馈粘贴没播报），
                // 所以粘贴结果一律由模块自己发一条播报（仍然是读屏念，内容由模块给）
                Speaker.unmute()
                Speaker.echo(if (!tail.isNullOrBlank()) "已粘贴 $tail" else "已粘贴")
                L.i("粘贴成功：字数 ${before.length} → ${now.length}")
                // 粘进来字以后编辑菜单要换成完整那套（并从“粘贴”回到“双击跳行首行尾”）
                EditOptions.refresh("粘贴成功")
                return@postDelayed
            }
            // 慢一点的编辑框再等一次
            if (round < 2) {
                checkPaste(ic, before, ok, round + 1)
                return@postDelayed
            }
            Speaker.speak("当前编辑框不支持粘贴")
            L.w("粘贴没生效：编辑框文字没变化，返回值=$ok")
        }, if (round == 0) 110L else 260L)
    }

    /** 剪贴板里现在是什么（只用来判断复制有没有成功，读不到就当没有） */
    private fun clipboardText(): String? = try {
        val ctx = ViewA11yHooks.service
        val cm = ctx?.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    } catch (_: Throwable) {
        null
    }

    private fun menu(id: Int, done: String) {
        val ic = ic() ?: return
        val ok = try {
            ic.performContextMenuAction(id)
        } catch (t: Throwable) {
            L.hookError("EditActions.menu", t)
            false
        }
        Speaker.speak(if (ok) done else "当前编辑框不支持")
    }

    private fun arrow(code: Int) = send(code)

    private fun send(code: Int) {
        val ic = ic() ?: return
        val t = android.os.SystemClock.uptimeMillis()
        try {
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        } catch (e: Throwable) {
            L.hookError("EditActions.send", e)
        }
    }

    /** 动作 id 是不是编辑类（这些交给本对象执行） */
    fun handles(action: Int): Boolean = when (action) {
        ConfigSpec.ACT_PREV_CHAR, ConfigSpec.ACT_NEXT_CHAR, ConfigSpec.ACT_PREV_LINE, ConfigSpec.ACT_NEXT_LINE,
        ConfigSpec.ACT_LINE_START, ConfigSpec.ACT_LINE_END, ConfigSpec.ACT_PREV_SENTENCE, ConfigSpec.ACT_NEXT_SENTENCE,
        ConfigSpec.ACT_PREV_PARA, ConfigSpec.ACT_NEXT_PARA,
        ConfigSpec.ACT_DOC_START, ConfigSpec.ACT_DOC_END,
        ConfigSpec.ACT_SELECT_TEXT, ConfigSpec.ACT_DESELECT,
        ConfigSpec.ACT_DELETE_LEFT, ConfigSpec.ACT_DELETE_RIGHT, ConfigSpec.ACT_ENTER,
        ConfigSpec.ACT_CLEAR_TEXT, ConfigSpec.ACT_UNDO,
        ConfigSpec.ACT_READ_EDITOR, ConfigSpec.ACT_READ_LINE, ConfigSpec.ACT_READ_ALL, ConfigSpec.ACT_READ_SELECTION,
        ConfigSpec.ACT_CHAR_COUNT, ConfigSpec.ACT_HINT,
        ConfigSpec.ACT_SELECT_ALL, ConfigSpec.ACT_COPY, ConfigSpec.ACT_PASTE, ConfigSpec.ACT_CUT,
        -> true
        else -> false
    }

    /** 执行编辑类动作；失败返回 false */
    fun run(action: Int): Boolean {
        when (action) {
            ConfigSpec.ACT_PREV_CHAR -> prevChar()
            ConfigSpec.ACT_NEXT_CHAR -> nextChar()
            ConfigSpec.ACT_PREV_LINE -> prevLine()
            ConfigSpec.ACT_NEXT_LINE -> nextLine()
            ConfigSpec.ACT_PREV_PARA -> prevParagraph()
            ConfigSpec.ACT_NEXT_PARA -> nextParagraph()
            ConfigSpec.ACT_LINE_START -> lineStart()
            ConfigSpec.ACT_LINE_END -> lineEnd()
            ConfigSpec.ACT_DOC_START -> docStart()
            ConfigSpec.ACT_DOC_END -> docEnd()
            ConfigSpec.ACT_PREV_SENTENCE -> prevSentence()
            ConfigSpec.ACT_NEXT_SENTENCE -> nextSentence()
            ConfigSpec.ACT_SELECT_TEXT -> beginSelect()
            ConfigSpec.ACT_DESELECT -> deselect()
            ConfigSpec.ACT_DELETE_LEFT -> deleteLeft()
            ConfigSpec.ACT_DELETE_RIGHT -> deleteRight()
            ConfigSpec.ACT_CLEAR_TEXT -> clearText()
            ConfigSpec.ACT_UNDO -> undo()
            ConfigSpec.ACT_ENTER -> enter()
            ConfigSpec.ACT_READ_EDITOR -> readEditor()
            ConfigSpec.ACT_READ_LINE -> readLine()
            ConfigSpec.ACT_READ_ALL -> readAll()
            ConfigSpec.ACT_READ_SELECTION -> readSelection()
            ConfigSpec.ACT_CHAR_COUNT -> charCount()
            ConfigSpec.ACT_HINT -> hint()
            ConfigSpec.ACT_SELECT_ALL -> selectAll()
            ConfigSpec.ACT_COPY -> copy()
            ConfigSpec.ACT_PASTE -> paste()
            ConfigSpec.ACT_CUT -> cut()
            else -> return false
        }
        return true
    }
}
