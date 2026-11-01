package kienzle.leasemanager.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

/** Renders a PDF with the platform [PdfRenderer] and writes a flattened signed copy. */
class PdfPages(private val file: File) {
    fun pageCount(): Int = withRenderer { it.pageCount }

    fun render(index: Int, widthPx: Int): Bitmap = withRenderer { renderer ->
        usePage(renderer, index) { page -> renderPage(page, widthPx) }
    }

    /** Draws [signature] on [signaturePage] (if any) and returns the flattened PDF bytes. */
    fun flatten(signaturePage: Int, signature: Bitmap?, at: Pair<Float, Float>?): ByteArray = withRenderer { renderer ->
        val document = PdfDocument()
        try {
            for (index in 0 until renderer.pageCount) {
                usePage(renderer, index) { source ->
                    val info = PdfDocument.PageInfo.Builder(source.width, source.height, index + 1).create()
                    val target = document.startPage(info)
                    val bitmap = renderPage(source, EXPORT_WIDTH_PX)
                    val rect = android.graphics.RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
                    target.canvas.drawBitmap(bitmap, null, rect, null)
                    bitmap.recycle()
                    if (index == signaturePage && signature != null && at != null) {
                        target.canvas.drawBitmap(
                            signature,
                            null,
                            PageView.signatureRect(signature, at, rect.width(), rect.height()),
                            null,
                        )
                    }
                    document.finishPage(target)
                }
            }
            ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray()
        } finally {
            document.close()
        }
    }

    private fun renderPage(page: PdfRenderer.Page, widthPx: Int): Bitmap {
        val height = widthPx * page.height / page.width
        val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun <T> usePage(renderer: PdfRenderer, index: Int, block: (PdfRenderer.Page) -> T): T {
        val page = renderer.openPage(index)
        try {
            return block(page)
        } finally {
            page.close()
        }
    }

    private fun <T> withRenderer(block: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val renderer = PdfRenderer(fd)
            try {
                block(renderer)
            } finally {
                renderer.close()
            }
        }

    private companion object {
        const val EXPORT_WIDTH_PX = 1240
    }
}
