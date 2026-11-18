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
                require(extras?.get("screenToolbar") is Boolean)
                putBoolean("saved", p.edit().putBoolean("screenToolbar", extras!!.getBoolean("screenToolbar")).commit())
            }
            "speak" -> Bundle().apply {
                val text = extras?.getString("text").orEmpty()
                require(text.length <= 4000)
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
            else -> throw IllegalArgumentException("不支持的设置操作")
        }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    companion object { val URI: Uri = Uri.parse("content://${ConfigSpec.SETTINGS_AUTHORITY}/config") }
}
