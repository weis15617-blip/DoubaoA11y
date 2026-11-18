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

        root.addView(a.heading("自定义编辑菜单"))
        root.addView(a.note("点击可更换功能，长按删除"))
        fun choose(index: Int?) {
            val options = ConfigSpec.EDIT_OPTION_CHOICES.filter { it.first !in ids || it.first == index?.let(ids::get) }
            if (options.isEmpty()) { a.toast("没有可添加的动作了"); return }
            AlertDialog.Builder(a).setTitle(if (index == null) "添加一项" else "更换功能")
                .setItems(options.map { it.second }.toTypedArray()) { _, which ->
                    if (index == null) ids.add(options[which].first) else ids[index] = options[which].first
                    a.saveEditOptions(ids)
                    a.rebuild()
                }.setNegativeButton("取消", null).show()
        }
        for ((index, id) in ids.withIndex()) {
            // 保留列表渲染所需数据
        }
        a.addList(
            root,
            ids.mapIndexed { index, id -> "第 ${index + 1} 项：${ConfigSpec.actionName(id)}" },
            onClick = { index -> choose(index) },
            onLongClick = { index ->
                ids.removeAt(index)
                a.saveEditOptions(ids)
                a.rebuild()
                true
            },
        )
        root.addView(a.button("添加一项") { choose(null) })
    }
}
