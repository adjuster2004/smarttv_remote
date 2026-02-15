package com.example.tvserver

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import android.util.Log
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    private val TAG = "TV_VIDEO_DEBUG"

    private val width = 540
    private val height = 960
    private val dpi = 240

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e(TAG, "ШАГ 1: Сервис onStartCommand вызван")

        try {
            startForegroundServiceCompat()
            Log.e(TAG, "ШАГ 2: Уведомление создано")
        } catch (e: Exception) {
            Log.e(TAG, "ОШИБКА ШАГ 2: Уведомление: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("DATA")

        Log.e(TAG, "ПРОВЕРКА ПРАВ: resultCode=$resultCode, data=$data")

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "ОШИБКА: Нет прав на запись экрана!")
            showToast("Нет прав на запись")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.e(TAG, "ШАГ 3: Права есть. Запуск потока...")

        Thread {
            try {
                Log.e(TAG, "ШАГ 4: Инициализация MediaProjection...")

                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
                Log.e(TAG, "ШАГ 5: MediaProjection получен")

                // !!! ИСПРАВЛЕНИЕ: РЕГИСТРИРУЕМ CALLBACK ОБЯЗАТЕЛЬНО !!!
                // Без этого Android убивает приложение при попытке создать VirtualDisplay
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        Log.e(TAG, "MediaProjection остановлен системой!")
                        stopSelf()
                    }
                }, handler)
                Log.e(TAG, "ШАГ 5.5: Callback зарегистрирован")

                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "TVStream", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface, null, null
                )
                Log.e(TAG, "ШАГ 6: Виртуальный дисплей создан")

                isRunning = true
                runServerLoop()

            } catch (e: Exception) {
                Log.e(TAG, "КРАШ В ПОТОКЕ: ${e.message}")
                e.printStackTrace()
                showToast("Ошибка: ${e.message}")
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun runServerLoop() {
        try {
            Log.e(TAG, "ШАГ 7: Открываем порт 8889...")
            serverSocket = ServerSocket(8889)
            Log.e(TAG, "ШАГ 8: ПОРТ 8889 ОТКРЫТ! Ждем клиента...")
            showToast("Видео-сервер ГОТОВ (8889)")

            while (isRunning) {
                val client = serverSocket?.accept()
                Log.e(TAG, "ШАГ 9: Клиент подключился: ${client?.inetAddress}")

                if (client != null) {
                    showToast("Клиент смотрит видео!")
                    val out = DataOutputStream(client.getOutputStream())

                    while (client.isConnected && !client.isClosed && isRunning) {
                        if (!sendFrame(out)) {
                            Log.e(TAG, "Ошибка отправки (клиент отключился)")
                            break
                        }
                    }
                    client.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ОШИБКА ПОРТА: ${e.message}")
            showToast("Ошибка порта 8889")
        }
    }

    private fun sendFrame(output: DataOutputStream): Boolean {
        var image: android.media.Image? = null
        var bitmap: Bitmap? = null

        try {
            image = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                val finalBitmap = if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)

                val stream = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 20, stream)
                val jpegData = stream.toByteArray()

                output.writeInt(jpegData.size)
                output.write(jpegData)
                output.flush()

                if (finalBitmap != bitmap) finalBitmap.recycle()
                image.close()
                bitmap?.recycle()
                return true
            }
        } catch (e: Exception) {
            return false
        } finally {
            try { image?.close(); bitmap?.recycle() } catch (e: Exception) {}
        }
        try { Thread.sleep(100) } catch (e: Exception) {}
        return true
    }

    private fun startForegroundServiceCompat() {
        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val chan = NotificationChannel("v_channel", "Video Stream", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(chan)
        }
    }

    @Suppress("DEPRECATION")
    private fun createNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "v_channel") else Notification.Builder(this)
        return b.setContentTitle("TV Streaming").setSmallIcon(android.R.drawable.ic_menu_camera).build()
    }

    private fun showToast(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
