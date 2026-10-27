// Regenerates shared/fixtures/envelope-vectors.json (test-only keys!).
//   deno run --allow-write=shared supabase/tests/gen_envelope_fixture.ts
// The Kotlin client (EnvelopeFixtureTest) must produce the exact same request
// bytes from these inputs and must open the response; the Deno tests must open
// the request and produce the exact same response bytes.

import { b64uEncode, utf8 } from "../functions/_shared/b64.ts";
import { loadKeyRing, openRequest, sealResponse } from "../functions/_shared/envelope.ts";
import { sealRequest } from "./client_sim.ts";

const gen = () => crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
const exportB64 = async (fmt: "pkcs8" | "raw", k: CryptoKey) =>
  b64uEncode(new Uint8Array(await crypto.subtle.exportKey(fmt, k)));

const kid = "test-k1";
const server = await gen();
const eph = await gen();
const serverPublicRaw = new Uint8Array(await crypto.subtle.exportKey("raw", server.publicKey));
const requestIv = crypto.getRandomValues(new Uint8Array(12));
const responseIv = crypto.getRandomValues(new Uint8Array(12));
const requestPlaintext = JSON.stringify({ m: '{"action":"profile.get","ts":1,"nonce":"n","payload":{}}', s: "sig" });
const responsePlaintext = JSON.stringify({ ok: true, data: { hello: "Ar_B0T", gems: 1200 } });

const sealed = await sealRequest(serverPublicRaw, kid, utf8.encode(requestPlaintext), { ephemeral: eph, iv: requestIv });
const ring = await loadKeyRing(JSON.stringify({ [kid]: await exportB64("pkcs8", server.privateKey) }));
const opened = await openRequest(ring, utf8.encode(sealed.body));
const responseBody = await sealResponse(opened, responsePlaintext, responseIv);

const fixture = {
  note: "TEST KEYS ONLY - never use in production",
  kid,
  serverPrivatePkcs8: await exportB64("pkcs8", server.privateKey),
  serverPublicRaw: b64uEncode(serverPublicRaw),
  ephemeralPrivatePkcs8: await exportB64("pkcs8", eph.privateKey),
  ephemeralPublicRaw: await exportB64("raw", eph.publicKey),
  requestIv: b64uEncode(requestIv),
  requestPlaintext,
  requestBody: sealed.body,
  responseIv: b64uEncode(responseIv),
  responsePlaintext,
  responseBody,
};
await Deno.writeTextFile(
  new URL("../../shared/fixtures/envelope-vectors.json", import.meta.url),
  JSON.stringify(fixture, null, 2) + "\n",
);
console.log("wrote envelope-vectors.json");
