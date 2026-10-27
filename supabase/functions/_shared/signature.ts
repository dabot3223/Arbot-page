// Verifies the per-install device signature. The Android app signs every
// inner message with a non-exportable P-256 key from the Android Keystore
// (StrongBox when available). Keystore emits DER (ASN.1) ECDSA signatures,
// WebCrypto expects IEEE P1363 (r || s), so we convert.

import { b64uDecode } from "./b64.ts";

export async function importDeviceKey(spkiB64u: string): Promise<CryptoKey> {
  const spki = b64uDecode(spkiB64u);
  if (spki.length < 80 || spki.length > 120) throw new Error("bad device key");
  return await crypto.subtle.importKey(
    "spki",
    spki as BufferSource,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
}

export function derToP1363(der: Uint8Array, size = 32): Uint8Array {
  let o = 0;
  const fail = () => {
    throw new Error("bad DER signature");
  };
  if (der[o++] !== 0x30) fail();
  let seqLen = der[o++];
  if (seqLen & 0x80) {
    const n = seqLen & 0x7f;
    if (n !== 1) fail();
    seqLen = der[o++];
  }
  if (o + seqLen !== der.length) fail();
  const out = new Uint8Array(size * 2);
  for (let k = 0; k < 2; k++) {
    if (der[o++] !== 0x02) fail();
    const len = der[o++];
    if (len === 0 || len > size + 1 || o + len > der.length) fail();
    let int = der.subarray(o, o + len);
    o += len;
    while (int.length > 0 && int[0] === 0) int = int.subarray(1);
    if (int.length > size) fail();
    out.set(int, k * size + (size - int.length));
  }
  if (o !== der.length) fail();
  return out;
}

export async function verifyDeviceSignature(
  key: CryptoKey,
  data: Uint8Array,
  derSigB64u: string,
): Promise<boolean> {
  try {
    const sig = derToP1363(b64uDecode(derSigB64u));
    return await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      sig as BufferSource,
      data as BufferSource,
    );
  } catch {
    return false;
  }
}
