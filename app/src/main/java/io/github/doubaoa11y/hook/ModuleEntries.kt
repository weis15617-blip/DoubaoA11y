package io.github.doubaoa11y.hook

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/** 模块在豆包内的入口：顶部工具栏已由 ToolbarInject 接入原生管线，此处仅保留刷新桥接与设置页入口。 */
object ModuleEntries {
    private const val LEGACY_TAG = "doubaoa11y_screen_switch"

    fun toggleToolbar(context: android.content.Context): Boolean = try {
        val enabled = !Config.b("screenToolbar")
        ToolbarInject.setToolEnabled(context, ToolbarInject.KEY_UPPER_GESTURE, enabled)
        Speaker.speak(if (enabled) "已添加上半屏手势" else "已移除上半屏手势")
        true
    } catch (t: Throwable) {
        L.hookError("ModuleEntries.toggleToolbar", t)
        Speaker.speak("保存工具栏设置失败")
        false
    }

    fun toolbar() {
        try {
            // 清理旧版残留的外挂按钮（如有）
            val root = ViewA11yHooks.service?.window?.window?.decorView
            if (root != null) {
                val id = root.resources.getIdentifier("middle_container", "id", root.context.packageName)
                val box = if (id != 0) root.findViewById<View>(id) as? ViewGroup else null
                box?.findViewWithTag<View>(LEGACY_TAG)?.let { box.removeView(it) }
            }
        } catch (t: Throwable) {
            L.hookError("ModuleEntries.toolbar", t)
        }
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
