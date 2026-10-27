import { assert, assertEquals } from "jsr:@std/assert@1";
import { b64uDecode, b64uEncode, sha256Hex, utf8 } from "../functions/_shared/b64.ts";
import { loadKeyRing, openRequest, sealResponse } from "../functions/_shared/envelope.ts";
import {
  ApiFailure,
  type Deps,
  type GatewayConfig,
  handle,
  type IapProductRow,
  type PlayClient,
  type SessionRow,
  type Store,
} from "../functions/_shared/gateway.ts";
import type { IntegrityPayload, ProductPurchase } from "../functions/_shared/google.ts";
import { FakeDevice, openResponse, requestHash, sealRequest } from "./client_sim.ts";

const fixture = JSON.parse(await Deno.readTextFile(new URL("../../shared/fixtures/envelope-vectors.json", import.meta.url)));
const engineFixture = JSON.parse(
  await Deno.readTextFile(new URL("../../shared/fixtures/engine-vectors.json", import.meta.url)),
);

// ------------------------------------------------------------- fixtures --

Deno.test("server opens the Kotlin-compatible request fixture", async () => {
  const ring = await loadKeyRing(JSON.stringify({ [fixture.kid]: fixture.serverPrivatePkcs8 }));
  const opened = await openRequest(ring, utf8.encode(fixture.requestBody));
  assertEquals(new TextDecoder().decode(opened.plaintext).trim(), fixture.requestPlaintext);
  const sealed = await sealResponse(opened, fixture.responsePlaintext, b64uDecode(fixture.responseIv));
  assertEquals(sealed, fixture.responseBody);
});

// ------------------------------------------------------- in-memory store --

class MemoryStore implements Store {
  players = new Map<string, { deviceKey: string; banned: boolean; gems: number; premium: boolean }>();
  nonces = new Set<string>();
  sessions = new Map<string, SessionRow & { player: string }>();
  events: { kind: string; detail: Record<string, unknown> }[] = [];
  granted = new Map<string, string>();
  hits = new Map<string, number>();

  consumeNonce(n: string) {
    if (this.nonces.has(n)) return Promise.resolve(false);
    this.nonces.add(n);
    return Promise.resolve(true);
  }
  rateLimit(b: string, _w: number, max: number) {
    const v = (this.hits.get(b) ?? 0) + 1;
    this.hits.set(b, v);
    return Promise.resolve(v <= max);
  }
  logEvent(_p: string | null, kind: string, detail: Record<string, unknown>) {
    this.events.push({ kind, detail });
    return Promise.resolve();
  }
  purge() {
    return Promise.resolve();
  }
  getPlayerAuth(id: string) {
    const p = this.players.get(id);
    return Promise.resolve(p ? { id, deviceKey: p.deviceKey, banned: p.banned } : null);
  }
  registerPlayer(deviceKey: string, displayName: string) {
    for (const [id, p] of this.players) if (p.deviceKey === deviceKey) return Promise.resolve({ playerId: id, displayName });
    const id = crypto.randomUUID();
    this.players.set(id, { deviceKey, banned: false, gems: 0, premium: false });
    return Promise.resolve({ playerId: id, displayName });
  }
  profileBundle(id: string) {
    return Promise.resolve({ player: { id, gems: this.players.get(id)!.gems } });
  }
  wallet(id: string) {
    const p = this.players.get(id)!;
    return Promise.resolve({ gems: p.gems, premium: p.premium });
  }
  rename(id: string, name: string) {
    return Promise.resolve({ id, name });
  }
  buyItem(id: string, _item: string): Promise<unknown> {
    if (this.players.get(id)!.gems < 100) return Promise.reject(new ApiFailure("insufficient_gems"));
    return Promise.resolve({});
  }
  equipSkin() {
    return Promise.resolve({});
  }
  startSession(id: string, seed: number, extraLife: boolean, powerStart: boolean) {
    const sessionId = crypto.randomUUID();
    const row = { player: id, seed, startLives: extraLife ? 6 : 5, powerStart, status: "active", startedAt: Date.now() };
    this.sessions.set(sessionId, row);
    return Promise.resolve({ sessionId, seed, startLives: row.startLives, powerStart });
  }
  getSession(id: string, sessionId: string) {
    const s = this.sessions.get(sessionId);
    return Promise.resolve(s && s.player === id ? s : null);
  }
  finishSession(id: string, sessionId: string, score: number, _t: number, accepted: boolean) {
    const s = this.sessions.get(sessionId)!;
    s.status = accepted ? "finished" : "rejected";
    if (accepted) this.players.get(id)!.gems += Math.floor(score / 20);
    return Promise.resolve({ score, gemsEarned: Math.floor(score / 20) });
  }
  leaderboard() {
    return Promise.resolve({ entries: [] });
  }
  iapProduct(productId: string): Promise<IapProductRow | null> {
    if (productId === "gems_pack_small") return Promise.resolve({ kind: "consumable", gems: 120, grantsPremium: false });
    return Promise.resolve(null);
  }
  grantPurchase(id: string, tokenHash: string) {
    const owner = this.granted.get(tokenHash);
    if (owner && owner !== id) return Promise.reject(new ApiFailure("purchase_token_reused"));
    const p = this.players.get(id)!;
    if (owner) return Promise.resolve({ status: "already_granted", gemsGranted: 0, gems: p.gems, premium: p.premium });
    this.granted.set(tokenHash, id);
    p.gems += 120;
    return Promise.resolve({ status: "granted", gemsGranted: 120, gems: p.gems, premium: p.premium });
  }
}

