package io.github.doubaoa11y.hook

/** 多读屏只使用区域穿透与实际收到的事件；不改变用户全局触摸浏览状态。 */
object TouchGuard {
    val isPaused: Boolean get() = ReaderControl.requested
    val needsLiftToType: Boolean get() = false
    fun pause() { TatansBridge.update("全屏区域"); ReaderControl.sync() }
    fun resume(why: String) { ReaderControl.sync(); TatansBridge.clearPassThrough(why); TatansBridge.update(why) }
    fun syncPanel() { TatansBridge.update("面板变化"); ReaderControl.sync(); ModuleEntries.toolbar() }
    fun describe(): String = when (A11yState.readerPackage) {
        "com.bjbyhd.voiceback", "com.bjbyhd.screenreader", "com.dianming.phoneapp" -> ReaderControl.describe()
        "com.android.tback", "com.nirenr.talkman" -> "按实际事件使用区域穿透或悬停手势"
        else -> "保留触摸浏览，使用悬停和读屏无障碍动作；用户关闭触摸浏览时使用直接触摸"
    }
}
