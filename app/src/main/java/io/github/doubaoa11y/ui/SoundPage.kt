package io.github.doubaoa11y.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.LinearLayout
import io.github.doubaoa11y.ConfigSpec
import io.github.doubaoa11y.SoundEngine
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors

object SoundPage {
    private const val IMPORT = 4813
    private const val EXPORT = 4814
    private val worker = Executors.newSingleThreadExecutor()
    private fun sounds(a: SettingsActivity) = SoundEngine.slots.map { "builtin.$it" } +
        SoundEngine.imports(a).map { it.nameWithoutExtension }

    fun fill(a: SettingsActivity, root: LinearLayout) {
        a.addKeys(root, "soundOn", "soundVolume")
        root.addView(a.switchOf(ConfigSpec.items.first { it.key == "ttsA11yStream" }))
        root.addView(a.heading("每种操作使用的声音"))
        for ((event, key) in SoundEngine.keys) {
            val title = ConfigSpec.intItems.first { it.key == key }.label
            val selected = SoundEngine.selection(a, event)
            root.addView(a.button("$title：${SoundEngine.label(a, selected)}") {
                val ids = sounds(a)
                AlertDialog.Builder(a).setTitle(title).setSingleChoiceItems(
                    ids.map { SoundEngine.label(a, it) }.toTypedArray(), ids.indexOf(selected)) { dialog, index ->
                    a.prefs.edit().putString(key + "Id", ids[index]).apply()
                    dialog.dismiss()
                    a.send(null)
                    a.rebuild()
                }.setNegativeButton("取消", null).show()
            })
        }
        root.addView(a.heading("音效管理"))
        manage(a, root)
    }

    fun manage(a: SettingsActivity, root: LinearLayout) {
        val ids = sounds(a)
        root.addView(a.button("导入音频") { pick(a, IMPORT, null) })
        root.addView(a.button("导出音频") {
            AlertDialog.Builder(a).setTitle("选择要导出的音效").setItems(
                ids.map { SoundEngine.label(a, it) }.toTypedArray()) { _, i -> pick(a, EXPORT, ids[i]) }
                .setNegativeButton("取消", null).show()
        })
        root.addView(a.note("点击试听，长按导入的音效可删除或重命名"))
        root.addView(android.widget.TextView(a).apply {
            text = "共 ${ids.size} 个音效"
            isFocusable = true
            setPadding(0, a.pad / 4, 0, a.pad / 4)
        })
        a.addList(
            root,
            ids.map { SoundEngine.label(a, it) },
            onClick = { index -> SoundEngine.play(a, ids[index], true, true) },
            onLongClick = { index ->
                val id = ids[index]
                if (id.startsWith("builtin.")) {
                    false
                } else {
                    val name = SoundEngine.label(a, id)
                    AlertDialog.Builder(a).setTitle(name).setItems(arrayOf("重命名", "删除")) { _, action ->
                        if (action == 0) {
                            val input = EditText(a).apply { setSingleLine(); setText(name); selectAll() }
                            AlertDialog.Builder(a).setTitle("重命名").setView(input).setPositiveButton("保存") { _, _ ->
                                val label = input.text.toString().trim()
                                if (label.isEmpty() || label.length > 80) a.toast("名称应为 1 到 80 个字符")
                                else {
                                    synchronized(BackupResourceTransaction.lock) {
                                        a.prefs.edit().putString("soundNames", SoundEngine.names(a).put(id, label).toString()).apply()
                                    }
                                    a.send(null)
                                    a.rebuild()
                                }
                            }.setNegativeButton("取消", null).show()
                        } else AlertDialog.Builder(a).setTitle("删除“$name”？").setMessage("使用它的操作将恢复默认音效。")
                            .setPositiveButton("删除") { _, _ -> remove(a, id) }.setNegativeButton("取消", null).show()
                    }.show()
                    true
                }
            },
        )
    }

    private fun remove(a: SettingsActivity, id: String) {
        synchronized(BackupResourceTransaction.lock) {
            val used = SoundEngine.keys.filterKeys { SoundEngine.selection(a, it) == id }
            SoundEngine.stop()
            if (!SoundEngine.file(a, id).delete()) { a.toast("删除失败"); return }
            val edit = a.prefs.edit()
            for ((event, key) in used) edit.putString(key + "Id", "builtin.$event")
            val names = SoundEngine.names(a).apply { remove(id) }
            edit.putString("soundNames", names.toString()).apply()
        }
        a.send(null)
        a.rebuild()
    }

    private fun pick(a: SettingsActivity, request: Int, id: String?) {
        try {
            val intent = Intent(if (id == null) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT)
                .setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE)
            if (id != null) {
                a.prefs.edit().putString("pendingSoundExport", id).apply()
                val ext = if (id.startsWith("builtin.")) "." + SoundEngine.assets[SoundEngine.slots.indexOf(id.removePrefix("builtin."))].substringAfterLast('.') else ""
                intent.putExtra(Intent.EXTRA_TITLE, SoundEngine.label(a, id) + ext)
            }
            a.startActivityForResult(intent, request)
        } catch (_: Throwable) { a.toast("无法打开文件选择器") }
    }

    fun onResult(a: SettingsActivity, request: Int, result: Int, uri: Uri?): Boolean {
        if (request != IMPORT && request != EXPORT) return false
        val id = a.prefs.getString("pendingSoundExport", "").orEmpty()
        a.prefs.edit().remove("pendingSoundExport").apply()
        if (result != Activity.RESULT_OK || uri == null) return true
        worker.execute {
            val message = try {
                if (request == EXPORT) {
                    val input = if (id.startsWith("builtin.")) {
                        val index = SoundEngine.slots.indexOf(id.removePrefix("builtin."))
                        a.assets.open("sfx/" + SoundEngine.assets[index])
                    } else SoundEngine.file(a, id).inputStream()
                    input.use { source -> requireNotNull(a.contentResolver.openOutputStream(uri, "wt")).use { source.copyTo(it) } }
                    "音频已导出"
                } else {
                    val name = a.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }.orEmpty().trim().take(80).ifEmpty { "导入音频" }
                    val temp = File.createTempFile("sound-", ".audio", a.cacheDir)
                    try {
                        requireNotNull(a.contentResolver.openInputStream(uri)).use { source ->
                            temp.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var size = 0
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    size += count
                                    require(size <= ZipBackup.MAX_AUDIO) { "音频不能超过 5 MB" }
                                    out.write(buffer, 0, count)
                                }
                            }
                        }
                        ZipBackup.validateAudio(temp)
                        synchronized(BackupResourceTransaction.lock) {
                            val files = SoundEngine.imports(a)
                            require(files.size + 2 <= ZipBackup.MAX_ENTRIES && files.sumOf { it.length() } + temp.length() < ZipBackup.MAX_TOTAL - BackupFormat.MAX_BYTES) { "音效库超过完整备份容量" }
                            val key = "clip_" + UUID.randomUUID().toString().replace("-", "")
                            val target = SoundEngine.file(a, key)
                            target.parentFile!!.mkdirs()
                            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                            a.prefs.edit().putString("soundNames", SoundEngine.names(a).put(key, name).toString()).apply()
                        }
                    } finally { temp.delete() }
                    "音频已导入"
                }
            } catch (e: Exception) { "音频操作失败：${e.message.orEmpty()}" }
            a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) { a.send(null); a.toast(message); a.rebuild() } }
        }
        return true
    }
}
