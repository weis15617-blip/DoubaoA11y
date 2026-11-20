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
import java.io.Closeable
import java.io.File
import java.io.PushbackInputStream
import java.util.concurrent.atomic.AtomicReference

/** JSON 继续可用；完整 ZIP 的资源与设置全部验证通过后才确认恢复。 */
object SettingsBackup {
    private const val IMPORT = 1202
    private const val EXPORT_ZIP = 1203
    private val dialogs = java.util.WeakHashMap<SettingsActivity, AlertDialog>()
    private data class Plan(val data: BackupFormat.Data, val archive: ZipBackup.Contents? = null,
        val warnings: List<String> = emptyList()) : Closeable {
        override fun close() { archive?.close() }
    }
    fun detach(a: SettingsActivity) {
        dialogs.remove(a)?.dismiss()
        NativeBackupUi.detach(a)
    }
    fun fill(a: SettingsActivity, root: LinearLayout) {
        root.addView(a.heading("备份与恢复"))
        root.addView(a.note("完整 ZIP 包含设置、自定义解释词、图标状态和导入音频。日志、调试和使用须知记录不备份。社区 ZIP 只恢复兼容设置和音频，不转换其解释词。"))
        root.addView(a.button("导出完整备份（ZIP，含导入音频）") { pick(a, EXPORT_ZIP) })
        root.addView(a.button("恢复备份（ZIP）") { pick(a, IMPORT) })
        NativeBackupUi.fill(a, root)
    }
    private fun pick(a: SettingsActivity, code: Int) {
        try {
            val type = if (code == EXPORT_ZIP) "application/zip" else "*/*"
            val i = Intent(if (code == IMPORT) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(type)
            if (code == EXPORT_ZIP) i.putExtra(Intent.EXTRA_TITLE, "DoubaoA11y-full-backup.zip")
            if (code == IMPORT) i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            a.startActivityForResult(i, code)
        } catch (_: Throwable) { a.toast("无法打开系统文件选择器") }
    }
    fun onResult(a: SettingsActivity, code: Int, result: Int, uri: Uri?): Boolean {
        if (NativeBackupUi.onResult(a, code, result, uri)) return true
        if (code !in setOf(IMPORT, EXPORT_ZIP)) return false
        if (result != Activity.RESULT_OK || uri == null) return true
        if (code != IMPORT) {
            val values = LinkedHashMap<String, Any>()
            try {
                for (i in BackupFormat.exportableBooleans()) values[i.key] = a.prefs.getBoolean(i.key, i.default)
                for (i in ConfigSpec.intItems) values[i.key] = a.prefs.getInt(i.key, i.default)
                for (i in ConfigSpec.strItems) values[i.key] = a.prefs.getString(i.key, i.default) ?: i.default
                val words = a.prefs.getString(ConfigSpec.EXTRA_CUSTOM_WORDS, "").orEmpty()
                val show = Shortcut.shown(a)
                work(a, "正在导出备份") {
                    val bytes = BackupFormat.encode(values, words, show)
                    a.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        ZipBackup.write(output, bytes, File(a.filesDir, "sounds"))
                    } ?: error("无法写入文件")
                    return@work { a.toast("备份已导出") }
                }
            } catch (_: Throwable) { a.toast("无法读取当前设置，未导出") }
        } else {
            val pending = AtomicReference<Plan?>()
            work(a, "正在检查备份", { pending.getAndSet(null)?.close() }) {
                val plan = read(a, uri)
                pending.set(plan)
                return@work { pending.set(null); confirm(a, plan) }
            }
        }
        return true
    }
    private fun read(a: SettingsActivity, uri: Uri): Plan {
        val input = a.contentResolver.openInputStream(uri) ?: error("无法读取文件")
        PushbackInputStream(input, 4).use { stream ->
            val head = ByteArray(4)
            var size = 0
            while (size < head.size) { val n = stream.read(head, size, head.size - size); if (n < 0) break; size += n }
            stream.unread(head, 0, size)
            if (size >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4b.toByte()) {
                val archive = ZipBackup.read(stream, a.cacheDir)
                try {
                    val legacy = if (archive.legacy) LegacyBackupFormat.parse(archive.settings) else null
                    val data = legacy?.data ?: BackupFormat.parse(archive.settings)
                    for (file in archive.sounds.values) ZipBackup.validateAudio(file)
                    return Plan(data, archive, legacy?.warnings.orEmpty())
                } catch (t: Throwable) { archive.close(); throw t }
            }
            error("请选择 ZIP 备份文件")
        }
    }
    private fun confirm(a: SettingsActivity, plan: Plan) {
        val data = plan.data
        val icon = when (data.showLauncher) { true -> "显示"; false -> "隐藏"; null -> "保持现状" }
        val audio = when {
            plan.archive == null -> "导入音频保持现状"
            plan.archive.legacy -> "覆盖社区备份内 ${plan.archive.sounds.size} 个音频槽位，其余保持现状"
            else -> "替换全部导入音频，共 ${plan.archive.sounds.size} 个；未包含的槽位恢复内置音频"
        }
        val warnings = plan.warnings.take(24).joinToString("\n") + if (plan.warnings.size > 24) "\n另有 ${plan.warnings.size - 24} 项未导入" else ""
        var restoring = false
        val dialog = AlertDialog.Builder(a).setTitle("恢复备份")
            .setMessage("将覆盖备份中的 ${data.settings.size} 项设置；自定义解释词${if (data.words == null) "保持现状" else "将被替换"}；桌面图标：$icon。$audio。日志和调试保持现状。\n$warnings\n是否恢复？")
            .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ ->
                restoring = true
                work(a, "正在恢复备份") {
                    try {
                        val before = a.prefs.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
                        val oldIcon = Shortcut.shown(a)
                        BackupResourceTransaction.restore(File(a.filesDir, "sounds"), plan.archive?.sounds,
                            plan.archive != null && !plan.archive.legacy,
                            applySettings = {
                                val e = a.prefs.edit()
                                for ((key, value) in data.settings) put(e, key, value)
                                data.words?.let { e.putString(ConfigSpec.EXTRA_CUSTOM_WORDS, it) }
                                check(e.commit()) { "保存设置失败" }
                            }, rollbackSettings = {
                                val e = a.prefs.edit().clear()
                                for ((key, value) in before) put(e, key, value)
                                check(e.commit()) { "设置回滚失败" }
                            }, applyIcon = { data.showLauncher?.let { Shortcut.setShown(a, it) } },
                            rollbackIcon = { Shortcut.setShown(a, oldIcon) })
                        SpeechEngine.stop()
                        return@work { a.rebuild(); a.send(null); a.toast("备份已恢复") }
                    } finally { plan.close() }
                }
            }.create()
        dialog.setOnDismissListener {
            if (dialogs[a] === dialog) dialogs.remove(a)
            if (!restoring) plan.close()
        }
        dialogs[a] = dialog
        dialog.show()
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
    private fun work(a: SettingsActivity, title: String, abandoned: () -> Unit = {}, action: () -> (() -> Unit)) {
        val progress = AlertDialog.Builder(a).setTitle(title).setMessage("请稍候…").setCancelable(false).create().apply { show() }
        dialogs[a] = progress
        Thread({
            val outcome = runCatching(action)
            a.runOnUiThread {
                runCatching { progress.dismiss() }
                if (dialogs[a] === progress) dialogs.remove(a)
                if (a.isFinishing || a.isDestroyed) { abandoned(); return@runOnUiThread }
                outcome.fold({ it() }, { abandoned(); a.toast("操作失败：${it.message ?: "无法处理这个文件"}") })
            }
        }, "SettingsBackup").start()
    }
}
