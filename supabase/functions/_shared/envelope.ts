// Server side of "envelope v1" – see android/core/.../crypto/Envelope.kt for the
// full description. Request: ECDH(P-256) with the client's ephemeral key ->
// HKDF-SHA256 -> AES-256-GCM. Response: AES-256-GCM with the derived response
// key, AAD bound to the request IV.

import { b64uDecode, b64uEncode, concat, utf8 } from "./b64.ts";

export const ENVELOPE_VERSION = 1;
const INFO_PREFIX = "arbot-envelope-v1|";
const REQ_AAD_PREFIX = "arbot-v1-req|";
const RES_AAD_PREFIX = "arbot-v1-res|";
export const PAD_BLOCK = 256;
const MAX_CIPHERTEXT = 512 * 1024;

export type KeyRing = Map<string, CryptoKey>;

export class EnvelopeError extends Error {}

/** `API_PRIVATE_KEYS` = {"k1":"<pkcs8 base64url>", ...}. Several kids allow rotation. */
export async function loadKeyRing(json: string): Promise<KeyRing> {
  const parsed = JSON.parse(json) as Record<string, string>;
  const ring: KeyRing = new Map();
  for (const [kid, pkcs8] of Object.entries(parsed)) {
    ring.set(
      kid,
      await crypto.subtle.importKey(
        "pkcs8",
        b64uDecode(pkcs8) as BufferSource,
        { name: "ECDH", namedCurve: "P-256" },
        false,
        ["deriveBits"],
      ),
    );
  }
  if (ring.size === 0) throw new Error("API_PRIVATE_KEYS is empty");
  return ring;
}

export interface OpenedRequest {
  plaintext: Uint8Array;
  responseKey: CryptoKey;
  requestIv: Uint8Array;
  kid: string;
}

async function deriveKeys(
  serverPrivate: CryptoKey,
  epkRaw: Uint8Array,
  kid: string,
): Promise<{ req: CryptoKey; res: CryptoKey }> {
  const epk = await crypto.subtle.importKey(
    "raw",
    epkRaw as BufferSource,
    { name: "ECDH", namedCurve: "P-256" },
    false,
    [],
  );
  const shared = await crypto.subtle.deriveBits(
    { name: "ECDH", public: epk },
    serverPrivate,
    256,
  );
  const hkdfKey = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveBits"]);
  const okm = new Uint8Array(
    await crypto.subtle.deriveBits(
      {
        name: "HKDF",
        hash: "SHA-256",
        salt: epkRaw as BufferSource,
        info: utf8.encode(INFO_PREFIX + kid) as BufferSource,
      },
      hkdfKey,
      512,
    ),
  );
  const req = await crypto.subtle.importKey("raw", okm.slice(0, 32), "AES-GCM", false, ["decrypt"]);
  const res = await crypto.subtle.importKey("raw", okm.slice(32, 64), "AES-GCM", false, ["encrypt"]);
  okm.fill(0);
  return { req, res };
}

export async function openRequest(ring: KeyRing, body: Uint8Array): Promise<OpenedRequest> {
  let env: { v?: unknown; kid?: unknown; epk?: unknown; iv?: unknown; ct?: unknown };
  try {
    env = JSON.parse(new TextDecoder().decode(body));
  } catch {
    throw new EnvelopeError("malformed envelope");
  }
  if (env.v !== ENVELOPE_VERSION) throw new EnvelopeError("unsupported version");
  if (typeof env.kid !== "string" || typeof env.epk !== "string" ||
    typeof env.iv !== "string" || typeof env.ct !== "string") {
    throw new EnvelopeError("malformed envelope");
  }
  const key = ring.get(env.kid);
  if (!key) throw new EnvelopeError("unknown key id");

  const epk = b64uDecode(env.epk);
  const iv = b64uDecode(env.iv);
  const ct = b64uDecode(env.ct);
  if (epk.length !== 65 || epk[0] !== 0x04) throw new EnvelopeError("bad ephemeral key");
  if (iv.length !== 12) throw new EnvelopeError("bad iv");
  if (ct.length < 16 || ct.length > MAX_CIPHERTEXT) throw new EnvelopeError("bad ciphertext");

  let keys;
  try {
    keys = await deriveKeys(key, epk, env.kid);
  } catch {
    // importKey rejects points that are not on the curve (invalid-curve attacks).
    throw new EnvelopeError("bad ephemeral key");
  }
  let plaintext: Uint8Array;
  try {
    plaintext = new Uint8Array(
      await crypto.subtle.decrypt(
        {
          name: "AES-GCM",
          iv: iv as BufferSource,
          additionalData: utf8.encode(REQ_AAD_PREFIX + env.kid) as BufferSource,
          tagLength: 128,
        },
        keys.req,
        ct as BufferSource,
      ),
    );
  } catch {
    throw new EnvelopeError("decryption failed");
  }
  return { plaintext, responseKey: keys.res, requestIv: iv, kid: env.kid };
}

export function pad(json: Uint8Array): Uint8Array {
  const target = (Math.floor(json.length / PAD_BLOCK) + 1) * PAD_BLOCK;
  const out = new Uint8Array(target).fill(0x20);
  out.set(json);
  return out;
}

/** Seals a JSON response. `iv` is only passed by the deterministic fixture tests. */
export async function sealResponse(
  opened: Pick<OpenedRequest, "responseKey" | "requestIv">,
  json: string,
  iv: Uint8Array = crypto.getRandomValues(new Uint8Array(12)),
): Promise<string> {
  const ct = new Uint8Array(
    await crypto.subtle.encrypt(
      {
        name: "AES-GCM",
        iv: iv as BufferSource,
        additionalData: concat(utf8.encode(RES_AAD_PREFIX), opened.requestIv) as BufferSource,
        tagLength: 128,
      },
      opened.responseKey,
      pad(utf8.encode(json)) as BufferSource,
    ),
  );
  return JSON.stringify({ v: ENVELOPE_VERSION, iv: b64uEncode(iv), ct: b64uEncode(ct) });
}
