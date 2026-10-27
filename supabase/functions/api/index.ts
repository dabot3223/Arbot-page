// POST /functions/v1/api – the only endpoint the Android app talks to.
// Deployed with verify_jwt = false: authentication is done by the device
// signature inside the encrypted envelope (see _shared/gateway.ts).

import { loadKeyRing } from "../_shared/envelope.ts";
import { env, gatewayConfig, packageName, serviceAccount } from "../_shared/config.ts";
import { SupabaseStore } from "../_shared/db.ts";
import { PlayApi } from "../_shared/google.ts";
import { type Deps, handle } from "../_shared/gateway.ts";

let depsPromise: Promise<Deps> | null = null;

function deps(): Promise<Deps> {
  depsPromise ??= (async () => {
    const sa = serviceAccount();
    return {
      ring: await loadKeyRing(env("API_PRIVATE_KEYS")),
      store: new SupabaseStore(env("SUPABASE_URL"), env("SUPABASE_SERVICE_ROLE_KEY")),
      play: sa ? new PlayApi(sa, packageName()) : null,
      config: gatewayConfig(),
      now: () => Date.now(),
      randomSeed: () => crypto.getRandomValues(new Uint32Array(1))[0],
    };
  })();
  return depsPromise;
}

Deno.serve(async (req) => {
  try {
    return await handle(req, await deps());
  } catch (e) {
    depsPromise = null;
    console.error("api fatal", e instanceof Error ? e.message : "unknown");
    return new Response(JSON.stringify({ error: "unavailable" }), {
      status: 503,
      headers: { "content-type": "application/json", "cache-control": "no-store" },
    });
  }
});
