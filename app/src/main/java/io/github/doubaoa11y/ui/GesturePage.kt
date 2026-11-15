package io.github.doubaoa11y.ui

import android.content.Intent
import android.widget.LinearLayout
import io.github.doubaoa11y.ConfigSpec

/**
 * 全屏与手势设置页。极简：只列手势和它绑定的动作，不写任何说明文字。
 */
object GesturePage {
    fun fill(a: SettingsActivity, root: LinearLayout) {
        root.addView(a.button("编辑选项（共 ${a.editOptions().size} 项）") {
            a.startActivity(Intent(a, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.EDITOPTS))
        })

        root.addView(a.heading("手势"))
        // 灵敏度放最前面：手感不对的先去调它
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
        a.addKeysNoHint(root, "fsCandidateFirst", "fsHaptic", "fsSpeakAction", "fsSpeakChar")

        root.addView(a.heading("按句移动"))
        a.textField(root, "按句移动标点", "sentenceSymbols", "逗号或换行分开，如：。，；？！.!")

        root.addView(a.button("恢复默认") { a.restoreGestureDefaults() })
    }
}
