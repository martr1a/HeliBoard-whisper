// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.whisper

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/** The mic icon with the selected voice language written in its lower right corner. */
class LanguageBadgeDrawable(private val icon: Drawable) : Drawable(), Drawable.Callback {
    var text = ""
        set(value) { field = value; invalidateSelf() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
        color = try { Settings.getValues().mColors.get(ColorType.KEY_TEXT) } catch (_: Exception) { 0xFFFFFFFF.toInt() }
    }

    init { icon.callback = this }

    override fun draw(canvas: Canvas) {
        icon.draw(canvas)
        if (text.isEmpty()) return
        val b = bounds
        paint.textSize = b.height() * if (text.length > 2) 0.26f else 0.36f
        // a little outside the icon, the button has room around it
        canvas.drawText(text, b.right + b.width() * 0.16f, b.bottom + b.height() * 0.12f, paint)
    }

    override fun onBoundsChange(bounds: Rect) { icon.bounds = bounds }
    override fun getIntrinsicWidth() = icon.intrinsicWidth
    override fun getIntrinsicHeight() = icon.intrinsicHeight
    override fun isStateful() = icon.isStateful
    override fun onStateChange(state: IntArray) = icon.setState(state)
    override fun setAlpha(alpha: Int) { icon.alpha = alpha; paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { icon.colorFilter = colorFilter; paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
}
