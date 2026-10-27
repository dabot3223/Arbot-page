// Runs the migration on an in-process Postgres (PGlite) and exercises every
// stored procedure plus the anon/authenticated lock-down.  `npm ci && npm test`
import { PGlite } from "@electric-sql/pglite";
import { pgcrypto } from "@electric-sql/pglite/contrib/pgcrypto";
import fs from "node:fs";
const db = new PGlite({ extensions: { pgcrypto } });
await db.exec(`create role anon; create role authenticated; create role service_role; create schema extensions;`);
await db.exec(fs.readFileSync(new URL("../../migrations/20261027000000_init.sql", import.meta.url), "utf8"));
const q = async (sql, params=[]) => (await db.query(sql, params)).rows;
const one = async (sql, params) => Object.values((await q(sql, params))[0])[0];
const expectErr = async (sql, params, code) => { try { await q(sql, params); console.log("FAIL expected", code); process.exitCode=1 } catch (e) { console.log(e.message === code ? "ok  " : "FAIL", "error", e.message) } };
const check = (name, cond) => { console.log(cond ? "ok  " : "FAIL", name); if (!cond) process.exitCode = 1; };

const reg = await one(`select public.register_player('devkeyAAA','Bot_AB12')`);
const pid = reg.playerId;
check("register idempotent", (await one(`select public.register_player('devkeyAAA','Bot_ZZZZ')`)).playerId === pid);
await expectErr(`select public.buy_item($1,'skin_neon')`, [pid], "insufficient_gems");
let g = await one(`select public.grant_purchase($1,'hash1','gems_pack_medium','GPA.1',false)`, [pid]);
check("grant 650", g.gems === 650 && g.status === "granted");
g = await one(`select public.grant_purchase($1,'hash1','gems_pack_medium','GPA.1',false)`, [pid]);
check("grant idempotent", g.gems === 650 && g.status === "already_granted");
const other = (await one(`select public.register_player('devkeyBBB','Bot_CD34')`)).playerId;
await expectErr(`select public.grant_purchase($1,'hash1','gems_pack_medium','GPA.1',false)`, [other], "purchase_token_reused");
let w = await one(`select public.buy_item($1,'skin_neon')`, [pid]);
check("bought skin", w.gems === 350 && w.inventory.some(i => i.itemId === "skin_neon"));
await expectErr(`select public.buy_item($1,'skin_neon')`, [pid], "already_owned");
await expectErr(`select public.equip_skin($1,'skin_gold')`, [pid], "not_owned");
w = await one(`select public.equip_skin($1,'skin_neon')`, [pid]);
check("equipped", w.equippedSkin === "skin_neon");
w = await one(`select public.buy_item($1,'booster_extra_life')`, [pid]);
check("booster", w.gems === 200);
const s = await one(`select public.start_game_session($1, 4294967295, true, false)`, [pid]);
check("session lives 6", s.startLives === 6);
await expectErr(`select public.start_game_session($1, 1, true, false)`, [pid], "no_booster");
const r = await one(`select public.finish_game_session($1,$2,425,9000,true)`, [pid, s.sessionId]);
check("finish", r.gemsEarned === 21 && r.newBest === true && r.bestScore === 425);
await expectErr(`select public.finish_game_session($1,$2,425,9000,true)`, [pid, s.sessionId], "session_closed");
const s2 = await one(`select public.start_game_session($1, 7, false, false)`, [pid]);
const rej = await one(`select public.finish_game_session($1,$2,99999,100,false)`, [pid, s2.sessionId]);
check("rejected", rej.rejected === true && (await one(`select best_score from public.players where id=$1`, [pid])) === 425);
const lb = await one(`select public.leaderboard($1, 10)`, [pid]);
check("leaderboard", lb.entries.length === 1 && lb.entries[0].isMe && lb.entries[0].rank === 1);
check("nonce once", await one(`select public.consume_nonce('n1',60)`) === true && await one(`select public.consume_nonce('n1',60)`) === false);
for (let i = 0; i < 3; i++) await one(`select public.rate_limit_hit('b',60,3)`);
check("rate limit", await one(`select public.rate_limit_hit('b',60,3)`) === false);
await q(`select public.grant_purchase($1,'hashP','premium_pass',null,false)`, [pid]);
check("premium", await one(`select premium from public.players where id=$1`, [pid]) === true);
await q(`select public.revoke_purchase('hashP','refund')`);
check("refund revokes premium & gems", await one(`select premium from public.players where id=$1`, [pid]) === false);
await q(`select public.revoke_purchase('hash1','refund')`);
check("gems clamp at 0", await one(`select gems from public.players where id=$1`, [pid]) === 0);
const bundle = await one(`select public.profile_bundle($1)`, [pid]);
check("bundle", bundle.catalog.length === 5 && bundle.iapProducts.length === 4);
await expectErr(`select public.rename_player($1,'x')`, [pid], `new row for relation "players" violates check constraint "players_display_name_check"`);
await q(`select public.purge_expired()`);
// privileges
await db.exec(`set role anon`);
try { await q(`select * from public.players`); check("anon cannot read players", false) } catch { check("anon cannot read players", true) }
try { await q(`select public.grant_purchase('${pid}','h','gems_pack_large',null,false)`); check("anon cannot call grant_purchase", false) } catch { check("anon cannot call grant_purchase", true) }
await db.exec(`reset role; set role authenticated`);
try { await q(`update public.players set gems = 99999`); check("authenticated cannot write", false) } catch { check("authenticated cannot write", true) }
await db.exec(`reset role; set role service_role`);
check("service_role can call", (await one(`select public.wallet_json($1)`, [pid])).gems === 0);
