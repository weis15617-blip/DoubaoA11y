package io.github.doubaoa11y.hook

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import io.github.doubaoa11y.ConfigSpec

/**
 * 全屏手势里跟“键盘”有关的动作：上屏候选、清空候选、发送、收起键盘、
 * 切键盘、剪贴板、常用语、菜单、编辑面板、语音输入。
 *
 * 这些不走编辑框，走的是豆包键盘自己的按键和工具栏按钮，所以和 EditActions 分开两份。
 */
object KeyboardOps {
    private fun root(): View? = try {
        ViewA11yHooks.service?.window?.window?.decorView
    } catch (t: Throwable) {
        null
    }

    private fun service() = try {
        ViewA11yHooks.service
    } catch (t: Throwable) {
        null
    }

    private fun ic() = try {
        ViewA11yHooks.service?.currentInputConnection
    } catch (t: Throwable) {
        null
    }

    /** 上屏：打拼音时点空格，把选中的候选写进编辑框 */
    fun commit(): Boolean {
        if (!Candidates.composing) {
            Speaker.speak("现在没有候选")
            return false
        }
        if (VirtualKeys.tapKeyNamed("空格")) {
            L.i("全屏手势：上屏候选")
            return true
        }
        Speaker.speak("没找到空格键")
        return false
    }

    /** 清空候选：把组字区清掉，拼音还没上屏时用 */
    fun clearCandidate(): Boolean {
        val ic = ic()
        if (ic != null) {
            try {
                ic.setComposingText("", 1)
                ic.finishComposingText()
                Candidates.reset("清空候选")
                L.i("全屏手势：清空候选")
                // 用户反馈：上滑清空候选栏之后一点声音都没有，不知道到底清没清掉
                Speaker.speak("候选栏已清空")
                return true
            } catch (t: Throwable) {
                L.hookError("KeyboardOps.clearCandidate", t)
            }
        }
        Speaker.speak("现在没有候选")
        return false
    }

    /**
     * 发送（1.2.1 重做）。
     *
     * 老版本先去键盘上找名字叫“发送”的键——豆包只有开了“回车键发送消息”、
     * 并且输入框支持发送时，回车键才会画成“发送”，不然就找不到，发送手势直接哑火（Bug4）；
     * 换一种输入框（搜索框、聊天框）行为又不一样，还得靠用户自己配关键词。
     *
     * 现在按“应用自己的发送”优先，完全不看豆包的设置：
     * 1. 编辑框声明的回车动作（IME_ACTION_SEND/GO/SEARCH/DONE）→ 直接 [InputConnection.performEditorAction]，
     *    这是应用自己实现的“发送”，和豆包怎么画回车键没有关系；
     * 2. 没声明动作（微信、QQ 这类常见）就先试一次 IME_ACTION_SEND，认的应用会照发；
     * 3. 再退到键盘上按关键词找发送键（用户自定义的“发送、发送键”之类）；
     * 4. 最后才是普通回车，并且看编辑框有没有变化来判断到底发出去没有（Bug7：不再依赖回车键发送开关）。
     */
    /**
     * 豆包自己的发送。
     *
     * 反编译 1.5.5 核出来的入口（都在 KeyboardJni 里，PUBLIC STATIC，非 native）：
     * - `doSendAction()`：回车/发送键走的就是它，按 mCurrentEditboxAction（编辑框声明的回车动作）
     *   自己决定是发送、搜索还是换行，B 站那种要拦的也由它拦；
     * - `doToolbarSendAction()`：工具栏“发送”按钮走它，语音面板发送时会直接 performEditorAction(4)。
     *
     * 调用它等于“用户按了豆包的回车/发送键”，不需要“回车键发送消息”这个开关，
     * 也不依赖编辑框有没有画成“发送”两个字。
     */
    /**
     * 调豆包自己的发送 —— 就是工具栏上那个"发送"按钮按下去走的路。
     *
     * 反编译核对（豆包 1.5.5，`KeyboardJni`）：
     * 工具栏按钮的点击处理是 `IdleClickHandler` → `getCurrentEnterType()` →
     * `shouldUseVoicePanelSendAction()` → **`doToolbarSendAction()`**，它内部再按
     * `mCurrentEditboxAction` 走 `performEditorAction` / `sendDownUpKeyEvents`，
     * 还会顺手把工具栏的撤销按钮点亮。所以顺序必须是 doToolbarSendAction 优先。
     *
     * `doSendAction()` 是"编辑框那边的动作"版本（不带工具栏的收尾），作为第二选择。
     * 两个都是公开静态方法，不需要键盘上真有一个发送键，也不需要回车。
     */
    private fun doubaoSend(): Boolean {
        val c = JniHooks.jniClass ?: return false
        // 先定位一个签名匹配的入口；调用后即使抛异常，也不再尝试另一个，避免重复发送。
        val m = arrayOf("doToolbarSendAction", "doSendAction").firstNotNullOfOrNull {
            de.robv.android.xposed.XposedHelpers.findMethodExactIfExists(c, it)
        } ?: return false
        return try { m.invoke(null); true } catch (t: Throwable) { L.hookError("KeyboardOps.send", t); false }
    }

