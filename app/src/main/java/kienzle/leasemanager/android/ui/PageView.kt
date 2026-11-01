package kienzle.leasemanager.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** One contract page; shows the placed signature and reports taps as fractions of the page. */
class PageView(context: Context, private val page: Bitmap) : View(context) {
    var signature: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Centre of the signature as fraction of the page, or null if not placed on this page. */
    var placement: Pair<Float, Float>? = null
        set(value) {
            field = value
            invalidate()
        }

    var onTap: ((Float, Float) -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width * page.height / page.width)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(page, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
        val sig = signature
        val at = placement
        if (sig != null && at != null) {
            canvas.drawBitmap(sig, null, signatureRect(sig, at, width.toFloat(), height.toFloat()), null)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && width > 0) {
            performClick()
            onTap?.invoke(event.x / width, event.y / height)
        }
        return event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        const val SIGNATURE_WIDTH_FRACTION = 0.3f

        /** Rectangle for the signature on a page of the given size, clamped to the page. */
        fun signatureRect(sig: Bitmap, at: Pair<Float, Float>, pageW: Float, pageH: Float): RectF {
            val w = pageW * SIGNATURE_WIDTH_FRACTION
            val h = w * sig.height / sig.width
            val left = (at.first * pageW - w / 2).coerceIn(0f, (pageW - w).coerceAtLeast(0f))
            val top = (at.second * pageH - h / 2).coerceIn(0f, (pageH - h).coerceAtLeast(0f))
            return RectF(left, top, left + w, top + h)
        }
    }
}
