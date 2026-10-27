# Setup guide

You need: a Supabase project, a Google Play Console developer account, a
Google Cloud project, the [Supabase CLI](https://supabase.com/docs/guides/cli),
[Deno](https://deno.com) 2.x and Android Studio (or JDK 17 + Android SDK 36).

## 1. Envelope keys

```bash
deno run scripts/generate-api-keys.ts k1
```

Keep the `API_PRIVATE_KEYS=…` line for step 2 (secret). Put the two public
lines in `android/local.properties` (step 4).

## 2. Supabase

```bash
supabase login
supabase link --project-ref <project-ref>
supabase db push                       # applies supabase/migrations
cp supabase/.env.example supabase/.env.production   # fill it in (git-ignored)
supabase secrets set --env-file supabase/.env.production
supabase functions deploy api --no-verify-jwt
supabase functions deploy play-rtdn --no-verify-jwt
```

In the dashboard, double-check: **Authentication → Sign In / Providers**:
sign-ups off; **Realtime** and **Storage** unused. The app never uses the
anon key.

Local development:

```bash
supabase start
supabase functions serve --env-file supabase/.env.local --no-verify-jwt
# emulator URL: http://10.0.2.2:54321/functions/v1/api  (debug builds only)
```

Use `INTEGRITY_MODE=off` locally (Play Integrity only works for apps installed from Play).

## 3. Google Play & Google Cloud

1. **Play Console → create the app** with package `com.arbot.game` and enable Play App Signing.
2. **Monetize → In-app products**, create these one-time products (IDs must match
   `iap_products` in the migration; prices are set in Play Console):
   | Product ID | Type | Grants |
   | --- | --- | --- |
   | `gems_pack_small` | consumable | 120 gems |
   | `gems_pack_medium` | consumable | 650 gems |
   | `gems_pack_large` | consumable | 1400 gems |
   | `premium_pass` | non-consumable | Premium (+1 life, 2× gems, all skins) + 500 gems |
3. **Google Cloud**: enable *Google Play Android Developer API* and *Play
   Integrity API*. Create a service account, download its JSON key and put it
   (single line) in `GOOGLE_SERVICE_ACCOUNT_JSON`.
4. **Play Console → Users and permissions**: invite the service account with
   *View financial data* and *Manage orders and subscriptions*.
5. **Play Console → App integrity → Play Integrity API**: link the Cloud
   project. Note the **Cloud project number** (Android build config) and copy
   the **app signing key certificate SHA-256** into `INTEGRITY_CERT_DIGESTS`
   (base64url, no padding).
6. **Refunds (RTDN)**: create a Pub/Sub topic, set it under *Monetization
   setup → Real-time developer notifications*, then a **push** subscription to
   `https://<project-ref>.supabase.co/functions/v1/play-rtdn` with
   *authentication enabled* (service account = `RTDN_PUSH_SERVICE_ACCOUNT`,
   audience = `RTDN_AUDIENCE`).
7. Add license testers; set `ALLOW_TEST_PURCHASES=true` **only** while testing.

## 4. Android build configuration

`android/local.properties` (git-ignored) or CI variables (`ARBOT_*`):

```properties
sdk.dir=/path/to/Android/sdk
arbot.apiUrl=https://<project-ref>.supabase.co/functions/v1/api
arbot.apiKeyId=k1
arbot.apiPublicKey=<from step 1>
arbot.cloudProjectNumber=<Cloud project number>
# Optional but recommended: pin the CA intermediates (current + backup)
arbot.certPins=sha256/AAAA...=,sha256/BBBB...=

# Release signing (or provide via ARBOT_SIGNING_* env vars in CI)
arbot.signing.storeFile=/secure/path/upload.jks
arbot.signing.storePassword=...
arbot.signing.keyAlias=upload
arbot.signing.keyPassword=...
```

Computing a pin for the certificate chain served by your Supabase host:

```bash
HOST=<project-ref>.supabase.co
openssl s_client -servername $HOST -connect $HOST:443 -showcerts </dev/null 2>/dev/null \
  | awk '/BEGIN CERT/{n++} n==2' \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
```

That pins the intermediate CA; add a backup pin for the CA's alternate
intermediate (or root) so a certificate rotation does not lock users out.
Because API bodies are already end-to-end encrypted to the pinned envelope
key, TLS pinning is defence in depth.

Without `arbot.apiUrl` the app builds in **offline practice mode**.

Build:

```bash
cd android
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:bundleRelease        # signed AAB for Play upload
```

## 5. Tests

```bash
(cd android/core && ../gradlew test)                 # engine + crypto protocol (JVM)
(cd supabase && deno test --allow-read tests/*.test.ts)      # gateway, envelope, engine parity
(cd supabase/tests/db && npm ci && npm test)          # migration on real Postgres (PGlite)
```

CI (`.github/workflows/ci.yml`) runs all of these plus Android lint and
debug/release builds on every push.
