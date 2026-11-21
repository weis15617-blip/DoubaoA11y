package io.github.doubaoa11y.ui

import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 仅认识明确的文件与音频槽位；先完整检查临时目录，确认后才接触实际资源。 */
object ZipBackup {
    const val MAX_ENTRIES = 34
    const val MAX_AUDIO = 5 * 1024 * 1024
    const val MAX_TOTAL = 32 * 1024 * 1024
    const val MAX_NATIVE_ZIP = 64 * 1024 * 1024
    const val MAX_UNIFIED_TOTAL = 96 * 1024 * 1024
    const val NATIVE_ENTRY = "native/doubao-native.zip"
    val slots: List<String> = listOf("key", "boundary", "gesture", "double", "scroll", "voice")
    fun validSlot(id: String): Boolean = id in slots || id.matches(Regex("clip_[a-f0-9]{32}"))
    fun audioFiles(dir: File): List<File> = dir.listFiles().orEmpty().filter {
        it.extension == "audio" && validSlot(it.nameWithoutExtension)
    }.sortedBy { it.name }
    private val legacyFiles = listOf("tt_key.ogg", "tt_complete.ogg", "tt_gesture.ogg", "tt_double.ogg", "tt_scroll.ogg", "tt_voice.wav")
        .mapIndexed { i, name -> "sfx/$name" to slots[i] }.toMap()
    data class Contents(
        val settings: ByteArray,
        val legacy: Boolean,
        val sounds: Map<String, File>,
        val directory: File,
        val nativeZip: File? = null,
    ) : Closeable {
        override fun close() {
            check(directory.name.startsWith("backup-checked-")) { "恢复临时目录无效" }
            directory.deleteRecursively()
        }
    }
    fun read(input: InputStream, cache: File): Contents {
        val dir = Files.createTempDirectory(cache.toPath(), "backup-checked-").toFile()
        try {
            var settings: ByteArray? = null
            var legacy: Boolean? = null
            var nativeZip: File? = null
            val sounds = LinkedHashMap<String, File>()
            val names = HashSet<String>()
            var moduleTotal = 0
            var total = 0L
            var compressed = 0L
            val bounded = object : FilterInputStream(input) {
                override fun read(): Int { val n = super.read(); if (n >= 0) account(1); return n }
                override fun read(b: ByteArray, off: Int, len: Int): Int { val n = `in`.read(b, off, len); if (n > 0) account(n); return n }
                private fun account(n: Int) { compressed += n; require(compressed <= MAX_UNIFIED_TOTAL) { "ZIP 文件超过 96 MB" } }
            }
            ZipInputStream(bounded).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(names.size < MAX_ENTRIES) { "ZIP 条目超过上限" }
                    val name = entry.name
                    require(name.isNotEmpty() && name.length <= 128 && !name.startsWith('/') &&
                        !name.contains('\\') && !name.contains(':') && !name.contains('\u0000') &&
                        name.trimEnd('/').split('/').none { it == "." || it == ".." || it.isEmpty() }) { "ZIP 路径不安全" }
                    require(names.add(name)) { "ZIP 存在重复条目" }
                    if (entry.isDirectory) {
                        require(name in setOf("sounds/", "sfx/", "native/") && zip.read() == -1) { "ZIP 目录条目无效" }
                        continue
                    }
                    val isSettings = name == "settings.json" || name == "settings.txt"
                    val isNative = name == NATIVE_ENTRY
                    val slot = if (name in legacyFiles) legacyFiles.getValue(name)
                        else name.removePrefix("sounds/").removeSuffix(".audio").takeIf { validSlot(it) && name == "sounds/$it.audio" }
                    require(isSettings || isNative || slot != null) { "ZIP 包含不支持的文件：$name" }
                    if (isSettings) require(settings == null) { "ZIP 必须仅有一份设置文件" }
                    if (isNative) require(nativeZip == null) { "ZIP 豆包原生备份重复" }
                    if (slot != null) require(!sounds.containsKey(slot)) { "ZIP 音频槽位重复" }
                    val limit = when {
                        isSettings -> BackupFormat.MAX_BYTES
                        isNative -> MAX_NATIVE_ZIP
                        else -> MAX_AUDIO
                    }
                    if (entry.size >= 0) require(entry.size <= limit) { "ZIP 单文件超过上限" }
                    val bytes = if (isSettings) ByteArrayOutputStream() else null
                    val target = when {
                        isNative -> File(dir, "doubao-native.zip")
                        slot != null -> File(dir, "$slot.audio")
                        else -> null
                    }
                    val output = bytes ?: target!!.outputStream()
                    var size = 0
                    output.use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            size += n; total += n
                            if (!isNative) moduleTotal += n
                            require(size <= limit && moduleTotal <= MAX_TOTAL && total <= MAX_UNIFIED_TOTAL) { "ZIP 解压内容超过单文件或总量上限" }
                            out.write(buffer, 0, n)
                        }
                    }
                    require(size > 0) { "ZIP 文件内容为空" }
                    when {
                        isSettings -> { settings = bytes!!.toByteArray(); legacy = name == "settings.txt" }
                        isNative -> nativeZip = target!!
                        else -> sounds[slot!!] = target!!
                    }
                    zip.closeEntry()
                }
                // ZipInputStream 到中央目录即停止；仍要计算剩余输入，不能用尾部填充绕过压缩体积限制。
                val remainder = ByteArray(8192)
                while (bounded.read(remainder) >= 0) { }
            }
            require(settings != null && legacy != null) { "ZIP 缺少设置文件" }
            // 不允许混用两个格式的资源目录，避免同名覆盖的含义不明确。
            require(if (legacy == true) names.none { it.startsWith("sounds/") } else names.none { it.startsWith("sfx/") }) { "ZIP 混合了不兼容格式" }
            return Contents(settings!!, legacy!!, sounds, dir, nativeZip)
        } catch (t: Throwable) { dir.deleteRecursively(); throw t }
    }
    fun validateAudio(file: File) {
        require(file.isFile && file.length() in 1..MAX_AUDIO.toLong()) { "音频为空或超过 5 MB" }
        val meta = MediaMetadataRetriever()
        try {
            meta.setDataSource(file.absolutePath)
            require(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { "文件不含有效音轨" }
            val duration = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            require(duration in 1..5000) { "音频时长必须在 0 到 5 秒之间" }
        } finally { meta.release() }
    }
    fun write(output: OutputStream, settings: ByteArray, sounds: File, nativeZip: File? = null) {
        require(settings.size <= BackupFormat.MAX_BYTES) { "设置超过 1 MB" }
        synchronized(BackupResourceTransaction.lock) {
            val files = audioFiles(sounds).map { it.nameWithoutExtension to it }
            val extraEntries = if (nativeZip != null && nativeZip.isFile) 1 else 0
            require(files.size + 1 + extraEntries <= MAX_ENTRIES) { "音频数量超过备份上限" }
            var total = settings.size.toLong()
            for ((_, file) in files) {
                require(file.canonicalFile.parentFile == sounds.canonicalFile && !Files.isSymbolicLink(file.toPath())) { "音频路径无效" }
                validateAudio(file); total += file.length(); require(total <= MAX_TOTAL) { "模块备份超过 32 MB" }
            }
            if (nativeZip != null && nativeZip.isFile) {
                require(nativeZip.length() in 1L..MAX_NATIVE_ZIP.toLong()) { "豆包原生备份大小无效" }
            }
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json")); zip.write(settings); zip.closeEntry()
                for ((slot, file) in files) {
                    zip.putNextEntry(ZipEntry("sounds/$slot.audio"))
                    file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                if (nativeZip != null && nativeZip.isFile) {
                    zip.putNextEntry(ZipEntry(NATIVE_ENTRY))
                    nativeZip.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
        }
    }
}
