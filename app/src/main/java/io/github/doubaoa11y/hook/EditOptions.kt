package io.github.doubaoa11y.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.doubaoa11y.ConfigSpec

/**
 * 全屏手势里的“编辑选项”。
 *
 * 单指上滑 = 上一个选项，单指下滑 = 下一个选项，单指双击 = 执行当前这一项。
 *
 * 选项是一个完整列表（用户可在设置里自由增删排序，出厂默认是“默认/全选文本/选择文本/粘贴/清空文本/收起键盘”）：
 * 1. 基础列表：用户配的那串（Config 里的 editOptions）；
 * 2. 选择态：执行“全选文本”或“选择文本”之后，上下滑只浏览“取消选择”这一项，
 *    剪切、复制、跳行首行尾、收起键盘这些都不出现；执行剪切/复制/删除/取消选择后退出选择态。
 *
 * 切换时只读当前项名字，不报“第几项共几项”。
 */
object EditOptions {

    private val main = Handler(Looper.getMainLooper())

    private var index = 0

    /** 最近一次切换选项的时间：刚切完（2 秒内）双击就是在执行它，不去抢候选 */
    private var navAt = 0L

    /** 动态项（剪切/复制/取消选择）现在有没有展开在列表里 */
    private var dynamicOpen = false

    /**
     * 编辑框现在是空的。
     *
     * 用户要求：空编辑框时只留「粘贴」「收起键盘」两项，默认停在「粘贴」，
     * 直接双击就把剪贴板内容贴进来；**只要有字，不管字是怎么进去的**
     * （打字、语音、粘贴、长按粘贴、自动纠错、别的应用改写…），
     * 立刻换回完整列表并停在「双击跳转行首行尾」。
     *
     * 2.0.0 第一版只在上屏/删除/粘贴这几个节点查，覆盖面太窄：
     * 语音说完、豆包自己把字写进编辑框这些路径根本不经过那几处，
     * 于是“都输入完了，轮盘还停在粘贴/收起键盘”（用户反馈）。
     * 2.0.1 起除了节点触发，键盘显示期间还有一条**兜底轮询**（[tick]），
     * 空着时 300 毫秒问一次、有字时 1.2 秒问一次，任何方式进来都跑不掉。
     */
    @Volatile
    private var empty = true

    /** 键盘显示中：只有这时候才值得盯着编辑框 */
    @Volatile
    private var keyboardUp = false

    /** 兜底轮询开着没有 */
    private var watching = false

    /** 连续几次问到“空”才真的算空（输入连接一瞬间拿不到时别误判） */
    private var emptyHits = 0

    /** 空着的时候查得勤一点：要尽快从“粘贴/收起键盘”切回完整列表 */
    private const val WATCH_EMPTY_MS = 300L

    /** 有字的时候只需要发现“被清空了”，慢一点省电省跨进程 */
    private const val WATCH_FULL_MS = 1200L

    /** 空编辑框时的选项：只留粘贴和收起键盘 */
    private val emptyIds: List<Int> = listOf(ConfigSpec.ACT_PASTE, ConfigSpec.ACT_HIDE)

    /** 基础列表（不含动态项），就是用户在设置里配的那串 */
    private val baseIds: List<Int>
        get() = ConfigSpec.parseEditOptions(Config.s(ConfigSpec.EDIT_OPTIONS_KEY))

    /**
     * 当前完整列表：
     * - 选择态：剪切、复制、删除文本、取消选择；
     * - 编辑框空的：粘贴、收起键盘；
     * - 其他：用户配的那串（出厂是 默认/全选文本/选择文本/粘贴/清空文本/收起键盘）。
     */
    private val ids: List<Int>
        get() = when {
            dynamicOpen -> ConfigSpec.EDIT_OPTIONS_DYNAMIC.map { it.second }
            empty -> emptyIds
            else -> baseIds
        }

    val size: Int get() = ids.size

    /** 编辑框现在是空的（给设置页状态行和手势用） */
    val editorEmpty: Boolean get() = empty

