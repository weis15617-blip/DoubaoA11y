package io.github.doubaoa11y.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import io.github.doubaoa11y.hook.CharWords

/** 只交换原格式解释词，不改变内置词库，也不自动读取任何读屏词库。 */
object WordTransfer {
    private const val IMPORT = 1301
    private const val EXPORT_CUSTOM = 1302
    private const val EXPORT_ALL = 1303
    private val dialogs = java.util.WeakHashMap<SettingsActivity, AlertDialog>()

    fun detach(a: SettingsActivity) { dialogs.remove(a)?.dismiss() }

    fun fill(a: SettingsActivity, root: LinearLayout) {
        root.addView(a.button("导入解释词") { pick(a, IMPORT) })
        root.addView(a.button("导出解释词") {
            AlertDialog.Builder(a).setTitle("导出解释词")
                .setItems(arrayOf("自定义解释词", "全部解释词（内置和自定义）")) { _, i ->
                    pick(a, if (i == 0) EXPORT_CUSTOM else EXPORT_ALL)
                }.setNegativeButton("取消", null).show()
        })
    }

    private fun pick(a: SettingsActivity, code: Int) {
        try {
            val intent = Intent(if (code == IMPORT) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(if (code == IMPORT) "*/*" else "text/tab-separated-values")
            if (code == IMPORT) intent.putExtra(Intent.EXTRA_MIME_TYPES,
                arrayOf("text/plain", "text/tab-separated-values", "application/octet-stream"))
            else intent.putExtra(Intent.EXTRA_TITLE, if (code == EXPORT_CUSTOM) "DoubaoA11y-custom-words.tsv" else "DoubaoA11y-all-words.tsv")
            a.startActivityForResult(intent, code)
        } catch (_: Throwable) { a.toast("无法打开系统文件选择器") }
    }

    fun onResult(a: SettingsActivity, code: Int, result: Int, uri: Uri?): Boolean {
        if (code !in setOf(IMPORT, EXPORT_CUSTOM, EXPORT_ALL)) return false
        if (result != Activity.RESULT_OK || uri == null) return true
        if (code == IMPORT) {
            work(a, "正在检查解释词") {
                val imported = a.contentResolver.openInputStream(uri)?.use { WordTransferFormat.read(it) } ?: error("无法读取文件")
                return@work { confirm(a, imported) }
            }
        } else {
            val custom = current(a)
            work(a, "正在导出解释词") {
                val words = LinkedHashMap<Char, String>()
                if (code == EXPORT_ALL) words.putAll(CharWords.builtIn())
                words.putAll(custom)
                val bytes = WordTransferFormat.encode(words)
                a.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("无法写入文件")
                return@work { a.toast("已导出 ${words.size} 个解释词") }
            }
        }
        return true
    }

    /** 与原 WordsPage.load 完全相同地读取已有自定义；导出及合并结果仍需严格校验。 */
    private fun current(a: SettingsActivity): Map<Char, String> {
        val words = LinkedHashMap<Char, String>()
        for (line in a.prefs.getString(SettingsActivity.PREF_WORDS, "").orEmpty().split('\n')) {
            if (line.length >= 3 && line[1] == '\t') words[line[0]] = line.substring(2)
        }
        return words
    }

    private fun confirm(a: SettingsActivity, imported: Map<Char, String>) {
        val before = current(a)
        val covered = imported.keys.count { it in before }
        val removed = before.keys.count { it !in imported }
        val message = "已检查 ${imported.size} 个解释词，其中 $covered 个字已自定义。\n" +
            "合并：保留现有自定义，导入覆盖同字。\n" +
            "替换自定义：移除现有但未导入的 $removed 个字，只保留本次导入。\n内置词库始终保留。" +
            if (imported.isEmpty()) "\n文件没有词条；选择替换将清空自定义解释词。" else ""
        val dialog = AlertDialog.Builder(a).setTitle("导入解释词").setMessage(message)
            .setNegativeButton("取消", null)
            .setNeutralButton("替换自定义") { _, _ -> restore(a, before, imported, true) }
            .setPositiveButton("合并（覆盖同字）") { _, _ -> restore(a, before, imported, false) }.create()
        dialogs[a] = dialog
        dialog.setOnDismissListener { if (dialogs[a] === dialog) dialogs.remove(a) }
        dialog.show()
    }

    private fun restore(a: SettingsActivity, before: Map<Char, String>, imported: Map<Char, String>, replace: Boolean) {
        // 验证及写入同在 UI 线程执行，确认后不会与本页的逐字编辑交错。
        try {
            check(current(a) == before) { "自定义解释词已变化，请重新导入并确认" }
            val change = WordTransferFormat.plan(before, imported, replace)
            val hadKey = a.prefs.contains(SettingsActivity.PREF_WORDS)
            val old = a.prefs.getString(SettingsActivity.PREF_WORDS, "")
            try {
                check(a.prefs.edit().putString(SettingsActivity.PREF_WORDS, change.text).commit()) { "保存失败" }
            } catch (failure: Throwable) {
                try {
                    val rollback = a.prefs.edit()
                    if (!hadKey) rollback.remove(SettingsActivity.PREF_WORDS) else rollback.putString(SettingsActivity.PREF_WORDS, old)
                    check(rollback.commit()) { "保存失败且回滚未完成，请检查自定义解释词" }
                } catch (rollback: Throwable) {
                    failure.addSuppressed(rollback)
                    throw IllegalStateException("保存失败且回滚未完成，请检查自定义解释词", failure)
                }
                error("保存失败，已保留原自定义解释词")
            }
            a.send(null)
            a.rebuild()
            a.toast("已导入 ${imported.size} 个，覆盖 ${change.overwritten} 个；现有 ${change.total} 个自定义解释词")
        } catch (t: Throwable) { a.toast("导入失败：${t.message ?: "无法处理这个文件"}") }
    }

    private fun work(a: SettingsActivity, title: String, action: () -> (() -> Unit)) {
        val progress = AlertDialog.Builder(a).setTitle(title).setMessage("请稍候…").setCancelable(false).create()
        dialogs[a] = progress
        progress.show()
        Thread({
            val outcome = runCatching(action)
            a.runOnUiThread {
                runCatching { progress.dismiss() }
                if (dialogs[a] === progress) dialogs.remove(a)
                if (!a.isFinishing && !a.isDestroyed) outcome.fold({ it() }, { a.toast("操作失败：${it.message ?: "无法处理这个文件"}") })
            }
        }, "WordTransfer").start()
    }
}
