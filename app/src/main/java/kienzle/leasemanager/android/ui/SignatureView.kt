package kienzle.leasemanager.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Drawing pad for a handwritten signature (finger or stylus). */
class SignatureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val strokes = mutableListOf<Path>()
    private var current: Path? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun clear() {
        strokes.clear()
        current = null
        invalidate()
    }

    /** Transparent PNG-ready bitmap cropped to the drawn strokes, or null if nothing was drawn. */
    fun toBitmap(): Bitmap? {
        if (strokes.isEmpty()) return null
        val bounds = android.graphics.RectF()
        val union = android.graphics.RectF()
        strokes.forEachIndexed { index, path ->
            path.computeBounds(bounds, true)
            if (index == 0) union.set(bounds) else union.union(bounds)
        }
        val pad = paint.strokeWidth
        union.inset(-pad, -pad)
        val bitmap = Bitmap.createBitmap(
            union.width().toInt().coerceAtLeast(1),
            union.height().toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.translate(-union.left, -union.top)
        strokes.forEach { canvas.drawPath(it, paint) }
        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        strokes.forEach { canvas.drawPath(it, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val path = Path().apply { moveTo(event.x, event.y); lineTo(event.x + 0.1f, event.y) }
                strokes.add(path)
                current = path
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    current?.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                current?.lineTo(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> current = null
            else -> return false
        }
        invalidate()
        return true
    }
}
