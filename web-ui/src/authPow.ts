export async function solveProofOfWork(
  prefix: string,
  difficultyBits: number,
  options: { batchSize?: number; maxNonce?: number } = {},
): Promise<number> {
  if (!Number.isInteger(difficultyBits) || difficultyBits < 8 || difficultyBits > 24) {
    throw new Error('PoW 难度超出支持范围');
  }
  const batchSize = options.batchSize ?? 64;
  const maxNonce = options.maxNonce ?? 20_000_000;
  const encoder = new TextEncoder();
  for (let start = 0; start <= maxNonce; start += batchSize) {
    const nonces = Array.from(
      { length: Math.min(batchSize, maxNonce - start + 1) },
      (_unused, index) => start + index,
    );
    const hashes = await Promise.all(
      nonces.map((nonce) => crypto.subtle.digest('SHA-256', encoder.encode(`${prefix}:${nonce}`))),
    );
    const matchIndex = hashes.findIndex((hash) => hasLeadingZeroBits(new Uint8Array(hash), difficultyBits));
    if (matchIndex >= 0) return nonces[matchIndex];
  }
  throw new Error('PoW 安全校验计算超时，请刷新验证码后重试');
}

export function hasLeadingZeroBits(hash: Uint8Array, difficultyBits: number): boolean {
  const fullBytes = Math.floor(difficultyBits / 8);
  const remainingBits = difficultyBits % 8;
  for (let index = 0; index < fullBytes; index += 1) {
    if (hash[index] !== 0) return false;
  }
  if (remainingBits === 0) return true;
  return (hash[fullBytes] >> (8 - remainingBits)) === 0;
}
