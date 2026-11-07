package com.smartbaccarat.connected

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private val requestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL

        status = TextView(this)
        status.text = "SMART BACCARAT AI V3.1"
        status.textSize = 20f
        layout.addView(status)

        val start = Button(this)
        start.text = "화면 자동 인식 시작"
        start.setOnClickListener {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            startActivityForResult(
                manager.createScreenCaptureIntent(),
                requestCode
            )
        }
        layout.addView(start)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(this, "AndroidCapture")
        layout.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(layout)
        webView.loadUrl("file:///android_asset/index.html")
    }

    @JavascriptInterface
    fun stop() {
        runOnUiThread {
            status.text = "화면 인식 중지"
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == this.requestCode &&
            resultCode == RESULT_OK && data != null) {
            status.text = "화면 캡처 권한 승인"
        }
    }
}
