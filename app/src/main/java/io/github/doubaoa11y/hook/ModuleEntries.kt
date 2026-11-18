package io.github.doubaoa11y.hook

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** 仅在确认的普通布局容器内添加控件，不向 RecyclerView 添加 unmanaged child。 */
object ModuleEntries {
    private const val TAG = "doubaoa11y_screen_switch"
    fun toggleToolbar(context: android.content.Context): Boolean = try {
        val enabled = !Config.b("screenToolbar")
        Config.setFromIme(context, "screenToolbar", enabled)
        toolbar()
        Speaker.speak(if (enabled) "已添加上半屏手势" else "已移除上半屏手势")
        true
    } catch (t: Throwable) { L.hookError("ModuleEntries.toggleToolbar", t); Speaker.speak("保存工具栏设置失败"); false }
    fun toolbar() {
        try {
            val root = ViewA11yHooks.service?.window?.window?.decorView ?: return
            val id = root.resources.getIdentifier("middle_container", "id", root.context.packageName)
            val box = root.findViewById<View>(id) as? LinearLayout ?: return
            var button = box.findViewWithTag<TextView>(TAG)
            if (!A11yState.recording || !Config.b("screenToolbar")) { button?.visibility = View.GONE; return }
            if (button == null) {
                button = TextView(box.context).apply {
                    tag = TAG; textSize = 13f; gravity = android.view.Gravity.CENTER
                    val pad = (6 * resources.displayMetrics.density).toInt(); setPadding(pad, 0, pad, 0)
                    minimumWidth = (44 * resources.displayMetrics.density).toInt()
                    isClickable = true; isFocusable = true
                    setOnClickListener { try { if (A11yState.recording) { FullscreenMode.toggleScreenMode(); toolbar() } } catch (t: Throwable) { L.hookError("ModuleEntries.click", t) } }
                }
                box.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            if (button.visibility != View.VISIBLE) button.visibility = View.VISIBLE
            val title = "上半屏"
            val description = if (Config.fullscreen) "关闭上半屏手势" else "开启上半屏手势"
            if (button.text.toString() != title) button.text = title
            if (button.contentDescription?.toString() != description) button.contentDescription = description
        } catch (t: Throwable) { L.hookError("ModuleEntries.toolbar", t) }
    }
    fun settings(root: View) {
        try {
            val parent = root.parent as? ViewGroup ?: return
            if (parent.tag == "doubaoa11y_settings_entry") return
            // 只包装普通容器；不接管适配器管理的父布局。
            if (parent !is android.widget.FrameLayout && parent !is LinearLayout) return
            val index = parent.indexOfChild(root); val params = root.layoutParams
            val wrapper = LinearLayout(root.context).apply { orientation = LinearLayout.VERTICAL; tag = "doubaoa11y_settings_entry" }
            val entry = android.widget.Button(root.context).apply {
                text = "豆包无障碍模块设置"
                setOnClickListener {
                    try { context.startActivity(Intent().setClassName("io.github.doubaoa11y", "io.github.doubaoa11y.ui.SettingsActivity")) }
                    catch (t: Throwable) { L.hookError("ModuleEntries.settings", t); android.widget.Toast.makeText(context, "无法打开模块设置，请从模块管理器打开", android.widget.Toast.LENGTH_LONG).show() }
                }
            }
            parent.removeView(root)
            try {
                wrapper.addView(entry, LinearLayout.LayoutParams(-1, -2))
                wrapper.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
                parent.addView(wrapper, index, params)
                root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit
                    override fun onViewDetachedFromWindow(v: View) {
                        parent.post {
                            try { if (v.parent !== wrapper) parent.removeView(wrapper) }
                            catch (t: Throwable) { L.hookError("ModuleEntries.cleanup", t) }
                        }
                    }
                })
            } catch (t: Throwable) {
                wrapper.removeView(root); parent.removeView(wrapper); parent.addView(root, index.coerceAtMost(parent.childCount), params); throw t
            }
        } catch (t: Throwable) { L.hookError("ModuleEntries.settingsAttach", t) }
    }
}
