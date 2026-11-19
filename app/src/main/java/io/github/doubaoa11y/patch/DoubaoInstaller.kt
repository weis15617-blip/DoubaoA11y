package io.github.doubaoa11y.patch

import android.app.Activity
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
import android.view.inputmethod.InputMethodManager
import io.github.doubaoa11y.ConfigSpec
import java.io.File
import java.util.WeakHashMap

/**
 * 豆包输入法的卸载、单包/分包安装与系统输入法设置跳转封装。
 */
object DoubaoInstaller {
    private const val ACTION_INSTALL_RESULT = "io.github.doubaoa11y.DOUBAO_INSTALL_RESULT"
    private val main = Handler(Looper.getMainLooper())
    private val activeReceivers = WeakHashMap<Activity, BroadcastReceiver>()

    fun canInstall(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(act: Activity) {
        try {
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
            )
        } catch (_: Throwable) {
            try {
                act.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}"))
                )
            } catch (_: Throwable) {
            }
        }
    }

    fun requestUninstallDoubao(act: Activity): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${ConfigSpec.TARGET_PACKAGE}"))
            act.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun openInputMethodSettings(act: Activity): Boolean {
        return try {
            act.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun showImePicker(act: Activity): Boolean {
        return try {
            val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun detach(act: Activity) {
        val r = activeReceivers.remove(act) ?: return
        try {
            act.unregisterReceiver(r)
        } catch (_: Throwable) {
        }
    }

    /**
     * 使用 PackageInstaller.Session 安装修补后的豆包 APK（支持单 APK 与 Split APKs）。
     */
    fun installPrepared(
        act: Activity,
        prepared: ApkPatcher.PreparedOutput,
        onStatus: (done: Boolean, ok: Boolean, msg: String) -> Unit
    ) {
        val files = mutableListOf<Pair<String, File>>()
        files.add("base.apk" to prepared.baseApk)
        prepared.splitApks.forEachIndexed { idx, f ->
            files.add("split_$idx.apk" to f)
        }

        val mode = if (prepared.isHotUpdate) {
            PackageInstaller.SessionParams.MODE_INHERIT_EXISTING
        } else {
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        }

        Thread {
            try {
                val pi = act.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(mode).apply {
                    setAppPackageName(ConfigSpec.TARGET_PACKAGE)
                    setSize(files.sumOf { it.second.length() })
                }
                val sessionId = pi.createSession(params)
                val session = pi.openSession(sessionId)
                try {
                    for ((name, file) in files) {
                        session.openWrite(name, 0, file.length()).use { out ->
                            file.inputStream().use { it.copyTo(out, 64 * 1024) }
                            session.fsync(out)
                        }
                    }

                    main.post {
                        if (act.isFinishing) {
                            try { session.abandon() } catch (_: Throwable) {}
                            return@post
                        }
                        detach(act)
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(c: Context, intent: Intent) {
                                val status = intent.getIntExtra(
                                    PackageInstaller.EXTRA_STATUS,
                                    PackageInstaller.STATUS_FAILURE
                                )
                                when (status) {
                                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                        @Suppress("DEPRECATION")
                                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                        if (confirm != null) {
                                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            try {
                                                c.startActivity(confirm)
                                                onStatus(false, true, "请在系统弹窗中确认安装豆包输入法")
                                            } catch (t: Throwable) {
                                                detach(act)
                                                onStatus(true, false, "无法打开系统安装界面：${t.message}")
                                            }
                                        }
                                    }
                                    PackageInstaller.STATUS_SUCCESS -> {
                                        detach(act)
                                        ApkPatcher.clearPrepared(act)
                                        onStatus(true, true, "修补版豆包输入法安装成功")
                                    }
                                    PackageInstaller.STATUS_FAILURE_ABORTED -> {
                                        detach(act)
                                        onStatus(true, false, "已取消安装")
                                    }
                                    else -> {
                                        detach(act)
                                        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                                        val friendly = when (status) {
                                            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                                                "安装冲突：请先卸载原版豆包输入法，再点安装"
                                            PackageInstaller.STATUS_FAILURE_STORAGE ->
                                                "手机存储空间不足，请清理空间后重试"
                                            else -> "安装失败${if (detail.isNotEmpty()) "：$detail" else ""}"
                                        }
                                        onStatus(true, false, friendly)
                                    }
                                }
                            }
                        }
                        activeReceivers[act] = receiver
                        val filter = IntentFilter(ACTION_INSTALL_RESULT)
                        if (Build.VERSION.SDK_INT >= 33) {
                            act.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                        } else {
                            act.registerReceiver(receiver, filter)
                        }
                        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                        val pending = PendingIntent.getBroadcast(
                            act,
                            sessionId,
                            Intent(ACTION_INSTALL_RESULT).setPackage(act.packageName),
                            piFlags
                        )
                        session.commit(pending.intentSender)
                        session.close()
                    }
                } catch (t: Throwable) {
                    try { session.abandon() } catch (_: Throwable) {}
                    try { session.close() } catch (_: Throwable) {}
                    throw t
                }
            } catch (t: Throwable) {
                main.post {
                    if (!act.isFinishing) {
                        onStatus(true, false, "打开安装器失败：${t.message ?: t.javaClass.simpleName}")
                    }
                }
            }
        }.start()
    }
}
