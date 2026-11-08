
package com.smartbaccarat.connected

import android.app.*
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
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "CAPTURE_START"
        const val ACTION_STOP = "CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        const val BROADCAST_STATUS = "BACCARAT_CAPTURE_STATUS"
        const val BROADCAST_SEQUENCE = "BACCARAT_CAPTURE_SEQUENCE"

        const val CHANNEL_ID = "baccarat_capture"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())

    private var lastSequence = ""
    private var candidateSequence = ""
    private var stableFrames = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()

                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        EXTRA_DATA,
                        Intent::class.java
                    )
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

    private fun startForegroundNotification() {
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
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle("BACCARAT LIVE AI")
            .setContentText("화면 인식 실행 중")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

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
                handler
            )

            virtualDisplay = projection?.createVirtualDisplay(
                "BaccaratScreen",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride

                    val bitmapWidth =
                        image.width + (rowStride - pixelStride * image.width) / pixelStride

                    val bitmap = Bitmap.createBitmap(
                        bitmapWidth,
                        image.height,
                        Bitmap.Config.ARGB_8888
                    )

                    buffer.rewind()
                    bitmap.copyPixelsFromBuffer(buffer)

                    val sequence = readBoard(bitmap)

                    if (sequence.length >= 4) {
                        if (sequence == candidateSequence) {
                            stableFrames++
                        } else {
                            candidateSequence = sequence
                            stableFrames = 1
                        }

                        if (stableFrames >= 2 &&
                            sequence != lastSequence
                        ) {
                            lastSequence = sequence
                            sendSequence(sequence)
                        }
                    }

                    bitmap.recycle()
                } catch (e: Exception) {
                    sendStatus("판독 오류: ${e.message}")
                } finally {
                    image.close()
                }
            }, handler)

            sendStatus("화면 캡처 연결 완료")

        } catch (e: Exception) {
            sendStatus("캡처 오류: ${e.message}")
            stopCapture()
        }
    }

    /*
     * 현재 판독 영역은 임시 비율이다.
     * 실제 게임 화면에 맞춰 보드 위치를 보정해야 한다.
     */
    private fun readBoard(bitmap: Bitmap): String {
        val result = StringBuilder()

        val columns = 14
        val rows = 6

        val left = 0.52f
        val top = 0.40f
        val boardWidth = 0.46f
        val boardHeight = 0.34f

        val cellWidth = boardWidth / columns
        val cellHeight = boardHeight / rows

        for (col in 0 until columns) {
            for (row in 0 until rows) {
                val x = (bitmap.width *
                    (left + cellWidth * (col + 0.5f))).toInt()

                val y = (bitmap.height *
                    (top + cellHeight * (row + 0.5f))).toInt()

                val value = classifyCell(bitmap, x, y)

                if (value != '.') {
                    result.append(value)
                }
            }
        }

        return result.toString()
    }

    private fun classifyCell(
        bitmap: Bitmap,
        centerX: Int,
        centerY: Int
    ): Char {
        var blue = 0
        var red = 0
        var green = 0
        var sampled = 0

        for (dx in -5..5 step 2) {
            for (dy in -5..5 step 2) {
                val x = centerX + dx
                val y = centerY + dy

                if (x !in 0 until bitmap.width ||
                    y !in 0 until bitmap.height
                ) continue

                val pixel = bitmap.getPixel(x, y)

                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (b > r * 1.25f && b > g * 1.12f && b > 90) {
                    blue++
                } else if (r > b * 1.25f && r > g * 1.10f && r > 100) {
                    red++
                } else if (g > r * 1.15f && g > b * 1.05f && g > 90) {
                    green++
                }

                sampled++
            }
        }

        if (sampled == 0) return '.'

        val threshold = 4

        return when {
            blue >= threshold && blue > red && blue > green -> 'B'
            red >= threshold && red > blue && red > green -> 'P'
            green >= threshold && green > blue && green > red -> 'T'
            else -> '.'
        }
    }

    private fun sendStatus(message: String) {
        sendBroadcast(
            Intent(BROADCAST_STATUS)
                .setPackage(packageName)
                .putExtra("status", message)
        )
    }

    private fun sendSequence(sequence: String) {
        sendBroadcast(
            Intent(BROADCAST_SEQUENCE)
                .setPackage(packageName)
                .putExtra("sequence", sequence)
        )

        sendStatus("결과 감지: ${sequence.length}개")
    }

    private fun stopCapture() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null

            projection?.stop()
            projection = null
        } catch (_: Exception) {
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendStatus("화면 인식 중지")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
