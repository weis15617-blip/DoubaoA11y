package io.github.doubaoa11y

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Point
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 模块进程侧的读屏接口代理（供 [SettingsProvider] 在校验调用方 UID 为豆包输入法后使用）。
 *
 * 豆包 1.5.6 等高 targetSdk 版本在未声明读屏 `<queries>` 时，输入法进程无法直接绑定
 * 天坦读屏的 `tatans.intent.action.IME_SERVICE` 或向解说/点明/保益发送控制请求。
 * 本模块清单声明了主流读屏的 `<queries>`，可作为兜底代理转发，并在豆包进程死亡时自动撤掉穿透。
 */
object ReaderBridge {
    private const val TATANS_PKG = "com.android.tback"
    private const val TATANS_ACTION = "tatans.intent.action.IME_SERVICE"
    private const val TATANS_DESCRIPTOR = "net.tatans.soundback.ITatansImeService"
    private const val TATANS_CONN_DESCRIPTOR = "net.tatans.soundback.ITatansImeConnection"
    private const val TX_ON_INPUT_MODE_CHANGED = 2
    private const val TX_SET_PASS_THROUGH = 3
    private const val TX_PERFORM_ACTION = 9
    private const val TX_SET_IME_CONNECTION = 10
    private const val ACTION_IME_EXPANDED = 10000
    private const val ACTION_IME_COLLAPSED = 10001
    private const val CONN_TX_GET_CLIP = 4
    private const val CONN_TX_IS_SHOWN = 5

    private const val TALKMAN_PKG = "com.nirenr.talkman"
    private const val TALKMAN_SHOW = "com.nirenr.talkman.ACTION_INPUTMETHOD_SHOW"
    private const val TALKMAN_RECT = "com.nirenr.talkman.EXTRA_RECT_DATA"

    private val lock = Any()
    private var appCtx: Context? = null
    private var remote: IBinder? = null
    private var binding = false
    private var bound = false
    private var pendingRect: Rect? = null
    private var pendingSuppress = false
    private var lastApplied: Rect? = null
    @Volatile
    private var lastSuppressed = false
    private var waitLatch: CountDownLatch? = null
    private var trackedToken: IBinder? = null

