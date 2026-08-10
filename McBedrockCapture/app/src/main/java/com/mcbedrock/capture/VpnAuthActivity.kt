package com.mcbedrock.capture

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle

/**
 * 透明无 UI 的 VPN 授权代理 Activity。
 *
 * Service 无法直接 startActivityForResult，所以由悬浮窗拉起本 Activity，
 * 由它完成 [VpnService.prepare] 的系统授权流程：
 *  - prepare 返回非空 intent：拉起系统 VPN 授权对话框；
 *  - prepare 返回 null：说明已经授权过；
 *  - 用户授权成功后立即启动 [CaptureVpnService] 开始抓包。
 *
 * 整个过程对用户而言只是「点一下开始抓包 → 系统弹一次 VPN 授权」。
 */
class VpnAuthActivity : Activity() {

    companion object {
        private const val REQ_VPN = 9001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(prepareIntent, REQ_VPN)
        } else {
            startCaptureService()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            startCaptureService()
        }
        finish()
    }

    private fun startCaptureService() {
        val intent = Intent(this, CaptureVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
