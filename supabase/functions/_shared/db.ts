// Store implementation backed by Postgres through supabase-js with the
// service-role key (available only inside edge functions). All mutations are
// SECURITY DEFINER functions from the migration; see 20261027000000_init.sql.

import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { mapDbError, type IapProductRow, type PlayerAuth, type SessionRow, type Store } from "./gateway.ts";

export class SupabaseStore implements Store {
  private db: SupabaseClient;

  constructor(url: string, serviceRoleKey: string) {
    this.db = createClient(url, serviceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
  }

  private async rpc<T>(fn: string, args: Record<string, unknown>): Promise<T> {
    const { data, error } = await this.db.rpc(fn, args);
    if (error) throw mapDbError(error.message);
    return data as T;
  }

  consumeNonce(nonce: string, ttlSeconds: number) {
    return this.rpc<boolean>("consume_nonce", { p_nonce: nonce, p_ttl_seconds: ttlSeconds });
  }

  rateLimit(bucket: string, windowSeconds: number, max: number) {
    return this.rpc<boolean>("rate_limit_hit", { p_bucket: bucket, p_window_seconds: windowSeconds, p_max: max });
  }

  async logEvent(playerId: string | null, kind: string, detail: Record<string, unknown>) {
    await this.rpc("log_security_event", { p_player: playerId, p_kind: kind, p_detail: detail });
  }

  async purge() {
    await this.rpc("purge_expired", {});
  }

  async getPlayerAuth(id: string): Promise<PlayerAuth | null> {
    const { data, error } = await this.db.from("players")
      .select("id, device_key_spki, banned").eq("id", id).maybeSingle();
    if (error) throw mapDbError(error.message);
    return data ? { id: data.id, deviceKey: data.device_key_spki, banned: data.banned } : null;
  }

  registerPlayer(deviceKey: string, displayName: string) {
    return this.rpc("register_player", { p_device_key: deviceKey, p_display_name: displayName });
  }

  profileBundle(id: string) {
    return this.rpc("profile_bundle", { p_player: id });
  }

  wallet(id: string) {
    return this.rpc<Record<string, unknown>>("wallet_json", { p_player: id });
  }

  rename(id: string, name: string) {
    return this.rpc("rename_player", { p_player: id, p_name: name });
  }

  buyItem(id: string, itemId: string) {
    return this.rpc("buy_item", { p_player: id, p_item: itemId });
  }

  equipSkin(id: string, skinId: string) {
    return this.rpc("equip_skin", { p_player: id, p_skin: skinId });
  }

  startSession(id: string, seed: number, extraLife: boolean, powerStart: boolean) {
    return this.rpc("start_game_session", {
      p_player: id,
      p_seed: seed,
      p_use_extra_life: extraLife,
      p_use_power_start: powerStart,
    });
  }

  async getSession(id: string, sessionId: string): Promise<SessionRow | null> {
    const { data, error } = await this.db.from("game_sessions")
      .select("seed, start_lives, power_start, status, started_at")
      .eq("id", sessionId).eq("player_id", id).maybeSingle();
    if (error) throw mapDbError(error.message);
    if (!data) return null;
    return {
      seed: Number(data.seed),
      startLives: data.start_lives,
      powerStart: data.power_start,
      status: data.status,
      startedAt: Date.parse(data.started_at),
    };
  }

  finishSession(id: string, sessionId: string, score: number, finalTick: number, accepted: boolean) {
    return this.rpc<Record<string, unknown>>("finish_game_session", {
      p_player: id,
      p_session: sessionId,
      p_score: score,
      p_final_tick: finalTick,
      p_accepted: accepted,
    });
  }

  leaderboard(id: string, limit: number) {
    return this.rpc("leaderboard", { p_player: id, p_limit: limit });
  }

  async iapProduct(productId: string): Promise<IapProductRow | null> {
    const { data, error } = await this.db.from("iap_products")
      .select("kind, gems, grants_premium").eq("product_id", productId).eq("active", true).maybeSingle();
    if (error) throw mapDbError(error.message);
    return data ? { kind: data.kind, gems: data.gems, grantsPremium: data.grants_premium } : null;
  }

  grantPurchase(id: string, tokenHash: string, productId: string, orderId: string | null, isTest: boolean) {
    return this.rpc<Record<string, unknown>>("grant_purchase", {
      p_player: id,
      p_token_hash: tokenHash,
      p_product: productId,
      p_order: orderId,
      p_is_test: isTest,
    });
  }

  async revokePurchase(tokenHash: string, reason: string) {
    await this.rpc("revoke_purchase", { p_token_hash: tokenHash, p_reason: reason });
  }
}
