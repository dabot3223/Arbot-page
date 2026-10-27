# Ar_B0T security design

Security is the primary requirement for this app, so every layer assumes the
layer above it can be compromised. This document describes what is protected,
how, and what is still the operator's responsibility.

## Threats addressed

| Threat | Mitigation |
| --- | --- |
| Reading / modifying API traffic (MITM proxy, user-installed CA, compromised CDN/TLS terminator) | All bodies end-to-end encrypted with **envelope v1** to a server key pinned in the app; TLS 1.2+/1.3 restricted ciphers; user CAs not trusted; optional OkHttp certificate pinning |
| Replaying captured requests | Per-request random nonce stored server-side (single use), ±5 min timestamp window, fresh ephemeral key per request |
| Replaying a response onto another request | Response AAD binds the request IV; response key derived from the request's ephemeral ECDH |
| Stolen session tokens | There are none: each request is signed by a **non-exportable Android Keystore key** (StrongBox when present). No bearer token exists to steal |
| Modded / repackaged APK, emulators, bots | **Play Integrity** (standard API) required for registration, purchase verification and score submission; token bound to `SHA-256(signed message)`; app recognition, signing cert digest, device integrity and licence verdicts checked server-side |
| Fake purchases / receipt replay / receipt sharing | Server verifies every token with the Google Play Developer API; token must be `PURCHASED`; `obfuscatedExternalAccountId == sha256(playerId)` binds it to the buyer; token hash is a primary key so it is credited once and never to two players; test purchases rejected unless explicitly allowed; server consumes/acknowledges |
| Refund abuse ("buy, spend, refund") | `play-rtdn` webhook (authenticated Pub/Sub OIDC JWT) revokes gems/premium on voided purchases |
| Score cheating (memory editing, forged requests) | Server issues the RNG seed; client submits only the input log; server **re-simulates the whole run** with a TypeScript port of the deterministic engine and records the score it computes. Runs that finish faster than real time are rejected. Cheat attempts go to `security_events` |
| Double spending gems / race conditions | All balance changes are single `SECURITY DEFINER` Postgres functions with row locks and `CHECK (gems >= 0)` |
| Leaked Supabase anon key | Players never use PostgREST. RLS on every table with **no policies**, all privileges revoked from `anon`/`authenticated`, sign-ups disabled, Realtime/Storage off |
| Server internals leaking through errors | Only whitelisted error codes leave the gateway; everything else becomes `internal`. Malformed envelopes get a generic `400 bad_request` |
| Brute force / abuse | Per-player (120/min) and per-IP registration (20/h) rate limits in Postgres |
| Local data theft (backup, rooted device file copy) | Backups and device transfer disabled; local values encrypted with AES-256-GCM under a Keystore key with the entry name as AAD |
| Reverse engineering | R8 full mode, class repackaging, log stripping; no secrets in the APK (only public key, URL, pins, project number) |
| Shoulder surfing / screen capture of payments | `FLAG_SECURE` on the store screen |

## Envelope v1 (request/response encryption)

```
client                                                     edge function `api`
------                                                     -------------------
eph = P-256 keypair (new per request)
shared = ECDH(eph.priv, serverPub[kid])                    shared = ECDH(serverPriv[kid], epk)
okm = HKDF-SHA256(shared, salt=epk, info="arbot-envelope-v1|"+kid, 64)
reqKey = okm[0..32], resKey = okm[32..64]
inner  = {"action","ts","nonce","sub","payload"}           (JSON bytes)
sig    = ECDSA-P256-SHA256(deviceKey, inner)               (Keystore)
integ  = PlayIntegrity(requestHash = b64u(SHA-256(inner)))  (sensitive actions)
pt     = pad256({"m": inner, "s": sig, "i": integ})
POST {"v":1,"kid","epk","iv","ct": AES-256-GCM(reqKey, iv, aad="arbot-v1-req|"+kid, pt)}
                                                           decrypt -> verify sig -> nonce -> rate limit
                                                           -> integrity -> handler
<- {"v":1,"iv","ct": AES-256-GCM(resKey, iv', aad="arbot-v1-res|"+reqIv, pad256(response))}
```

* Primitives are all standard (JCA on Android, WebCrypto in Deno) — no custom crypto.
* Bodies are padded to 256-byte buckets to reduce size side-channels.
* Interoperability is pinned by `shared/fixtures/envelope-vectors.json`
  (test keys only), checked by both the Kotlin and Deno test suites.
* Forward secrecy: the client side is ephemeral per request. The server key is
  static, so **rotate it** (see below) and keep it only in Supabase secrets.

### Key rotation

1. `deno run scripts/generate-api-keys.ts k2`
2. Set `API_PRIVATE_KEYS={"k1":"…","k2":"…"}` (both accepted).
3. Ship an app update built with `arbot.apiKeyId=k2`.
4. Remove `k1` once old app versions are no longer supported.

## Score verification

`android/core/.../GameEngine.kt` and `supabase/functions/_shared/engine.ts`
implement the same deterministic simulation (10 ms ticks, mulberry32 PRNG,
32-bit integer math). `shared/fixtures/engine-vectors.json` contains long
recorded runs that both engines must reproduce bit-for-bit. **Any rule change
must be made in both files** and the fixture regenerated with
`REGEN_FIXTURES=1 ./gradlew test` (from `android/core`).

## Operator checklist (production)

- [ ] `INTEGRITY_MODE=enforce`, `INTEGRITY_CERT_DIGESTS` set to the Play app-signing certificate digest
- [ ] `ALLOW_TEST_PURCHASES=false`
- [ ] Service account has only the Play Console permissions it needs (view financial data, manage orders) and Play Integrity API access
- [ ] `arbot.certPins` set with at least two pins (current + backup CA) and a rotation plan
- [ ] RTDN Pub/Sub push subscription uses authentication with `RTDN_AUDIENCE` / `RTDN_PUSH_SERVICE_ACCOUNT`
- [ ] Release keystore stored outside the repo (CI secret), Play App Signing enabled
- [ ] Review `security_events` regularly (`bad_signature`, `replay`, `integrity_failed`, `cheat_suspected`, `purchase_*`)
- [ ] Envelope key rotation scheduled

## Known limitations

* Player identity is bound to the device key. Uninstalling the app (or wiping
  the Keystore) starts a new account; gems and a Premium Pass do not move to a
  new install. Adding account linking (e.g. Google sign-in via Credential
  Manager, verified server-side) is the recommended next step.
* Replay verification proves a run follows the rules, not that a human played
  it. Because the client must know the seed to simulate locally, a tool-assisted
  "solver" could play optimally. Play Integrity raises the bar; for more,
  watch `security_events` and score distributions, or add server-side
  plausibility checks (inputs per second, reaction-time statistics).
* Client-side tamper checks (`RuntimeGuard`) are only a speed bump; the
  server-side checks above are what actually enforce the rules.
* The original web game's background track (Pirates of the Caribbean theme) is
  copyrighted and is **not** included in the app; sound effects are synthesised
  at runtime instead.
