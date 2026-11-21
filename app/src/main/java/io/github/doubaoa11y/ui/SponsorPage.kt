package io.github.doubaoa11y.ui

import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.doubaoa11y.ConfigSpec
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 赞赏支持页：
 * 1. 内置赞赏码图片，点击图片或下方按钮可保存到系统相册；
 * 2. 赞赏名单（昵称 + 留言）从 GitHub 仓库的 data/sponsors.json 实时拉取（jsDelivr 国内加速多源回退），
 *    本地缓存保底，无需更新模块即可随时增改名单。
 */
object SponsorPage {
    private const val ASSET_QR = "sponsor_qr.png"
    private const val PREF_CACHE = "sponsorListCache"
    private const val MAX_BYTES = 256 * 1024
    private const val REPO = "zzzttt1573/DoubaoA11y"
    private const val DATA_PATH = "data/sponsors.json"

    private val URLS = listOf(
        "https://fastly.jsdelivr.net/gh/$REPO@main/$DATA_PATH",
        "https://gcore.jsdelivr.net/gh/$REPO@main/$DATA_PATH",
        "https://cdn.jsdelivr.net/gh/$REPO@main/$DATA_PATH",
        "https://raw.githubusercontent.com/$REPO/main/$DATA_PATH",
    )

    private val main = Handler(Looper.getMainLooper())

    private data class SponsorItem(
        val name: String,
        val note: String,
    )

    private data class SponsorData(
        val updatedAt: String,
        val notice: String,
        val sponsors: List<SponsorItem>,
    )

