package io.github.doubaoa11y.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.github.doubaoa11y.ConfigSpec
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 检查更新：读 GitHub 仓库最新的 Release，有新版就下载模块 apk，用系统安装器安装。
 * 只连 GitHub（api.github.com 和 Release 附件的下载地址），不收集、不上传任何信息。
 */
object Updater {
    private const val REPO = "zzzttt1573/DoubaoA11y"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val ACTION_INSTALLED = "io.github.doubaoa11y.UPDATE_INSTALLED"

    private val main = Handler(Looper.getMainLooper())

    private class Release(val version: String, val notes: String, val apkUrl: String?, val apkSize: Long)

    /** 版本号比较："1.1.10" > "1.1.9" */
    fun newer(a: String, b: String): Boolean {
        val x = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    private fun say(act: Activity, status: TextView, msg: String) {
        status.text = "更新：$msg"
        status.announceForAccessibility(msg)
    }

    private fun buildProgressDialog(
        act: Activity,
        title: String,
        initialMsg: String,
        indeterminate: Boolean,
        onCancel: () -> Unit
    ): Triple<AlertDialog, TextView, ProgressBar> {
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
        }
        val msgView = TextView(act).apply {
            text = initialMsg
            textSize = 16f
            isFocusable = true
            setPadding(0, 0, 0, pad / 2)
        }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = indeterminate
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        box.addView(msgView)
        box.addView(bar)
        val dialog = AlertDialog.Builder(act)
            .setTitle(title)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("取消") { d, _ ->
                d.dismiss()
                onCancel()
            }
            .create()
        dialog.show()
        return Triple(dialog, msgView, bar)
    }

    fun check(act: Activity, status: TextView) {
        say(act, status, "正在检查更新…")
        val cancelled = AtomicBoolean(false)
        val connRef = AtomicReference<HttpURLConnection?>(null)
        val (dialog, _, _) = buildProgressDialog(
            act = act,
            title = "检查更新",
            initialMsg = "正在检查更新，请稍候…",
            indeterminate = true
        ) {
            cancelled.set(true)
            say(act, status, "已取消检查更新")
            Thread { try { connRef.getAndSet(null)?.disconnect() } catch (_: Throwable) {} }.start()
        }

        Thread {
            val r = try {
                fetch(cancelled, connRef)
            } catch (t: Throwable) {
                null
            }
            main.post {
                try { if (dialog.isShowing) dialog.dismiss() } catch (_: Throwable) {}
                if (act.isFinishing || cancelled.get()) return@post
                when {
                    r == null -> AlertDialog.Builder(act)
                        .setTitle("检查更新失败")
                        .setMessage("连不上 GitHub。可能是网络不好，或者 GitHub 在你的网络下打不开。请稍后再试，或者加入QQ群获取最新版。\n$QQ_LINE")
                        .setPositiveButton("复制QQ群号") { _, _ -> copyQq(act) }
                        .setNegativeButton("关闭", null)
                        .show().also { say(act, status, "检查更新失败") }
                    !newer(r.version, ConfigSpec.MODULE_VERSION_NAME) -> {
                        say(act, status, "已是最新版本 ${ConfigSpec.MODULE_VERSION_NAME}")
                        AlertDialog.Builder(act)
                            .setTitle("已是最新版本")
                            .setMessage("当前版本 ${ConfigSpec.MODULE_VERSION_NAME}，GitHub 上最新的是 ${r.version}，不用更新。")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                    else -> offer(act, status, r)
                }
            }
        }.start()
    }

    private fun fetch(cancelled: AtomicBoolean, connRef: AtomicReference<HttpURLConnection?>): Release {
        val c = URL(API).openConnection() as HttpURLConnection
        connRef.set(c)
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "DoubaoA11y-updater")
        try {
            if (cancelled.get()) error("已取消")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = j.optJSONArray("assets")
            var url: String? = null
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", true)) {
                        url = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            return Release(j.optString("tag_name").removePrefix("v"), j.optString("body"), url, size)
        } finally {
            connRef.compareAndSet(c, null)
            c.disconnect()
        }
    }

