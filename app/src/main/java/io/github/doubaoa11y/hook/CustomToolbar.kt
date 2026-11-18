package io.github.doubaoa11y.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

/**
 * 定制工具栏（菜单 → 定制工具栏）：豆包用一个弹出窗口显示可选工具，两页，每页 8 格（第二页原生 7 个）。
 * 交给读屏焦点：每个工具一个焦点，读“名字，已添加/未添加”，双击添加或移除；
 * 底部小圆点做成“下一页”/“上一页”（由 ToolboxPager 处理）。
 * 顶部已添加的工具在候选栏上（CandidateToolbarEditItemLayout），读成“名字，已添加”。
 * 模块新增的“上半屏手势”作为原生样式的方块放在下方工具网格第 2 页第 8 格（“清空”右侧）。
 */
object CustomToolbar {
    private val main = Handler(Looper.getMainLooper())
    private var popup: WeakReference<PopupWindow>? = null
    private var current: WeakReference<View>? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private const val ROW_TAG = "doubaoa11y_top_row"
    private const val BLOCK_TAG = "doubaoa11y_upper_block"
    const val OPTION_TAG = "doubaoa11y_upper_option_root"
    private const val UPPER_LABEL = "上半屏手势"
    private var lastToggleAt = 0L

    /** 定制工具栏开着（顶部工具栏处于编辑状态） */
    fun editing(): Boolean = popup?.get()?.isShowing == true || current?.get()?.isShown == true

    private fun id(v: View, name: String): Int = v.resources.getIdentifier(name, "id", v.context.packageName)

    private fun idName(v: View): String? {
        if (v.id == View.NO_ID || v.id ushr 24 == 0) return null
        return try {
            v.resources.getResourceEntryName(v.id)
        } catch (_: Throwable) {
            null
        }
    }

    /** 弹出窗口里的内容根视图（有“靠左吸附”和翻页） */
    fun isPanel(v: View): Boolean {
        if (v !is ViewGroup) return false
        val pName = v.parent?.javaClass?.simpleName ?: ""
        if (pName != "PopupDecorView" && pName != "PopupBackgroundView") return false
        return isContent(v)
    }

    private fun isContent(v: View): Boolean {
        if (v !is ViewGroup) return false
        val tab = id(v, "tabLeft")
        val vp = id(v, "viewPager")
        return tab != 0 && vp != 0 && v.findViewById<View>(tab) != null && v.findViewById<View>(vp) != null
    }

    fun panelOf(v: View): View? {
        var p: Any? = v
        while (p is View) {
            if (isPanel(p)) return p
            p = p.parent
        }
        return null
    }

    /** 一个可选工具的可点外框（OptionItemView 里的 id=root，或模块插入的方块 OPTION_TAG） */
    fun isOption(v: View): Boolean =
        v.tag == OPTION_TAG || (v.parent?.javaClass?.simpleName == "OptionItemView" && idName(v) == "root")

    private fun optionOf(v: View): View? {
        var p: Any? = v
        var depth = 0
        while (p is View && depth < 6) {
            if (p.javaClass.simpleName == "OptionItemView" || p.tag == BLOCK_TAG) return p
            p = p.parent
            depth++
        }
        return null
    }

    /** OptionItemView 里唯一的非静态 boolean 字段就是“已添加” */
    private fun added(opt: View): Boolean? {
        if (opt.tag == BLOCK_TAG || opt.tag == OPTION_TAG) return Config.b("screenToolbar")
        return try {
            val f = opt.javaClass.declaredFields.firstOrNull {
                it.type == Boolean::class.javaPrimitiveType && !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
            f?.isAccessible = true
            f?.getBoolean(opt)
        } catch (t: Throwable) {
            L.hookError("CustomToolbar.added", t)
            null
        }
    }

    private fun labelOf(opt: View): String {
        if (opt.tag == BLOCK_TAG || opt.tag == OPTION_TAG) return UPPER_LABEL
        // 单手模式已改成编辑面板，和工具栏上的读法一致
        if (idName(opt) == "item_one_hand") return "编辑面板"
        val t = (opt.findViewById<View>(id(opt, "labelText")) as? TextView)?.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) return t.replace("(大)", "，大").replace("(小)", "，小")
        return ViewA11yHooks.mappedName(opt) ?: "工具"
    }