class FakePlay implements PlayClient {
  purchases = new Map<string, ProductPurchase>();
  consumed: string[] = [];
  integrity: (token: string) => IntegrityPayload = () => ({});
  getProductPurchase(_p: string, token: string) {
    return Promise.resolve(this.purchases.get(token) ?? null);
  }
  consume(_p: string, token: string) {
    this.consumed.push(token);
    return Promise.resolve();
  }
  acknowledge() {
    return Promise.resolve();
  }
  decodeIntegrityToken(token: string) {
    return Promise.resolve(this.integrity(token));
  }
}

const baseConfig: GatewayConfig = {
  integrityMode: "off",
  integrityPolicy: { packageName: "com.arbot.game", allowedCertDigests: [], requireLicensed: true, maxAgeMs: 300_000 },
  allowTestPurchases: false,
  clockSkewMs: 300_000,
  nonceTtlSeconds: 900,
};

async function harness(config: Partial<GatewayConfig> = {}, now = () => Date.now()) {
  const server = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
  const pkcs8 = b64uEncode(new Uint8Array(await crypto.subtle.exportKey("pkcs8", server.privateKey)));
  const pub = new Uint8Array(await crypto.subtle.exportKey("raw", server.publicKey));
  const store = new MemoryStore();
  const play = new FakePlay();
  const deps: Deps = {
    ring: await loadKeyRing(JSON.stringify({ k1: pkcs8 })),
    store,
    play,
    config: { ...baseConfig, ...config },
    now,
    randomSeed: () => engineFixture.scenarios[0].seed,
  };
  const device = await FakeDevice.create();

  async function rawCall(message: string, signature: string, integrity?: string, ip = "1.1.1.1") {
    const outer = JSON.stringify({ m: message, s: signature, i: integrity });
    const sealed = await sealRequest(pub, "k1", utf8.encode(outer));
    const res = await handle(
      new Request("http://x/api", { method: "POST", body: sealed.body, headers: { "x-forwarded-for": ip } }),
      deps,
    );
    assertEquals(res.status, 200);
    const text = await res.text();
    return { wire: { request: sealed.body, response: text }, ...JSON.parse(await openResponse(sealed.resKey, sealed.iv, text)) };
  }

  async function call(
    action: string,
    payload: Record<string, unknown>,
    opts: { sub?: string; dev?: FakeDevice; integrity?: (hash: string) => string; ts?: number; nonce?: string } = {},
  ) {
    const message = JSON.stringify({
      action,
      ts: opts.ts ?? Date.now(),
      nonce: opts.nonce ?? b64uEncode(crypto.getRandomValues(new Uint8Array(16))),
      sub: opts.sub,
      payload,
    });
    const dev = opts.dev ?? device;
    const integrity = opts.integrity ? opts.integrity(await requestHash(message)) : undefined;
    return { message, ...await rawCall(message, await dev.sign(utf8.encode(message)), integrity) };
  }

  async function register(dev = device) {
    const r = await call("auth.register", { devicePublicKey: dev.spki }, { dev });
    assert(r.ok, JSON.stringify(r));
    return r.data.playerId as string;
  }

  return { deps, store, play, device, call, rawCall, register, pub };
}

// ---------------------------------------------------------------- tests --

Deno.test("register + authenticated call round-trip; nothing readable on the wire", async () => {
  const h = await harness();
  const id = await h.register();
  const r = await h.call("profile.get", {}, { sub: id });
  assert(r.ok);
  assertEquals(r.data.player.id, id);
  for (const secret of [id, "profile.get", h.device.spki]) {
    assert(!r.wire.request.includes(secret) && !r.wire.response.includes(secret));
  }
});

