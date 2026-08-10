package com.mcbedrock.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.mcbedrock.capture.databinding.FloatingWindowBinding
import kotlin.math.abs

/**
 * 毛玻璃风格悬浮窗。
 *
 * - 进入应用只有一个「打开悬浮窗」按钮，点击后由 [MainActivity] 启动本服务；
 * - 悬浮窗默认展开，标题栏可拖动，右上角折叠按钮可收起为小巧形态；
 * - 展开态展示「状态 + 服务器地址卡片 + 开始抓包 + 复制/关闭」；
 * - 折叠态仅一个图标 + MC 字样 + 展开按钮，单击空白处亦可展开；
 * - 点击「开始抓包」会拉起 [VpnAuthActivity] 完成 VPN 授权并启动 [CaptureVpnService]；
 * - 抓包结果通过 [onCaptureResult] 回调刷新到悬浮窗，点击 IP 卡片即可复制。
 *
 * 毛玻璃：Android 12+ 使用 [WindowManager.LayoutParams.setBlurBehindRadius] 实现真正的
 * 背景模糊；低版本回退为半透明深色圆角渐变 + 描边，视觉上同样高级。
 */
class FloatingWindowService : Service() {

    companion object {
        var instance: FloatingWindowService? = null
            private set

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "mc_floating"
    }

    private lateinit var binding: FloatingWindowBinding
    private var windowManager: WindowManager? = null
    private lateinit var params: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentIp: String? = null
    private var isExpanded = true
    private var capturing = false

    // 拖动辅助
    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var moved = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat()
        showFloatingWindow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun showFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        binding = FloatingWindowBinding.inflate(LayoutInflater.from(this))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 260
        }

        // 真正的背景毛玻璃（Android 12+），低版本回退到半透明渐变背景
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.setBlurBehindRadius(28)
            } catch (_: Throwable) {
                // 部分 ROM 不支持 blur-behind，忽略即可
            }
        }

        setupViews()
        windowManager?.addView(binding.root, params)
    }

    private fun setupViews() {
        binding.headerLayout.setOnTouchListener(dragListener)
        binding.collapsedContainer.setOnTouchListener(dragListener)

        binding.btnCollapse.setOnClickListener { setExpanded(false) }
        binding.btnExpand.setOnClickListener { setExpanded(true) }
        binding.btnClose.setOnClickListener { close() }

        binding.cardIp.setOnClickListener { copyIp() }
        binding.btnCopy.setOnClickListener { copyIp() }
        binding.btnCapture.setOnClickListener { startCapture() }
    }

    private val dragListener = View.OnTouchListener { v, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                touchX = e.rawX
                touchY = e.rawY
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - touchX
                val dy = e.rawY - touchY
                if (abs(dx) > 8 || abs(dy) > 8) moved = true
                params.x = initialX + dx.toInt()
                params.y = initialY + dy.toInt()
                windowManager?.updateViewLayout(binding.root, params)
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && v.id == R.id.collapsedContainer) setExpanded(true)
                v.performClick()
            }
        }
        true
    }

    private fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        binding.expandedContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.collapsedContainer.visibility = if (expanded) View.GONE else View.VISIBLE
        try {
            windowManager?.updateViewLayout(binding.root, params)
        } catch (_: Throwable) {
        }
    }

    private fun startCapture() {
        if (capturing) return
        capturing = true
        binding.btnCapture.isEnabled = false
        binding.btnCapture.text = getString(R.string.status_capturing)
        binding.tvStatus.text = getString(R.string.status_capturing)
        val intent = Intent(this, VpnAuthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /** 由 [CaptureVpnService] 回调，把抓包结果回写到悬浮窗。 */
    fun onCaptureResult(ip: String?, error: Boolean) {
        mainHandler.post {
            capturing = false
            binding.btnCapture.isEnabled = true
            binding.btnCapture.text = getString(R.string.btn_capture)
            when {
                error -> binding.tvStatus.text = getString(R.string.status_error)
                ip.isNullOrEmpty() -> binding.tvStatus.text = getString(R.string.status_empty)
                else -> {
                    currentIp = ip
                    binding.tvIp.text = ip
                    binding.tvStatus.text = getString(R.string.status_done)
                }
            }
        }
    }

    private fun copyIp() {
        val ip = currentIp
        if (ip.isNullOrEmpty()) {
            Toast.makeText(this, R.string.status_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mc_server_ip", ip))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun close() {
        try { windowManager?.removeView(binding.root) } catch (_: Throwable) {}
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        try {
            if (this::binding.isInitialized) windowManager?.removeView(binding.root)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_floating),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_notif_title))
            .setContentText(getString(R.string.floating_notif_text))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    @Suppress("unused")
    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
