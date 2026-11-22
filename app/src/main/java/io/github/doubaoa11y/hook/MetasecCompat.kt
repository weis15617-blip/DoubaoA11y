package io.github.doubaoa11y.hook

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicInteger

/**
 * LSPatch 环境兼容层：拦截字节风控库 libmetasec_ml.so，防止其在 LSPatch 重签名/内存 DEX
 * 环境下调用 art::JavaVMExt::VisitRoots 时触发 SIGSEGV 闪退死循环。
 *
 * 设计约束：
 * 1. 严格限定生效范围：仅当 [RuntimeEnv.detectEarly] 确认当前进程处于 LSPatch 环境时才安装；
 *    原版 APK（LSPosed/Root）完全不挂载任何钩子，零副作用。
 * 2. 两层防护：
 *    - 第一层：精准架空 ImeMSSDKInitializer 中返回 void 的 run(...) 初始化入口，避免触发安全库加载；
 *      若未找到该方法仅记录签名日志，绝不盲目架空该类所有方法。
 *    - 第二层：拦截 java.lang.Runtime 的 loadLibrary0 / load0，命中 metasec_ml 时按标准契约抛出
 *      UnsatisfiedLinkError（而非静默 return null 假装加载成功），交给豆包加载器自身 try-catch 处理。
 */
object MetasecCompat {
    private const val LIBRARY = "metasec_ml"
    private const val INIT_TASK_CLASS = "com.bytedance.android.input.common_biz.init.task.ImeMSSDKInitializer"

    private val blockedLoads = AtomicInteger(0)
    private val blockedInits = AtomicInteger(0)

    @Volatile
    private var installed = false

    @Volatile
    private var active = false

    @Volatile
    private var installSummary = "未初始化"

    fun isMetasecLibrary(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        val baseName = fileName.removePrefix("lib").substringBeforeLast('.')
        return baseName == LIBRARY
    }

    /** Runtime.loadLibrary0 / load0 多个重载的库名或路径均为最后一个非空 String 参数 */
    fun libraryName(args: Array<out Any?>): String? =
        args.filterIsInstance<String>().lastOrNull { it.isNotBlank() }

    fun statusLine(): String {
        val base = installSummary
        if (!active) return base
        return "$base，已拦截加载=${blockedLoads.get()}次，已跳过初始化=${blockedInits.get()}次"
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        try {
            val env = RuntimeEnv.detectEarly(lpparam.appInfo, lpparam.classLoader)
            if (!env.isLspatch) {
                active = false
                installSummary = "未启用（原版APK/非LSPatch环境）"
                L.i("MetasecCompat：$installSummary")
                return
            }

            val cl = lpparam.classLoader
            val initState = neutralizeInitTask(cl)
            val (r1, r2) = hookRuntimeLoad(cl)
            active = initState.startsWith("已架空") || r1 || r2
            installSummary = "已启用（LSPatch:${env.evidence}，init=$initState，loadLibrary0=$r1，load0=$r2）"
            L.w("MetasecCompat：$installSummary")
        } catch (t: Throwable) {
            active = false
            installSummary = "安装异常（${t.javaClass.simpleName}: ${t.message ?: ""}）"
            L.e("MetasecCompat 安装失败（已降级，不影响输入法启动）", t)
        }
    }

    private fun hookRuntimeLoad(hostClassLoader: ClassLoader?): Pair<Boolean, Boolean> {
        val runtimeClz = try {
            hostClassLoader?.loadClass("java.lang.Runtime") ?: Runtime::class.java
        } catch (_: Throwable) {
            Runtime::class.java
        }

        val callback = SafeHook("MetasecCompat.runtimeLoad", before = { param ->
            val name = libraryName(param.args)
            if (isMetasecLibrary(name)) {
                val count = blockedLoads.incrementAndGet()
                if (count <= 5 || count % 50 == 0) {
                    L.w("MetasecCompat：已拦截安全库加载（第 $count 次）：$name")
                }
                param.throwable = UnsatisfiedLinkError("Blocked $name for LSPatch compatibility")
            }
        })

        val r1 = hookVoidMethodsByName(runtimeClz, "loadLibrary0", callback)
        val r2 = hookVoidMethodsByName(runtimeClz, "load0", callback)
        return r1 to r2
    }

    private fun neutralizeInitTask(hostClassLoader: ClassLoader?): String {
        val cl = hostClassLoader ?: return "无ClassLoader"
        val cls = Hooker.findClassQuiet(INIT_TASK_CLASS, cl) ?: return "未找到类"
        val runMethods = cls.declaredMethods.filter { it.name == "run" && it.returnType == Void.TYPE }
        if (runMethods.isEmpty()) {
            val sigs = cls.declaredMethods.joinToString(", ") { "${Hooker.sig(it)}:${it.returnType.simpleName}" }
            L.w("MetasecCompat：找到 $INIT_TASK_CLASS 但无 void run()，仅依赖 Runtime 拦截。现有方法：$sigs")
            return "无void-run(降级)"
        }
        var hooked = 0
        val noop = SafeHook("MetasecCompat.initTask", before = { param ->
            val count = blockedInits.incrementAndGet()
            if (count <= 3) {
                L.w("MetasecCompat：已跳过 $INIT_TASK_CLASS.run（第 $count 次）")
            }
            // void 方法在 Xposed before 钩子中设 result = null 即可正常跳过原方法
            param.result = null
        })
        for (m in runMethods) {
            try {
                XposedBridge.hookMethod(m, noop)
                hooked++
            } catch (t: Throwable) {
                L.e("MetasecCompat：hook ${Hooker.sig(m)} 失败", t)
            }
        }
        return if (hooked > 0) "已架空($hooked)" else "hook失败"
    }

    private fun hookVoidMethodsByName(cls: Class<*>, name: String, cb: SafeHook): Boolean {
        val targets = cls.declaredMethods.filter { it.name == name && it.returnType == Void.TYPE }
        if (targets.isEmpty()) return false
        var hooked = 0
        for (m in targets) {
            try {
                XposedBridge.hookMethod(m, cb)
                hooked++
            } catch (t: Throwable) {
                L.e("MetasecCompat：hook ${cls.name}.${Hooker.sig(m)} 失败", t)
            }
        }
        return hooked > 0
    }
}