Deno.test("garbage and tampered envelopes get a generic 400", async () => {
  const h = await harness();
  const bad = await handle(new Request("http://x", { method: "POST", body: "{}" }), h.deps);
  assertEquals(bad.status, 400);
  const sealed = await sealRequest(h.pub, "k1", utf8.encode("{}"));
  const env = JSON.parse(sealed.body);
  const ct = b64uDecode(env.ct);
  ct[0] ^= 1;
  env.ct = b64uEncode(ct);
  const res = await handle(new Request("http://x", { method: "POST", body: JSON.stringify(env) }), h.deps);
  assertEquals(res.status, 400);
  assertEquals(await res.text(), '{"error":"bad_request"}');
  const get = await handle(new Request("http://x", { method: "GET" }), h.deps);
  assertEquals(get.status, 405);
});

Deno.test("requests signed by another device are rejected", async () => {
  const h = await harness();
  const id = await h.register();
  const attacker = await FakeDevice.create();
  const r = await h.call("profile.get", {}, { sub: id, dev: attacker });
  assertEquals(r.error.code, "unauthorized");
  assertEquals(h.store.events.at(-1)?.kind, "bad_signature");
});

Deno.test("a modified signed message fails the signature check", async () => {
  const h = await harness();
  const id = await h.register();
  const message = JSON.stringify({ action: "profile.get", ts: Date.now(), nonce: "a".repeat(22), sub: id, payload: {} });
  const sig = await h.device.sign(utf8.encode(message));
  const r = await h.rawCall(message.replace("profile.get", "store.buy"), sig);
  assertEquals(r.error.code, "unauthorized");
});

Deno.test("replayed nonces are rejected", async () => {
  const h = await harness();
  const id = await h.register();
  const nonce = b64uEncode(crypto.getRandomValues(new Uint8Array(16)));
  assert((await h.call("profile.get", {}, { sub: id, nonce })).ok);
  const again = await h.call("profile.get", {}, { sub: id, nonce });
  assertEquals(again.error.code, "replay");
});

Deno.test("stale timestamps get clock_skew with the server time", async () => {
  const h = await harness();
  const id = await h.register();
  const r = await h.call("profile.get", {}, { sub: id, ts: Date.now() - 3_600_000 });
  assertEquals(r.error.code, "clock_skew");
  assert(typeof r.serverTime === "number");
});

Deno.test("verified game run is accepted, forged score is rejected", async () => {
  const h = await harness();
  const id = await h.register();
  const sc = engineFixture.scenarios[0];
  // Pretend the run started long enough ago.
  const start = await h.call("game.start", {}, { sub: id });
  assert(start.ok);
  h.store.sessions.get(start.data.sessionId)!.startedAt = Date.now() - sc.finalTick * 10 - 1000;
  const ok = await h.call("game.finish", {
    sessionId: start.data.sessionId,
    inputs: sc.inputs,
    finalTick: sc.finalTick,
    claimedScore: sc.expected.score,
  }, { sub: id });
  assert(ok.ok, JSON.stringify(ok.error));
  assertEquals(ok.data.score, sc.expected.score);

  const again = await h.call("game.finish", {
    sessionId: start.data.sessionId,
    inputs: sc.inputs,
    finalTick: sc.finalTick,
    claimedScore: sc.expected.score,
  }, { sub: id });
  assertEquals(again.error.code, "session_closed");

  const s2 = await h.call("game.start", {}, { sub: id });
  h.store.sessions.get(s2.data.sessionId)!.startedAt = Date.now() - sc.finalTick * 10 - 1000;
  const forged = await h.call("game.finish", {
    sessionId: s2.data.sessionId,
    inputs: sc.inputs,
    finalTick: sc.finalTick,
    claimedScore: sc.expected.score + 1000,
  }, { sub: id });
  assertEquals(forged.error.code, "score_rejected");
  assertEquals(h.store.events.at(-1)?.detail.reason, "score_mismatch");
});

Deno.test("runs finished faster than real time are rejected", async () => {
  const h = await harness();
  const id = await h.register();
  const sc = engineFixture.scenarios[2];
  const start = await h.call("game.start", {}, { sub: id });
  const r = await h.call("game.finish", {
    sessionId: start.data.sessionId,
    inputs: sc.inputs,
    finalTick: sc.finalTick,
    claimedScore: sc.expected.score,
  }, { sub: id });
  assertEquals(r.error.code, "score_rejected");
  assertEquals(h.store.events.at(-1)?.detail.reason, "too_fast");
});

