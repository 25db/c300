package com.mcbedrock.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream

/**
 * 基于 [VpnService] 的无 root 抓包核心。
 *
 * 工作流程：
 * 1. 建立一条接管全部流量的 VPN 隧道（TUN 接口）；
 * 2. 在 TUN 接口上读取裸 IP 报文，筛选出 UDP 报文，统计每个 <目的IP:端口> 的包数量；
 * 3. 抓取 [CAPTURE_DURATION_MS] 毫秒后主动关闭 VPN，恢复系统网络；
 * 4. 在收集到的目标里挑出属于网易我的世界服务器网段、且最活跃的一条，
 *    通过 [FloatingWindowService.onCaptureResult] 回传给悬浮窗展示。
 *
 * 说明：只读取与丢弃报文（不做转发），所以抓包期间游戏会有约 1 秒的断流，
 * 这正是用户预期「1 秒后关闭 VPN」的取舍 —— 牺牲极短断网换取精准识别。
 */
class CaptureVpnService : VpnService() {

    companion object {
        private const val TAG = "CaptureVpn"
        private const val CAPTURE_DURATION_MS = 1000L
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "mc_capture"
        const val ACTION_RESULT = "com.mcbedrock.capture.CAPTURE_RESULT"
        const val EXTRA_IP = "extra_ip"
        const val EXTRA_OK = "extra_ok"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        val builder = Builder()
            .setSession("MC抓包")
            .addAddress("10.121.0.1", 24)
            .addRoute("0.0.0.0", 0)   // 接管所有流量
            .setMtu(1500)
            .setBlocking(true)        // read() 阻塞到有包为止，空转少
        // 注：如需只抓我的世界，可放开下面一行（包名按实际网易版填写）
        // builder.addAllowedApplication("com.mojang.minecraftpe")

        val pfd = builder.establish()
        if (pfd == null) {
            report(error = true)
            stopSelf()
            return START_NOT_STICKY
        }

        scope.launch {
            val counter = HashMap<String, Int>()
            try {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val buffer = ByteArray(32767)
                    val endTime = System.currentTimeMillis() + CAPTURE_DURATION_MS
                    while (System.currentTimeMillis() < endTime) {
                        val n = input.read(buffer)
                        if (n <= 0) continue
                        val flow = IpPacketParser.parseUdp(buffer, n) ?: continue
                        // 游戏客户端 → 服务器，目的 IP 即为服务器 IP
                        val key = "${flow.dstIp}:${flow.dstPort}"
                        counter[key] = (counter[key] ?: 0) + 1
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture failed", e)
                pfd.close()
                report(null, true)
                stopSelf()
                return@launch
            } finally {
                try { pfd.close() } catch (_: Exception) {}
            }

            // 在收集到的目标里挑选最活跃且属于网易 MC 网段的那一条
            val matched = counter.entries
                .filter { e ->
                    val ip = e.key.substringBefore(':')
                    NeteaseIpMatcher.isNeteaseMc(ip)
                }
                .sortedByDescending { it.value }

            val best = matched.firstOrNull()
            report(best?.key ?: "", false)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * 把结果回传给悬浮窗服务（若还活着）。
     * - error=true：抓包失败（VPN 建立失败或读取异常）
     * - ip 为空串：抓包成功但未匹配到网易 MC 服务器
     * - ip 非空：命中的 <ip:端口>
     */
    private fun report(ip: String?, error: Boolean) {
        scope.launch(Dispatchers.Main) {
            FloatingWindowService.instance?.onCaptureResult(ip, error)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_capture),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notif_title))
            .setContentText(getString(R.string.capture_notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
