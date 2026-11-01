package kienzle.leasemanager.android

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kienzle.leasemanager.android.transfer.LeasePackage
import kienzle.leasemanager.android.transfer.readLeasePackage
import kienzle.leasemanager.android.transfer.writeSignedReturn
import kienzle.leasemanager.android.ui.PageView
import kienzle.leasemanager.android.ui.PdfPages
import kienzle.leasemanager.android.ui.SignatureView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var title: TextView
    private lateinit var pages: LinearLayout
    private lateinit var signerName: EditText
    private lateinit var drawButton: Button
    private lateinit var shareButton: Button

    private var source: LeasePackage? = null
    private var pdfPages: PdfPages? = null
    private var pageViews: List<PageView> = emptyList()
    private var signature: Bitmap? = null
    private var placedPage = -1
    private var placement: Pair<Float, Float>? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        title = findViewById(R.id.contractTitle)
        pages = findViewById(R.id.pages)
        signerName = findViewById(R.id.signerName)
        drawButton = findViewById(R.id.drawSignature)
        shareButton = findViewById(R.id.shareSigned)
        findViewById<Button>(R.id.openPackage).setOnClickListener { openDocument.launch(arrayOf("*/*")) }
        drawButton.setOnClickListener { showSignaturePad() }
        shareButton.setOnClickListener { shareSigned() }
        setSigningEnabled(false)
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) load(uri)
    }

    private fun load(uri: Uri) {
        setSigningEnabled(false)
        title.setText(R.string.loading)
        val width = resources.displayMetrics.widthPixels
        worker.execute {
            try {
                val pkg = contentResolver.openInputStream(uri)?.use { readLeasePackage(it) }
                    ?: throw java.io.IOException("Cannot open file")
                val file = File(cacheDir, "contract.pdf").also { it.writeBytes(pkg.pdf) }
                val renderer = PdfPages(file)
                val bitmaps = (0 until renderer.pageCount()).map { renderer.render(it, width) }
                runOnUiThread { show(pkg, renderer, bitmaps) }
            } catch (e: Exception) {
                runOnUiThread {
                    title.setText(R.string.no_contract)
                    Toast.makeText(this, getString(R.string.load_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun show(pkg: LeasePackage, renderer: PdfPages, bitmaps: List<Bitmap>) {
        source = pkg
        pdfPages = renderer
        signature = null
        placement = null
        placedPage = -1
        title.text = pkg.contractName.ifBlank { pkg.contractId.toString() }
        pages.removeAllViews()
        pageViews = bitmaps.mapIndexed { index, bitmap ->
            PageView(this, bitmap).also { view ->
                view.onTap = { x, y -> placeSignature(index, x, y) }
                pages.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                })
            }
        }
        setSigningEnabled(true)
    }

    private fun setSigningEnabled(enabled: Boolean) {
        drawButton.isEnabled = enabled
        shareButton.isEnabled = enabled
        signerName.isEnabled = enabled
    }

    private fun showSignaturePad() {
        val pad = SignatureView(this)
        val height = (200 * resources.displayMetrics.density).toInt()
        pad.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.draw_signature)
            .setView(pad)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.clear, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val bitmap = pad.toBitmap()
                if (bitmap == null) {
                    Toast.makeText(this, R.string.signature_empty, Toast.LENGTH_SHORT).show()
                } else {
                    signature = bitmap
                    pageViews.forEach { it.signature = bitmap }
                    Toast.makeText(this, R.string.tap_to_place, Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun placeSignature(page: Int, x: Float, y: Float) {
        if (signature == null) return
        pageViews.forEachIndexed { index, view -> view.placement = if (index == page) x to y else null }
        placedPage = page
        placement = x to y
    }

    private fun shareSigned() {
        val pkg = source ?: return
        val renderer = pdfPages ?: return
        val sig = signature
        val at = placement
        val name = signerName.text.toString().trim()
        when {
            sig == null || at == null -> return toast(R.string.signature_missing)
            name.isEmpty() -> return toast(R.string.name_missing)
        }
        val page = placedPage
        shareButton.isEnabled = false
        worker.execute {
            try {
                val signedPdf = renderer.flatten(page, sig, at)
                val dir = File(cacheDir, "shared").apply { mkdirs() }
                val target = File(dir, "${pkg.contractId}.leasesigned")
                target.outputStream().use {
                    writeSignedReturn(it, pkg, signedPdf, name, "Android ${Build.VERSION.RELEASE} / ${Build.MODEL}")
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
                runOnUiThread {
                    shareButton.isEnabled = true
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.share_signed)))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    shareButton.isEnabled = true
                    Toast.makeText(this, getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
