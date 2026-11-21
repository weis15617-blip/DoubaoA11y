package io.github.doubaoa11y.hook

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** 独立版本的接口协议按动作名重新实现；不使用其控制循环或资源。 */
object ReaderControl {
    private var context: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var owner = ""
    private var visible = false
    private var polling = false
    private var retryAfter = 0L
    val requested: Boolean get() = owner.isNotEmpty()
    private val packages = setOf("com.bjbyhd.voiceback", "com.bjbyhd.screenreader", "com.dianming.phoneapp")
    fun init(c: Context) {
        context = c.applicationContext ?: c
        owner = c.getSharedPreferences("reader_control_recovery", Context.MODE_PRIVATE).getString("owner", "").orEmpty().takeIf { it in packages }.orEmpty()
        if (owner.isNotEmpty()) restore()
    }
    fun shown() { visible = true; sync(); if (!polling) { polling = true; main.post(tick) } }
    fun hidden() { visible = false; main.removeCallbacks(tick); polling = false; restore() }
    private val tick = object : Runnable {
        override fun run() { sync(); ModuleEntries.toolbar(); if (visible) main.postDelayed(this, 300) else polling = false }
    }
    fun sync() {
        try {
            val c = context ?: return
            val pkg = A11yState.readerPackage
            val awake = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            val want = visible && awake && FullscreenMode.active && A11yState.recording &&
                Config.typeMode != io.github.doubaoa11y.ConfigSpec.TYPE_DOUBLE_TAP && Config.b("tatansFast") && !TatansBridge.panelOpen() && pkg in packages
            if (owner.isNotEmpty() && (!want || owner != pkg)) { if (!restore()) return }
            if (want && owner.isEmpty() && !A11yState.explorationOff && android.os.SystemClock.uptimeMillis() >= retryAfter) {
                // 不恢复用户原本关闭的触摸浏览，仅恢复本模块实际请求关闭的服务。
                // 先保存恢复信息，避免正常重启后遗留全局状态。
                if (c.getSharedPreferences("reader_control_recovery", Context.MODE_PRIVATE).edit().putString("owner", pkg).commit()) {
                    owner = pkg
                    if (!send(pkg, false)) { restore(); retryAfter = android.os.SystemClock.uptimeMillis() + 5000 }
                }
            }
        } catch (t: Throwable) { L.hookError("ReaderControl.sync", t); restore() }
    }
    private fun restore(): Boolean = try {
        if (owner.isEmpty()) true else if (!send(owner, true)) false else {
            context?.getSharedPreferences("reader_control_recovery", Context.MODE_PRIVATE)?.edit()?.remove("owner")?.apply()
            owner = ""
            true
        }
    } catch (t: Throwable) { L.hookError("ReaderControl.restore", t); false }
    private val proxyUri = android.net.Uri.parse("content://${io.github.doubaoa11y.ConfigSpec.SETTINGS_AUTHORITY}/config")

    private fun canSeePackage(c: Context, pkg: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return true
        return try {
            c.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun sendViaProxy(c: Context, pkg: String, on: Boolean): Boolean = try {
        c.contentResolver.call(
            proxyUri,
            "readerControl",
            null,
            android.os.Bundle().apply {
                putString("pkg", pkg)
                putBoolean("on", on)
            }
        )?.getBoolean("applied", false) == true
    } catch (_: Throwable) {
        false
    }

    private fun send(pkg: String, on: Boolean): Boolean = try {
        val c = context
        if (c == null) false else {
            val viaProxy: Boolean
            if (!canSeePackage(c, pkg)) {
                check(sendViaProxy(c, pkg, on)) { "模块代理未能向 $pkg 发送控制请求" }
                viaProxy = true
            } else if (pkg == "com.dianming.phoneapp") {
                val i = Intent("com.dianming.phoneapp.SpeakServiceForApp").setPackage(pkg)
                    .putExtra("TouchExplorationMode", if (on) 1 else 0).putExtra("notifyInput", true)
                if (c.startService(i) != null) {
                    viaProxy = false
                } else {
                    check(sendViaProxy(c, pkg, on)) { "点明没有接受控制服务请求" }
                    viaProxy = true
                }
            } else {
                c.sendBroadcast(Intent("com.bjbyhd.action.TOUCH_EXPLORATION").setPackage(pkg)
                    .putExtra("exploration_state", on).addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
                viaProxy = false
            }
            L.i("读屏接口请求${if (viaProxy) "（代理）" else ""}：$pkg ${if (on) "恢复触摸浏览" else "直接触摸"}；响应以实际事件为准")
            true
        }
    } catch (t: Throwable) { L.hookError("ReaderControl.request", t); false }
    fun describe() = if (requested) "已请求保益/点明直接触摸，响应以实际事件为准" else "保留读屏触摸浏览"
}
