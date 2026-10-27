// Server-to-server calls to Google: service-account OAuth, Play Developer API
// (purchase verification / consume / acknowledge) and Play Integrity decoding.
// Credentials come from the GOOGLE_SERVICE_ACCOUNT_JSON secret and never
// leave the edge function.

import { b64uEncode, utf8 } from "./b64.ts";

export interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

const SCOPES = [
  "https://www.googleapis.com/auth/androidpublisher",
  "https://www.googleapis.com/auth/playintegrity",
].join(" ");

let cachedToken: { token: string; exp: number } | null = null;

async function importPem(pem: string): Promise<CryptoKey> {
  const body = pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return await crypto.subtle.importKey(
    "pkcs8",
    der as BufferSource,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

export async function googleAccessToken(sa: ServiceAccount, fetchFn = fetch): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.exp - 60 > now) return cachedToken.token;
  const tokenUri = sa.token_uri ?? "https://oauth2.googleapis.com/token";
  const header = b64uEncode(utf8.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = b64uEncode(utf8.encode(JSON.stringify({
    iss: sa.client_email,
    scope: SCOPES,
    aud: tokenUri,
    iat: now,
    exp: now + 3600,
  })));
  const key = await importPem(sa.private_key);
  const sig = new Uint8Array(
    await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, utf8.encode(`${header}.${claims}`) as BufferSource),
  );
  const res = await fetchFn(tokenUri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${header}.${claims}.${b64uEncode(sig)}`,
    }),
  });
  if (!res.ok) throw new Error(`google oauth failed: ${res.status}`);
  const json = await res.json();
  cachedToken = { token: json.access_token, exp: now + (json.expires_in ?? 3600) };
  return cachedToken.token;
}

export interface ProductPurchase {
  purchaseState?: number; // 0 purchased, 1 canceled, 2 pending
  consumptionState?: number; // 0 yet to be consumed, 1 consumed
  acknowledgementState?: number; // 0 yet to be acknowledged, 1 acknowledged
  orderId?: string;
  purchaseType?: number; // 0 = test (license tester), 1 = promo, 2 = rewarded
  obfuscatedExternalAccountId?: string;
  productId?: string;
}

const PUBLISHER = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";

export class PlayApi {
  constructor(
    private sa: ServiceAccount,
    private packageName: string,
    private fetchFn: typeof fetch = fetch,
  ) {}

  private async call(method: string, url: string, body?: unknown): Promise<Response> {
    const token = await googleAccessToken(this.sa, this.fetchFn);
    return await this.fetchFn(url, {
      method,
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  }

  private productUrl(productId: string, token: string) {
    return `${PUBLISHER}/${encodeURIComponent(this.packageName)}/purchases/products/${
      encodeURIComponent(productId)
    }/tokens/${encodeURIComponent(token)}`;
  }

  async getProductPurchase(productId: string, token: string): Promise<ProductPurchase | null> {
    const res = await this.call("GET", this.productUrl(productId, token));
    if (res.status === 404 || res.status === 400 || res.status === 410) return null;
    if (!res.ok) throw new Error(`play purchases.get failed: ${res.status}`);
    return await res.json();
  }

  async consume(productId: string, token: string): Promise<void> {
    const res = await this.call("POST", this.productUrl(productId, token) + ":consume");
    if (!res.ok) throw new Error(`play consume failed: ${res.status}`);
  }

  async acknowledge(productId: string, token: string): Promise<void> {
    const res = await this.call("POST", this.productUrl(productId, token) + ":acknowledge", {});
    if (!res.ok) throw new Error(`play acknowledge failed: ${res.status}`);
  }

  async decodeIntegrityToken(token: string): Promise<IntegrityPayload> {
    const res = await this.call(
      "POST",
      `https://playintegrity.googleapis.com/v1/${encodeURIComponent(this.packageName)}:decodeIntegrityToken`,
      { integrity_token: token },
    );
    if (!res.ok) throw new Error(`integrity decode failed: ${res.status}`);
    const json = await res.json();
    return json.tokenPayloadExternal as IntegrityPayload;
  }
}

export interface IntegrityPayload {
  requestDetails?: { requestPackageName?: string; requestHash?: string; timestampMillis?: string };
  appIntegrity?: { appRecognitionVerdict?: string; packageName?: string; certificateSha256Digest?: string[] };
  deviceIntegrity?: { deviceRecognitionVerdict?: string[] };
  accountDetails?: { appLicensingVerdict?: string };
}

export interface IntegrityPolicy {
  packageName: string;
  allowedCertDigests: string[]; // base64url SHA-256 of the app signing cert(s); empty = skip
  requireLicensed: boolean;
  maxAgeMs: number;
}

/** Returns null when the verdict is acceptable, otherwise a reason code. */
export function evaluateIntegrity(
  p: IntegrityPayload,
  expectedHash: string,
  policy: IntegrityPolicy,
  now = Date.now(),
): string | null {
  const rd = p.requestDetails ?? {};
  if (rd.requestPackageName !== policy.packageName) return "package_mismatch";
  if (rd.requestHash !== expectedHash) return "hash_mismatch";
  const ts = Number(rd.timestampMillis ?? 0);
  if (!ts || Math.abs(now - ts) > policy.maxAgeMs) return "stale_token";
  const app = p.appIntegrity ?? {};
  if (app.appRecognitionVerdict !== "PLAY_RECOGNIZED") return "app_not_recognized";
  if (app.packageName !== policy.packageName) return "package_mismatch";
  if (
    policy.allowedCertDigests.length > 0 &&
    !(app.certificateSha256Digest ?? []).some((d) => policy.allowedCertDigests.includes(d))
  ) return "bad_signing_cert";
  const device = p.deviceIntegrity?.deviceRecognitionVerdict ?? [];
  if (!device.includes("MEETS_DEVICE_INTEGRITY")) return "device_integrity";
  if (policy.requireLicensed && p.accountDetails?.appLicensingVerdict !== "LICENSED") return "unlicensed";
  return null;
}