    private fun stateName(opt: View): String {
        val name = labelOf(opt)
        return when (added(opt)) {
            true -> "$name，已添加"
            false -> "$name，未添加"
            null -> name
        }
    }

    /** 节点处理；返回 true 表示已处理 */
    fun label(v: View, info: AccessibilityNodeInfo): Boolean {
        if (panelOf(v) == null) return false
        if (isOption(v)) {
            val opt = if (v.tag == OPTION_TAG) v else v.parent as View
            val t = stateName(opt)
            info.text = t
            info.contentDescription = t
            info.className = "android.widget.Button"
            info.isClickable = true
            info.isFocusable = true
            return true
        }
        // 工具里的图标、名字不再单独占焦点
        if (optionOf(v) != null) {
            Panels.hide(info)
            return true
        }
        val entry = idName(v)
        if (entry == "tabLeft" || entry == "tabRight") {
            val name = (v as? TextView)?.text?.toString() ?: return false
            val t = if (v.background != null) "$name，已选中" else name
            info.contentDescription = t
            return true
        }
        return false
    }

    /** 读屏双击一个工具：点图标切换添加/移除，然后读出结果 */
    fun click(v: View): Boolean {
        if (!isOption(v) || panelOf(v) == null) return false
        if (v.tag == OPTION_TAG) {
            toggleUpperOption(v)
            return true
        }
        val opt = v.parent as View
        val icon = opt.findViewById<View>(id(opt, "iconImage")) ?: return false
        icon.performClick()
        main.postDelayed({
            try {
                val name = labelOf(opt)
                val msg = when (added(opt)) {
                    true -> "已添加$name"
                    false -> "已移除$name"
                    null -> name
                }
                L.i("定制工具栏：$msg")
                Speaker.speak(msg)
                panelOf(v)?.let { refreshRowLater(it) }
                v.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            } catch (t: Throwable) {
                L.hookError("CustomToolbar.click", t)
            }
        }, 120)
        return true
    }

