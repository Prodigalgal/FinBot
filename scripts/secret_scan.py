from __future__ import annotations

import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PATTERNS = {
    "private_key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "openai_style_key": re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b"),
    "proxy_credential_url": re.compile(r"\b(?:hysteria2|vless|trojan)://[^\s<]+@[^\s<]+", re.IGNORECASE),
}
TEXT_SUFFIXES = {
    ".env", ".example", ".json", ".md", ".py", ".ps1", ".sh", ".toml", ".ts", ".tsx", ".txt", ".yaml", ".yml"
}


def main() -> None:
    files = _tracked_files()
    violations: list[str] = []
    for path in files:
        if path.suffix.lower() not in TEXT_SUFFIXES and path.name not in {"Dockerfile", ".gitignore", ".dockerignore"}:
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for name, pattern in PATTERNS.items():
            if name == "proxy_credential_url" and path.suffix.lower() not in {".env", ".json", ".md", ".txt", ".yaml", ".yml"}:
                continue
            if pattern.search(content):
                violations.append(f"{path.relative_to(ROOT)}: {name}")
        if _contains_sensitive_assignment(path, content):
            violations.append(f"{path.relative_to(ROOT)}: sensitive_assignment")
    if violations:
        raise SystemExit("Secret scan failed:\n" + "\n".join(sorted(violations)))
    print(f"Secret scan passed: {len(files)} tracked files")


def _tracked_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=ROOT,
        check=False,
        capture_output=True,
    )
    if result.returncode == 0 and result.stdout:
        return [ROOT / value.decode("utf-8") for value in result.stdout.split(b"\0") if value]
    return [
        path
        for path in ROOT.rglob("*")
        if path.is_file()
        and path.relative_to(ROOT).as_posix() not in {
            "config/ai_sites.json",
            "config/proxy_policy.json",
            "config/runtime_config.json",
        }
        and not any(
            part in {
                ".agents", ".codex", ".git", ".playwright-cli", ".playwright-mcp",
                "__pycache__", "build", "data", "dist", "finbot.egg-info", "node_modules",
                "output", "tmp", "参考",
            }
            for part in path.relative_to(ROOT).parts
        )
    ]


def _contains_sensitive_assignment(path: Path, content: str) -> bool:
    relative_parts = path.relative_to(ROOT).parts
    if "tests" in relative_parts or "docs" in relative_parts or ".github" in relative_parts:
        return False
    if "example" in path.name.lower():
        return False
    assignment = re.compile(r"(?i)^\s*[A-Z0-9_]*(?:API_KEY|TOKEN|SECRET|PASSWORD|PRIVATE_KEY)\s*=\s*(.+?)\s*$")
    placeholders = ("<", "${", "example", "redacted", "change-me", "your-", "test-")
    for line in content.splitlines():
        match = assignment.match(line)
        if not match:
            continue
        raw_value = match.group(1).strip()
        if path.suffix.lower() in {".py", ".ps1", ".sh", ".ts", ".tsx"}:
            literal = re.fullmatch(r'["\']([^"\']+)["\']', raw_value)
            if literal is None:
                continue
            raw_value = literal.group(1)
        value = raw_value.strip().strip('"\'')
        if value and not value.lower().startswith(placeholders):
            return True
    return False


if __name__ == "__main__":
    main()
