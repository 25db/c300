package com.mcbedrock.capture

/**
 * 网易我的世界基岩版服务器 IP 段识别。
 *
 * 数据来源：网易我的世界基岩版公开服务器列表（花雨庭、EaseCation、CTD、
 * 梦世界、云之海、九龙谷、国建 Cthuwork、仙境家园、冒险世界、神魔战域、
 * 星际时代、梦境、梦之地、ICE_GAME、像素看中国、SPLine Craft、迷你庄园等）
 * 它们的真实出口 IP 集中在以下网段，因此用 CIDR 匹配即可覆盖。
 *
 * 如官方更换 IP 段，只需在 [NETEASE_CIDRS] 增删即可，无需改动其他逻辑。
 */
object NeteaseIpMatcher {

    private val NETEASE_CIDRS = listOf(
        "42.186.0.0/16",   // 网易 MC 基岩版主力服务器段（绝大多数子服均在此段）
        "101.67.0.0/16",    // 花雨庭等
        "115.236.0.0/16",  // CTD 等
        "223.252.0.0/16",   // 网易
        "59.111.0.0/16",    // 网易
        "123.151.0.0/16"    // 网易
    )

    private data class Cidr(val network: Int, val mask: Int)

    private val ranges: List<Cidr> = NETEASE_CIDRS.map { parseCidr(it) }

    private fun parseCidr(cidr: String): Cidr {
        val (ip, bits) = cidr.split("/")
        val maskBits = bits.toInt()
        val mask = if (maskBits == 0) 0 else (-1 shl (32 - maskBits))
        return Cidr(ipToInt(ip) and mask, mask)
    }

    private fun ipToInt(ip: String): Int {
        val p = ip.split(".").map { it.toInt() }
        require(p.size == 4) { "invalid ip: $ip" }
        return (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
    }

    /** 判断一个点分十进制 IPv4 是否属于网易我的世界服务器网段。 */
    fun isNeteaseMc(ip: String): Boolean {
        val ipInt = try {
            ipToInt(ip)
        } catch (e: Exception) {
            return false
        }
        return ranges.any { (ipInt and it.mask) == it.network }
    }

    /** 获取当前生效的网段列表，便于在 UI 上展示或调试。 */
    fun cidrs(): List<String> = NETEASE_CIDRS
}
