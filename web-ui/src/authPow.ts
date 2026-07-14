export async function solveProofOfWork(nonce: string, difficulty: number): Promise<string> {
  if (!Number.isInteger(difficulty) || difficulty < 1 || difficulty > 8) {
    throw new Error('PoW 难度超出支持范围');
  }
  const encoder = new TextEncoder();
  const prefix = '0'.repeat(difficulty);
  const maximum = 50_000_000;
  for (let start = 0; start < maximum; start += 128) {
    const candidates = Array.from({ length: 128 }, (_unused, offset) => String(start + offset));
    const digests = await Promise.all(candidates.map((solution) =>
      crypto.subtle.digest('SHA-256', encoder.encode(`${nonce}:${solution}`))));
    const found = digests.findIndex((digest) => hex(new Uint8Array(digest)).startsWith(prefix));
    if (found >= 0) return candidates[found];
    if (start % 8192 === 0) await new Promise<void>((resolve) => window.setTimeout(resolve, 0));
  }
  throw new Error('PoW 计算超时，请刷新验证码后重试');
}

function hex(bytes: Uint8Array): string {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
}
