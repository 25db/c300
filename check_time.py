#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""查看当前时间脚本"""

from datetime import datetime

def main():
    now = datetime.now()
    print(f"当前时间: {now.strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"时区: Asia/Shanghai")
    print(f"星期: {['一', '二', '三', '四', '五', '六', '日'][now.weekday()]}")

if __name__ == "__main__":
    main()