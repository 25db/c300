package com.mcbedrock.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mcbedrock.capture.databinding.ActivityMainBinding

/**
 * 入口界面。整屏只有一个「打开悬浮窗」按钮。
 *
 * 点击后：
 * 1. 没有悬浮窗权限 → 跳到系统授权页；
 * 2. 已有权限 → 启动 [FloatingWindowService] 显示悬浮窗，并关闭自身让用户回到游戏。
 *
 * 另外在 Android 13+ 顺手申请一下通知权限（前台服务需要）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startFloatingWindow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenFloating.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startFloatingWindow()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户从悬浮窗授权页返回后，若已授权直接提示再次点击即可
    }

    private fun startFloatingWindow() {
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish() // 悬浮窗已显示，关闭主界面让用户回到游戏
    }
}