    private fun toggleUpperOption(v: View) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastToggleAt < 200) return
        lastToggleAt = now
        if (!A11yState.recording) return
        if (ModuleEntries.toggleToolbar(v.context)) {
            val panel = panelOf(v)
            if (panel != null) {
                ensureOptionBlock(panel)
                refreshRow(panel)
            }
            v.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    /** 弹出窗口显示时：把读屏焦点放到第一个工具 */
    fun install() {
        Hooker.byName(PopupWindow::class.java, "showAtLocation", SafeHook("PopupWindow.showAtLocation", after = { p -> onShown(p.thisObject as PopupWindow) }))
        Hooker.byName(PopupWindow::class.java, "showAsDropDown", SafeHook("PopupWindow.showAsDropDown", after = { p -> onShown(p.thisObject as PopupWindow) }))
    }

    private fun onShown(w: PopupWindow) {
        if (!A11yState.recording) return
        val content = w.contentView ?: return
        if (!isContent(content)) return
        L.i("打开了定制工具栏，交给读屏")
        popup = WeakReference(w)
        current = WeakReference(content)
        // 拦截空白处触摸，防止穿透到底层键盘
        content.isClickable = true
        VirtualKeys.cancelExplore()
        TouchGuard.syncPanel()
        FullscreenMode.syncPanelVisibility()
        w.setOnDismissListener(wrapDismiss(w))

        try {
            val vp = content.findViewById<View>(id(content, "viewPager"))
            if (vp != null) XposedHelpers.callMethod(vp, "setOffscreenPageLimit", 1)
        } catch (_: Throwable) {
        }

        val li = ViewTreeObserver.OnGlobalLayoutListener {
            if (editing() && content.isShown) {
                ensureOptionBlock(content)
            }
        }
        layoutListener = li
        try {
            content.viewTreeObserver.addOnGlobalLayoutListener(li)
        } catch (_: Throwable) {
        }

        main.postDelayed({
            try {
                TouchGuard.syncPanel()
                FullscreenMode.syncPanelVisibility()
                ensureOptionBlock(content)
                refreshRow(content)
            } catch (t: Throwable) {
                L.hookError("CustomToolbar.refreshRow", t)
            }
        }, 200)
        main.postDelayed({
            try {
                TouchGuard.syncPanel()
                FullscreenMode.syncPanelVisibility()
                ensureOptionBlock(content)
                val panel = content.parent as? View ?: return@postDelayed
                if (panel.isShown) ToolboxPager.focusFirst(content)
            } catch (t: Throwable) {
                L.hookError("CustomToolbar.onShown", t)
            }
        }, 450)
    }

    /** 定制工具栏关掉后恢复穿透（保留豆包自己的关闭回调） */
    private fun wrapDismiss(w: PopupWindow): PopupWindow.OnDismissListener {
        val old = try {
            val f = PopupWindow::class.java.getDeclaredField("mOnDismissListener")
            f.isAccessible = true
            f.get(w) as? PopupWindow.OnDismissListener
        } catch (_: Throwable) {
            null
        }
        return PopupWindow.OnDismissListener {
            try {
                old?.onDismiss()
            } finally {
                val c = current?.get()
                val li = layoutListener
                layoutListener = null
                if (c != null && li != null) {
                    try {
                        c.viewTreeObserver.removeOnGlobalLayoutListener(li)
                    } catch (_: Throwable) {
                    }
                }
                popup = null
                current = null
                TouchGuard.syncPanel()
                FullscreenMode.syncPanelVisibility()
                main.postDelayed({
                    TouchGuard.syncPanel()
                    FullscreenMode.syncPanelVisibility()
                }, 200)
            }
        }
    }

    // ---- 下方工具网格中的“上半屏手势”方块（第 2 页第 8 格，“发送”右侧） ----

    fun ensureOptionBlock(content: View) {
        try {
            val vp = content.findViewById<View>(id(content, "viewPager")) as? ViewGroup ?: return
            // 在 viewPager 中找可见 OptionItemView 数量不足 8 个的那一页（第 2 页有 7 个可见项）
            val pages = ArrayList<Pair<ViewGroup, List<View>>>()
            collectOptionPages(vp, pages)
            val target = pages.firstOrNull { it.second.size in 1..7 } ?: return
            val parent = target.first
            val visibleOpts = target.second
            val sample = visibleOpts.last()
            val col4View = visibleOpts.getOrNull(3)

            val existing = parent.findViewWithTag<UpperOptionBlock>(BLOCK_TAG)
            if (existing != null) {
                existing.updateRefs(sample, col4View)
                existing.refreshState()
                existing.syncBounds()
                return
            }

            val block = UpperOptionBlock(content.context, sample, col4View)
            parent.addView(block, ViewGroup.LayoutParams(
                sample.width.coerceAtLeast(1),
                sample.height.coerceAtLeast(1),
            ))
            block.refreshState()
            block.syncBounds()
            sample.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                block.syncBounds()
            }
            parent.post {
                block.syncBounds()
                parent.invalidate()
            }
            L.i("定制工具栏：已在第 2 页末尾插入「$UPPER_LABEL」方块")
        } catch (t: Throwable) {
            L.hookError("CustomToolbar.ensureOptionBlock", t)
        }
    }

    private fun collectOptionPages(root: ViewGroup, out: MutableList<Pair<ViewGroup, List<View>>>) {
        val visibleOpts = ArrayList<View>()
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c.javaClass.simpleName == "OptionItemView" && c.visibility == View.VISIBLE) {
                visibleOpts.add(c)
            }
        }
        if (visibleOpts.isNotEmpty()) {
            out.add(root to visibleOpts)
            return
        }
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is ViewGroup) collectOptionPages(c, out)
        }
    }

    /**
     * 模拟豆包原生 OptionItemView 的方块视图：
     * - 尺寸与第 2 页最后一个可见方块（“发送”）完全一致；
     * - 坐标自动对齐到第 2 行第 4 列（与第 1 行第 4 列同列、与第 2 行同行）；
     * - 内部包含与原生同款的 150x150 方块图标区（含已添加/未添加角标）和下方文字标签；
     * - 在 RecyclerView 已完成布局后主动 measure + layout，确保立即可见、可聚焦。
     */
    private class UpperOptionBlock(
        ctx: Context,
        private var sample: View,
        private var col4Sample: View?,
    ) : FrameLayout(ctx) {
        val innerRoot: FrameLayout
        private val columnView: LinearLayout
        private val iconBox: FrameLayout
        private val iconView: ImageView
        private val labelView: TextView
        private var textColor: Int = 0xFF222222.toInt()
        private var drawCardBox: Boolean = true

        init {
            tag = BLOCK_TAG
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            val sampleRoot = sample.findViewById<View>(id(sample, "root")) ?: sample
            val sampleIconBox = sample.findViewById<View>(id(sample, "iconContainer"))
            val sampleIcon = sample.findViewById<View>(id(sample, "iconImage"))
            val sampleLabel = sample.findViewById<View>(id(sample, "labelText")) as? TextView
            if (sampleLabel != null) textColor = sampleLabel.currentTextColor

            val d = resources.displayMetrics.density
            innerRoot = FrameLayout(ctx).apply {
                tag = OPTION_TAG
                isClickable = true
                isFocusable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                background = cloneBg(sampleRoot) ?: cloneBg(sample)
                setOnClickListener { toggleUpperOption(this) }
            }

            columnView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            val boxW = (sampleIconBox?.width?.takeIf { it > 0 }
                ?: sampleIcon?.width?.takeIf { it > 0 }
                ?: (44 * d).toInt())
            val boxH = (sampleIconBox?.height?.takeIf { it > 0 }
                ?: sampleIcon?.height?.takeIf { it > 0 }
                ?: (44 * d).toInt())

            val boxBg = (sampleIconBox?.let { cloneBg(it) }) ?: (sampleIcon?.let { cloneBg(it) })
            drawCardBox = (boxBg == null && innerRoot.background == null)

            iconBox = FrameLayout(ctx).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (boxBg != null) background = boxBg
            }
            iconView = ImageView(ctx).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            iconBox.addView(iconView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            columnView.addView(iconBox, LinearLayout.LayoutParams(boxW, boxH))

            val gap = if (sampleLabel != null && sampleIconBox != null && sampleLabel.top > sampleIconBox.bottom) {
                sampleLabel.top - sampleIconBox.bottom
            } else {
                (6 * d).toInt()
            }

            labelView = TextView(ctx).apply {
                text = UPPER_LABEL
                gravity = Gravity.CENTER
                isSingleLine = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (sampleLabel != null) {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, sampleLabel.textSize)
                    setTextColor(sampleLabel.textColors)
                    typeface = sampleLabel.typeface
                    includeFontPadding = sampleLabel.includeFontPadding
                } else {
                    textSize = 12f
                    setTextColor(textColor)
                }
            }
            val labelLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = gap
            }
            columnView.addView(labelView, labelLp)

            innerRoot.addView(columnView, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL,
            ))
            addView(innerRoot, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        fun updateRefs(newSample: View, newCol4: View?) {
            sample = newSample
            col4Sample = newCol4
        }

        private fun cloneBg(v: View): Drawable? = try {
            v.background?.constantState?.newDrawable(resources)?.mutate()
        } catch (_: Throwable) {
            null
        }

        fun refreshState() {
            val present = Config.b("screenToolbar")
            val desc = "$UPPER_LABEL，${if (present) "已添加" else "未添加"}"
            innerRoot.contentDescription = desc
            iconView.setImageDrawable(UpperIconDrawable(textColor, present, drawCardBox, resources.displayMetrics.density))
        }

        /** 主动与同行/同列的原生 OptionItemView 对齐尺寸和坐标，不依赖 RecyclerView 重新触发整页布局 */
        fun syncBounds() {
            val sw = sample.width.takeIf { it > 0 } ?: sample.measuredWidth
            val sh = sample.height.takeIf { it > 0 } ?: sample.measuredHeight
            if (sw <= 0 || sh <= 0) return

            val sampleIconBox = sample.findViewById<View>(id(sample, "iconContainer"))
            val sampleIcon = sample.findViewById<View>(id(sample, "iconImage"))
            val sampleLabel = sample.findViewById<View>(id(sample, "labelText")) as? TextView
            val boxW = (sampleIconBox?.width?.takeIf { it > 0 } ?: sampleIcon?.width?.takeIf { it > 0 })
            val boxH = (sampleIconBox?.height?.takeIf { it > 0 } ?: sampleIcon?.height?.takeIf { it > 0 })
            if (boxW != null && boxH != null) {
                val lp = iconBox.layoutParams as? LinearLayout.LayoutParams
                if (lp != null && (lp.width != boxW || lp.height != boxH)) {
                    lp.width = boxW
                    lp.height = boxH
                    iconBox.layoutParams = lp
                }
            }
            if (sampleLabel != null && sampleIconBox != null && sampleLabel.top > sampleIconBox.bottom) {
                val gap = sampleLabel.top - sampleIconBox.bottom
                val llp = labelView.layoutParams as? LinearLayout.LayoutParams
                if (llp != null && llp.topMargin != gap) {
                    llp.topMargin = gap
                    labelView.layoutParams = llp
                }
            }

            val c4 = col4Sample
            val targetL = if (c4 != null && c4.width > 0) c4.left else sample.right
            val targetT = sample.top

            measure(
                MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY),
            )
            layout(targetL, targetT, targetL + sw, targetT + sh)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val sw = sample.width.takeIf { it > 0 } ?: sample.measuredWidth
            val sh = sample.height.takeIf { it > 0 } ?: sample.measuredHeight
            if (sw > 0 && sh > 0) {
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY),
                )
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val p = parent
            val sw = sample.width
            val sh = sample.height
            if (p != null && sample.parent === p && sw > 0 && sh > 0) {
                val c4 = col4Sample
                val targetL = if (c4 != null && c4.parent === p && c4.width > 0) c4.left else sample.right
                val targetT = sample.top
                if (l != targetL || t != targetT || r != targetL + sw || b != targetT + sh) {
                    left = targetL
                    top = targetT
                    right = targetL + sw
                    bottom = targetT + sh
                }
                super.onLayout(changed, left, top, right, bottom)
            } else {
                super.onLayout(changed, l, t, r, b)
            }
        }
    }

    /** 绘制上半屏手势方块（150x150 圆角方块底板 + 内部手势图标 + 右上角添加/已添加状态角标） */
    private class UpperIconDrawable(
        private val color: Int,
        private val added: Boolean,
        private val drawCard: Boolean,
        private val density: Float,
    ) : Drawable() {
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0x16888888
            style = Paint.Style.FILL
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@UpperIconDrawable.color
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (added) 0xFF2E7D32.toInt() else 0xFF555555.toInt()
            style = Paint.Style.FILL
        }
        private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (drawCard) {
                val cardRect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                val cr = w * 0.22f
                canvas.drawRoundRect(cardRect, cr, cr, cardPaint)
            }
            val pad = w * 0.26f
            val rect = RectF(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad)
            val r = 3f * density
            // 外框与上下分界线
            canvas.drawRoundRect(rect, r, r, paint)
            val midY = rect.top + rect.height() * 0.56f
            canvas.drawLine(rect.left, midY, rect.right, midY, paint)
            // 上半屏十字滑动手势箭头
            val cx = rect.centerX()
            val cy = (rect.top + midY) * 0.5f
            val arm = minOf(rect.width(), midY - rect.top) * 0.25f
            canvas.drawLine(cx - arm, cy, cx + arm, cy, paint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, paint)
            // 方块右上角状态圆角标（+ 或 ✓）
            val br = w * 0.11f
            val bx = b.right - w * 0.16f
            val by = b.top + h * 0.16f
            canvas.drawCircle(bx, by, br, badgeBgPaint)
            val s = br * 0.52f
            if (added) {
                canvas.drawLine(bx - s, by, bx - s * 0.2f, by + s * 0.75f, badgeStrokePaint)
                canvas.drawLine(bx - s * 0.2f, by + s * 0.75f, bx + s, by - s * 0.65f, badgeStrokePaint)
            } else {
                canvas.drawLine(bx - s, by, bx + s, by, badgeStrokePaint)
                canvas.drawLine(bx, by - s, bx, by + s, badgeStrokePaint)
            }
        }

        override fun setAlpha(alpha: Int) {
            cardPaint.alpha = alpha
            paint.alpha = alpha
            badgeBgPaint.alpha = alpha
            badgeStrokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ---- 弹出窗口顶部的一排代理按钮：对应键盘上方工具栏的“完成”和已添加的工具 ----
    // 读屏左右滑动只在弹出窗口里转，到不了键盘窗口里的工具栏，所以在弹出窗口里放一份。

    private fun refreshRowLater(content: View) {
        main.postDelayed({
            try {
                ensureOptionBlock(content)
                refreshRow(content)
            } catch (t: Throwable) {
                L.hookError("CustomToolbar.refreshRow", t)
            }
        }, 350)
    }

    private fun imeRoot(): View? = try {
        ViewA11yHooks.service?.window?.window?.decorView
    } catch (_: Throwable) {
        null
    }

    /** 键盘上方工具栏现在的样子：(读法, 双击时要点的控件) */
    private fun topItems(): List<Pair<String, View?>> {
        val root = imeRoot() ?: return emptyList()
        val out = ArrayList<Pair<String, View?>>()
        fun byId(n: String): View? {
            val id = id(root, n)
            return if (id != 0) root.findViewById(id) else null
        }
        byId("btn_logo")?.takeIf { it.isShown }?.let { out.add("完成" to it) }
        val container = byId("middle_container") as? ViewGroup
        if (container != null) for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (!c.isShown || c.javaClass.simpleName != "CandidateToolbarEditItemLayout") continue
            val cd = c.contentDescription?.toString() ?: continue
            val name = Names.toolbarKeys[cd] ?: Names.guessFromId(cd) ?: cd
            out.add("$name，已添加，双击移除" to c)
        }
        if (Config.b("screenToolbar")) {
            out.add("$UPPER_LABEL，已添加，双击移除" to null)
        }
        val hide = byId("btn_hide")
        val hideRemove = byId("btn_hide_remove")
        if (hide != null && hide.isShown && hideRemove != null && hideRemove.isShown) {
            out.add("收起键盘，已添加，双击移除" to hideRemove)
        }
        return out
    }

    private fun refreshRow(content: View) {
        val box = content as? LinearLayout ?: return
        if (!content.isShown) return
        ensureOptionBlock(content)
        var row = box.findViewWithTag<LinearLayout>(ROW_TAG)
        if (row == null) {
            row = LinearLayout(box.context).apply {
                tag = ROW_TAG
                orientation = LinearLayout.HORIZONTAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            box.addView(row, 0, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        row.removeAllViews()
        val items = topItems()
        val pad = (6 * box.resources.displayMetrics.density).toInt()
        for ((name, target) in items) {
            val b = TextView(box.context).apply {
                text = name.substringBefore("，")
                contentDescription = name
                textSize = 12f
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener {
                    L.i("定制工具栏顶部：$name")
                    if (target == null) {
                        if (A11yState.recording && ModuleEntries.toggleToolbar(context)) {
                            ensureOptionBlock(content)
                            refreshRow(content)
                        }
                    } else {
                        target.performClick()
                        val n = name.substringBefore("，")
                        if (n != "完成") {
                            Speaker.speak("已移除$n")
                            refreshRowLater(content)
                        }
                    }
                }
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        L.i("定制工具栏顶部代理：${items.joinToString { it.first }}")
    }
}
