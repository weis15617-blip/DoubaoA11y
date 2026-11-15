package io.github.doubaoa11y.hook

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.doubaoa11y.ConfigSpec

/**
 * 全屏输入的自建触摸层（1.2.1 重做）。
 *
 * 目的：全屏模式下**不再依赖屏幕阅读器的触摸浏览**。手指怎么走，全由模块自己判：
 *
 * - 上半屏（键盘顶边以上）：手势区。单击、双击、上下左右滑、双指双击、拖动，
 *   槽位和天坦输入法一一对应，见 [GestureLayer]。
 * - 键盘区（键盘顶边以下）：模块自己的一套触摸浏览。手指落在哪个键/按钮上就读哪个，
 *   抬手就输入那个键，停在删除/空格上就连续删除、开始语音。见 [VirtualKeys.onFullscreenTouch]。
 *
 * 和以前最大的区别：
 * 1. 以前键盘区要不要自己处理，看读屏的触摸浏览是不是被关掉了（`VirtualKeys.touchMode`），
 *    读屏没关、或者读屏状态没来得及变，键盘区就落回豆包原生触摸——没有朗读，读屏一暂停还会漏键。
 *    现在全屏一律自己处理，读屏暂停、读屏关掉、甚至根本没装读屏，都能摸到键、听到朗读、抬手打字。
 * 2. 一次手势从头到尾只归一个区域：按下在哪边，抬手也在哪边结束，中途划过边界不会半路换手，
 *    免得“上半屏滑到一半掉进键盘”把键盘点了一下。
 * 3. 键盘区的双指手势在这里识别（双指双击＝语音输入那类），单指的摸键/输入交给 VirtualKeys。
 */
object FullscreenTouch {
    private const val NONE = 0
    private const val UPPER = 1
    private const val KEYBOARD = 2

    /** 这次手势按下时落在哪个区域（整个手势期间不变） */
    private var region = NONE

    private val density get() = android.content.res.Resources.getSystem().displayMetrics.density

    /** 判“有没有滑动”的距离：跟手势灵敏度一起缩放（和 GestureLayer 保持同一套手感） */
    private val slop: Float
        get() = 18f * density * when (Config.i("gestureSensitivity").coerceIn(0, 2)) {
            0 -> 1.45f
            2 -> 0.55f
            else -> 1f
        }

    // 键盘区双指手势
    private var lastTwoTapAt = 0L
    private var twoDown = false
    private var twoAt = 0L
    private var twoX = 0f
    private var twoY = 0f
    private var twoFired = false

    /** 全屏触摸的统一入口，所有手指事件都从这里进来 */
    fun handleTouch(root: View, ev: MotionEvent): Boolean {
        if (!FullscreenMode.wanted || !FullscreenMode.active) return false
        if (VirtualKeys.injecting || TatansBridge.panelOpen()) return false
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            region = NONE
            twoDown = false
            twoFired = false
        }
        if (region == NONE) {
            if (!downInKeyboard(root, ev)) {
                // 上半屏：交给手势层
                region = UPPER
                FullscreenMode.onRootTouch(root, ev)
                return true
            }
            region = KEYBOARD
        }

        if (region == UPPER) {
            // 上半屏：整个手势都给手势层。手指划到键盘区去了就把这一下作废（不当作点了键盘），
            // 免得“往上滑到一半滑进键盘”被当成摸了一下某个键
            val handled = FullscreenMode.onRootTouch(root, ev)
            if (!handled && ev.actionMasked == MotionEvent.ACTION_MOVE) {
                FullscreenMode.cancelGesture()
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) region = NONE
            return true
        }

        // ---- 键盘区 ----
        if (ev.pointerCount >= 2 || twoDown) return twoFinger(root, ev)

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            region = NONE
        }
        return VirtualKeys.onFullscreenTouch(root, ev)
    }

    /** 手指按下的位置在不在键盘区（键盘顶边以下），坐标按屏幕坐标算 */
    private fun downInKeyboard(root: View, ev: MotionEvent): Boolean {
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val ry = ev.y + loc[1]
        val top = FullscreenMode.keyboardTopOnScreen()
        // 拿不到键盘顶边时按“键盘区”处理：全屏时手指落在下半屏几乎都是键盘
        if (top <= 0) return ry >= screenH(root) * 0.42f
        return ry >= top
    }

    private fun screenH(v: View): Int = try {
        v.resources.displayMetrics.heightPixels
    } catch (_: Throwable) {
        1
    }

    /**
     * 键盘区的双指手势：双指双击＝执行“双指双击”槽位的动作（默认语音输入），
     * 双指左右上下滑＝对应槽位。识别期间吞掉事件，不让它漏给豆包或 VirtualKeys。
     */
    private fun twoFinger(root: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                // 第一根手指可能已经摸到一个键并开始朗读了：撤掉，改成双指手势
                VirtualKeys.cancelExplore()
                twoDown = true
                twoFired = false
                twoAt = ev.eventTime
                twoX = midX(ev)
                twoY = midY(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                // 已经抬起一根手指了，剩下这一根怎么划都不再算双指手势
                if (!twoDown || twoFired || ev.pointerCount < 2) return true
                val dx = midX(ev) - twoX
                val dy = midY(ev) - twoY
                if (kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop) return true
                twoFired = true
                val slot = GestureMap.doubleSlide(dx, dy, slop) ?: return true
                GestureLayer.fireSlot(slot, "全屏双指滑动")
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 还有一根手指在屏幕上，等它抬起来再判定
            }
            MotionEvent.ACTION_UP -> {
                val quick = ev.eventTime - twoAt <= 420
                val still = !twoFired
                twoDown = false
                region = NONE
                if (quick && still) {
                    if (lastTwoTapAt > 0 && ev.eventTime - lastTwoTapAt <= 350) {
                        lastTwoTapAt = 0L
                        GestureLayer.fireSlot(ConfigSpec.G_DOUBLE_TAP, "全屏双指双击", double = true)
                    } else lastTwoTapAt = ev.eventTime
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                twoDown = false
                twoFired = false
                region = NONE
            }
        }
        return true
    }

    private fun midX(ev: MotionEvent): Float {
        if (ev.pointerCount <= 1) return ev.x
        return (ev.getX(0) + ev.getX(1)) / 2f
    }

    private fun midY(ev: MotionEvent): Float {
        if (ev.pointerCount <= 1) return ev.y
        return (ev.getY(0) + ev.getY(1)) / 2f
    }

    /** 键盘收起、退出全屏时把状态清干净 */
    fun reset() {
        region = NONE
        lastTwoTapAt = 0L
        twoDown = false
        twoFired = false
    }

    /** 状态行用 */
    fun describe(): String = when (region) {
        UPPER -> "全屏触摸：上半屏手势"
        KEYBOARD -> "全屏触摸：键盘区自建触摸浏览"
        else -> "全屏触摸：待命"
    }
}
