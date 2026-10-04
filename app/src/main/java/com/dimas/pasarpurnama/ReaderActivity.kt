package com.dimas.pasarpurnama

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

/**
 * Hosts the original HTML reader (assets/reader/reader.html) in a WebView. The reader gets its
 * book through the `Android` JavaScript bridge, so one template serves every book in the library.
 */
class ReaderActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private lateinit var repo: BookRepository
    private lateinit var bookId: String
    private var immersive = true

    private var pendingExport: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri == null || text == null) return@registerForActivityResult
        runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            .onSuccess { toast("Catatan disimpan") }
            .onFailure { toast("Gagal menyimpan: ${it.message}") }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = AuthManager(this).user ?: return finish()
        repo = BookRepository(this, user.userId)
        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return finish()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.textZoom = 100 // the reader has its own text-size setting
            setBackgroundColor(0xFF15171B.toInt())
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    return true
                }
            }
        }

        root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(webView, FrameLayout.LayoutParams(-1, -1))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        applyImmersive()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.PPback ? window.PPback() : false") { handled ->
                    if (handled != "true") finish()
                }
            }
        })

        webView.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/reader/reader.html")
    }

    private fun applyImmersive() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        webView.destroy()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Methods called from reader.html as `Android.xxx(...)`. They run on a WebView thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun getNovel(): String = repo.loadNovelJson(bookId)
            ?: """{"id":"missing","title":"Buku tidak ditemukan","cover":{},"chapters":[{"title":"-","html":"<p>Buku ini sudah dihapus.</p>"}]}"""

        @JavascriptInterface
        fun onProgress(chapter: Int) = repo.saveProgress(bookId, chapter)

        /** Saved reader state (theme, size, bookmarks, position) for this account, or "" if none yet. */
        @JavascriptInterface
        fun getState(): String = repo.readerState(bookId).orEmpty()

        @JavascriptInterface
        fun saveState(json: String) = repo.saveReaderState(bookId, json)

        /** Whether the page may fall back to the pre-account localStorage state (first account on this device only). */
        @JavascriptInterface
        fun useLegacyState(): Boolean = repo.ownsLegacyData()

        /** Paints the notch / system-bar padding in the reader's current page colour. */
        @JavascriptInterface
        fun onThemeColor(css: String) = runOnUiThread {
            runCatching { android.graphics.Color.parseColor(css) }.getOrNull()?.let { root.setBackgroundColor(it) }
        }

        @JavascriptInterface
        fun close() = runOnUiThread { finish() }

        @JavascriptInterface
        fun toggleFullscreen() = runOnUiThread {
            immersive = !immersive
            applyImmersive()
        }

        @JavascriptInterface
        fun saveText(fileName: String, text: String) = runOnUiThread {
            pendingExport = text
            exportLauncher.launch(fileName)
        }

        @JavascriptInterface
        fun print(jobName: String) = runOnUiThread {
            val pm = getSystemService(PrintManager::class.java)
            pm.print(jobName, webView.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }
}