Deno.test("purchases: verified, bound to the account, idempotent and consumed", async () => {
  const h = await harness();
  const id = await h.register();
  const token = "tok_" + crypto.randomUUID();
  h.play.purchases.set(token, {
    purchaseState: 0,
    consumptionState: 0,
    orderId: "GPA.1",
    purchaseType: undefined,
    obfuscatedExternalAccountId: await sha256Hex(id),
  });
  const r = await h.call("purchase.verify", { productId: "gems_pack_small", purchaseToken: token }, { sub: id });
  assert(r.ok, JSON.stringify(r.error));
  assertEquals(r.data.status, "granted");
  assertEquals(r.data.gems, 120);
  assertEquals(h.play.consumed, [token]);

  const again = await h.call("purchase.verify", { productId: "gems_pack_small", purchaseToken: token }, { sub: id });
  assertEquals(again.data.status, "already_granted");
  assertEquals(again.data.gems, 120);

  // Another player cannot redeem the same token (account binding).
  const other = await FakeDevice.create();
  const otherId = await h.register(other);
  const stolen = await h.call("purchase.verify", { productId: "gems_pack_small", purchaseToken: token }, {
    sub: otherId,
    dev: other,
  });
  assertEquals(stolen.error.code, "invalid_purchase");

  // Fake tokens are rejected.
  const fake = await h.call("purchase.verify", { productId: "gems_pack_small", purchaseToken: "x".repeat(40) }, { sub: id });
  assertEquals(fake.error.code, "invalid_purchase");
});

Deno.test("test purchases are refused unless explicitly allowed", async () => {
  const h = await harness();
  const id = await h.register();
  h.play.purchases.set("t".repeat(30), { purchaseState: 0, purchaseType: 0, obfuscatedExternalAccountId: await sha256Hex(id) });
  const r = await h.call("purchase.verify", { productId: "gems_pack_small", purchaseToken: "t".repeat(30) }, { sub: id });
  assertEquals(r.error.code, "invalid_purchase");
});

Deno.test("integrity enforce mode: missing, mismatched and valid verdicts", async () => {
  const h = await harness({ integrityMode: "enforce" });
  const dev = h.device;
  const missing = await h.call("auth.register", { devicePublicKey: dev.spki });
  assertEquals(missing.error.code, "integrity_failed");

  const good = (hash: string): IntegrityPayload => ({
    requestDetails: { requestPackageName: "com.arbot.game", requestHash: hash, timestampMillis: String(Date.now()) },
    appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED", packageName: "com.arbot.game" },
    deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY"] },
    accountDetails: { appLicensingVerdict: "LICENSED" },
  });
  h.play.integrity = (token) => good(token); // token carries the hash in this fake
  const ok = await h.call("auth.register", { devicePublicKey: dev.spki }, { integrity: (hash) => hash });
  assert(ok.ok, JSON.stringify(ok.error));

  const replayedToken = await h.call("auth.register", { devicePublicKey: dev.spki }, { integrity: () => "some-other-hash" });
  assertEquals(replayedToken.error.code, "integrity_failed");
  assertEquals(h.store.events.at(-1)?.detail.reason, "hash_mismatch");

  h.play.integrity = (token) => ({ ...good(token), deviceIntegrity: { deviceRecognitionVerdict: [] } });
  const rooted = await h.call("auth.register", { devicePublicKey: dev.spki }, { integrity: (hash) => hash });
  assertEquals(rooted.error.code, "integrity_failed");
});

Deno.test("server errors are sealed and internal details are not leaked", async () => {
  const h = await harness();
  const id = await h.register();
  h.store.buyItem = () => Promise.reject(new Error("relation players does not exist at 10.0.0.1"));
  const r = await h.call("store.buy", { itemId: "skin_gold" }, { sub: id });
  assertEquals(r.error.code, "internal");
  assert(!JSON.stringify(r).includes("10.0.0.1"));
  const unknown = await h.call("admin.giveGems", {}, { sub: id });
  assertEquals(unknown.error.code, "unknown_action");
});

Deno.test("registration is rate limited per IP", async () => {
  const h = await harness();
  for (let i = 0; i < 20; i++) await h.register(await FakeDevice.create());
  const r = await h.call("auth.register", { devicePublicKey: h.device.spki });
  assertEquals(r.error.code, "rate_limited");
});
