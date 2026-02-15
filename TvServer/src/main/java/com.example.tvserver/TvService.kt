package com.example.tvserver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class TvService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var pinView: TextView? = null
    private var generatedPin = ""
    private var serverSocket: ServerSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val TAG = "TvService"

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Служба Accessibility запущена")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        generatedPin = (1000..9999).random().toString()

        // 1. Показываем Overlay с IP и PIN
        showOverlay("IP: ${getIpAddress()}\nPIN: $generatedPin")

        // 2. Запускаем сервер
        Thread { startServer() }.start()
    }

    private fun startServer() {
        try {
            serverSocket = ServerSocket(8888)
            isRunning = true
            Log.d(TAG, "Сервер ждет команд на порту 8888")

            while (isRunning) {
                val client = serverSocket?.accept()
                Log.d(TAG, "Новое подключение: ${client?.inetAddress}")
                handleClient(client)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleClient(socket: Socket?) {
        socket?.use {
            try {
                val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                val out = PrintWriter(it.getOutputStream(), true)

                // 1. Читаем строку авторизации
                val rawAuth = reader.readLine()

                // ЗАЩИТА: Если клиент отключился во время подключения (например, при повороте)
                if (rawAuth == null) {
                    Log.w(TAG, "Клиент отключился до завершения авторизации")
                    return // Выходим, не проверяя дальше
                }

                // 2. Очищаем и проверяем
                val receivedAuth = rawAuth.trim()
                val expectedAuth = "AUTH:$generatedPin"

                Log.d(TAG, "Auth check: '$receivedAuth' vs '$expectedAuth'")

                if (receivedAuth == expectedAuth) {
                    out.println("OK")
                    updateOverlayText("") // Прячем PIN, клиент подключен

                    // 3. Чтение команд
                    var line = reader.readLine()
                    while (line != null) {
                        val cmd = line.trim()
                        if (cmd.isNotEmpty()) {
                            performCommand(cmd)
                        }
                        line = reader.readLine()
                    }
                } else {
                    Log.e(TAG, "Неверный PIN: $receivedAuth")
                    out.println("WRONG_PIN")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Разрыв соединения: ${e.message}")
            } finally {
                // Возвращаем PIN при отключении
                Log.d(TAG, "Клиент отключился")
                updateOverlayText("IP: ${getIpAddress()}\nPIN: $generatedPin")
            }
        }
    }

    private fun performCommand(cmd: String) {
        // Log.d(TAG, "Команда: $cmd")

        when {
            // --- Глобальные действия ---
            cmd.equals("BACK", ignoreCase = true) -> performGlobalAction(GLOBAL_ACTION_BACK)
            cmd.equals("HOME", ignoreCase = true) -> performGlobalAction(GLOBAL_ACTION_HOME)

            // --- КЛИК (CLICK_AT:0.5,0.5) ---
            cmd.startsWith("CLICK_AT:") -> {
                try {
                    val parts = cmd.removePrefix("CLICK_AT:").split(",")
                    val width = resources.displayMetrics.widthPixels
                    val height = resources.displayMetrics.heightPixels

                    val x = parts[0].toFloat() * width
                    val y = parts[1].toFloat() * height

                    click(x, y)
                } catch (e: Exception) { e.printStackTrace() }
            }

            // --- СВАЙП (SWIPE:0.1,0.5,0.9,0.5) ---
            cmd.startsWith("SWIPE:") -> {
                try {
                    val parts = cmd.removePrefix("SWIPE:").split(",")
                    val width = resources.displayMetrics.widthPixels
                    val height = resources.displayMetrics.heightPixels

                    val startX = parts[0].toFloat() * width
                    val startY = parts[1].toFloat() * height
                    val endX = parts[2].toFloat() * width
                    val endY = parts[3].toFloat() * height

                    swipe(startX, startY, endX, endY)
                } catch (e: Exception) { e.printStackTrace() }
            }

            // --- УПРАВЛЕНИЕ ГРОМКОСТЬЮ (Кнопки) ---
            cmd == "VOL_UP" -> {
                adjustVolume(AudioManager.ADJUST_RAISE)
            }
            cmd == "VOL_DOWN" -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
            }
        }
    }

    // --- Методы Управления Жестами ---

    private fun click(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)) // 50мс нажатие
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300)) // 300мс на свайп
            .build()
        dispatchGesture(gesture, null, null)
    }

    // --- Методы Управления Звуком (Симуляция кнопок) ---
    private fun adjustVolume(direction: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // FLAG_SHOW_UI покажет системный бар громкости на экране сервера
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка звука: $e")
        }
    }

    // --- Overlay (Отображение IP и PIN) ---
    private fun showOverlay(text: String) {
        mainHandler.post {
            if (pinView != null) return@post

            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 20
            params.y = 40

            pinView = TextView(this).apply {
                this.text = text
                textSize = 20f
                setTextColor(Color.GREEN)
                setBackgroundColor(Color.parseColor("#AA000000"))
                setPadding(20, 20, 20, 20)
            }

            try {
                windowManager?.addView(pinView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateOverlayText(text: String) {
        mainHandler.post {
            if (text.isEmpty()) {
                pinView?.visibility = View.GONE
            } else {
                pinView?.visibility = View.VISIBLE
                pinView?.text = text
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