    /** 提供给天坦读屏挂 linkToDeath 的连接对象：模块进程异常退出时天坦读屏会自动清零屏蔽与穿透状态 */
    private val imeConnBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(TATANS_CONN_DESCRIPTOR)
                return true
            }
            if (code in 1..CONN_TX_IS_SHOWN) {
                data.enforceInterface(TATANS_CONN_DESCRIPTOR)
                reply?.writeNoException()
                when (code) {
                    CONN_TX_GET_CLIP -> reply?.writeString("")
                    CONN_TX_IS_SHOWN -> reply?.writeInt(if (lastSuppressed) 1 else 0)
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        val ctx = synchronized(lock) { appCtx } ?: return@DeathRecipient
        tatansPassThrough(ctx, Rect(), token = null, disconnect = true, suppress = false)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val ctx: Context?
            val rectToSend: Rect?
            val suppressToSend: Boolean
            val latch: CountDownLatch?
            synchronized(lock) {
                ctx = appCtx
                remote = service
                binding = false
                rectToSend = pendingRect?.let { Rect(it) }
                suppressToSend = pendingSuppress
                latch = waitLatch
                waitLatch = null
            }
            if (service != null) {
                transactSetImeConnection(service)
                if (rectToSend != null) {
                    val ok = applyTatansState(ctx, service, rectToSend, suppressToSend)
                    if (ok) {
                        synchronized(lock) {
                            if (remote === service) {
                                lastApplied = Rect(rectToSend)
                                lastSuppressed = suppressToSend
                            }
                        }
                    }
                }
            }
            latch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val latch: CountDownLatch?
            synchronized(lock) {
                remote = null
                lastApplied = null
                lastSuppressed = false
                latch = waitLatch
                waitLatch = null
            }
            latch?.countDown()
        }

        override fun onBindingDied(name: ComponentName?) {
            val ctx: Context?
            val wasBound: Boolean
            val latch: CountDownLatch?
            synchronized(lock) {
                ctx = appCtx
                remote = null
                binding = false
                wasBound = bound
                bound = false
                lastApplied = null
                lastSuppressed = false
                latch = waitLatch
                waitLatch = null
            }
            latch?.countDown()
            if (wasBound && ctx != null) {
                try {
                    ctx.unbindService(this)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun tatansPassThrough(
        ctx: Context,
        rect: Rect,
        token: IBinder?,
        disconnect: Boolean,
        suppress: Boolean = false,
    ): Bundle {
        val c = ctx.applicationContext ?: ctx
        synchronized(lock) {
            appCtx = c
            trackTokenLocked(token)
        }

        if (disconnect) {
            val b: IBinder?
            val wasBound: Boolean
            val wasSuppressed: Boolean
            val latch: CountDownLatch?
            synchronized(lock) {
                b = remote
                remote = null
                binding = false
                wasBound = bound
                bound = false
                wasSuppressed = lastSuppressed
                pendingRect = null
                pendingSuppress = false
                lastApplied = null
                lastSuppressed = false
                latch = waitLatch
                waitLatch = null
                unlinkTokenLocked()
            }
            latch?.countDown()
            if (b != null) {
                if (wasSuppressed) resetTatansSuppression(b)
                transactPassThrough(b, Rect())
            }
            if (wasBound) {
                try {
                    c.unbindService(conn)
                } catch (_: Throwable) {
                }
            }
            return Bundle().apply {
                putBoolean("bound", false)
                putBoolean("connected", false)
                putBoolean("applied", true)
                putBoolean("suppressed", false)
            }
        }

        val currentRemote: IBinder?
        var needBind = false
        var latch: CountDownLatch? = null
        synchronized(lock) {
            pendingRect = Rect(rect)
            pendingSuppress = suppress
            currentRemote = remote
            if (currentRemote == null) {
                if (!binding) {
                    binding = true
                    needBind = true
                    waitLatch = CountDownLatch(1)
                }
                latch = waitLatch
            }
        }

        if (currentRemote != null) {
            val ok = applyTatansState(c, currentRemote, rect, suppress)
            synchronized(lock) {
                if (remote === currentRemote) {
                    if (ok) {
                        lastApplied = Rect(rect)
                        lastSuppressed = suppress
                    } else {
                        remote = null
                        lastApplied = null
                        lastSuppressed = false
                    }
                }
            }
            return Bundle().apply {
                putBoolean("bound", ok)
                putBoolean("connected", ok)
                putBoolean("applied", ok)
                putBoolean("suppressed", ok && suppress)
            }
        }

        if (needBind) {
            val ok = try {
                c.bindService(Intent(TATANS_ACTION).setPackage(TATANS_PKG), conn, Context.BIND_AUTO_CREATE)
            } catch (_: Throwable) {
                false
            }
            if (!ok) {
                val failLatch: CountDownLatch?
                synchronized(lock) {
                    binding = false
                    bound = false
                    failLatch = waitLatch
                    waitLatch = null
                }
                failLatch?.countDown()
                try {
                    c.unbindService(conn)
                } catch (_: Throwable) {
                }
                return Bundle().apply {
                    putBoolean("bound", false)
                    putBoolean("connected", false)
                    putBoolean("applied", false)
                    putBoolean("suppressed", false)
                }
            }
            synchronized(lock) { bound = true }
        }

        if (latch != null && Looper.myLooper() != Looper.getMainLooper()) {
            try {
                latch.await(250, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }

        val afterRemote: IBinder?
        val alreadyApplied: Boolean
        synchronized(lock) {
            afterRemote = remote
            alreadyApplied = afterRemote != null && lastApplied == rect && lastSuppressed == suppress
        }
        if (alreadyApplied) {
            return Bundle().apply {
                putBoolean("bound", true)
                putBoolean("connected", true)
                putBoolean("applied", true)
                putBoolean("suppressed", suppress)
            }
        }
        if (afterRemote != null) {
            val ok = applyTatansState(c, afterRemote, rect, suppress)
            synchronized(lock) {
                if (remote === afterRemote && ok) {
                    lastApplied = Rect(rect)
                    lastSuppressed = suppress
                }
            }
            return Bundle().apply {
                putBoolean("bound", true)
                putBoolean("connected", ok)
                putBoolean("applied", ok)
                putBoolean("suppressed", ok && suppress)
            }
        }
        return Bundle().apply {
            putBoolean("bound", true)
            putBoolean("connected", false)
            putBoolean("applied", false)
            putBoolean("suppressed", false)
        }
    }

    fun talkmanPassThrough(ctx: Context, rect: Rect): Bundle {
        val c = ctx.applicationContext ?: ctx
        val ok = try {
            val area = if (rect.isEmpty) Rect(-1, 0, 0, 0) else Rect(rect)
            c.sendBroadcast(
                Intent(TALKMAN_SHOW)
                    .putExtra(TALKMAN_RECT, area)
                    .setPackage(TALKMAN_PKG)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            )
            true
        } catch (_: Throwable) {
            false
        }
        return Bundle().apply { putBoolean("applied", ok) }
    }

    fun readerControl(ctx: Context, pkg: String, on: Boolean): Bundle = Bundle().apply {
        try {
            ReaderControlTransport.send(ctx.applicationContext ?: ctx, pkg, on)
            putBoolean("applied", true)
        } catch (t: Throwable) {
            putBoolean("applied", false)
            putString("error", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun trackTokenLocked(token: IBinder?) {
        if (token == null || token === trackedToken) return
        unlinkTokenLocked()
        try {
            token.linkToDeath(deathRecipient, 0)
            trackedToken = token
        } catch (_: Throwable) {
            trackedToken = null
        }
    }

    private fun unlinkTokenLocked() {
        val old = trackedToken ?: return
        trackedToken = null
        try {
            old.unlinkToDeath(deathRecipient, 0)
        } catch (_: Throwable) {
        }
    }

    private fun applyTatansState(ctx: Context?, b: IBinder, rect: Rect, suppress: Boolean): Boolean {
        if (suppress) {
            val fullRect = displayAppSize(ctx) ?: rect
            if (!transactPassThrough(b, fullRect)) return false
            return transactPerformAction(b, ACTION_IME_EXPANDED)
        }
        // 面板打开或撤掉穿透时：先撤穿透、再退出全屏抑制态、再补撤一次，避免天坦仍停留在全屏透传模式
        val ok1 = transactPassThrough(b, rect)
        if (lastSuppressed || rect.isEmpty) resetTatansSuppression(b)
        val ok2 = if (rect.isEmpty) transactPassThrough(b, rect) else ok1
        return ok1 && ok2
    }

    private fun resetTatansSuppression(b: IBinder) {
        transactPerformAction(b, ACTION_IME_COLLAPSED)
        transactInputModeChanged(b, false)
    }

    @Suppress("DEPRECATION")
    private fun displayAppSize(c: Context?): Rect? {
        if (c == null) return null
        return try {
            val wm = c.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
            val p = Point()
            wm.defaultDisplay?.getSize(p)
            if (p.x > 0 && p.y > 0) Rect(0, 0, p.x, p.y) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun transactSetImeConnection(b: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TATANS_DESCRIPTOR)
            data.writeStrongBinder(b)
            data.writeStrongBinder(imeConnBinder)
            b.transact(TX_SET_IME_CONNECTION, data, reply, 0)
            reply.readException()
            true
        } catch (_: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun transactPerformAction(b: IBinder, action: Int): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TATANS_DESCRIPTOR)
            data.writeStrongBinder(b)
            data.writeInt(action)
            data.writeString("")
            b.transact(TX_PERFORM_ACTION, data, reply, 0)
            reply.readException()
            true
        } catch (_: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun transactInputModeChanged(b: IBinder, fullScreen: Boolean): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TATANS_DESCRIPTOR)
            data.writeStrongBinder(b)
            data.writeInt(if (fullScreen) 1 else 0)
            b.transact(TX_ON_INPUT_MODE_CHANGED, data, reply, 0)
            reply.readException()
            true
        } catch (_: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun transactPassThrough(b: IBinder, r: Rect): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            val left = if (r.isEmpty) -1 else r.left
            val top = if (r.isEmpty) 0 else r.top
            val right = if (r.isEmpty) 0 else r.right
            val bottom = if (r.isEmpty) 0 else r.bottom
            data.writeInterfaceToken(TATANS_DESCRIPTOR)
            data.writeStrongBinder(b)
            data.writeInt(left)
            data.writeInt(top)
            data.writeInt(right)
            data.writeInt(bottom)
            b.transact(TX_SET_PASS_THROUGH, data, reply, 0)
            reply.readException()
            true
        } catch (_: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

/** 模块代理和输入法本地共用读屏请求协议。 */
internal object ReaderControlTransport {
    private const val DIANMING = "com.dianming.phoneapp"
    private const val DIANMING_ACTION = "$DIANMING.SpeakServiceForApp"
    val CONTROL_PACKAGES = setOf("com.bjbyhd.voiceback", "com.bjbyhd.screenreader", DIANMING)

    fun send(c: Context, pkg: String, on: Boolean) {
        require(pkg in CONTROL_PACKAGES) { "不支持的读屏包名" }
        if (pkg == DIANMING) {
            startDianming(c, on)
        } else {
            c.sendBroadcast(
                Intent("com.bjbyhd.action.TOUCH_EXPLORATION")
                    .setPackage(pkg)
                    .putExtra("exploration_state", on)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            )
        }
    }

    private fun startDianming(c: Context, on: Boolean) {
        val intent = Intent(DIANMING_ACTION).setPackage(DIANMING)
            .putExtra("TouchExplorationMode", if (on) 1 else 0)
            .putExtra("notifyInput", true)
        val errors = mutableListOf<String>()
        val resolved = try {
            c.packageManager.resolveService(intent, 0)?.serviceInfo?.let {
                ComponentName(it.packageName, it.name)
            }
        } catch (t: Throwable) {
            errors.add("解析服务：${t.javaClass.simpleName}: ${t.message}")
            null
        }
        val fallback = ComponentName(DIANMING, DIANMING_ACTION)
        for (component in listOf(resolved, fallback).distinct()) {
            val name = component?.className ?: "按 action 启动"
            try {
                if (c.startService(Intent(intent).setComponent(component)) != null) return
                errors.add("$name 返回 null")
            } catch (t: Throwable) {
                errors.add("$name：${t.javaClass.simpleName}: ${t.message}")
            }
        }
        error("点明控制服务启动失败：${errors.joinToString("；")}")
    }
}