    fun send(): Boolean {
        if (!A11yState.recording) return false
        if (!doubaoSend()) {
            Speaker.speak("当前豆包版本无法执行发送，请使用应用自己的发送按钮")
            return false
        }
        return true
    }

    /** 显式动作：只调用一次发送入口，然后收起；不声称服务端已收到消息。 */
    fun sendAndHide(): Boolean = send() && hide()

    /** 返回：先收起豆包自己开着的面板（菜单、剪贴板、常用语），都没有就收起键盘 */
    fun back(): Boolean {
        if (EditPanel.isOpen) {
            EditPanel.close()
            Speaker.speak("已返回键盘")
            return true
        }
        if (CustomToolbar.editing()) {
            EditPanel.closeToolboxThenOpen()
            return true
        }
        if (StretchPanel.isOpen) {
            StretchPanel.close()
            Speaker.speak("已返回键盘")
            return true
        }
        return hide()
    }

    /** 收起键盘 */
    fun hide(): Boolean {
        val svc = service() ?: return false
        return try {
            svc.requestHideSelf(0)
            true
        } catch (t: Throwable) {
            L.hookError("KeyboardOps.hide", t)
            false
        }
    }

    /** 切到某种键盘：按按键名找，找不到就报没有 */
    fun switchTo(names: List<String>, what: String): Boolean {
        for (name in names) {
            if (VirtualKeys.tapKeyNamed(name)) {
                L.i("全屏手势：切到$what（$name）")
                return true
            }
        }
        Speaker.speak("没找到${what}的按键")
        return false
    }

    /** 打开工具栏上的某个按钮（剪贴板、常用语、菜单） */
    private fun tapToolbar(name: String, what: String): Boolean {
        if (VirtualKeys.tapKeyNamed(name)) return true
        val r = root() ?: return false
        val v = findByName(r, name) ?: run {
            Speaker.speak("没找到" + what + "按钮")
            return false
        }
        L.i("全屏手势：打开$what")
        VirtualKeys.tapViewOnScreen(r, v)
        return true
    }

    fun clipboard(): Boolean = tapToolbar("剪贴板", "剪贴板")

    fun phrases(): Boolean = tapToolbar("常用语", "常用语")

    fun menu(): Boolean = tapToolbar("菜单", "菜单")

    /** 编辑面板：菜单开着时先关菜单 */
    fun editPanel(): Boolean {
        if (EditPanel.isOpen) {
            EditPanel.close()
            Speaker.speak("已返回键盘")
            return true
        }
        if (CustomToolbar.editing()) {
            EditPanel.closeToolboxThenOpen()
            return true
        }
        val ok = EditPanel.open()
        if (!ok) Speaker.speak("打不开编辑面板")
        return ok
    }

    /** 正在用豆包原生语音说话（下一次执行就是结束） */
    @Volatile
    private var voiceOn = false

    /** 键盘收起时清掉语音状态 */
    fun resetVoice() {
        voiceOn = false
    }

    /** 豆包那边语音真的停了（用户自己点麦克风停的、或者豆包自己结束的）：状态对齐 */
    fun onVoiceStopped() {
        voiceOn = false
    }

