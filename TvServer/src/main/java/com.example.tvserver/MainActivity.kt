package com.example.tvserver

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

class MainActivity : Activity() {
    private val REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Показываем, что приложение запустилось
        Toast.makeText(this, "Запрос прав на запись...", Toast.LENGTH_SHORT).show()

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // 2. Логируем результат
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Toast.makeText(this, "Права получены! Запуск сервиса...", Toast.LENGTH_SHORT).show()

                val intent = Intent(this, ScreenCaptureService::class.java)
                intent.putExtra("RESULT_CODE", resultCode)
                intent.putExtra("DATA", data)

                // Важно: на Android 8+ нужно startForegroundService
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }

                // Сворачиваем, но не убиваем
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "ОШИБКА: Пользователь отказал в записи!", Toast.LENGTH_LONG).show()
            }
        }
    }
}
