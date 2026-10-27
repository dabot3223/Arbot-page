# Ar_B0T

Ar_B0T began as a small browser game (`index.html`, `ab.js`, `des.css`). This
repository now also contains a native **Android** version with animated neon
graphics, Google Play in-app purchases and a hardened **Supabase** backend.

## Gameplay

Guide Ar_B0T around a wrapping 20×11 grid:

* **Data gems** (+5) and **energy cells** (+10) appear and move around.
* **Mines** cover the board and reshuffle every 30 s. Touch one and you lose a life.
* The **hunter bot** chases you and speeds up as your score grows.
* Collect **3 energy cells** to overload for 5 s. The hunter flees, and you can destroy it for +25.
* You have 5 lives. Ranked runs earn gems.

Controls: swipe, tap toward a direction, the optional on-screen D-pad, or the
arrow/WASD keys.

## What's inside

```
android/
  core/   pure-Kotlin deterministic engine + encrypted API protocol (JVM-tested)
  app/    Jetpack Compose game: renderer, particles, synth SFX, haptics,
          store (Play Billing 8), leaderboard, Keystore security
supabase/
  migrations/          locked-down Postgres schema + transactional functions
  functions/api        single encrypted gateway for every app request
  functions/play-rtdn  Play refund/chargeback webhook
  tests/               Deno gateway/engine tests + Postgres (PGlite) tests
shared/fixtures/       cross-language test vectors (engine + crypto)
scripts/               key generation
docs/                  SETUP.md, SECURITY.md
```

### In-app purchases

| Product | Contents |
| --- | --- |
| `gems_pack_small` / `medium` / `large` | 120 / 650 / 1400 gems |
| `premium_pass` (one-time) | +1 life every run, 2× gem rewards, every skin, 500 gems |

Gems buy skins (Neon Runner, Pirate Captain, Golden Legend) and boosters
(Extra Life, Power Start). Every purchase is verified with Google on the
server before anything is credited.

### Security highlights

* All API requests and responses are **end-to-end encrypted** (ECDH P-256 →
  HKDF-SHA256 → AES-256-GCM) on top of TLS, padded, and protected against replay.
* Every request is **signed by a hardware-backed device key** (Android Keystore / StrongBox).
* **Play Integrity** attestation is required for sign-up, purchases and score submission.
* The server **replays every run** with the same deterministic engine, so client-side scores are never trusted.
* Purchases are bound to the buyer's account, credited once, and revoked on refund.
* The database is unreachable with the public keys (RLS with no policies, all privileges revoked).

See [docs/SECURITY.md](docs/SECURITY.md) for the full design and
[docs/SETUP.md](docs/SETUP.md) to deploy it.
