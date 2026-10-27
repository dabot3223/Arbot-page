// Environment / secrets for the edge functions. Set with
//   supabase secrets set --env-file supabase/.env.production
// (see docs/SETUP.md). Nothing here is ever sent to the client.

import type { GatewayConfig } from "./gateway.ts";
import type { ServiceAccount } from "./google.ts";

export function env(name: string, fallback?: string): string {
  const v = Deno.env.get(name) ?? fallback;
  if (v === undefined || v === "") throw new Error(`missing required env ${name}`);
  return v;
}

export function packageName(): string {
  return env("ANDROID_PACKAGE_NAME", "com.arbot.game");
}

export function serviceAccount(): ServiceAccount | null {
  const raw = Deno.env.get("GOOGLE_SERVICE_ACCOUNT_JSON");
  return raw ? JSON.parse(raw) as ServiceAccount : null;
}

export function gatewayConfig(): GatewayConfig {
  const mode = env("INTEGRITY_MODE", "enforce");
  if (mode !== "off" && mode !== "log" && mode !== "enforce") throw new Error("INTEGRITY_MODE must be off|log|enforce");
  return {
    integrityMode: mode,
    integrityPolicy: {
      packageName: packageName(),
      allowedCertDigests: (Deno.env.get("INTEGRITY_CERT_DIGESTS") ?? "").split(",").map((s) => s.trim()).filter(Boolean),
      requireLicensed: (Deno.env.get("INTEGRITY_REQUIRE_LICENSED") ?? "true") === "true",
      maxAgeMs: 5 * 60_000,
    },
    allowTestPurchases: Deno.env.get("ALLOW_TEST_PURCHASES") === "true",
    clockSkewMs: 5 * 60_000,
    nonceTtlSeconds: 15 * 60,
  };
}
