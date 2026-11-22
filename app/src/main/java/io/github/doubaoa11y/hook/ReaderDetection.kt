package io.github.doubaoa11y.hook

import io.github.doubaoa11y.ConfigSpec

object ReaderDetection {
    data class Service(val pkg: String, val name: String, val requestsTouch: Boolean)
    data class State(val readerPackage: String, val touchExploration: Boolean) {
        val readerOn: Boolean get() = touchExploration || readerPackage.isNotEmpty()
    }

    private val readerPackages = ReaderControlPolicy.packages + setOf(
        "com.google.android.marvin.talkback", "com.android.tback", "com.nirenr.talkman",
        "com.samsung.android.accessibility.talkback", "io.github.aaron_gh.sandfly",
    )

    // 自动化工具、跳广告、输入法及其他已知辅助服务。
    private val notReaderPrefixes = listOf(
        "hello.litiaotiao.", "li.songe.gkd", "com.arlosoft.macrodroid", "org.autojs.", "com.stardust.",
        "com.zdanjian.", "com.x7890.", "me.zhanghai.", "bin.mt.",
        "com.miui.", "com.xiaomi.", "com.mipay.", "com.milink.", "com.android.settings", "com.android.systemui",
        "com.google.android.accessibility.switchaccess",
        "com.iflytek.", "com.baidu.input", "com.sohu.inputmethod", "com.tencent.wetype", "com.tatans.inputmethod",
        ConfigSpec.TARGET_PACKAGE,
        "com.ss.android.", "com.xingin.", "tv.danmaku.", "com.netease.uuremote", "com.limelight",
        "com.eureka.nvdapocketremote",
    )
    private val notReaderClasses = listOf("SelectToSpeak", "SwitchAccess", "AccessibilityMenu", "VoiceAccess")

    fun parseServices(value: String?): List<Service> = value.orEmpty().split(':').mapNotNull { entry ->
        val component = entry.trim()
        val slash = component.indexOf('/')
        if (slash <= 0 || slash == component.lastIndex || component.lastIndexOf('/') != slash ||
            component.any { it.isWhitespace() }) return@mapNotNull null
        val pkg = component.substring(0, slash)
        val name = component.substring(slash + 1)
        Service(pkg, if (name.startsWith('.')) pkg + name else name, false)
    }

    fun detect(enabled: Boolean, touch: Boolean, services: List<Service>, setting: String?): State {
        if (!enabled) return State("", false)
        // 同优先级时以系统设置为先，避免切换读屏时列表里尚未更新的旧服务抢占。
        val reader = (parseServices(setting).asSequence() + services.asSequence())
            .filter { it.pkg.isNotEmpty() && it.name.isNotEmpty() }
            .maxByOrNull(::priority)?.takeIf { priority(it) > 0 }
        return State(reader?.pkg.orEmpty(), touch)
    }

    private fun priority(service: Service): Int = with(service) {
        when {
            notReaderClasses.any { name.contains(it) } || notReaderPrefixes.any { pkg.startsWith(it) } -> 0
            pkg in readerPackages -> 4
            requestsTouch -> 3
            name.endsWith("TalkBackService") || name.endsWith("SoundBackService") ||
                name.contains("ScreenReader") -> 2
            else -> 1
        }
    }
}
