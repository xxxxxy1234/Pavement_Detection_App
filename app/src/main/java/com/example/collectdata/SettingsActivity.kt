package com.example.collectdata

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 1. 绑定所有输入框和按钮
        val etT = findViewById<EditText>(R.id.etThreshold)
        val etF = findViewById<EditText>(R.id.etFrequency)
        val etFps = findViewById<EditText>(R.id.etFps)
        val btnSave = findViewById<Button>(R.id.btnSave)

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)

        // 2. 回显当前值（让用户进来能看到之前设了多少）
        etT.setText(prefs.getFloat("threshold", 3.0f).toString())
        etF.setText(prefs.getInt("frequency", 50).toString())
        etFps.setText(prefs.getInt("video_fps", 30).toString())

        // 3. 点击保存按钮时的逻辑
        btnSave.setOnClickListener {
            // 从输入框获取最新的值
            val t = etT.text.toString().toFloatOrNull()
            val f = etF.text.toString().toIntOrNull()
            val fpsValue = etFps.text.toString().toIntOrNull()

            if (t != null && f != null && fpsValue != null) {
                // 统一保存到 SharedPreferences
                prefs.edit().apply {
                    putFloat("threshold", t)
                    putInt("frequency", f)
                    putInt("video_fps", fpsValue)
                    apply()
                }

                Toast.makeText(this, "保存成功，返回主页生效", Toast.LENGTH_SHORT).show()
                finish() // 关闭当前页面回到主页
            } else {
                Toast.makeText(this, "请输入正确的数字格式", Toast.LENGTH_SHORT).show()
            }
        }
    }
}