    private fun offer(act: Activity, status: TextView, r: Release) {
        say(act, status, "发现新版本 ${r.version}")
        val notes = r.notes.replace("\r", "").replace(Regex("[#*`]"), "").trim().take(1500)
        val size = if (r.apkSize > 0) "，安装包 ${"%.1f".format(r.apkSize / 1048576.0)} MB" else ""
        val b = AlertDialog.Builder(act)
            .setTitle("发现新版本 ${r.version}")
            .setMessage("当前 ${ConfigSpec.MODULE_VERSION_NAME}，最新 ${r.version}$size。\n\n" +
                "更新完成后，一定要强行停止一次豆包输入法，新版本才会生效。\n\n$notes")
            .setNegativeButton("以后再说", null)
        if (r.apkUrl != null) b.setPositiveButton("下载并安装") { _, _ -> download(act, status, r) }
        else b.setPositiveButton("用浏览器打开下载页") { _, _ -> openPage(act) }
        b.show()
    }

    private const val QQ_LINE = "QQ群：乐天的居家小屋，${SettingsActivity.QQ_GROUP}"

    /** 把 QQ 群号复制到剪贴板 */
    private fun copyQq(act: Activity) {
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("QQ群号", SettingsActivity.QQ_GROUP))
            Toast.makeText(act, "已复制QQ群号 ${SettingsActivity.QQ_GROUP}", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    fun openPage(act: Activity) {
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)))
        } catch (_: Throwable) {
        }
    }

    private fun download(act: Activity, status: TextView, r: Release) {
        // 安卓 8 起要允许“安装未知应用”
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("需要允许安装应用")
                .setMessage("请允许安装应用")
                .setPositiveButton("去打开") { _, _ ->
                    try {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                    } catch (_: Throwable) {
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        say(act, status, "正在下载 ${r.version}…")
        val file = File(act.cacheDir, "update.apk")
        val cancelled = AtomicBoolean(false)
        val connRef = AtomicReference<HttpURLConnection?>(null)
        val (dialog, msgView, bar) = buildProgressDialog(
            act = act,
            title = "正在下载 ${r.version}",
            initialMsg = "正在连接下载服务器…",
            indeterminate = true
        ) {
            cancelled.set(true)
            say(act, status, "已取消下载")
            Thread {
                try { connRef.getAndSet(null)?.disconnect() } catch (_: Throwable) {}
                try { file.delete() } catch (_: Throwable) {}
            }.start()
        }

        Thread {
            val ok = try {
                save(r.apkUrl!!, file, cancelled, connRef) { pct, done, total ->
                    main.post {
                        if (act.isFinishing || cancelled.get()) return@post
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.progress = pct
                            val doneMb = "%.1f".format(done / 1048576.0)
                            val totalMb = "%.1f".format(total / 1048576.0)
                            msgView.text = "正在下载 $pct%（$doneMb MB / $totalMb MB）"
                            status.text = "更新：正在下载 ${r.version}，$pct%"
                        } else {
                            bar.isIndeterminate = true
                            val doneMb = "%.1f".format(done / 1048576.0)
                            msgView.text = "正在下载（已下载 $doneMb MB）"
                            status.text = "更新：正在下载 ${r.version}…"
                        }
                    }
                }
                true
            } catch (t: Throwable) {
                false
            }
            main.post {
                try { if (dialog.isShowing) dialog.dismiss() } catch (_: Throwable) {}
                if (act.isFinishing || cancelled.get()) {
                    try { file.delete() } catch (_: Throwable) {}
                    return@post
                }
                if (!ok) {
                    try { file.delete() } catch (_: Throwable) {}
                    AlertDialog.Builder(act)
                        .setTitle("下载失败")
                        .setMessage("下载中断了。可以再试一次，或者加入QQ群获取最新版。\n$QQ_LINE")
                        .setPositiveButton("复制QQ群号") { _, _ -> copyQq(act) }
                        .setNegativeButton("关闭", null)
                        .show()
                    say(act, status, "下载失败")
                    return@post
                }
                say(act, status, "下载完成，正在打开安装界面")
                install(act, status, file)
            }
        }.start()
    }

    private fun save(
        url: String,
        out: File,
        cancelled: AtomicBoolean,
        connRef: AtomicReference<HttpURLConnection?>,
        progress: (pct: Int, done: Long, total: Long) -> Unit
    ) {
        var u = URL(url)
        // GitHub 附件会跳转到另一个域名，HttpURLConnection 跨协议不自动跳，这里手动跟
        repeat(5) {
            if (cancelled.get()) error("已取消")
            val c = u.openConnection() as HttpURLConnection
            connRef.set(c)
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "DoubaoA11y-updater")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    u = URL(u, c.getHeaderField("Location") ?: error("跳转没有地址"))
                    return@repeat
                }
                if (code != 200) error("HTTP $code")
                val total = c.contentLengthLong
                var done = 0L
                var lastPct = -1
                var lastReportMs = 0L
                c.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled.get()) error("已取消")
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct && (pct == 100 || now - lastReportMs >= 150L)) {
                                    lastPct = pct
                                    lastReportMs = now
                                    progress(pct, done, total)
                                }
                            } else if (now - lastReportMs >= 300L) {
                                lastReportMs = now
                                progress(0, done, -1L)
                            }
                        }
                    }
                }
                if (cancelled.get()) error("已取消")
                if (total > 0 && done != total) error("没下完")
                return
            } finally {
                connRef.compareAndSet(c, null)
                c.disconnect()
            }
        }
        error("跳转次数太多")
    }

    /** 用系统安装器装下载好的 apk（安装会结束本应用，下次打开时提示强行停止豆包） */
    private fun install(act: Activity, status: TextView, file: File) {
        try {
            val pi = act.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(act.packageName)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("update.apk", 0, file.length()).use { o -> file.inputStream().use { it.copyTo(o) } }
                val filter = IntentFilter(ACTION_INSTALLED)
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                @Suppress("DEPRECATION")
                                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                if (confirm != null) {
                                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    c.startActivity(confirm)
                                }
                            }
                            PackageInstaller.STATUS_SUCCESS -> say(act, status, "更新已安装")
                            else -> {
                                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                                say(act, status, "安装没有完成 $msg")
                                try { c.unregisterReceiver(this) } catch (_: Throwable) {}
                            }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else act.registerReceiver(receiver, filter)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(act, id, Intent(ACTION_INSTALLED).setPackage(act.packageName), flags)
                s.commit(pending.intentSender)
            }
        } catch (t: Throwable) {
            say(act, status, "打开安装界面失败：${t.message}")
        }
    }

    // ---------------- 更新后：提示强行停止豆包输入法 ----------------

    /** 打开豆包输入法的应用信息页（里面有“强行停止”） */
    fun openDoubaoInfo(act: Activity) {
        try {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ConfigSpec.TARGET_PACKAGE}")))
        } catch (_: Throwable) {
        }
    }

    fun forceStopDialog(act: Activity, title: String, msg: String) {
        AlertDialog.Builder(act)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("打开豆包输入法的应用信息") { _, _ -> openDoubaoInfo(act) }
            .setNegativeButton("知道了", null)
            .show()
    }

    /** 这次打开时模块版本比上次高（刚更新过）：提示强行停止豆包输入法 */
    fun afterUpdate(act: Activity) {
        val prefs = act.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val last = prefs.getInt("lastVersionCode", 0)
        prefs.edit().putInt("lastVersionCode", ConfigSpec.MODULE_VERSION_CODE).apply()
        try { File(act.cacheDir, "update.apk").delete() } catch (_: Throwable) {}
        if (last != 0 && last < ConfigSpec.MODULE_VERSION_CODE) {
            forceStopDialog(act, "模块已更新到 ${ConfigSpec.MODULE_VERSION_NAME}",
                "请在豆包输入法的应用信息里点“强行停止”，再弹出一次键盘，新版本才会生效。用 LSPatch 集成模式的，要用新模块重新修补豆包。")
        }
    }
}