    fun fill(a: SettingsActivity, root: LinearLayout) {
        root.addView(
            a.note(
                "感谢使用豆包输入法无障碍模块！如果这个模块帮到了你，期待你的赞赏支持，帮助我持续维护。让我们一起把模块做的更好，你的鼓励是我前进最大的动力！"
            )
        )

        val qrBitmap = runCatching {
            a.assets.open(ASSET_QR).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

        if (qrBitmap != null) {
            val maxSide = minOf(
                a.resources.displayMetrics.widthPixels - a.pad * 2,
                (280 * a.resources.displayMetrics.density).toInt(),
            ).coerceAtLeast(1)
            val qrView = ImageView(a).apply {
                setImageBitmap(qrBitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                isFocusable = true
                contentDescription = "作者赞赏码，点击保存到相册"
                setOnClickListener { saveQrCode(a) }
            }
            val lp = LinearLayout.LayoutParams(maxSide, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = a.pad / 2
                bottomMargin = a.pad / 2
            }
            root.addView(qrView, lp)
        } else {
            root.addView(a.note("赞赏码图片加载失败"))
        }

        root.addView(a.button("保存赞赏码到相册") { saveQrCode(a) })

        root.addView(a.heading("赞赏名单"))

        val metaView = TextView(a).apply {
            isFocusable = true
            setPadding(0, a.pad / 4, 0, a.pad / 4)
        }
        root.addView(metaView)

        val listBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        val refreshBtn = a.button("刷新名单") {}
        root.addView(refreshBtn)
        root.addView(listBox)

        fun render(data: SponsorData?, loading: Boolean, failed: Boolean) {
            listBox.removeAllViews()
            if (data == null) {
                metaView.text = when {
                    loading -> "正在加载赞赏名单…"
                    failed -> "暂时无法获取赞赏名单，请检查网络后点击刷新。"
                    else -> "暂无赞赏名单"
                }
                return
            }
            val metaParts = ArrayList<String>()
            if (data.notice.isNotBlank()) metaParts.add(data.notice)
            val countLine = buildString {
                append("共 ${data.sponsors.size} 位赞赏者")
                if (data.updatedAt.isNotBlank()) append("（更新于 ${data.updatedAt}）")
                if (loading) append(" · 正在刷新…")
            }
            metaParts.add(countLine)
            metaView.text = metaParts.joinToString("\n")

            if (data.sponsors.isEmpty()) {
                listBox.addView(a.note("名单整理中，感谢每一位支持与使用本模块的朋友！"))
                return
            }
            a.addList(
                listBox,
                data.sponsors.map { item ->
                    if (item.note.isBlank()) item.name else "${item.name}\n${item.note}"
                },
                descriptions = data.sponsors.map { item ->
                    if (item.note.isBlank()) item.name else "${item.name}，留言：${item.note}"
                },
                onClick = { idx ->
                    val item = data.sponsors[idx]
                    val text = if (item.note.isBlank()) item.name else "${item.name}：${item.note}"
                    a.toast(text)
                },
            )
        }

        fun loadRemote(manual: Boolean) {
            val cached = readCache(a)
            render(cached, loading = true, failed = false)
            refreshBtn.isEnabled = false
            Thread({
                val fetched = fetchRemote()
                main.post {
                    if (a.isFinishing || a.isDestroyed) return@post
                    refreshBtn.isEnabled = true
                    if (fetched != null) {
                        val (raw, data) = fetched
                        a.prefs.edit().putString(PREF_CACHE, raw).apply()
                        render(data, loading = false, failed = false)
                        if (manual) {
                            val msg = "赞赏名单已刷新，共 ${data.sponsors.size} 位"
                            a.toast(msg)
                            metaView.announceForAccessibility(msg)
                        }
                    } else {
                        render(cached, loading = false, failed = true)
                        if (manual) {
                            val msg = "刷新赞赏名单失败，请检查网络连接"
                            a.toast(msg)
                            metaView.announceForAccessibility(msg)
                        }
                    }
                }
            }, "FetchSponsors").start()
        }

        refreshBtn.setOnClickListener { loadRemote(manual = true) }

        val cached = readCache(a)
        render(cached, loading = true, failed = false)
        loadRemote(manual = false)
    }

    private fun readCache(a: SettingsActivity): SponsorData? {
        val raw = a.prefs.getString(PREF_CACHE, null) ?: return null
        return runCatching { parse(raw) }.getOrNull()
    }

    private fun fetchRemote(): Pair<String, SponsorData>? {
        for (urlStr in URLS) {
            val res = runCatching {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    useCaches = false
                    setRequestProperty("User-Agent", "DoubaoA11y/${ConfigSpec.MODULE_VERSION_NAME}")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                try {
                    if (conn.responseCode != 200) return@runCatching null
                    val baos = ByteArrayOutputStream()
                    conn.inputStream.use { input ->
                        val buf = ByteArray(4096)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) return@runCatching null
                            baos.write(buf, 0, n)
                        }
                    }
                    val raw = baos.toString("UTF-8")
                    val data = parse(raw)
                    raw to data
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    private fun parse(raw: String): SponsorData {
        val root = JSONObject(raw.removePrefix("\uFEFF"))
        val updatedAt = root.optString("updatedAt", "").trim().take(40)
        val notice = root.optString("notice", "").trim().take(200)
        val arr = root.optJSONArray("sponsors")
        val items = ArrayList<SponsorItem>()
        if (arr != null) {
            val max = minOf(arr.length(), 1000)
            for (i in 0 until max) {
                val elem = arr.opt(i) ?: continue
                when (elem) {
                    is JSONObject -> {
                        val name = elem.optString("name", "").trim().take(60)
                        val note = elem.optString("note", "").trim().take(160)
                        if (name.isNotEmpty()) items.add(SponsorItem(name, note))
                    }
                    is String -> {
                        val name = elem.trim().take(60)
                        if (name.isNotEmpty()) items.add(SponsorItem(name, ""))
                    }
                }
            }
        }
        return SponsorData(updatedAt, notice, items)
    }

    private fun saveQrCode(a: SettingsActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            doSaveQrQ(a)
        } else {
            AppStorage.ensurePermission(a) { doSaveQrLegacy(a) }
        }
    }

    private fun doSaveQrQ(a: SettingsActivity) {
        Thread({
            val outcome = runCatching {
                val fileName = "豆包输入法无障碍_赞赏码_${AppStorage.timestamp()}.png"
                val relPath = "${Environment.DIRECTORY_PICTURES}/${AppStorage.ROOT_NAME}"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = a.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建相册图片")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        a.assets.open(ASSET_QR).use { input -> input.copyTo(out, 32 * 1024) }
                        out.flush()
                    } ?: error("无法写入相册图片")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
                "$relPath/$fileName"
            }
            main.post { notifyQrSaved(a, outcome) }
        }, "SaveSponsorQr").start()
    }

    private fun doSaveQrLegacy(a: SettingsActivity) {
        Thread({
            val outcome = runCatching {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), AppStorage.ROOT_NAME)
                val dest = AppStorage.uniqueFile(dir, "豆包输入法无障碍_赞赏码_${AppStorage.timestamp()}", "png")
                a.assets.open(ASSET_QR).use { input ->
                    FileOutputStream(dest).use { out ->
                        input.copyTo(out, 32 * 1024)
                        out.flush()
                    }
                }
                MediaScannerConnection.scanFile(a, arrayOf(dest.absolutePath), arrayOf("image/png"), null)
                "Pictures/${AppStorage.ROOT_NAME}/${dest.name}"
            }
            main.post { notifyQrSaved(a, outcome) }
        }, "SaveSponsorQrLegacy").start()
    }

    private fun notifyQrSaved(a: SettingsActivity, outcome: Result<String>) {
        if (a.isFinishing || a.isDestroyed) return
        outcome.fold(
            onSuccess = { path ->
                AlertDialog.Builder(a)
                    .setTitle("赞赏码已保存到相册")
                    .setMessage("保存位置：$path\n\n可打开微信“扫一扫”，点击右下角相册选择赞赏码图片识别。")
                    .setPositiveButton("知道了", null)
                    .show()
            },
            onFailure = { t ->
                a.toast("保存赞赏码失败：${t.message ?: "未知错误"}")
            },
        )
    }
}
