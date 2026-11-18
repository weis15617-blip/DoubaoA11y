package io.github.doubaoa11y.ui

import android.content.Intent
import android.widget.LinearLayout
import io.github.doubaoa11y.ConfigSpec

/**
 * 全屏与手势设置页。极简：只列手势和它绑定的动作，不写任何说明文字。
 */
object GesturePage {
    fun fill(a: SettingsActivity, root: LinearLayout) {
        a.addKeysNoHint(root, "upperOperation")
        EditOptsPage.fill(a, root)
        a.addKeysNoHint(root, "gestureSensitivity")

        // 单指、双指、拖动，直接排下来，每组一个标题
        root.addView(a.heading("单指"))
        a.addKeysNoHint(
            root,
            *ConfigSpec.GESTURE_SLOTS.filter { it.first in 0..ConfigSpec.G_SINGLE_DOWN }
                .map { ConfigSpec.gestureKey(it.first) }.toTypedArray(),
        )

        root.addView(a.heading("双指"))
        a.addKeysNoHint(
            root,
            *ConfigSpec.GESTURE_SLOTS.filter { it.first in ConfigSpec.G_DOUBLE_TAP..ConfigSpec.G_DOUBLE_DOWN }
                .map { ConfigSpec.gestureKey(it.first) }.toTypedArray(),
        )

        root.addView(a.heading("拖动"))
        a.addKeysNoHint(root, "fsDrag", "fsDragMs")
        a.addKeysNoHint(
            root,
            *ConfigSpec.GESTURE_SLOTS.filter { it.first >= ConfigSpec.G_DRAG_LEFT }
                .map { ConfigSpec.gestureKey(it.first) }.toTypedArray(),
        )

        root.addView(a.heading("候选与反馈"))
        a.addKeysNoHint(root, "fsSpeakAction", "fsSpeakChar")

        root.addView(a.heading("按句移动光标"))
        val symbols = ConfigSpec.sentenceList(a.prefs.getString("sentenceSymbols", "")).toMutableList()
        fun save() {
            a.prefs.edit().putString("sentenceSymbols", "list:" + symbols.joinToString("")).apply()
            a.send(null)
            a.rebuild()
        }
        root.addView(a.note("根据下列标点符号分隔句子，点击已有标点可删除"))
        root.addView(a.button("添加标点") {
            val input = android.widget.EditText(a).apply { hint = "输入一个标点或“换行”" }
            android.app.AlertDialog.Builder(a).setTitle("添加标点").setView(input)
                .setPositiveButton("添加") { _, _ ->
                    val symbol = input.text.toString().let { if (it == "换行") "\n" else it }
                    if (symbol.length == 1 && symbol !in symbols && (symbol == "\n" || Regex("\\p{P}").matches(symbol))) {
                        symbols.add(symbol)
                        save()
                    } else a.toast("请输入一个尚未添加的标点或换行")
                }.setNegativeButton("取消", null).show()
        })
        a.addList(
            root,
            symbols.map { if (it == "\n") "换行" else it },
            descriptions = symbols.map { if (it == "\n") "换行" else io.github.doubaoa11y.hook.Names.spoken(it) },
            onClick = { index ->
                symbols.removeAt(index)
                save()
            },
        )
        root.addView(a.button("恢复默认") {
            android.app.AlertDialog.Builder(a).setTitle("恢复默认")
                .setMessage("恢复手势、编辑菜单和按句移动光标的默认设置？")
                .setPositiveButton("恢复") { _, _ -> a.restoreGestureDefaults() }.setNegativeButton("取消", null).show()
        })
    }
}
