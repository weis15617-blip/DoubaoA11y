package io.github.doubaoa11y.hook

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 运行环境识别：可靠区分原版 APK（LSPosed/Root）与 LSPatch 修补环境。
 *
 * LSPatch sigBypassLevel=2 会通过 liblspatch.so 的 openat 钩子把读取 base.apk 的请求
 * 重定向到 cache/lspatch/origin/ 里的原版 APK，因此不能仅靠 ZipFile(sourceDir) 检查
 * assets/lspatch/config.json，需优先结合系统侧解析的 appComponentFactory / meta-data、
 * 加载器类与 /proc/self/maps 判断。
 */
object RuntimeEnv {
    private const val LSPATCH_FACTORY = "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub"

    data class Info(
        val isLspatch: Boolean,
        val useManager: Boolean? = null,
        val sigBypassLevel: Int? = null,
        val evidence: String = "",
    ) {
        fun describe(): String {
            val abi = if (Process.is64Bit()) "64位" else "32位"
            if (!isLspatch) return "原版APK（LSPosed/Root，$abi）"
            val mode = when (useManager) {
                true -> "外部LSPatch本地模式(useManager=true)"
                false -> "内置LSPatch集成模式(useManager=false)"
                null -> "LSPatch修补环境"
            }
            val sig = if (sigBypassLevel != null) "，sigBypass=$sigBypassLevel" else ""
            val ev = if (evidence.isNotEmpty()) "，依据=$evidence" else ""
            return "$mode（$abi$sig$ev）"
        }
    }

    @Volatile
    private var cachedEarly: Info? = null

    /** HookEntry 早期阶段（尚无 Context）检测当前进程是否运行在 LSPatch 环境 */
    fun detectEarly(appInfo: ApplicationInfo?, classLoader: ClassLoader?): Info {
        cachedEarly?.let { return it }
        val reasons = LinkedHashSet<String>()
        var metaB64: String? = null

        if (Build.VERSION.SDK_INT >= 28) {
            val factory = try {
                appInfo?.appComponentFactory
            } catch (_: Throwable) {
                null
            }
            if (!factory.isNullOrEmpty() && factory.contains("lspatch", ignoreCase = true)) {
                reasons.add("factory")
            }
        }

        try {
            val meta = appInfo?.metaData?.getString("lspatch")
            if (!meta.isNullOrBlank()) {
                metaB64 = meta
                reasons.add("metaData")
            }
        } catch (_: Throwable) {
        }

        if (classLoader != null && Hooker.findClassQuiet(LSPATCH_FACTORY, classLoader) != null) {
            reasons.add("stubClass")
        }

        if (reasons.isEmpty() && procMapsHasLspatch()) {
            reasons.add("procMaps")
        }

        val parsed = metaB64?.let { parseLspatchConfigBase64(it) }
        val info = Info(
            isLspatch = reasons.isNotEmpty(),
            useManager = parsed?.first,
            sigBypassLevel = parsed?.second,
            evidence = reasons.joinToString("+"),
        )
        cachedEarly = info
        return info
    }

    /** 拿到 Context 后补充读取 PackageManager GET_META_DATA 中的 lspatch 配置 */
    fun detect(c: Context): Info {
        val early = cachedEarly
        val reasons = LinkedHashSet<String>()
        if (early != null && early.evidence.isNotEmpty()) {
            reasons.addAll(early.evidence.split('+').filter { it.isNotEmpty() })
        }
        var useManager = early?.useManager
        var sigBypass = early?.sigBypassLevel

        val ai = try {
            @Suppress("DEPRECATION")
            c.packageManager.getApplicationInfo(c.packageName, PackageManager.GET_META_DATA)
        } catch (_: Throwable) {
            c.applicationInfo
        }

        if (Build.VERSION.SDK_INT >= 28) {
            val factory = try {
                ai.appComponentFactory
            } catch (_: Throwable) {
                null
            }
            if (!factory.isNullOrEmpty() && factory.contains("lspatch", ignoreCase = true)) {
                reasons.add("factory")
            }
        }

        val metaB64 = try {
            ai.metaData?.getString("lspatch")
        } catch (_: Throwable) {
            null
        }
        if (!metaB64.isNullOrBlank()) {
            reasons.add("metaData")
            parseLspatchConfigBase64(metaB64)?.let { (um, sb) ->
                if (useManager == null) useManager = um
                if (sigBypass == null) sigBypass = sb
            }
        }

        if (reasons.isEmpty() && Hooker.findClassQuiet(LSPATCH_FACTORY, c.classLoader) != null) {
            reasons.add("stubClass")
        }

        if (reasons.isEmpty() && procMapsHasLspatch()) {
            reasons.add("procMaps")
        }

        // 兜底：若 sigBypassLevel=0/1 未重定向 openat，仍可从 APK 读取 config.json
        if (useManager == null) {
            readConfigFromZip(ai.sourceDir)?.let { (um, sb) ->
                reasons.add("apkAsset")
                useManager = um
                if (sigBypass == null) sigBypass = sb
            }
        }

        return Info(
            isLspatch = reasons.isNotEmpty(),
            useManager = useManager,
            sigBypassLevel = sigBypass,
            evidence = reasons.joinToString("+"),
        )
    }

    private fun procMapsHasLspatch(): Boolean = try {
        var found = false
        File("/proc/self/maps").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.contains("liblspatch.so") || line.contains("/cache/lspatch/")) {
                    found = true
                    break
                }
            }
        }
        found
    } catch (_: Throwable) {
        false
    }

    private fun parseLspatchConfigBase64(b64: String): Pair<Boolean?, Int?>? = try {
        val raw = Base64.decode(b64.trim(), Base64.DEFAULT)
        parseLspatchConfigJson(String(raw, Charsets.UTF_8))
    } catch (_: Throwable) {
        null
    }

    private fun readConfigFromZip(sourceDir: String?): Pair<Boolean?, Int?>? {
        if (sourceDir.isNullOrEmpty()) return null
        return try {
            val apk = File(sourceDir)
            if (!apk.isFile) return null
            ZipFile(apk).use { zf ->
                val e = zf.getEntry("assets/lspatch/config.json") ?: return null
                val txt = zf.getInputStream(e).bufferedReader().use { it.readText() }
                parseLspatchConfigJson(txt)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseLspatchConfigJson(txt: String): Pair<Boolean?, Int?>? = try {
        val j = JSONObject(txt)
        val um = if (j.has("useManager")) j.optBoolean("useManager") else null
        val sb = if (j.has("sigBypassLevel")) j.optInt("sigBypassLevel") else null
        um to sb
    } catch (_: Throwable) {
        null
    }
}
