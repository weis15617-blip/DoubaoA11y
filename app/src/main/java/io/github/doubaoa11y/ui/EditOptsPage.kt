package io.github.doubaoa11y.ui

import android.app.AlertDialog
import android.widget.LinearLayout
import io.github.doubaoa11y.ConfigSpec

/**
 * “自定义编辑选项”页：完整的编辑选项列表，可自由增删排序。
 *
 * 出厂默认是“默认、全选文本、选择文本、粘贴、收起键盘”，但用户可以随便改；
 * 剪切、复制、取消选择是动态项（全选/选择文本之后自动出现），不能手动加。
 */
object EditOptsPage {
    fun fill(a: SettingsActivity, root: LinearLayout) {
        val ids = a.editOptions().toMutableList()

        root.addView(a.heading("编辑选项（上下滑切换、双击执行）"))
        if (ids.isEmpty()) {
            root.addView(a.note("（空列表，请先添加）"))
        } else {
            for ((i, id) in ids.withIndex()) {
                root.addView(a.note("第 ${i + 1} 项：${ConfigSpec.actionName(id)}"))
            }
        }
        root.addView(a.note("提示：剪切、复制、删除文本、取消选择在全选或选择文本之后自动出现，执行完就收起，不用在这里配。"))

        root.addView(a.heading("调整列表"))
        root.addView(a.button("添加一项") {
            val options = ConfigSpec.EDIT_OPTION_CHOICES
            val existing = ids.toSet()
            val available = options.filter { it.first !in existing }
            if (available.isEmpty()) {
                a.toast("没有可添加的动作了")
                return@button
            }
            val names = available.map { it.second }.toTypedArray()
            AlertDialog.Builder(a)
                .setTitle("添加一项")
                .setItems(names) { d, which ->
                    ids.add(available[which].first)
                    a.saveEditOptions(ids)
                    d.dismiss()
                    a.rebuild()
                }
                .show()
        })
        root.addView(a.button("删除一项") {
            if (ids.isEmpty()) {
                a.toast("列表是空的")
                return@button
            }
            val names = ids.map { ConfigSpec.actionName(it) }.toTypedArray()
            AlertDialog.Builder(a)
                .setTitle("删除一项")
                .setItems(names) { d, which ->
                    ids.removeAt(which)
                    a.saveEditOptions(ids)
                    d.dismiss()
                    a.rebuild()
                }
                .show()
        })
        root.addView(a.button("上移一项") {
            if (ids.size < 2) {
                a.toast("列表不够两项")
                return@button
            }
            val names = ids.map { ConfigSpec.actionName(it) }.toTypedArray()
            AlertDialog.Builder(a)
                .setTitle("上移一项")
                .setItems(names) { d, which ->
                    if (which > 0) {
                        val t = ids[which]
                        ids[which] = ids[which - 1]
                        ids[which - 1] = t
                        a.saveEditOptions(ids)
                    }
                    d.dismiss()
                    a.rebuild()
                }
                .show()
        })
        root.addView(a.button("下移一项") {
            if (ids.size < 2) {
                a.toast("列表不够两项")
                return@button
            }
            val names = ids.map { ConfigSpec.actionName(it) }.toTypedArray()
            AlertDialog.Builder(a)
                .setTitle("下移一项")
                .setItems(names) { d, which ->
                    if (which < ids.size - 1) {
                        val t = ids[which]
                        ids[which] = ids[which + 1]
                        ids[which + 1] = t
                        a.saveEditOptions(ids)
                    }
                    d.dismiss()
                    a.rebuild()
                }
                .show()
        })
        root.addView(a.button("恢复默认") {
            a.saveEditOptions(ConfigSpec.DEFAULT_EDIT_OPTION_IDS.toList())
            a.rebuild()
        })
    }
}
