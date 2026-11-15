package io.github.doubaoa11y.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.LinearLayout
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.SpeechEngine
import java.io.ByteArrayOutputStream

/** 系统文件选择器管理文件授权，不申请存储权限，不包含运行日志。 */
object SettingsBackup {
    private const val EXPORT = 1201
    private const val IMPORT = 1202
    private val dialogs = java.util.WeakHashMap<SettingsActivity, AlertDialog>()

    fun detach(a: SettingsActivity) { dialogs.remove(a)?.dismiss() }

    fun fill(a: SettingsActivity, root: LinearLayout) {
        root.addView(a.heading("备份与恢复"))
        root.addView(a.note("备份输入方式、手势、语音设置、自定义解释词和桌面图标状态。日志、调试选项和使用须知同意记录不参与备份；恢复前会让你确认"))
        root.addView(a.button("导出设置备份") { pick(a, EXPORT) })
        root.addView(a.button("恢复设置备份") { pick(a, IMPORT) })
    }

    private fun pick(a: SettingsActivity, code: Int) {
        try {
            val i = Intent(if (code == EXPORT) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
            if (code == EXPORT) i.putExtra(Intent.EXTRA_TITLE, "DoubaoA11y-settings.json")
            a.startActivityForResult(i, code)
        } catch (_: Throwable) { a.toast("无法打开系统文件选择器") }
    }

    fun onResult(a: SettingsActivity, code: Int, result: Int, uri: Uri?): Boolean {
        if (code != EXPORT && code != IMPORT) return false
        if (result != Activity.RESULT_OK || uri == null) return true
        if (code == EXPORT) {
            val values = LinkedHashMap<String, Any>()
            try {
                for (i in BackupFormat.exportableBooleans()) values[i.key] = a.prefs.getBoolean(i.key, i.default)
                for (i in ConfigSpec.intItems) values[i.key] = a.prefs.getInt(i.key, i.default)
                for (i in ConfigSpec.strItems) values[i.key] = a.prefs.getString(i.key, i.default) ?: i.default
                val words = a.prefs.getString(ConfigSpec.EXTRA_CUSTOM_WORDS, "").orEmpty()
                val show = Shortcut.shown(a)
                work(a, "正在导出设置") {
                    val bytes = BackupFormat.encode(values, words, show)
                    a.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("无法写入文件")
                    return@work { a.toast("设置备份已导出") }
                }
            } catch (_: Throwable) { a.toast("无法读取当前设置，未导出") }
        } else {
            work(a, "正在检查备份") {
                val bytes = a.contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(out.size() + n <= BackupFormat.MAX_BYTES) { "备份文件超过 1 MB" }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } ?: error("无法读取文件")
                val data = BackupFormat.parse(bytes)
                return@work { confirm(a, data) }
            }
        }
        return true
    }

    private fun confirm(a: SettingsActivity, data: BackupFormat.Data) {
        val icon = when (data.showLauncher) { true -> "显示"; false -> "隐藏"; null -> "保持现状" }
        AlertDialog.Builder(a).setTitle("恢复设置备份")
            .setMessage("将覆盖备份中的 ${data.settings.size} 项设置；自定义解释词${if (data.words == null) "保持现状" else "将被替换"}；桌面图标：$icon。日志和调试开关保持现状。是否恢复？")
            .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ ->
                work(a, "正在恢复设置") {
                    val before = a.prefs.all
                    val oldIcon = Shortcut.shown(a)
                    try {
                        data.showLauncher?.let { Shortcut.setShown(a, it) }
                        val e = a.prefs.edit()
                        for ((key, value) in data.settings) put(e, key, value)
                        data.words?.let { e.putString(ConfigSpec.EXTRA_CUSTOM_WORDS, it) }
                        check(e.commit()) { "保存设置失败" }
                    } catch (t: Throwable) {
                        // commit 失败时内存值也可能已变化，恢复原快照并回滚组件。
                        val e = a.prefs.edit().clear()
                        for ((key, value) in before) put(e, key, value)
                        val restored = e.commit()
                        val iconRestored = runCatching { Shortcut.setShown(a, oldIcon) }.isSuccess
                        if (!restored || !iconRestored) error("恢复失败，回滚未完成，请检查当前设置")
                        throw t
                    }
                    SpeechEngine.stop()
                    return@work { a.rebuild(); a.send(null); a.toast("设置已恢复") }
                }
            }.show()
    }

    private fun put(e: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            is Long -> e.putLong(key, value)
            is Float -> e.putFloat(key, value)
            is String -> e.putString(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") e.putStringSet(key, value as Set<String>) }
        }
    }

    private fun work(a: SettingsActivity, title: String, action: () -> (() -> Unit)) {
        val progress = AlertDialog.Builder(a).setTitle(title).setMessage("请稍候…").setCancelable(false).create().apply { show() }
        dialogs[a] = progress
        Thread({
            val outcome = runCatching(action)
            a.runOnUiThread {
                runCatching { progress.dismiss() }
                if (dialogs[a] === progress) dialogs.remove(a)
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                outcome.fold({ it() }, { a.toast("操作失败：${it.message ?: "无法处理这个文件"}") })
            }
        }, "SettingsBackup").start()
    }
}
