from __future__ import annotations

import argparse
import getpass

from finbot.web.auth import hash_password


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成 FinBot 管理员 PBKDF2 密码摘要。")
    parser.add_argument("--password", help="仅用于自动化；默认从无回显终端读取。")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    password = args.password or getpass.getpass("管理员密码: ")
    if args.password is None:
        confirmation = getpass.getpass("再次输入: ")
        if password != confirmation:
            raise SystemExit("两次密码不一致")
    print(hash_password(password))


if __name__ == "__main__":
    main()

