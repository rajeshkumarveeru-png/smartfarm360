/**
 * UUIDv7 client ids (mobile offline design, N4): 48 bits of milliseconds, then random bits, so ids sort by the time they
 * were made. The id is the record's Idempotency-Key.
 */
export function uuid7(now: number = Date.now(), random: (n: number) => Uint8Array = randomBytes): string {
  const bytes = random(16);
  let ms = Math.max(0, Math.floor(now));
  for (let i = 5; i >= 0; i--) {
    bytes[i] = ms % 256;
    ms = Math.floor(ms / 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x70;   // version 7
  bytes[8] = (bytes[8] & 0x3f) | 0x80;   // RFC 4122 variant
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function randomBytes(n: number): Uint8Array {
  const out = new Uint8Array(n);
  const c = globalThis.crypto as Crypto | undefined;
  if (c?.getRandomValues) return c.getRandomValues(out);
  for (let i = 0; i < n; i++) out[i] = Math.floor(Math.random() * 256);
  return out;
}

/** The time a UUIDv7 was made, in milliseconds. */
export function uuid7Time(id: string): number {
  return parseInt(id.replace(/-/g, '').slice(0, 12), 16);
}
