// Test-only TypeScript re-implementation of the Android client side of the
// protocol (envelope seal/open + device signature), used to exercise the
// gateway end-to-end and to generate cross-language fixtures.

import { b64uDecode, b64uEncode, concat, sha256, utf8 } from "../functions/_shared/b64.ts";
import { pad } from "../functions/_shared/envelope.ts";

export interface ClientKeys {
  ephemeral: CryptoKeyPair;
}

export async function sealRequest(
  serverPublicRaw: Uint8Array,
  kid: string,
  plaintext: Uint8Array,
  opts: { ephemeral?: CryptoKeyPair; iv?: Uint8Array } = {},
) {
  const eph = opts.ephemeral ??
    await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
  const iv = opts.iv ?? crypto.getRandomValues(new Uint8Array(12));
  const epk = new Uint8Array(await crypto.subtle.exportKey("raw", eph.publicKey));
  const serverPub = await crypto.subtle.importKey(
    "raw",
    serverPublicRaw as BufferSource,
    { name: "ECDH", namedCurve: "P-256" },
    false,
    [],
  );
  const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: serverPub }, eph.privateKey, 256);
  const hk = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveBits"]);
  const okm = new Uint8Array(
    await crypto.subtle.deriveBits(
      { name: "HKDF", hash: "SHA-256", salt: epk, info: utf8.encode("arbot-envelope-v1|" + kid) },
      hk,
      512,
    ),
  );
  const reqKey = await crypto.subtle.importKey("raw", okm.slice(0, 32), "AES-GCM", false, ["encrypt"]);
  const resKey = await crypto.subtle.importKey("raw", okm.slice(32), "AES-GCM", false, ["decrypt"]);
  const ct = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: iv as BufferSource, additionalData: utf8.encode("arbot-v1-req|" + kid) },
      reqKey,
      pad(plaintext) as BufferSource,
    ),
  );
  const body =
    `{"v":1,"kid":"${kid}","epk":"${b64uEncode(epk)}","iv":"${b64uEncode(iv)}","ct":"${b64uEncode(ct)}"}`;
  return { body, resKey, iv };
}

export async function openResponse(resKey: CryptoKey, reqIv: Uint8Array, body: string): Promise<string> {
  const env = JSON.parse(body);
  const pt = await crypto.subtle.decrypt(
    {
      name: "AES-GCM",
      iv: b64uDecode(env.iv) as BufferSource,
      additionalData: concat(utf8.encode("arbot-v1-res|"), reqIv) as BufferSource,
    },
    resKey,
    b64uDecode(env.ct) as BufferSource,
  );
  return new TextDecoder().decode(pt).trim();
}

export function p1363ToDer(sig: Uint8Array): Uint8Array {
  const enc = (int: Uint8Array) => {
    let i = 0;
    while (i < int.length - 1 && int[i] === 0) i++;
    let v = int.subarray(i);
    if (v[0] & 0x80) v = concat(new Uint8Array([0]), v);
    return concat(new Uint8Array([0x02, v.length]), v);
  };
  const body = concat(enc(sig.subarray(0, 32)), enc(sig.subarray(32)));
  return concat(new Uint8Array([0x30, body.length]), body);
}

export class FakeDevice {
  constructor(public keys: CryptoKeyPair, public spki: string) {}

  static async create(): Promise<FakeDevice> {
    const keys = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, [
      "sign",
      "verify",
    ]);
    const spki = b64uEncode(new Uint8Array(await crypto.subtle.exportKey("spki", keys.publicKey)));
    return new FakeDevice(keys, spki);
  }

  async sign(data: Uint8Array): Promise<string> {
    const sig = new Uint8Array(
      await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, this.keys.privateKey, data as BufferSource),
    );
    return b64uEncode(p1363ToDer(sig));
  }
}

export async function requestHash(message: string): Promise<string> {
  return b64uEncode(await sha256(utf8.encode(message)));
}
