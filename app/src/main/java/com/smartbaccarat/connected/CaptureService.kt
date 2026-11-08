package com.smartbaccarat.connected

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.max

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "CAPTURE_START"
        const val ACTION_STOP = "CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        const val BROADCAST_STATUS = "BACCARAT_CAPTURE_STATUS"
        const val BROADCAST_SEQUENCE = "BACCARAT_CAPTURE_SEQUENCE"
        const val CHANNEL_ID = "baccarat_capture"

        // 폴드 분할 화면에서 오른쪽 게임창의 결과판 영역
        private const val BOARD_LEFT = 0.505f
        private const val BOARD_WIDTH = 0.490f
        private const val BOARD_TOP = 0.378f
        private const val BOARD_HEIGHT = 0.185f
        private const val BOARD_ROWS = 6
        private const val BOARD_COLUMNS = 14
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

                val code = intent.getIntExtra(
                    EXTRA_RESULT_CODE,
                    0
                )

                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        EXTRA_DATA,
                        Intent::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (data != null) {
                    startCapture(code, data)
                } else {
                    sendStatus("화면 캡처 권한 데이터가 없습니다.")
                    stopCapture()
                }
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

        val builder = if (Build.VERSION.SDK_INT >= 26) {

            Notification.Builder(this, CHANNEL_ID)

        } else {

            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        startForeground(
            10,
            builder
                .setContentTitle("BACCARAT LIVE AI")
                .setContentText("게임 결과판 화면 인식 중")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        )
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent
    ) {

        try {

            releaseCaptureResources()

            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(
                resultCode,
                data
            )

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
                        sendStatus("화면 공유가 종료되었습니다.")
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

            imageReader?.setOnImageAvailableListener(
                { reader ->

                    val image = reader.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                    try {

                        val plane = image.planes[0]
                        val buffer = plane.buffer

                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride

                        val bitmapWidth =
                            image.width +
                            (rowStride - pixelStride * image.width) /
                            pixelStride

                        val bitmap = Bitmap.createBitmap(
                            bitmapWidth,
                            image.height,
                            Bitmap.Config.ARGB_8888
                        )

                        buffer.rewind()
                        bitmap.copyPixelsFromBuffer(buffer)

                        val sequence = readBoard(bitmap)

                        bitmap.recycle()

                        // 두 번 연속 같은 결과일 때만 전송
                        if (sequence.length >= 4) {

                            if (sequence == candidateSequence) {

                                stableFrames++

                            } else {

                                candidateSequence = sequence
                                stableFrames = 1
                            }

                            if (
                                stableFrames >= 2 &&
                                sequence != lastSequence
                            ) {

                                lastSequence = sequence
                                sendSequence(sequence)
                            }
                        }

                    } catch (e: Exception) {

                        sendStatus(
                            "화면 판독 오류: ${e.message ?: "알 수 없는 오류"}"
                        )

                    } finally {

                        image.close()
                    }

                },
                handler
            )

            sendStatus("화면 캡처 연결 완료 · 결과판을 읽는 중")

        } catch (e: Exception) {

            sendStatus(
                "캡처 시작 오류: ${e.message ?: "알 수 없는 오류"}"
            )

            stopCapture()
        }
    }

    /*
     * 6행 결과판 판독
     *
     * 세로 방향으로 6칸을 읽고
     * 다음 열로 이동한다.
     *
     * 빈칸이 나오면 즉시 판독을 멈춘다.
     * 빈칸을 건너뛰면 결과 순서가 틀어지기 때문이다.
     */

    private fun readBoard(bitmap: Bitmap): String {

        val result = StringBuilder()

        val boardLeft = bitmap.width * BOARD_LEFT
        val boardWidth = bitmap.width * BOARD_WIDTH
        val top = bitmap.height * BOARD_TOP

        val cellWidth = boardWidth / BOARD_COLUMNS
        val cellHeight = bitmap.height * BOARD_HEIGHT / BOARD_ROWS

        for (col in 0 until BOARD_COLUMNS) {

            for (row in 0 until BOARD_ROWS) {

                val centerX =
                    (boardLeft + (col + 0.5f) * cellWidth).toInt()

                val centerY =
                    (top + (row + 0.5f) * cellHeight).toInt()

                val value = classifyCell(
                    bitmap,
                    centerX,
                    centerY,
                    cellWidth,
                    cellHeight
                )

                if (value == '.') {

                    return result.toString()
                }

                result.append(value)

                if (result.length >= 80) {
                    return result.toString()
                }
            }
        }

        return result.toString()
    }

    /*
     * 색상 판독
     *
     * 파랑 = 플레이어 P
     * 빨강 = 뱅커 B
     * 초록 = 타이 T
     */

    private fun classifyCell(
        bitmap: Bitmap,
        centerX: Int,
        centerY: Int,
        cellWidth: Float,
        cellHeight: Float
    ): Char {

        var blue = 0
        var red = 0
        var green = 0
        var samples = 0

        val radiusX = max(
            2,
            (cellWidth * 0.16f).toInt()
        )

        val radiusY = max(
            2,
            (cellHeight * 0.16f).toInt()
        )

        val stepX = max(2, radiusX / 3)
        val stepY = max(2, radiusY / 3)

        for (dy in -radiusY..radiusY step stepY) {

            for (dx in -radiusX..radiusX step stepX) {

                val x = centerX + dx
                val y = centerY + dy

                if (
                    x !in 0 until bitmap.width ||
                    y !in 0 until bitmap.height
                ) continue

                val pixel = bitmap.getPixel(x, y)

                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // 플레이어 파란색
                if (
                    b > 105 &&
                    b > r * 1.35f &&
                    b > g * 1.12f
                ) {

                    blue++

                }

                // 뱅커 빨간색
                else if (
                    r > 115 &&
                    r > b * 1.30f &&
                    r > g * 1.12f
                ) {

                    red++

                }

                // 타이 초록색
                else if (
                    g > 85 &&
                    g > r * 1.12f &&
                    g > b * 1.08f
                ) {

                    green++
                }

                samples++
            }
        }

        if (samples == 0) return '.'

        val threshold = max(2, samples / 7)

        return when {

            blue >= threshold &&
            blue > red &&
            blue > green -> 'P'

            red >= threshold &&
            red > blue &&
            red > green -> 'B'

            green >= threshold &&
            green > blue &&
            green > red -> 'T'

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

        sendStatus("결과 감지: ${sequence.length}판")
    }

    private fun releaseCaptureResources() {

        try {

            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.setOnImageAvailableListener(
                null,
                null
            )

            imageReader?.close()
            imageReader = null

            projection?.stop()
            projection = null

        } catch (_: Exception) {
        }
    }

    private fun stopCapture() {

        releaseCaptureResources()

        if (Build.VERSION.SDK_INT >= 24) {

            stopForeground(STOP_FOREGROUND_REMOVE)

        } else {

            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()

        sendStatus("화면 인식 중지")
    }

    override fun onDestroy() {

        releaseCaptureResources()

        super.onDestroy()
    }
}
