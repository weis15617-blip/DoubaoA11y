package io.github.doubaoa11y

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** 仅向真实目标输入法 UID 提供配置和语音服务，不接受调用方自报的包名。 */
class SettingsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle()
        val uid = Binder.getCallingUid()
        val allowed = uid == Process.myUid() ||
            ctx.packageManager.getPackagesForUid(uid)?.contains(ConfigSpec.TARGET_PACKAGE) == true
        if (!allowed) throw SecurityException("不允许访问模块设置")
        val identity = Binder.clearCallingIdentity()
        try {
        val p = ctx.getSharedPreferences(ConfigSpec.SETTINGS_PREFS, Context.MODE_PRIVATE)
        ConfigSpec.migrateInput(p)
        return when (method) {
            "dump" -> Bundle().apply {
                for (i in ConfigSpec.items) putBoolean(i.key, p.getBoolean(i.key, i.default))
                for (i in ConfigSpec.intItems) putInt(i.key, p.getInt(i.key, i.default))
                for (i in ConfigSpec.strItems) putString(i.key, p.getString(i.key, i.default)?.take(16000))
                putString(ConfigSpec.EXTRA_CUSTOM_WORDS, p.getString(ConfigSpec.EXTRA_CUSTOM_WORDS, "")?.take(200000))
            }
            "setUpperOperation" -> Bundle().apply {
                require(extras?.get("upperOperation") is Boolean)
                putBoolean("saved", p.edit().putBoolean("upperOperation", extras!!.getBoolean("upperOperation")).commit())
            }
            "setToolbar" -> Bundle().apply {
                val ed = p.edit()
                var any = false
                if (extras?.get("screenToolbar") is Boolean) {
                    ed.putBoolean("screenToolbar", extras.getBoolean("screenToolbar"))
                    any = true
                }
                if (extras?.get("editToolbar") is Boolean) {
                    ed.putBoolean("editToolbar", extras.getBoolean("editToolbar"))
                    any = true
                }
                if (extras?.get("toolbarCustomOrder") is String) {
                    ed.putString("toolbarCustomOrder", extras.getString("toolbarCustomOrder").orEmpty().take(4000))
                    any = true
                }
                require(any)
                putBoolean("saved", ed.commit())
            }
            "speak" -> Bundle().apply {
                val text = extras?.getString("text").orEmpty()
                require(text.length <= 200_000)
                val enabled = p.getBoolean("moduleOn", true) && !p.getBoolean("speakByReader", true)
                putBoolean("accepted", enabled && SpeechEngine.speak(ctx, p, text))
            }
            "warmup" -> Bundle().apply {
                if (p.getBoolean("moduleOn", true) && !p.getBoolean("speakByReader", true)) SpeechEngine.prepare(ctx, p)
            }
            "stop" -> Bundle().apply { SpeechEngine.stop() }
            "sound" -> Bundle().apply { putBoolean("accepted", SoundEngine.play(ctx, extras?.getString("event").orEmpty())) }
            "openSound" -> Bundle().apply {
                val id = arg.orEmpty()
                if (io.github.doubaoa11y.ui.ZipBackup.validSlot(id)) {
                    val f = SoundEngine.file(ctx, id)
                    if (f.isFile) {
                        putParcelable("fd", android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY))
                    }
                }
            }
            "openNativeBackupWriteFd" -> Bundle().apply {
                val f = xferFile(ctx, arg.orEmpty()) ?: throw IllegalArgumentException("无效传输令牌")
                f.parentFile?.mkdirs()
                putParcelable(
                    "fd",
                    android.os.ParcelFileDescriptor.open(
                        f,
                        android.os.ParcelFileDescriptor.MODE_READ_WRITE or
                            android.os.ParcelFileDescriptor.MODE_CREATE or
                            android.os.ParcelFileDescriptor.MODE_TRUNCATE
                    )
                )
            }
            "openNativeBackupReadFd" -> Bundle().apply {
                val f = xferFile(ctx, arg.orEmpty()) ?: throw IllegalArgumentException("无效传输令牌")
                if (!f.isFile) throw IllegalStateException("未找到待恢复的中转文件")
                putParcelable(
                    "fd",
                    android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                )
            }
            "tatansPassThrough" -> {
                val rect = android.graphics.Rect(
                    extras?.getInt("left", 0) ?: 0,
                    extras?.getInt("top", 0) ?: 0,
                    extras?.getInt("right", 0) ?: 0,
                    extras?.getInt("bottom", 0) ?: 0
                )
                val token = extras?.getBinder("token")
                val disconnect = extras?.getBoolean("disconnect", false) == true
                val suppress = extras?.getBoolean("suppress", false) == true
                ReaderBridge.tatansPassThrough(ctx, rect, token, disconnect, suppress)
            }
            "talkmanPassThrough" -> {
                val rect = android.graphics.Rect(
                    extras?.getInt("left", 0) ?: 0,
                    extras?.getInt("top", 0) ?: 0,
                    extras?.getInt("right", 0) ?: 0,
                    extras?.getInt("bottom", 0) ?: 0
                )
                ReaderBridge.talkmanPassThrough(ctx, rect)
            }
            "readerControl" -> {
                val pkg = extras?.getString("pkg").orEmpty()
                val on = extras?.getBoolean("on", true) != false
                ReaderBridge.readerControl(ctx, pkg, on)
            }
            else -> throw IllegalArgumentException("不支持的设置操作")
        }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val ctx = context ?: return null
        val segments = uri.pathSegments
        // 允许系统安装器通过本应用签发的一次性令牌只读打开修补版豆包 APK（用于 Android 8–13 通用 ACTION_VIEW 安装）
        if (segments.size == 2 && segments[0] == "patched_apk") {
            if (mode.contains('w')) throw SecurityException("安装包仅允许只读访问")
            val f = resolvePatchedApk(segments[1]) ?: throw SecurityException("安装包访问令牌已失效")
            val identity = Binder.clearCallingIdentity()
            return try {
                if (!f.isFile) throw java.io.FileNotFoundException("未找到待安装的修补包")
                android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        val uid = Binder.getCallingUid()
        val allowed = uid == Process.myUid() ||
            ctx.packageManager.getPackagesForUid(uid)?.contains(ConfigSpec.TARGET_PACKAGE) == true
        if (!allowed) throw SecurityException("不允许访问模块中转文件")
        if (segments.size != 2 || segments[0] != "native_xfer") {
            throw IllegalArgumentException("无效中转路径")
        }
        val f = xferFile(ctx, segments[1]) ?: throw IllegalArgumentException("无效传输令牌")
        val identity = Binder.clearCallingIdentity()
        return try {
            val isWrite = mode.contains('w')
            if (isWrite) {
                f.parentFile?.mkdirs()
                android.os.ParcelFileDescriptor.open(
                    f,
                    android.os.ParcelFileDescriptor.MODE_READ_WRITE or
                        android.os.ParcelFileDescriptor.MODE_CREATE or
                        android.os.ParcelFileDescriptor.MODE_TRUNCATE
                )
            } else {
                if (!f.isFile) throw java.io.FileNotFoundException("未找到待恢复的中转文件")
                android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val segments = uri.pathSegments
        if (segments.size == 2 && segments[0] == "patched_apk") {
            val f = resolvePatchedApk(segments[1]) ?: return null
            if (!f.isFile) return null
            val cols = projection ?: arrayOf(
                android.provider.OpenableColumns.DISPLAY_NAME,
                android.provider.OpenableColumns.SIZE,
            )
            val row = arrayOfNulls<Any>(cols.size)
            for (i in cols.indices) {
                row[i] = when (cols[i]) {
                    android.provider.OpenableColumns.DISPLAY_NAME -> "DoubaoA11y_patched.apk"
                    android.provider.OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            }
            return android.database.MatrixCursor(cols, 1).apply { addRow(row) }
        }
        return null
    }
    override fun getType(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.size == 2 && segments[0] == "patched_apk" && resolvePatchedApk(segments[1]) != null) {
            return "application/vnd.android.package-archive"
        }
        return null
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    companion object {
        val URI: Uri = Uri.parse("content://${ConfigSpec.SETTINGS_AUTHORITY}/config")
        private val TOKEN_RE = Regex("^[a-f0-9]{16,64}$")
        private val apkGrantLock = Any()
        private var activeApkToken: String? = null
        private var activeApkFile: java.io.File? = null

        fun issuePatchedApkUri(apkFile: java.io.File): Uri {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            val token = bytes.joinToString("") { "%02x".format(it) }
            synchronized(apkGrantLock) {
                activeApkToken = token
                activeApkFile = apkFile
            }
            return Uri.parse("content://${ConfigSpec.SETTINGS_AUTHORITY}/patched_apk/$token")
        }

        private fun resolvePatchedApk(token: String): java.io.File? = synchronized(apkGrantLock) {
            if (token.isNotEmpty() && token == activeApkToken) activeApkFile else null
        }

        fun xferUri(token: String): Uri =
            Uri.parse("content://${ConfigSpec.SETTINGS_AUTHORITY}/native_xfer/$token")

        fun xferFile(ctx: Context, token: String): java.io.File? {
            if (!TOKEN_RE.matches(token)) return null
            return java.io.File(java.io.File(ctx.cacheDir, "native_backup_xfer"), "$token.zip")
        }

        fun cleanupXfer(ctx: Context, token: String? = null) {
            val dir = java.io.File(ctx.cacheDir, "native_backup_xfer")
            if (token != null) {
                xferFile(ctx, token)?.delete()
            } else {
                dir.deleteRecursively()
            }
        }
    }
}
