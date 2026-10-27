// Generates the envelope key pair for one key id.
//
//   deno run scripts/generate-api-keys.ts k1
//
// * PRIVATE key  -> Supabase secret API_PRIVATE_KEYS (JSON map kid -> key).
//                   Never commit it, never put it in the app.
// * PUBLIC key   -> android/local.properties (or CI secrets) as
//                   arbot.apiKeyId / arbot.apiPublicKey. It is public by design.
//
// Rotation: generate k2, set API_PRIVATE_KEYS={"k1":"…","k2":"…"}, ship an app
// update pinned to k2, and drop k1 once old versions are gone.

const kid = Deno.args[0] ?? "k1";
const kp = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
const b64u = (b: ArrayBuffer) =>
  btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const priv = b64u(await crypto.subtle.exportKey("pkcs8", kp.privateKey));
const pub = b64u(await crypto.subtle.exportKey("raw", kp.publicKey));

console.log("# --- Supabase secret (keep private) ---");
console.log(`API_PRIVATE_KEYS={"${kid}":"${priv}"}`);
console.log("\n# --- android/local.properties (public) ---");
console.log(`arbot.apiKeyId=${kid}`);
console.log(`arbot.apiPublicKey=${pub}`);
