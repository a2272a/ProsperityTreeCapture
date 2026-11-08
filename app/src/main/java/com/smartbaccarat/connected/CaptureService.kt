package com.smartbaccarat.connected

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "CAPTURE_START"
        const val ACTION_STOP = "CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val CHANNEL_ID = "baccarat_capture"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundServiceNotification()

                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (data != null) startCapture(code, data)
            }

            ACTION_STOP -> stopCapture()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "바카라 화면 인식",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("BACCARAT LIVE AI")
                .setContentText("화면 캡처 대기 중")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("BACCARAT LIVE AI")
                .setContentText("화면 캡처 대기 중")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }

        startForeground(10, notification)
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopCapture()
                    }
                },
                null
            )

            virtualDisplay = projection?.createVirtualDisplay(
                "BaccaratScreen",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride

                        val rowPadding =
                            rowStride - pixelStride * image.width

                        val bitmap = Bitmap.createBitmap(
                            image.width + rowPadding / pixelStride,
                            image.height,
                            Bitmap.Config.ARGB_8888
                        )

                        bitmap.copyPixelsFromBuffer(buffer)

                        // 다음 단계에서 이 bitmap으로
                        // 바카라 보드의 B/P/T를 판독할 예정

                        bitmap.recycle()
                    } finally {
                        image.close()
                    }
                }
            }, null)

            sendStatus("화면 캡처 시작")
        } catch (e: Exception) {
            sendStatus("캡처 오류: ${e.message}")
            stopCapture()
        }
    }

    private fun sendStatus(message: String) {
        val intent = Intent("BACCARAT_CAPTURE_STATUS")
        intent.setPackage(packageName)
        intent.putExtra("status", message)
        sendBroadcast(intent)
    }

    private fun stopCapture() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            projection?.stop()
            projection = null
        } catch (_: Exception) {
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendStatus("화면 캡처 중지")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
