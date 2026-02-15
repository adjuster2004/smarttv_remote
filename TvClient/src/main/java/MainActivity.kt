package com.example.tvclient

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent // Важный импорт
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.*
import java.io.DataInputStream
import java.io.PrintWriter
import java.net.Socket
import kotlin.math.abs

class MainActivity : Activity() {

    private var commandSocket: Socket? = null
    private var commandWriter: PrintWriter? = null
    private var isConnected = false

    private lateinit var imgScreen: ImageView
    private lateinit var etIp: EditText
    private lateinit var etPin: EditText
    private lateinit var btnConnect: Button
    private lateinit var inputPanel: View
    private lateinit var btnToggleMenu: TextView
    // Seekbar и btnMute удалены

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imgScreen = findViewById(R.id.imgScreen)
        etIp = findViewById(R.id.etIp)
        etPin = findViewById(R.id.etPin)
        btnConnect = findViewById(R.id.btnConnect)
        inputPanel = findViewById(R.id.inputPanel)
        btnToggleMenu = findViewById(R.id.btnToggleMenu)

        val prefs = getSharedPreferences("TvClientPrefs", Context.MODE_PRIVATE)
        etIp.setText(prefs.getString("IP", "192.168."))
        etPin.setText(prefs.getString("PIN", ""))

        btnConnect.setOnClickListener {
            if (!isConnected) {
                val ip = etIp.text.toString()
                val pin = etPin.text.toString()
                prefs.edit().putString("IP", ip).putString("PIN", pin).apply()
                connectToServer(ip, pin)
                toggleMenu(false)
            }
        }

        btnToggleMenu.setOnClickListener {
            val isVisible = inputPanel.visibility == View.VISIBLE
            toggleMenu(!isVisible)
        }

        scaleGestureDetector = ScaleGestureDetector(this, ScaleListener())
        setupTouchControl()
    }

    // --- НОВАЯ ФУНКЦИЯ: ПЕРЕХВАТ ФИЗИЧЕСКИХ КНОПОК ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isConnected) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    sendCommand("VOL_UP")
                    return true // Блокируем изменение громкости на самом Клиенте
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    sendCommand("VOL_DOWN")
                    return true // Блокируем изменение громкости на самом Клиенте
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    // ---------------------------------------------------

    private fun toggleMenu(show: Boolean) {
        if (show) {
            inputPanel.visibility = View.VISIBLE
            btnToggleMenu.text = "▲"
        } else {
            inputPanel.visibility = View.GONE
            btnToggleMenu.text = "▼"
        }
    }

    private fun connectToServer(ip: String, pin: String) {
        Toast.makeText(this, "Подключение...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                commandSocket = Socket(ip, 8888)
                commandWriter = PrintWriter(commandSocket!!.getOutputStream(), true)
                commandWriter?.println("AUTH:$pin")

                val reader = java.io.BufferedReader(java.io.InputStreamReader(commandSocket!!.getInputStream()))
                val response = reader.readLine()

                if (response == "OK") {
                    isConnected = true
                    runOnUiThread { Toast.makeText(this, "Подключено!", Toast.LENGTH_SHORT).show() }
                } else {
                    isConnected = false
                    runOnUiThread { Toast.makeText(this, "Ошибка PIN", Toast.LENGTH_LONG).show() }
                    commandSocket?.close()
                }
            } catch (e: Exception) {
                isConnected = false
            }
        }.start()

        Thread {
            try {
                val videoSocket = Socket(ip, 8889)
                val input = DataInputStream(videoSocket.getInputStream())
                while (videoSocket.isConnected) {
                    val size = input.readInt()
                    if (size > 0 && size < 5000000) {
                        val bytes = ByteArray(size)
                        input.readFully(bytes)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, size)
                        if (bitmap != null) {
                            runOnUiThread { imgScreen.setImageBitmap(bitmap) }
                        }
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun sendCommand(cmd: String) {
        Thread { try { commandWriter?.println(cmd) } catch (e: Exception) {} }.start()
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = Math.max(1.0f, Math.min(scaleFactor, 5.0f))

            imgScreen.scaleX = scaleFactor
            imgScreen.scaleY = scaleFactor

            if (scaleFactor == 1.0f) {
                imgScreen.translationX = 0f
                imgScreen.translationY = 0f
            }
            return true
        }
    }

    private fun setupTouchControl() {
        var startX = 0f
        var startY = 0f
        var dX = 0f
        var dY = 0f
        val touchThreshold = 10f

        imgScreen.setOnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (!isConnected) return@setOnTouchListener true

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    if (scaleFactor > 1.0f) {
                        v.animate().x(event.rawX + dX).y(event.rawY + dY).setDuration(0).start()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (scaleFactor == 1.0f) {
                        val endX = event.x
                        val endY = event.y
                        val deltaX = endX - startX
                        val deltaY = endY - startY

                        val w = v.width.toFloat()
                        val h = v.height.toFloat()

                        if (abs(deltaX) < touchThreshold && abs(deltaY) < touchThreshold) {
                            val xPct = endX / w
                            val yPct = endY / h
                            sendCommand("CLICK_AT:$xPct,$yPct")
                        } else {
                            val x1 = startX / w
                            val y1 = startY / h
                            val x2 = endX / w
                            val y2 = endY / h
                            sendCommand("SWIPE:$x1,$y1,$x2,$y2")
                        }
                    }
                }
            }
            true
        }
    }
}
