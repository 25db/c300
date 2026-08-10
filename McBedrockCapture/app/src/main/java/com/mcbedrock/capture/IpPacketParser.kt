package com.mcbedrock.capture

/**
 * 原始 IP 数据包解析器。
 *
 * VPN 的 TUN 接口读出来的是裸 IP 报文（不包含链路层头），
 * 这里只关心 IPv4 + UDP，提取出五元组里我们需要的源/目的 IP 与端口。
 */
object IpPacketParser {

    private const val PROTO_UDP = 17

    data class UdpFlow(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int
    )

    /**
     * 从一份原始 IP 报文里解析出 UDP 流信息。
     * @param packet 报文缓冲区
     * @param length 实际读到的长度
     * @return 解析成功返回 UdpFlow，否则 null（非 IPv4 / 非 UDP / 长度不足）
     */
    fun parseUdp(packet: ByteArray, length: Int): UdpFlow? {
        if (length < 20) return null
        val firstByte = packet[0].toInt() and 0xFF
        val version = (firstByte ushr 4) and 0x0F
        if (version != 4) return null // 仅处理 IPv4

        val ihl = (firstByte and 0x0F) * 4 // IP 头长度
        if (ihl < 20 || length < ihl + 8) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != PROTO_UDP) return null

        val srcIp = intToIp(packet, 12)
        val dstIp = intToIp(packet, 16)

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

        return UdpFlow(srcIp, srcPort, dstIp, dstPort)
    }

    private fun intToIp(packet: ByteArray, offset: Int): String {
        return "${packet[offset].toInt() and 0xFF}." +
                "${packet[offset + 1].toInt() and 0xFF}." +
                "${packet[offset + 2].toInt() and 0xFF}." +
                "${packet[offset + 3].toInt() and 0xFF}"
    }
}
