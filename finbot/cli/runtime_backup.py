from __future__ import annotations

import argparse
import json
from pathlib import Path

from finbot.operations.backup import create_runtime_backup, restore_runtime_backup, verify_runtime_backup


def main() -> None:
    parser = argparse.ArgumentParser(description="FinBot runtime backup and restore")
    subparsers = parser.add_subparsers(dest="command", required=True)
    backup = subparsers.add_parser("backup")
    backup.add_argument("--runtime-root", required=True, type=Path)
    backup.add_argument("--output", type=Path)
    verify = subparsers.add_parser("verify")
    verify.add_argument("--archive", required=True, type=Path)
    restore = subparsers.add_parser("restore")
    restore.add_argument("--archive", required=True, type=Path)
    restore.add_argument("--runtime-root", required=True, type=Path)
    restore.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()
    if args.command == "backup":
        result = create_runtime_backup(args.runtime_root, args.output)
    elif args.command == "verify":
        result = verify_runtime_backup(args.archive)
    else:
        result = restore_runtime_backup(args.archive, args.runtime_root, overwrite=args.overwrite)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