    /**
     * 语音输入：调豆包自己的语音输入，不再模拟“长按空格”。
     *
     * 反编译核出来的入口：KeyboardJni.DoFunctionKey(int)，KEY_START_SPEECH=6、KEY_STOP_SPEECH=7，
     * 这就是豆包工具栏那个麦克风按钮按下去时走的路（DoFunctionKey 里 6/7 就是开关语音面板）。
     * 模拟长按空格的老办法要算坐标、要注入触摸、还依赖空格键的位置和停留时间设置，
     * 豆包换布局就哑火；直接调这个入口稳定得多，而且识别结果的显示、候选、上屏全按豆包自己的来。
     */
    private fun doubaoVoice(start: Boolean): Boolean {
        val c = JniHooks.jniClass ?: return false
        return try {
            de.robv.android.xposed.XposedHelpers.callStaticMethod(c, "DoFunctionKey", if (start) 6 else 7)
            L.i("语音：调用豆包 DoFunctionKey(${if (start) 6 else 7})")
            true
        } catch (t: Throwable) {
            L.w("语音：调用豆包语音入口失败：${t.javaClass.simpleName}")
            false
        }
    }

    fun voice(): Boolean {
        // 该开始还是该结束，以“豆包是不是真的在录音”为准：
        // 用户自己点键盘上的麦克风时模块不知道，只看 voiceOn 会出现“第一下反而是停止”
        val start = !Voice.isRecording() && !voiceOn
        if (start) Speaker.stop()
        if (doubaoVoice(start)) {
            voiceOn = start
            // ★ 开始说话时**绝对不能出声**（不发播报、不调语音库）：
            // 播报要过 TTS，TTS 一出声就抢音频焦点，豆包的识别当场就停——
            // 用户听到的就是"话还没说完就自动停了"。这里只放一声很短的音效，
            // 音效走 SoundPool 的 SFX 通道，不参与焦点竞争。
            if (start) {            } else {
                Speaker.speak("结束语音")
            }
            return true
        }
        // 豆包版本对不上时退回老办法（按住空格）
        val on = VirtualKeys.toggleVoice()
        voiceOn = on
        if (!on) Speaker.speak("结束语音")
        return true
    }

    fun handles(a: Int): Boolean = when (a) {
        ConfigSpec.ACT_COMMIT, ConfigSpec.ACT_CLEAR_CANDIDATE, ConfigSpec.ACT_SEND, ConfigSpec.ACT_SEND_AND_HIDE,
        ConfigSpec.ACT_HIDE, ConfigSpec.ACT_SWITCH_SCREEN,
        ConfigSpec.ACT_BACK,
        ConfigSpec.ACT_SYMBOL, ConfigSpec.ACT_ENGLISH, ConfigSpec.ACT_CHINESE, ConfigSpec.ACT_NUMBER,
        ConfigSpec.ACT_CLIPBOARD, ConfigSpec.ACT_PHRASES, ConfigSpec.ACT_MENU,
        ConfigSpec.ACT_EDIT_PANEL, ConfigSpec.ACT_VOICE,
        -> true
        else -> false
    }

    fun run(a: Int): Boolean = try {
        when (a) {
            ConfigSpec.ACT_COMMIT -> commit()
            ConfigSpec.ACT_CLEAR_CANDIDATE -> clearCandidate()
            ConfigSpec.ACT_SEND -> send()
            ConfigSpec.ACT_SEND_AND_HIDE -> sendAndHide()
            ConfigSpec.ACT_SWITCH_SCREEN -> FullscreenMode.toggleScreenMode()
            ConfigSpec.ACT_HIDE -> hide()
            ConfigSpec.ACT_BACK -> back()
            ConfigSpec.ACT_SYMBOL -> switchTo(listOf("符号", "符号键盘"), "符号键盘")
            ConfigSpec.ACT_ENGLISH -> switchTo(listOf("英文输入", "英文", "英文键盘"), "英文键盘")
            ConfigSpec.ACT_CHINESE -> switchTo(listOf("中文输入", "中文", "中文键盘"), "中文键盘")
            ConfigSpec.ACT_NUMBER -> switchTo(listOf("数字", "数字键盘", "123"), "数字键盘")
            ConfigSpec.ACT_CLIPBOARD -> clipboard()
            ConfigSpec.ACT_PHRASES -> phrases()
            ConfigSpec.ACT_MENU -> menu()
            ConfigSpec.ACT_EDIT_PANEL -> editPanel()
            ConfigSpec.ACT_VOICE -> voice()
            else -> false
        }
    } catch (t: Throwable) {
        L.hookError("KeyboardOps.run($a)", t)
        false
    }

    private fun findByName(v: View, name: String): View? {
        if (v.visibility != View.VISIBLE) return null
        if (ViewA11yHooks.spokenName(v) == name && (v.isClickable || v.isLongClickable)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            findByName(v.getChildAt(i), name)?.let { return it }
        }
        return null
    }
}
