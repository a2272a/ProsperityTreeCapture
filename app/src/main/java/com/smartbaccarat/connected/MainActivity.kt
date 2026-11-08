package com.smartbaccarat.connected

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView

    companion object {
        private const val REQUEST_CAPTURE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090E19.toInt())
        }

        status = TextView(this).apply {
            text = "BACCARAT LIVE AI V4 · 캡처 대기"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(12, 12, 12, 12)
        }
        layout.addView(status)

        val startButton = Button(this).apply {
            text = "화면 자동 인식 시작"
            setOnClickListener {
                val manager = getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

                startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    REQUEST_CAPTURE
                )
            }
        }
        layout.addView(startButton)

        val stopButton = Button(this).apply {
            text = "화면 인식 중지"
            setOnClickListener {
                stopService(
                    Intent(this@MainActivity, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_STOP)
                )
                status.text = "캡처 중지"
            }
        }
        layout.addView(stopButton)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()

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

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CAPTURE &&
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

            startForegroundService(serviceIntent)
            status.text = "화면 캡처 승인 완료"
        }
    }
}
