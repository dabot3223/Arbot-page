// The single entry point for the Android app. Pipeline for every request:
//
//   envelope decrypt -> parse signed message -> clock window -> device signature
//   -> nonce (anti-replay) -> rate limit -> Play Integrity (sensitive actions)
//   -> handler -> envelope encrypt
//
// Nothing in the request is trusted before the signature check, and every
// response that leaves this function after decryption succeeded is encrypted.
// Dependencies are injected so the whole pipeline is unit-testable.

import { b64uEncode, sha256, sha256Hex, utf8, utf8d } from "./b64.ts";
import { EnvelopeError, type KeyRing, openRequest, sealResponse } from "./envelope.ts";
import { evaluateIntegrity, type IntegrityPayload, type IntegrityPolicy, type ProductPurchase } from "./google.ts";
import { importDeviceKey, verifyDeviceSignature } from "./signature.ts";
import { replay, ReplayError, RULES } from "./engine.ts";

export class ApiFailure extends Error {
  constructor(public code: string, message = code, public extra: Record<string, unknown> = {}) {
    super(message);
  }
}

export interface PlayerAuth {
  id: string;
  deviceKey: string;
  banned: boolean;
}

export interface SessionRow {
  seed: number;
  startLives: number;
  powerStart: boolean;
  status: string;
  startedAt: number; // epoch ms
}

export interface IapProductRow {
  kind: "consumable" | "non_consumable";
  gems: number;
  grantsPremium: boolean;
}

export interface Store {
  consumeNonce(nonce: string, ttlSeconds: number): Promise<boolean>;
  rateLimit(bucket: string, windowSeconds: number, max: number): Promise<boolean>;
  logEvent(playerId: string | null, kind: string, detail: Record<string, unknown>): Promise<void>;
  purge(): Promise<void>;
  getPlayerAuth(id: string): Promise<PlayerAuth | null>;
  registerPlayer(deviceKey: string, displayName: string): Promise<unknown>;
  profileBundle(id: string): Promise<unknown>;
  wallet(id: string): Promise<Record<string, unknown>>;
  rename(id: string, name: string): Promise<unknown>;
  buyItem(id: string, itemId: string): Promise<unknown>;
  equipSkin(id: string, skinId: string): Promise<unknown>;
  startSession(id: string, seed: number, extraLife: boolean, powerStart: boolean): Promise<unknown>;
  getSession(id: string, sessionId: string): Promise<SessionRow | null>;
  finishSession(id: string, sessionId: string, score: number, finalTick: number, accepted: boolean): Promise<Record<string, unknown>>;
  leaderboard(id: string, limit: number): Promise<unknown>;
  iapProduct(productId: string): Promise<IapProductRow | null>;
  grantPurchase(id: string, tokenHash: string, productId: string, orderId: string | null, isTest: boolean): Promise<Record<string, unknown>>;
}

export interface PlayClient {
  getProductPurchase(productId: string, token: string): Promise<ProductPurchase | null>;
  consume(productId: string, token: string): Promise<void>;
  acknowledge(productId: string, token: string): Promise<void>;
  decodeIntegrityToken(token: string): Promise<IntegrityPayload>;
}

export interface GatewayConfig {
  integrityMode: "off" | "log" | "enforce";
  integrityPolicy: IntegrityPolicy;
  allowTestPurchases: boolean;
  clockSkewMs: number;
  nonceTtlSeconds: number;
}

export interface Deps {
  ring: KeyRing;
  store: Store;
  play: PlayClient | null;
  config: GatewayConfig;
  now: () => number;
  randomSeed: () => number;
}

const MAX_BODY = 600 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const NAME = /^[A-Za-z0-9_ ]{3,16}$/;
const ID = /^[a-z0-9_]{1,40}$/;

const INTEGRITY_ACTIONS = new Set(["auth.register", "purchase.verify", "game.finish"]);

/** Error codes that are safe to show to the client verbatim. */
const PUBLIC_DB_ERRORS = new Set([
  "insufficient_gems",
  "already_owned",
  "not_owned",
  "unknown_item",
  "unknown_product",
  "no_booster",
  "session_closed",
  "purchase_token_reused",
  "banned",
]);

