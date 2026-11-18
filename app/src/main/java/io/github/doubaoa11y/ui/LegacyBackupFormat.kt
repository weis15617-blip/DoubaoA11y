package io.github.doubaoa11y.ui

import io.github.doubaoa11y.ConfigSpec
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 社区 2.0.1 Backup.kt 的真实 settings.txt 格式；不推断词条语义或移植调试设置。 */
object LegacyBackupFormat {
    data class Result(val data: BackupFormat.Data, val warnings: List<String>)
    fun parse(bytes: ByteArray): Result {
        require(bytes.size <= BackupFormat.MAX_BYTES) { "社区设置文件超过 1 MB" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        val lines = text.split('\n')
        require(lines.firstOrNull()?.trim() == "DOUBAOA11Y-BACKUP-1") { "不支持的社区备份格式" }
        val booleans = BackupFormat.exportableBooleans().associateBy { it.key }
        val integers = ConfigSpec.intItems.associateBy { it.key }
        val strings = ConfigSpec.strItems.associateBy { it.key }
        val values = LinkedHashMap<String, Any>()
        val seen = HashSet<String>()
        val warnings = LinkedHashSet<String>()
        var icon: Boolean? = null
        for (line in lines.drop(1).filter { it.isNotBlank() }) {
            require(seen.size < 256) { "社区设置条目过多" }
            val parts = line.trimEnd('\r').split('\t', limit = 3)
            require(parts.size == 3 && parts[0] in setOf("b", "i", "s") &&
                parts[1].matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}"))) { "社区设置条目格式错误" }
            val (type, original, raw) = parts
            require(seen.add(original)) { "社区设置存在重复条目：$original" }
            if (original == ConfigSpec.EXTRA_CUSTOM_WORDS) {
                warnings.add("社区解释词使用完整短语，语义与当前词条不同；未导入或转换，保留当前解释词")
                continue
            }
            if (original == "hideShortcut" || original == "showShortcut") {
                require(type == "b" && raw in setOf("true", "false")) { "社区图标设置格式错误" }
                val show = if (original == "hideShortcut") raw == "false" else raw == "true"
                require(icon == null || icon == show) { "社区图标设置相互矛盾" }
                icon = show
                continue
            }
            val key = when (original) {
                "moduleSound" -> "soundOn"
                "sfxVolume" -> "soundVolume"
                "toolbarFullscreenSwitch" -> "screenToolbar"
                else -> original
            }
            val value: Any = when {
                key in booleans -> {
                    require(type == "b" && raw in setOf("true", "false")) { "社区开关格式错误：$original" }
                    raw == "true"
                }
                key in integers -> {
                    require(type == "i") { "社区整数类型错误：$original" }
                    val n = raw.toIntOrNull() ?: error("社区整数格式错误：$original")
                    if (integers.getValue(key).options.none { it.first == n }) {
                        warnings.add("设置 $original 的旧选项不受支持，保留当前值")
                        continue
                    }
                    n
                }
                key in strings -> {
                    require(type == "s") { "社区文本类型错误：$original" }
                    val decoded = unescape(raw)
                    require(decoded.length <= 16000) { "社区文本过长：$original" }
                    if (key == ConfigSpec.EDIT_OPTIONS_KEY && decoded.isNotBlank()) {
                        val ids = decoded.split(',').map { it.trim().toIntOrNull() }
                        if (ids.any { it == null } || ids != ConfigSpec.parseEditOptions(decoded)) {
                            warnings.add("旧编辑选项含不兼容动作，保留当前编辑选项")
                            continue
                        }
                    }
                    decoded
                }
                else -> {
                    warnings.add("未导入调试、固定或不支持的设置：$original")
                    continue
                }
            }
            require(!values.containsKey(key) || values[key] == value) { "社区设置映射冲突：$original" }
            values[key] = value
        }
        return Result(BackupFormat.Data(values, null, icon), warnings.toList())
    }
    private fun unescape(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw[i] != '\\') out.append(raw[i])
            else {
                require(i + 1 < raw.length) { "社区文本转义不完整" }
                out.append(when (raw[++i]) { 'n' -> '\n'; 't' -> '\t'; '\\' -> '\\'; else -> error("社区文本含未知转义") })
            }
            i++
        }
        return out.toString()
    }
}
