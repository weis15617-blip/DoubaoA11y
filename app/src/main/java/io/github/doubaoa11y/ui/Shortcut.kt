package io.github.doubaoa11y.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** 桌面入口的真实状态由 PackageManager 保存，不另存一份偏好值。 */
object Shortcut {
    private fun component(ctx: Context) = ComponentName(ctx, "${ctx.packageName}.ui.LauncherAlias")

    fun shown(ctx: Context): Boolean = when (ctx.packageManager.getComponentEnabledSetting(component(ctx))) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
        // 清单中的默认值为 enabled=true。
        else -> true
    }

    /** 失败交给设置页回显真实状态；不能把失败保存成“已隐藏/已显示”。 */
    fun setShown(ctx: Context, show: Boolean) {
        ctx.packageManager.setComponentEnabledSetting(
            component(ctx),
            if (show) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        check(shown(ctx) == show) { "桌面入口状态没有改变" }
    }
}
