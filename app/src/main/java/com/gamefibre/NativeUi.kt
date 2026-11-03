package com.gamefibre

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Native Android widgets with the original app's palette, typography and shapes. */
class NativeUi(val context: Context) {
    val dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val background = if (dark) Color.rgb(18, 20, 24) else Color.WHITE
    val blue = if (dark) Color.rgb(168, 199, 250) else Color.rgb(11, 87, 208)
    val ink = if (dark) Color.rgb(230, 232, 237) else Color.rgb(32, 33, 36)
    val secondary = if (dark) Color.rgb(174, 180, 190) else Color.rgb(95, 99, 104)
    val surface = if (dark) Color.rgb(30, 35, 43) else Color.rgb(240, 244, 249)
    val paleBlue = if (dark) Color.rgb(40, 60, 85) else Color.rgb(232, 240, 254)
    val onPrimary = if (dark) Color.rgb(15, 35, 65) else Color.WHITE
    val error = if (dark) Color.rgb(242, 184, 181) else Color.rgb(165, 14, 14)
    val font: Typeface = context.resources.getFont(R.font.dm_sans)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun shape(color: Int, radius: Int = 16, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }
    fun column(padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun text(value: String, size: Int = 14, color: Int = ink, medium: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        typeface = Typeface.create(font, if (medium) 500 else 400, false)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun link(label: String, size: Int = 14, action: () -> Unit) = text(label, size, blue).apply {
        minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        isFocusable = true
        setOnClickListener { if (isEnabled) action() }
    }
    fun icon(resource: Int, description: String? = null, color: Int = secondary) = ImageView(context).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(color)
        contentDescription = description
        importantForAccessibility = if (description == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun button(label: String, primary: Boolean = false, icon: Int? = null, action: () -> Unit): LinearLayout = row().apply {
        gravity = Gravity.CENTER; minimumHeight = dp(48); setPadding(dp(16), dp(8), dp(16), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(0x220b57d0), shape(if (primary) blue else Color.TRANSPARENT, 24), null)
        isFocusable = true; contentDescription = label
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.className = "android.widget.Button"
            }
        }
        icon?.let { addView(icon(it, color = if (primary) onPrimary else blue), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) }) }
        addView(text(label, 14, if (primary) onPrimary else blue, true))
        setOnClickListener { if (isEnabled) action() }
    }
    fun iconButton(resource: Int, label: String, action: () -> Unit) = FrameLayout(context).apply {
        minimumWidth = dp(48); minimumHeight = dp(48)
        background = RippleDrawable(ColorStateList.valueOf(0x220b57d0), shape(Color.TRANSPARENT, 24), null)
        isFocusable = true; contentDescription = label
        addView(icon(resource), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        setOnClickListener { if (isEnabled) action() }
    }
    fun listChip(label: String, checked: Boolean, change: (Boolean) -> Unit) = CheckBox(context).apply {
        text = label; typeface = font; textSize = 14f; setTextColor(blue)
        buttonDrawable = null
        minimumHeight = dp(48); minimumWidth = 0
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        compoundDrawablePadding = dp(6)
        fun update() {
            isSelected = isChecked
            background = RippleDrawable(ColorStateList.valueOf(0x220b57d0),
                shape(if (isChecked) paleBlue else Color.TRANSPARENT, 24, if (isChecked) blue else secondary), null)
            val check = if (isChecked) context.getDrawable(R.drawable.ic_check)?.mutate()?.apply { setTint(blue) }
                else null
            check?.setBounds(0, 0, dp(18), dp(18))
            setCompoundDrawablesRelative(check, null, null, null)
        }
        isChecked = checked
        update()
        setOnCheckedChangeListener { _, selected -> update(); change(selected) }
    }
    fun actionChip(label: String, description: String = label, action: () -> Unit) = button(label, action = action).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(0x220b57d0), shape(paleBlue, 24), null)
        contentDescription = description
        (getChildAt(0) as TextView).apply {
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    fun dropdownChip(label: String, selected: Boolean, description: String, action: () -> Unit) = button(label, action = action).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(0x220b57d0),
            shape(if (selected) paleBlue else Color.TRANSPARENT, 24, if (selected) blue else secondary), null)
        contentDescription = description
        (getChildAt(0) as TextView).typeface = font
        addView(icon(R.drawable.ic_chevron_down, color = blue), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(6) })
    }

    fun input(label: String, password: Boolean = false) = EditText(context).apply {
        hint = label; contentDescription = label; textSize = 16f; typeface = font
        setTextColor(ink); setHintTextColor(secondary); isSingleLine = true
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = shape(this@NativeUi.background, 8, Color.rgb(128, 134, 139))
        setPadding(dp(14), dp(12), dp(14), dp(12)); minimumHeight = dp(52)
        isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    fun spinner(labels: List<String>) = Spinner(context).apply {
        adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    typeface = font; textSize = 14f; setTextColor(ink)
                    isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
                    val arrow = context.getDrawable(R.drawable.ic_arrow_down)?.apply { setBounds(0, 0, dp(16), dp(16)) }
                    setCompoundDrawablesRelative(null, null, arrow, null); compoundDrawablePadding = dp(8)
                }
        }
        background = shape(this@NativeUi.background, 8, secondary); minimumHeight = dp(52)
    }
    fun gap(parent: LinearLayout, size: Int = 16) { parent.addView(Space(context), LinearLayout.LayoutParams(1, dp(size))) }
    fun add(parent: LinearLayout, child: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
        parent.addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (height < 0) height else dp(height)))
    }
}
