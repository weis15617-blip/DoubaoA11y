package io.github.doubaoa11y.hook

import io.github.doubaoa11y.ReaderControlTransport

/** 保益和点明的触摸浏览归属及重试策略。由输入法主线程调用。 */
class ReaderControlPolicy {
    companion object {
        val packages = ReaderControlTransport.CONTROL_PACKAGES
        const val RETRY_MS = 5000L
    }

    data class Conditions(
        val pkg: String,
        val fullscreen: Boolean,
        val awake: Boolean,
        val recording: Boolean,
        val doubleTap: Boolean,
        val panelOpen: Boolean,
        val explorationOn: Boolean,
    ) {
        // 实际挂载的全屏已校验键盘可见性，显示回调无需再次作为门槛。
        val wantsPause: Boolean
            get() = fullscreen && awake && recording && !doubleTap && !panelOpen && pkg in packages
    }

    var owner = ""
        private set
    private var restoring = false
    private var retryAfter = 0L

    fun recover(pkg: String) {
        owner = pkg.takeIf { it in packages }.orEmpty()
        restoring = owner.isNotEmpty()
    }

    fun sync(
        state: Conditions,
        now: () -> Long,
        saveOwner: (String) -> Boolean,
        send: (String, Boolean) -> Boolean,
    ) {
        if (owner.isNotEmpty() && (restoring || !state.wantsPause || owner != state.pkg)) {
            if (!restore(saveOwner, send)) return
        }
        if (!state.wantsPause || owner.isNotEmpty() || !state.explorationOn || now() < retryAfter) return

        // 先留恢复记录，避免发送后进程退出时丢失恢复目标。
        if (saveOwner(state.pkg) && send(state.pkg, false)) {
            owner = state.pkg
        } else {
            retryAfter = now() + RETRY_MS
            saveOwner("")
        }
    }

    fun restore(saveOwner: (String) -> Boolean, send: (String, Boolean) -> Boolean): Boolean {
        if (owner.isEmpty()) return true
        restoring = true
        if (!send(owner, true)) return false
        owner = ""
        restoring = false
        saveOwner("")
        return true
    }
}
