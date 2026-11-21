package io.github.doubaoa11y.hook

/**
 * 编辑模式入口：
 * - 工具栏入口已由 ToolbarInject 作为独立自定义工具（KEY_EDIT_MODE）接入原生定制工具栏，不再替换豆包原生的“单手模式”。
 * - 菜单（工具箱）里的“移动光标”仍保留打开编辑面板，单手模式保持豆包原生行为。
 */
object EditHooks {
    private var toolboxActionClass: Class<*>? = null
    @Volatile
    private var toolboxItemClick: java.lang.ref.WeakReference<Any>? = null

    fun install(cl: ClassLoader) {
        // 菜单（工具箱）里的“移动光标”打开编辑面板（不再劫持单手模式 ONE_HAND）
        val action = Hooker.findClassQuiet("com.bytedance.common_biz.tool_box.ToolboxItemAction", cl) ?: return
        toolboxActionClass = action
        val handler = Hooker.findClassQuiet("com.bytedance.android.input.keyboard.areacontrol.v", cl)
        val old = handler?.let { de.robv.android.xposed.XposedHelpers.findMethodExactIfExists(it, "invoke", action) }
        if (old != null) {
            // 1.5.3：菜单点击的处理函数 invoke(ToolboxItemAction)，返回 Kotlin Unit
            Hooker.hookMember(old, SafeHook("Toolbox.invoke", before = { p ->
                p.thisObject?.let { toolboxItemClick = java.lang.ref.WeakReference(it) }
                if (wantsEditPanel(p.args[0])) {
                    val unit = unitOf((p.method as java.lang.reflect.Method).returnType) ?: return@SafeHook
                    p.result = unit
                    EditPanel.closeToolboxThenOpen()
                }
            }))
            return
        }
        // 1.5.4 起：ToolboxKeyboardView 的静态方法 (ToolboxKeyboardView, ToolboxItemAction) -> void 把点击转给处理函数
        val view = Hooker.findClassQuiet("com.bytedance.common_biz.tool_box.ToolboxKeyboardView", cl) ?: return
        // 捕获未混淆的 setOnItemClick 回调，用于直接触发工具箱功能而无需打开菜单界面
        val setClick = view.declaredMethods.firstOrNull { it.name == "setOnItemClick" && it.parameterTypes.size == 1 }
        if (setClick != null) {
            Hooker.hookMember(setClick, SafeHook("Toolbox.setOnItemClick", after = { p ->
                val listener = p.args.getOrNull(0)
                if (listener != null) toolboxItemClick = java.lang.ref.WeakReference(listener)
            }))
        }
        val dispatch = view.declaredMethods.filter {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(view, action))
        }
        if (dispatch.size != 1) {
            L.w("找不到菜单点击方法，候选 ${dispatch.size} 个，菜单里的移动光标不会改成编辑面板")
            return
        }
        L.i("菜单点击方法：${dispatch[0].name}")
        Hooker.hookMember(dispatch[0], SafeHook("Toolbox.${dispatch[0].name}", before = { p ->
            if (wantsEditPanel(p.args[1])) {
                p.result = null
                EditPanel.closeToolboxThenOpen()
            }
        }))
    }

    /**
     * 通过已捕获的 ToolboxKeyboardView.setOnItemClick 回调直接分发工具箱动作（如 TEXT_ORGANIZE），
     * 不需要展开菜单或点击控件。
     */
    fun triggerToolboxAction(actionName: String): Boolean {
        val listener = toolboxItemClick?.get() ?: return false
        val actCls = toolboxActionClass ?: return false
        return try {
            val consts = actCls.enumConstants ?: return false
            val target = consts.firstOrNull { (it as? Enum<*>)?.name == actionName } ?: return false
            val invoke = listener.javaClass.methods.firstOrNull {
                it.name == "invoke" && it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0].isAssignableFrom(actCls) || it.parameterTypes[0] == Any::class.java)
            } ?: return false
            invoke.isAccessible = true
            invoke.invoke(listener, target)
            L.i("工具箱直连分发：$actionName")
            true
        } catch (t: Throwable) {
            L.hookError("EditHooks.triggerToolboxAction($actionName)", t)
            false
        }
    }

    private fun wantsEditPanel(a: Any?): Boolean {
        val n = (a as? Enum<*>)?.name
        return n == "MOVE_CURSOR" && Config.b("editPanel")
    }

    /** 豆包混淆过的 Kotlin Unit：返回类型里那个单例 */
    private fun unitOf(c: Class<*>): Any? = try {
        c.declaredFields.firstOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == c }?.apply { isAccessible = true }?.get(null)
    } catch (_: Throwable) {
        null
    }

    /** 供外部命令添加/移除工具栏的“编辑模式”自定义工具 */
    fun setToolbar(add: Boolean): String {
        return try {
            val ok = ToolbarInject.setToolEnabled(ServiceHooks.appContext, ToolbarInject.KEY_EDIT_MODE, add)
            if (ok) {
                if (add) "已把编辑模式加到工具栏" else "已从工具栏移除编辑模式"
            } else {
                "失败：未能更新编辑模式工具"
            }
        } catch (t: Throwable) {
            L.e("修改工具栏失败", t)
            "失败：${t.javaClass.simpleName}"
        }
    }
}