    /**
     * 重新问一次编辑框有没有内容（关键节点调用：换输入框、键盘显示、上屏、删除、粘贴…）。
     *
     * 真正的判断收敛在 [apply]，这里只负责跨进程问一次。
     */
    fun refresh(why: String) {
        if (!FullscreenMode.wanted || !FullscreenMode.active) return
        apply(EditActions.editorEmpty(), why)
    }

    /**
     * 键盘显示：开始盯编辑框。
     *
     * 首查故意晚一点点（[ServiceHooks] 那边已经延迟过），因为弹键盘的瞬间
     * `getCurrentInputConnection` 可能还没接上，太早问会一律判成空。
     */
    fun onKeyboardShown() {
        keyboardUp = true
        emptyHits = 0
        refresh("键盘显示")
        startWatch()
    }

    /** 键盘收起：停掉轮询，不留下一条空转的延迟任务 */
    fun onKeyboardHidden() {
        keyboardUp = false
        stopWatch()
    }

    private fun startWatch() {
        if (watching) return
        watching = true
        main.removeCallbacks(tick)
        main.postDelayed(tick, WATCH_EMPTY_MS)
        L.i("编辑选项开始盯着编辑框（空=${empty}）")
    }

    private fun stopWatch() {
        if (!watching) return
        watching = false
        main.removeCallbacks(tick)
        emptyHits = 0
        L.i("编辑选项停止盯着编辑框")
    }

    /**
     * 兜底轮询：不管文字是打字、语音、长按粘贴、自动纠错还是别的应用写进来的，
     * 这里都能发现，然后走和 [refresh] 同一套切换逻辑。
     */
    private val tick = object : Runnable {
        override fun run() {
            if (!watching) return
            if (!keyboardUp || !FullscreenMode.wanted || !FullscreenMode.active) {
                stopWatch()
                return
            }
            try {
                val nowEmpty = EditActions.editorEmpty()
                if (nowEmpty != empty) {
                    if (!nowEmpty) {
                        // 有字了：立刻切，一次都不等（用户要的就是“一出现文字就变”）
                        emptyHits = 0
                        apply(false, "轮询发现编辑框有内容")
                    } else {
                        // 变空：连续两次都问到空才切，防输入连接瞬时为空误判
                        emptyHits++
                        if (emptyHits >= 2) {
                            emptyHits = 0
                            apply(true, "轮询发现编辑框空了")
                        }
                    }
                } else {
                    emptyHits = 0
                }
            } catch (t: Throwable) {
                L.hookError("EditOptions 轮询", t)
            }
            if (watching) main.postDelayed(this, if (empty) WATCH_EMPTY_MS else WATCH_FULL_MS)
        }
    }

    /** 切换列表：变空停在“粘贴”，有字停在“默认”，顺带清掉选择态和“刚切过”的标记 */
    private fun apply(nowEmpty: Boolean, why: String) {
        if (empty == nowEmpty) {
            // 空着的时候列表只有两项，index 别越界
            if (nowEmpty && index >= emptyIds.size) index = 0
            return
        }
        empty = nowEmpty
        dynamicOpen = false
        // 变空：停在“粘贴”（第一步就能直接双击粘贴）；有字：停在“默认”（双击跳行首行尾）
        index = if (nowEmpty) 0 else baseIds.indexOf(ConfigSpec.ACT_DEFAULT).let { if (it < 0) 0 else it }
        navAt = 0L
        L.i("编辑框${if (nowEmpty) "变空" else "有内容"}（$why），编辑选项：${ids.joinToString { ConfigSpec.actionName(it) }}")
    }

    /** 刚打了字：一定是“有内容”，直接切到完整列表并回到默认项，不用跨进程问 */
    fun markTyped() {
        emptyHits = 0
        if (!empty) {
            backToDefault()
            return
        }
        empty = false
        dynamicOpen = false
        backToDefault()
    }

    /** 换输入框、键盘收起时回到第一项，并把动态项收起来 */
    fun reset() {
        index = 0
        navAt = 0L
        dynamicOpen = false
    }

    /** 是不是在选择态（全选/选择文本之后） */
    val isSelecting: Boolean get() = dynamicOpen

    /** 进入选择态：动态项变成“剪切/复制/删除文本/取消选择”，光标停在第一项（剪切）上 */
    fun enterSelection() {
        dynamicOpen = true
        index = 0
    }

