
package com.smartbaccarat.connected

import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val requestCapture = 1001

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CaptureService.BROADCAST_STATUS -> {
                    val message = intent.getStringExtra("status") ?: ""
                    sendToPage("onCaptureStatus", message)
                }

                CaptureService.BROADCAST_SEQUENCE -> {
                    val sequence = intent.getStringExtra("sequence") ?: ""
                    sendToPage("onScreenSequence", sequence)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(CaptureBridge(), "AndroidCapture")
            webViewClient = WebViewClient()
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        val filter = IntentFilter().apply {
            addAction(CaptureService.BROADCAST_STATUS)
            addAction(CaptureService.BROADCAST_SEQUENCE)
        }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(captureReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(captureReceiver, filter)
        }
    }

    private inner class CaptureBridge {
        @JavascriptInterface
        fun start() {
            runOnUiThread {
                val manager = getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

                startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    requestCapture
                )
            }
        }

        @JavascriptInterface
        fun stop() {
            stopService(
                Intent(this@MainActivity, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_STOP)
            )
        }
    }

    private fun sendToPage(function: String, value: String) {
        val safe = org.json.JSONObject.quote(value)
        runOnUiThread {
            webView.evaluateJavascript(
                "if(window.ScreenBridge && window.ScreenBridge.$function)" +
                    "{window.ScreenBridge.$function($safe);}",
                null
            )
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == requestCapture &&
            resultCode == RESULT_OK &&
            data != null
        ) {
            val serviceIntent = Intent(
                this,
                CaptureService::class.java
            ).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_DATA, data)
            }

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(captureReceiver)
        } catch (_: Exception) {
        }
        webView.destroy()
        super.onDestroy()
    }
}
