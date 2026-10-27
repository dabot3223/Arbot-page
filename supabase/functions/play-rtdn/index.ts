// Google Play Real-time Developer Notifications (Pub/Sub push).
// Revokes gems / premium when a purchase is refunded or charged back
// (voidedPurchaseNotification), closing the "buy, spend, refund" hole.
//
// The Pub/Sub push subscription must use authentication: Google signs an OIDC
// JWT for RTDN_PUSH_SERVICE_ACCOUNT with audience RTDN_AUDIENCE, which we
// verify against Google's JWKS before trusting the message.

import { b64uDecode, sha256Hex, utf8 } from "../_shared/b64.ts";
import { env, packageName } from "../_shared/config.ts";
import { SupabaseStore } from "../_shared/db.ts";

const JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
let jwksCache: { keys: Record<string, CryptoKey>; exp: number } | null = null;

async function googleKey(kid: string): Promise<CryptoKey | null> {
  if (!jwksCache || jwksCache.exp < Date.now() || !jwksCache.keys[kid]) {
    const res = await fetch(JWKS_URL);
    if (!res.ok) return null;
    const { keys } = await res.json() as { keys: (JsonWebKey & { kid: string })[] };
    const imported: Record<string, CryptoKey> = {};
    for (const k of keys) {
      imported[k.kid] = await crypto.subtle.importKey(
        "jwk",
        k,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["verify"],
      );
    }
    jwksCache = { keys: imported, exp: Date.now() + 3600_000 };
  }
  return jwksCache.keys[kid] ?? null;
}

async function verifyPushToken(auth: string | null): Promise<boolean> {
  if (!auth?.startsWith("Bearer ")) return false;
  const parts = auth.slice(7).split(".");
  if (parts.length !== 3) return false;
  try {
    const header = JSON.parse(new TextDecoder().decode(b64uDecode(parts[0])));
    const claims = JSON.parse(new TextDecoder().decode(b64uDecode(parts[1])));
    if (header.alg !== "RS256") return false;
    const key = await googleKey(header.kid);
    if (!key) return false;
    const ok = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      key,
      b64uDecode(parts[2]) as BufferSource,
      utf8.encode(`${parts[0]}.${parts[1]}`) as BufferSource,
    );
    const now = Date.now() / 1000;
    return ok &&
      ["https://accounts.google.com", "accounts.google.com"].includes(claims.iss) &&
      claims.aud === env("RTDN_AUDIENCE") &&
      claims.email === env("RTDN_PUSH_SERVICE_ACCOUNT") &&
      claims.email_verified === true &&
      typeof claims.exp === "number" && claims.exp > now &&
      typeof claims.iat === "number" && claims.iat < now + 300;
  } catch {
    return false;
  }
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response(null, { status: 405 });
  if (!await verifyPushToken(req.headers.get("authorization"))) return new Response(null, { status: 401 });

  try {
    const push = await req.json();
    const data = JSON.parse(new TextDecoder().decode(
      Uint8Array.from(atob(push?.message?.data ?? ""), (c) => c.charCodeAt(0)),
    ));
    if (data.packageName !== packageName()) return new Response(null, { status: 204 });

    const voided = data.voidedPurchaseNotification;
    if (voided?.purchaseToken) {
      const store = new SupabaseStore(env("SUPABASE_URL"), env("SUPABASE_SERVICE_ROLE_KEY"));
      await store.revokePurchase(await sha256Hex(voided.purchaseToken), `refund_type_${voided.refundType ?? "?"}`);
    }
    // Acknowledge everything else (test notifications, one-time product updates).
    return new Response(null, { status: 204 });
  } catch (e) {
    console.error("rtdn error", e instanceof Error ? e.message : "unknown");
    // Non-2xx makes Pub/Sub retry.
    return new Response(null, { status: 500 });
  }
});
