package com.fakelocation.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/**
 * 使用引导页面。
 *
 * 当检测到系统未开启开发者选项，或当前模拟位置应用不是本应用时，
 * 由 MainActivity 跳转到此页面，引导用户完成必要设置。
 */
class GuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        // 「打开开发者选项」按钮：跳转到系统开发者选项页面
        findViewById<MaterialButton>(R.id.btn_open_developer).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                // 部分机型没有标准入口，尝试通用设置页
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                    Toast.makeText(this, "请在设置中搜索「开发者选项」", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "无法打开开发者选项，请手动进入设置", Toast.LENGTH_LONG).show()
                }
            }
        }

        // 「已完成，进入应用」按钮：返回主页面
        findViewById<MaterialButton>(R.id.btn_done).setOnClickListener {
            // 用 FLAG_ACTIVITY_CLEAR_TOP 返回 MainActivity，避免栈混乱
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
        }
    }
}