interface Inner {
  action: string;
  ts: number;
  nonce: string;
  sub?: string;
  payload: Record<string, unknown>;
}

type Handler = (ctx: Ctx) => Promise<unknown>;
interface Ctx {
  deps: Deps;
  inner: Inner;
  playerId: string; // "" for auth.register
  payload: Record<string, unknown>;
}

const plainError = (status: number) =>
  new Response(JSON.stringify({ error: status === 405 ? "method_not_allowed" : "bad_request" }), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });

export async function handle(req: Request, deps: Deps): Promise<Response> {
  if (req.method !== "POST") return plainError(405);
  const declared = Number(req.headers.get("content-length") ?? "0");
  if (declared > MAX_BODY) return plainError(400);
  const body = new Uint8Array(await req.arrayBuffer());
  if (body.length === 0 || body.length > MAX_BODY) return plainError(400);

  let opened;
  try {
    opened = await openRequest(deps.ring, body);
  } catch (e) {
    if (e instanceof EnvelopeError) return plainError(400);
    throw e;
  }

  let result: Record<string, unknown>;
  try {
    const data = await process(opened.plaintext, req, deps);
    result = { ok: true, data };
  } catch (e) {
    if (e instanceof ApiFailure) {
      result = { ok: false, error: { code: e.code, message: e.message }, ...e.extra };
    } else {
      console.error("api internal error", e instanceof Error ? e.name : "unknown");
      result = { ok: false, error: { code: "internal", message: "Something went wrong" } };
    }
  }
  if (Math.random() < 0.01) deps.store.purge().catch(() => {});
  return new Response(await sealResponse(opened, JSON.stringify(result)), {
    status: 200,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}

async function process(plaintext: Uint8Array, req: Request, deps: Deps): Promise<unknown> {
  const { store, config } = deps;
  let outer: { m?: unknown; s?: unknown; i?: unknown };
  try {
    outer = JSON.parse(utf8d.decode(plaintext));
  } catch {
    throw new ApiFailure("bad_request");
  }
  if (typeof outer.m !== "string" || typeof outer.s !== "string" || outer.m.length > MAX_BODY) {
    throw new ApiFailure("bad_request");
  }
  if (outer.i !== undefined && outer.i !== null && typeof outer.i !== "string") throw new ApiFailure("bad_request");

  const signed = utf8.encode(outer.m);
  const inner = parseInner(outer.m);
  const handler = HANDLERS[inner.action];
  if (!handler) throw new ApiFailure("unknown_action");

  const now = deps.now();
  if (Math.abs(now - inner.ts) > config.clockSkewMs) {
    throw new ApiFailure("clock_skew", "Device clock is out of sync", { serverTime: now });
  }

  // --- authenticate the device ------------------------------------------
  let playerId = "";
  if (inner.action === "auth.register") {
    const ip = (req.headers.get("x-forwarded-for") ?? "unknown").split(",")[0].trim();
    if (!await store.rateLimit(`register:${ip}`, 3600, 20)) throw new ApiFailure("rate_limited");
    const keyB64 = inner.payload.devicePublicKey;
    if (typeof keyB64 !== "string" || keyB64.length > 200) throw new ApiFailure("bad_request");
    let key: CryptoKey;
    try {
      key = await importDeviceKey(keyB64);
    } catch {
      throw new ApiFailure("bad_request");
    }
    if (!await verifyDeviceSignature(key, signed, outer.s)) throw new ApiFailure("unauthorized");
  } else {
    if (typeof inner.sub !== "string" || !UUID.test(inner.sub)) throw new ApiFailure("unauthorized");
    const player = await store.getPlayerAuth(inner.sub);
    if (!player) throw new ApiFailure("unauthorized");
    const key = await importDeviceKey(player.deviceKey);
    if (!await verifyDeviceSignature(key, signed, outer.s)) {
      await store.logEvent(player.id, "bad_signature", { action: inner.action });
      throw new ApiFailure("unauthorized");
    }
    if (player.banned) throw new ApiFailure("banned");
    playerId = player.id;
  }

  // --- anti-replay & abuse limits ----------------------------------------
  if (!await store.consumeNonce(inner.nonce, config.nonceTtlSeconds)) {
    await store.logEvent(playerId || null, "replay", { action: inner.action });
    throw new ApiFailure("replay");
  }
  if (playerId && !await store.rateLimit(`player:${playerId}`, 60, 120)) throw new ApiFailure("rate_limited");

  // --- Play Integrity for sensitive actions --------------------------------
  if (INTEGRITY_ACTIONS.has(inner.action) && config.integrityMode !== "off") {
    const reason = await checkIntegrity(outer.i as string | undefined, signed, deps);
    if (reason) {
      await store.logEvent(playerId || null, "integrity_failed", { action: inner.action, reason });
      if (config.integrityMode === "enforce") throw new ApiFailure("integrity_failed", "Device verification failed");
    }
  }

  return await handler({ deps, inner, playerId, payload: inner.payload });
}

function parseInner(m: string): Inner {
  let v: Record<string, unknown>;
  try {
    v = JSON.parse(m);
  } catch {
    throw new ApiFailure("bad_request");
  }
  if (
    !v || typeof v !== "object" || typeof v.action !== "string" || !Number.isInteger(v.ts) ||
    typeof v.nonce !== "string" || !/^[A-Za-z0-9_-]{16,64}$/.test(v.nonce) ||
    (v.sub !== undefined && v.sub !== null && typeof v.sub !== "string") ||
    !v.payload || typeof v.payload !== "object" || Array.isArray(v.payload)
  ) throw new ApiFailure("bad_request");
  return {
    action: v.action,
    ts: v.ts as number,
    nonce: v.nonce,
    sub: (v.sub as string | null) ?? undefined,
    payload: v.payload as Record<string, unknown>,
  };
}

async function checkIntegrity(token: string | undefined, signed: Uint8Array, deps: Deps): Promise<string | null> {
  if (!token) return "missing_token";
  if (!deps.play) return "integrity_unavailable";
  let payload: IntegrityPayload;
  try {
    payload = await deps.play.decodeIntegrityToken(token);
  } catch {
    return "decode_failed";
  }
  const expected = b64uEncode(await sha256(signed));
  return evaluateIntegrity(payload, expected, deps.config.integrityPolicy, deps.now());
}

// ------------------------------------------------------------- handlers --

const bool = (v: unknown) => v === true;

const HANDLERS: Record<string, Handler> = {
  "auth.register": async ({ deps, payload }) => {
    const suffix = Array.from(crypto.getRandomValues(new Uint8Array(2)))
      .map((b) => b.toString(16).padStart(2, "0")).join("").toUpperCase();
    return await deps.store.registerPlayer(payload.devicePublicKey as string, `Bot_${suffix}`);
  },

  "profile.get": async ({ deps, playerId }) => await deps.store.profileBundle(playerId),

  "profile.rename": async ({ deps, playerId, payload }) => {
    const name = typeof payload.name === "string" ? payload.name.trim() : "";
    if (!NAME.test(name)) throw new ApiFailure("invalid_name", "3-16 letters, numbers, spaces or _");
    return await deps.store.rename(playerId, name);
  },

  "leaderboard.top": async ({ deps, playerId, payload }) => {
    const limit = Number.isInteger(payload.limit) ? payload.limit as number : 50;
    return await deps.store.leaderboard(playerId, Math.min(100, Math.max(1, limit)));
  },

  "store.buy": async ({ deps, playerId, payload }) => {
    if (typeof payload.itemId !== "string" || !ID.test(payload.itemId)) throw new ApiFailure("unknown_item");
    return await deps.store.buyItem(playerId, payload.itemId);
  },

  "skin.equip": async ({ deps, playerId, payload }) => {
    if (typeof payload.skinId !== "string" || !ID.test(payload.skinId)) throw new ApiFailure("unknown_item");
    return await deps.store.equipSkin(playerId, payload.skinId);
  },

  "game.start": async ({ deps, playerId, payload }) => {
    return await deps.store.startSession(
      playerId,
      deps.randomSeed(),
      bool(payload.useExtraLife),
      bool(payload.usePowerStart),
    );
  },

  "game.finish": async ({ deps, playerId, payload }) => {
    const { sessionId, inputs, finalTick, claimedScore } = payload;
    if (typeof sessionId !== "string" || !UUID.test(sessionId)) throw new ApiFailure("bad_request");
    if (!Number.isInteger(claimedScore) || !Number.isInteger(finalTick)) throw new ApiFailure("bad_request");
    const session = await deps.store.getSession(playerId, sessionId);
    if (!session || session.status !== "active") throw new ApiFailure("session_closed");

    // The run cannot have taken less wall-clock time than its simulated time.
    const elapsed = deps.now() - session.startedAt;
    const simulated = (finalTick as number) * RULES.TICK_MS;
    let reason: string | null = null;
    if (elapsed < simulated * 0.9 - 3_000) reason = "too_fast";

    let score = 0;
    if (!reason) {
      try {
        const snap = replay(session.seed, session.startLives, session.powerStart, inputs, finalTick);
        score = snap.score;
        if (snap.score !== claimedScore) reason = "score_mismatch";
      } catch (e) {
        if (!(e instanceof ReplayError)) throw e;
        reason = "invalid_replay";
      }
    }
    const result = await deps.store.finishSession(playerId, sessionId, reason ? claimedScore as number : score, finalTick as number, !reason);
    if (reason) {
      await deps.store.logEvent(playerId, "cheat_suspected", { sessionId, reason });
      throw new ApiFailure("score_rejected", "This run could not be verified");
    }
    return result;
  },

  "purchase.verify": async ({ deps, playerId, payload }) => {
    const { productId, purchaseToken } = payload;
    if (typeof productId !== "string" || !ID.test(productId)) throw new ApiFailure("unknown_product");
    if (typeof purchaseToken !== "string" || purchaseToken.length < 10 || purchaseToken.length > 4096) {
      throw new ApiFailure("invalid_purchase");
    }
    const product = await deps.store.iapProduct(productId);
    if (!product) throw new ApiFailure("unknown_product");
    if (!deps.play) throw new ApiFailure("billing_unavailable", "Purchases are temporarily unavailable");

    const pp = await deps.play.getProductPurchase(productId, purchaseToken);
    if (!pp) throw new ApiFailure("invalid_purchase");
    if (pp.purchaseState === 2) {
      return { status: "pending", gemsGranted: 0, ...await deps.store.wallet(playerId) };
    }
    if (pp.purchaseState !== 0) throw new ApiFailure("invalid_purchase");

    // The app passes sha256(playerId) as obfuscatedAccountId to the billing
    // flow, so a token bought by someone else cannot be redeemed here.
    if (pp.obfuscatedExternalAccountId !== await sha256Hex(playerId)) {
      await deps.store.logEvent(playerId, "purchase_account_mismatch", { productId });
      throw new ApiFailure("invalid_purchase");
    }
    const isTest = pp.purchaseType === 0;
    if (isTest && !deps.config.allowTestPurchases) throw new ApiFailure("invalid_purchase", "Test purchases are disabled");

    const granted = await deps.store.grantPurchase(
      playerId,
      await sha256Hex(purchaseToken),
      productId,
      pp.orderId ?? null,
      isTest,
    );
    try {
      if (product.kind === "consumable" && pp.consumptionState !== 1) await deps.play.consume(productId, purchaseToken);
      if (product.kind === "non_consumable" && pp.acknowledgementState !== 1) {
        await deps.play.acknowledge(productId, purchaseToken);
      }
    } catch {
      // Already credited; a retry is idempotent and will consume/acknowledge again.
      await deps.store.logEvent(playerId, "purchase_finalize_failed", { productId });
    }
    return {
      status: granted.status,
      gemsGranted: granted.gemsGranted,
      gems: granted.gems,
      premium: granted.premium,
    };
  },
};

export function mapDbError(message: string | undefined): ApiFailure {
  const code = (message ?? "").trim();
  if (PUBLIC_DB_ERRORS.has(code)) return new ApiFailure(code);
  return new ApiFailure("internal", "Something went wrong");
}
