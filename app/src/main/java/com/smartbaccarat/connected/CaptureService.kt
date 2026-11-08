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
import android.os.SystemClock
import android.util.DisplayMetrics
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

        // 실제 화면의 원형 비드로드(결과 원판) 영역
        // Fold 분할 화면 실제 결과판 위치(전체 화면 기준 비율)
        private const val BOARD_LEFT = 0.505f
        private const val BOARD_WIDTH = 0.490f
        private const val BOARD_TOP = 0.444f
        private const val BOARD_HEIGHT = 0.216f
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
    private var lastProcessedAt = 0L

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

            // 분할 화면 앱 창 크기가 아니라 기기 전체 화면 크기로 캡처
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?.getRealMetrics(metrics)

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

                        // 최신 프레임만 600ms 간격으로 처리해 CPU 점유율 제한
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProcessedAt < 600L) {
                            return@setOnImageAvailableListener
                        }
                        lastProcessedAt = now

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

                        // 분석 전 해상도를 절반으로 낮춰 픽셀 처리량을 약 1/4로 감소
                        val scaled = Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width / 2).coerceAtLeast(1),
                            (bitmap.height / 2).coerceAtLeast(1),
                            false
                        )
                        bitmap.recycle()

                        val sequence = readBoard(scaled)
                        scaled.recycle()

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
     * 화면 안의 실제 색상 원을 찾아 결과판을 자동 검출한다.
     * 좌표 격자를 고정하지 않고 색상 픽셀 연결요소로 원의 중심을 계산한다.
     */
    private data class DetectedCell(
        val x: Float,
        val y: Float,
        val value: Char,
        val area: Int,
        val diameter: Float
    )

    private fun readBoard(bitmap: Bitmap): String {
        // 분할 화면의 게임 영역 오른쪽 절반에서 결과판 세로 구간만 탐색
        val left = (bitmap.width * 0.50f).toInt().coerceIn(0, bitmap.width - 1)
        val right = (bitmap.width * 0.999f).toInt().coerceIn(left + 1, bitmap.width)
        val top = (bitmap.height * 0.375f).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = (bitmap.height * 0.565f).toInt().coerceIn(top + 1, bitmap.height)

        val w = right - left
        val h = bottom - top
        val mask = BooleanArray(w * h)

        // 결과 원의 파랑/빨강/초록 픽셀만 마스크로 만든다.
        for (y in 0 until h) {
            val sy = top + y
            val row = y * w
            for (x in 0 until w) {
                mask[row + x] = colorClass(bitmap.getPixel(left + x, sy)) != '.'
            }
        }

        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val cells = ArrayList<DetectedCell>()

        for (i in mask.indices) {
            if (!mask[i] || visited[i]) continue

            var head = 0
            var tail = 0
            queue[tail++] = i
            visited[i] = true

            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            var sumX = 0L
            var sumY = 0L
            var count = 0
            var blue = 0
            var red = 0
            var green = 0

            while (head < tail) {
                val p = queue[head++]
                val px = p % w
                val py = p / w
                val sx = left + px
                val sy = top + py
                val c = colorClass(bitmap.getPixel(sx, sy))

                minX = minOf(minX, px)
                maxX = maxOf(maxX, px)
                minY = minOf(minY, py)
                maxY = maxOf(maxY, py)
                sumX += px
                sumY += py
                count++
                when (c) {
                    'P' -> blue++
                    'B' -> red++
                    'T' -> green++
                }

                // 대각선으로 이웃한 서로 다른 원이 합쳐지지 않도록 4방향만 연결
                val neighbors = intArrayOf(
                    if (py > 0) p - w else -1,
                    if (px > 0) p - 1 else -1,
                    if (px < w - 1) p + 1 else -1,
                    if (py < h - 1) p + w else -1
                )
                for (ni in neighbors) {
                    if (ni >= 0 && mask[ni] && !visited[ni]) {
                        visited[ni] = true
                        if (tail < queue.size) queue[tail++] = ni
                    }
                }
            }

            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val diameter = (bw + bh) / 2f
            val aspect = bw.toFloat() / bh.coerceAtLeast(1)

            // 원형 결과 마커 크기만 통과시켜 작은 점/문자/장식은 제외
            if (count < 85 || bw !in 15..52 || bh !in 15..52 ||
                aspect !in 0.62f..1.48f) continue

            val strongest = maxOf(blue, red, green)
            if (strongest < count * 0.28f) continue
            val value = when {
                blue == strongest -> 'P'
                red == strongest -> 'B'
                else -> 'T'
            }

            cells.add(
                DetectedCell(
                    left + sumX.toFloat() / count,
                    top + sumY.toFloat() / count,
                    value,
                    count,
                    diameter
                )
            )
        }

        if (cells.size < 4) return ""

        // 원 중심의 실제 간격으로 행/열을 묶는다. 기기 배율 변화에 대응한다.
        val typicalDiameter = cells.map { it.diameter }.sorted()[cells.size / 2]
        val tolerance = (typicalDiameter * 0.58f).coerceIn(9f, 23f)

        fun cluster(values: List<Float>): List<Float> {
            val sorted = values.sorted()
            val groups = ArrayList<MutableList<Float>>()
            for (v in sorted) {
                val last = groups.lastOrNull()
                if (last == null || v - last.average().toFloat() > tolerance) {
                    groups.add(mutableListOf(v))
                } else {
                    last.add(v)
                }
            }
            return groups.map { it.average().toFloat() }
        }

        val rowCenters = cluster(cells.map { it.y })
        val colCenters = cluster(cells.map { it.x })

        // 결과판의 진행 순서: 왼쪽 열부터 위→아래, 다음 열로 이동
        val ordered = cells.sortedWith(
            compareBy<DetectedCell> {
                colCenters.indices.minByOrNull { idx ->
                    kotlin.math.abs(colCenters[idx] - it.x)
                } ?: 0
            }.thenBy {
                rowCenters.indices.minByOrNull { idx ->
                    kotlin.math.abs(rowCenters[idx] - it.y)
                } ?: 0
            }
        )

        return ordered.take(80).joinToString("") { it.value.toString() }
    }

    private fun colorClass(pixel: Int): Char {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        return when {
            b > 105 && b > r * 1.30f && b > g * 1.08f -> 'P'
            r > 110 && r > b * 1.25f && r > g * 1.10f -> 'B'
            g > 80 && g > r * 1.12f && g > b * 1.05f -> 'T'
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