    /** 退出选择态：收起动态项，回到基础列表第一项 */
    fun selectionEnded() {
        dynamicOpen = false
        index = 0
    }

    /**
     * 键盘区打了字：轮盘回到“双击跳转行首行尾”（编辑选项里的“默认”项）。
     *
     * 用户打完字再双击，要的是跳行首/行尾，不是上一次上下滑浏览到的“粘贴”“清空文本”；
     * 全屏下打字时手指一直在键盘区，根本看不到轮盘停在哪，所以打完一按就该回到默认那一项。
     * 如果刚才在选择态里，打字会把选区替换掉，选择态跟着静默退出。
     */
    fun keyTyped() {
        val wasDynamic = dynamicOpen
        dynamicOpen = false
        // 打了字编辑框就有内容了：列表切回完整那套，并停在“双击跳转行首行尾”
        empty = false
        backToDefault()
        // 刚打过字，不算“刚切过编辑选项”，候选优先重新生效
        navAt = 0L
        if (wasDynamic) EditActions.leaveSelectionQuiet()
    }

    val currentName: String
        get() {
            val id = ids.getOrNull(index) ?: return "默认"
            // 选择态下只有“取消选择”这一项，用动态项名字；普通状态用动作名
            if (dynamicOpen) {
                return ConfigSpec.dynamicName(id) ?: ConfigSpec.actionName(id)
            }
            return ConfigSpec.actionName(id)
        }

    val currentAction: Int
        get() = ids.getOrNull(index) ?: ConfigSpec.ACT_DEFAULT

    /** 刚切换过选项（2 秒内） */
    fun isFresh(): Boolean = navAt > 0 && SystemClock.uptimeMillis() - navAt < 2000

    fun next(): Boolean {
        val n = ids.size
        if (n == 0) return false
        index = if (index + 1 >= n) 0 else index + 1
        navAt = SystemClock.uptimeMillis()
        announce()
        return true
    }

    fun prev(): Boolean {
        val n = ids.size
        if (n == 0) return false
        index = if (index <= 0) n - 1 else index - 1
        navAt = SystemClock.uptimeMillis()
        announce()
        return true
    }

    /** 执行当前选项；返回 false 表示这个动作在当前编辑框做不了 */
    fun run(): Boolean {
        val n = ids.size
        if (n == 0) return false
        if (index >= n) index = 0
        val action = ids[index]
        navAt = SystemClock.uptimeMillis()
        L.i("执行编辑选项：${ConfigSpec.dynamicName(action) ?: ConfigSpec.actionName(action)}")
        val ok = GestureMap.run(action)
        if (!ok) return false
        // 全选文本 / 选择文本：执行成功后进入选择态，动态项变成剪切/复制/删除文本/取消选择
        if (action == ConfigSpec.ACT_SELECT_ALL || action == ConfigSpec.ACT_SELECT_TEXT) {
            enterSelection()
            return true
        }
        // 剪切 / 复制 / 删除文本 / 取消选择：执行完退出选择态，回到基础列表第一项
        if (dynamicOpen && action in setOf(
                ConfigSpec.ACT_CUT, ConfigSpec.ACT_COPY,
                ConfigSpec.ACT_DELETE_LEFT, ConfigSpec.ACT_DESELECT,
            )) {
            selectionEnded()
            return true
        }
        // 其余动作都是“做一次就完”的（粘贴、清空文本、收起键盘、朗读…）：
        // 做完回到“默认”那一项（双击跳行首行尾）。
        // 用户反馈：上下滑选了“粘贴”、双击粘贴完，轮盘还停在“粘贴”上，
        // 想跳行首行尾还得再滑回去，很容易误粘第二次。
        backToDefault()
        return true
    }

    /** 回到“默认”项（双击跳行首行尾）；列表里没有默认项就回到第一项 */
    private fun backToDefault() {
        dynamicOpen = false
        val list = baseIds
        index = list.indexOf(ConfigSpec.ACT_DEFAULT).let { if (it < 0) 0 else it }
        navAt = 0L
    }

    private fun announce() {        Speaker.speak(currentName)
    }

    /** 给“查询模块状态”用 */
    fun describe(): String = "当前编辑选项：$currentName"
}
