package com.vremover.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast

/**
 * VREMOVER: cangkang WebView yang nampilin UI dari server VREMOVER
 * (jalan di Colab / PC). Alamat server disimpan, bisa diganti kapan aja.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var bar: ProgressBar
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val prefs by lazy { getSharedPreferences("vremover", Context.MODE_PRIVATE) }

    private fun currentServer(): String? = prefs.getString("server", null)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bg = 0xFF0B0B12.toInt()
        val root = FrameLayout(this)
        root.setBackgroundColor(bg)

        web = WebView(this)
        web.setBackgroundColor(bg)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(bar, FrameLayout.LayoutParams(-1, 8))
        setContentView(root)

        if (Build.VERSION.SDK_INT <= 28) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
        }

        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun changeServer() {
                runOnUiThread { askServer() }
            }
        }, "Android")

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                bar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                bar.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val server = Uri.parse(currentServer() ?: return false)
                if (request.url.host == server.host) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    bar.visibility = View.GONE
                    showConnectError()
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                bar.progress = newProgress
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
                }
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent.createChooser(pick, "Pilih lagu"), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        web.setDownloadListener { url, _, contentDisposition, mime, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "VREMOVER/$name")
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, "Download: $name", Toast.LENGTH_SHORT).show()
        }

        val server = currentServer()
        if (server == null) askServer() else web.loadUrl(server)
    }

    private fun askServer() {
        val input = EditText(this).apply {
            hint = "contoh: https://xxxx.trycloudflare.com"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(currentServer() ?: "")
            setSingleLine(true)
        }
        val firstTime = currentServer() == null
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Alamat server VREMOVER")
            .setMessage("Tempel link server dari Colab, atau alamat PC lu (contoh: http://192.168.1.5:5000).")
            .setView(input)
            .setCancelable(!firstTime)
            .setPositiveButton("Simpan") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isEmpty()) {
                    if (currentServer() == null) askServer()
                    return@setPositiveButton
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
                url = url.trimEnd('/')
                prefs.edit().putString("server", url).apply()
                web.loadUrl(url)
            }
            .show()
    }

    private fun showConnectError() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Gagal konek ke server")
            .setMessage("Pastikan server VREMOVER lagi nyala dan alamatnya bener.\n\n${currentServer() ?: ""}")
            .setPositiveButton("Coba lagi") { _, _ -> web.reload() }
            .setNegativeButton("Ganti server") { _, _ -> askServer() }
            .show()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        private const val REQ_FILE = 1001
    }
}
