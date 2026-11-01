package io.github.akaiikitsune.plsshare

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers to build a tidy screen from framework widgets only. */
class Ui(private val context: Context) {
    companion object {
        const val ACCENT = 0xFFFF6B4A.toInt()
        const val ACCENT_DARK = 0xFFE0552F.toInt()
        const val OK_GREEN = 0xFF2EBD6B.toInt()
        const val WARN = 0xFFF2A93B.toInt()
        const val CARD = 0x14808080
        const val CARD_STRONG = 0x22808080
    }

    val match = ViewGroup.LayoutParams.MATCH_PARENT
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    fun rounded(color: Int, radiusDp: Int = 16) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    fun column(padding: Int = 0, gravity: Int = Gravity.NO_GRAVITY) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        this.gravity = gravity
    }

    fun row(vararg views: View, gap: Int = 8) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            val lp = LinearLayout.LayoutParams(0, wrap, 1f)
            if (i > 0) lp.marginStart = dp(gap)
            addView(v, lp)
        }
    }

    fun space(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(context), ViewGroup.LayoutParams(match, dp(heightDp)))
    }

    fun card(vararg children: View, padding: Int = 16, color: Int = CARD) = column(padding).apply {
        background = rounded(color)
        for (c in children) addView(c, LinearLayout.LayoutParams(match, wrap))
    }

    fun text(value: CharSequence, sizeSp: Float = 15f, bold: Boolean = false, alpha: Float = 1f, color: Int? = null) =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            this.alpha = alpha
            if (color != null) setTextColor(color)
        }

    fun title(value: String) = text(value, 24f, bold = true)

    fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    /** A rounded, filled call-to-action button. */
    fun primaryButton(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.DEFAULT_BOLD
        minimumHeight = dp(56)
        background = RippleDrawable(android.content.res.ColorStateList.valueOf(ACCENT_DARK), rounded(ACCENT, 28), null)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /** A flat secondary button with a subtle rounded background. */
    fun softButton(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        minimumHeight = dp(44)
        background = RippleDrawable(android.content.res.ColorStateList.valueOf(CARD_STRONG), rounded(CARD_STRONG, 22), null)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /** A small round status dot. */
    fun dot(color: Int) = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }
}
