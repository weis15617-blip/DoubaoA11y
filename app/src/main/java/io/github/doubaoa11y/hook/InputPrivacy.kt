package io.github.doubaoa11y.hook

import android.text.InputType

object InputPrivacy {
    fun isPassword(type: Int): Boolean {
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
    }
    fun mayRead(): Boolean {
        val info = ViewA11yHooks.service?.currentInputEditorInfo ?: return false
        return !isPassword(info.inputType)
    }
}
