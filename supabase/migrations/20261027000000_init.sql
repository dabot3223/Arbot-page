-- Ar_B0T backend schema.
--
-- Security model
--   * Clients NEVER talk to PostgREST/Realtime directly. Every call goes
--     through the `api` edge function inside an encrypted, signed envelope.
--   * All tables have RLS enabled with no policies, and every privilege is
--     revoked from `anon` / `authenticated`, so the public API keys can read
--     or write nothing even if they leak.
--   * State changes go through SECURITY DEFINER functions that only
--     `service_role` (the edge function) may execute. They lock rows and
--     validate everything, so balances cannot go negative or double-spend.

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------- tables --

create table public.players (
  id               uuid primary key default gen_random_uuid(),
  device_key_spki  text not null unique,           -- base64url SPKI of the Keystore key
  display_name     text not null check (display_name ~ '^[A-Za-z0-9_ ]{3,16}$'),
  gems             integer not null default 0 check (gems >= 0),
  premium          boolean not null default false,
  equipped_skin    text not null default 'classic',
  best_score       integer not null default 0 check (best_score >= 0),
  games_played     integer not null default 0,
  banned           boolean not null default false,
  created_at       timestamptz not null default now(),
  last_seen_at     timestamptz not null default now()
);
create index players_best_score_idx on public.players (best_score desc) where not banned;

create table public.catalog_items (
  item_id       text primary key,
  kind          text not null check (kind in ('skin', 'booster')),
  price_gems    integer not null check (price_gems > 0),
  display_name  text not null,
  active        boolean not null default true,
  sort_order    integer not null default 0
);

create table public.inventory (
  player_id  uuid not null references public.players (id) on delete cascade,
  item_id    text not null references public.catalog_items (item_id),
  quantity   integer not null default 0 check (quantity >= 0),
  primary key (player_id, item_id)
);

-- Google Play products. Gem amounts live on the server, never in the app.
create table public.iap_products (
  product_id      text primary key,
  kind            text not null check (kind in ('consumable', 'non_consumable')),
  gems            integer not null default 0 check (gems >= 0),
  grants_premium  boolean not null default false,
  active          boolean not null default true,
  sort_order      integer not null default 0
);

create table public.purchases (
  token_hash     text primary key,                  -- sha256(purchaseToken); raw token is never stored
  player_id      uuid not null references public.players (id) on delete cascade,
  product_id     text not null references public.iap_products (product_id),
  order_id       text,
  state          text not null check (state in ('granted', 'revoked')),
  gems_granted   integer not null,
  is_test        boolean not null default false,
  created_at     timestamptz not null default now(),
  revoked_at     timestamptz
);
create index purchases_player_idx on public.purchases (player_id);
create unique index purchases_order_idx on public.purchases (order_id) where order_id is not null;

create table public.game_sessions (
  id            uuid primary key default gen_random_uuid(),
  player_id     uuid not null references public.players (id) on delete cascade,
  seed          bigint not null check (seed between 0 and 4294967295),
  start_lives   integer not null,
  power_start   boolean not null,
  status        text not null default 'active' check (status in ('active', 'finished', 'rejected', 'expired')),
  started_at    timestamptz not null default now(),
  finished_at   timestamptz,
  score         integer,
  final_tick    integer,
  gems_earned   integer
);
create index game_sessions_player_active_idx on public.game_sessions (player_id) where status = 'active';

-- Anti-replay: every signed message nonce is accepted once.
create table public.api_nonces (
  nonce       text primary key,
  expires_at  timestamptz not null
);
create index api_nonces_expires_idx on public.api_nonces (expires_at);

create table public.rate_limits (
  bucket        text not null,
  window_start  timestamptz not null,
  hits          integer not null default 0,
  primary key (bucket, window_start)
);

-- Audit trail for anything suspicious (bad signatures, replays, cheats, refunds).
create table public.security_events (
  id          bigint generated always as identity primary key,
  player_id   uuid,
  kind        text not null,
  detail      jsonb not null default '{}'::jsonb,
  created_at  timestamptz not null default now()
);
create index security_events_player_idx on public.security_events (player_id, created_at desc);

-- ----------------------------------------------------------- seed data --

insert into public.catalog_items (item_id, kind, price_gems, display_name, sort_order) values
  ('skin_neon',            'skin',    300, 'Neon Runner',      10),
  ('skin_pirate',          'skin',    500, 'Pirate Captain',   20),
  ('skin_gold',            'skin',   1200, 'Golden Legend',    30),
  ('booster_extra_life',   'booster', 150, 'Extra Life',       40),
  ('booster_power_start',  'booster', 120, 'Power Start',      50);

insert into public.iap_products (product_id, kind, gems, grants_premium, sort_order) values
  ('gems_pack_small',  'consumable',       120, false, 10),
  ('gems_pack_medium', 'consumable',       650, false, 20),
  ('gems_pack_large',  'consumable',      1400, false, 30),
  ('premium_pass',     'non_consumable',   500, true,  40);

-- --------------------------------------------------------- lock-down --

alter table public.players          enable row level security;
alter table public.catalog_items    enable row level security;
alter table public.inventory        enable row level security;
alter table public.iap_products     enable row level security;
alter table public.purchases        enable row level security;
alter table public.game_sessions    enable row level security;
alter table public.api_nonces       enable row level security;
alter table public.rate_limits      enable row level security;
alter table public.security_events  enable row level security;

revoke all on all tables    in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
revoke all on all functions in schema public from anon, authenticated, public;
alter default privileges in schema public revoke all on tables    from anon, authenticated;
alter default privileges in schema public revoke all on sequences from anon, authenticated;
alter default privileges in schema public revoke all on functions from anon, authenticated, public;

-- ------------------------------------------------------------ helpers --

create or replace function public.consume_nonce(p_nonce text, p_ttl_seconds integer)
returns boolean
language plpgsql security definer set search_path = '' as $$
declare
  v_inserted integer;
begin
  insert into public.api_nonces (nonce, expires_at)
  values (p_nonce, now() + make_interval(secs => p_ttl_seconds))
  on conflict (nonce) do nothing;
  get diagnostics v_inserted = row_count;
  return v_inserted = 1;
end $$;

create or replace function public.purge_expired()
returns void
language sql security definer set search_path = '' as $$
  delete from public.api_nonces where expires_at < now();
  delete from public.rate_limits where window_start < now() - interval '1 day';
  update public.game_sessions set status = 'expired'
   where status = 'active' and started_at < now() - interval '40 minutes';
$$;

-- Returns true while the bucket is within its limit.
create or replace function public.rate_limit_hit(p_bucket text, p_window_seconds integer, p_max integer)
returns boolean
language plpgsql security definer set search_path = '' as $$
declare
  v_window timestamptz := to_timestamp(floor(extract(epoch from now()) / p_window_seconds) * p_window_seconds);
  v_hits integer;
begin
  insert into public.rate_limits (bucket, window_start, hits) values (p_bucket, v_window, 1)
  on conflict (bucket, window_start) do update set hits = public.rate_limits.hits + 1
  returning hits into v_hits;
  return v_hits <= p_max;
end $$;

create or replace function public.log_security_event(p_player uuid, p_kind text, p_detail jsonb)
returns void
language sql security definer set search_path = '' as $$
  insert into public.security_events (player_id, kind, detail) values (p_player, p_kind, coalesce(p_detail, '{}'::jsonb));
$$;

create or replace function public.wallet_json(p_player uuid)
returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'gems', p.gems,
    'premium', p.premium,
    'equippedSkin', p.equipped_skin,
    'inventory', coalesce((
      select jsonb_agg(jsonb_build_object('itemId', i.item_id, 'quantity', i.quantity) order by i.item_id)
        from public.inventory i where i.player_id = p.id and i.quantity > 0), '[]'::jsonb))
  from public.players p where p.id = p_player;
$$;

-- ------------------------------------------------------------ players --

create or replace function public.register_player(p_device_key text, p_display_name text)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_player public.players;
begin
  select * into v_player from public.players where device_key_spki = p_device_key;
  if not found then
    insert into public.players (device_key_spki, display_name)
    values (p_device_key, p_display_name)
    returning * into v_player;
  end if;
  if v_player.banned then
    raise exception 'banned' using errcode = 'P0001';
  end if;
  return jsonb_build_object('playerId', v_player.id, 'displayName', v_player.display_name);
end $$;

create or replace function public.profile_bundle(p_player uuid)
returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'player', jsonb_build_object(
      'id', p.id, 'displayName', p.display_name, 'gems', p.gems, 'premium', p.premium,
      'equippedSkin', p.equipped_skin, 'bestScore', p.best_score, 'gamesPlayed', p.games_played),
    'inventory', coalesce((
      select jsonb_agg(jsonb_build_object('itemId', i.item_id, 'quantity', i.quantity) order by i.item_id)
        from public.inventory i where i.player_id = p.id and i.quantity > 0), '[]'::jsonb),
    'catalog', coalesce((
      select jsonb_agg(jsonb_build_object('itemId', c.item_id, 'kind', c.kind, 'priceGems', c.price_gems,
                                          'displayName', c.display_name) order by c.sort_order)
        from public.catalog_items c where c.active), '[]'::jsonb),
    'iapProducts', coalesce((
      select jsonb_agg(jsonb_build_object('productId', x.product_id, 'kind', x.kind, 'gems', x.gems,
                                          'grantsPremium', x.grants_premium) order by x.sort_order)
        from public.iap_products x where x.active), '[]'::jsonb))
  from public.players p where p.id = p_player;
$$;

create or replace function public.rename_player(p_player uuid, p_name text)
returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  update public.players set display_name = p_name where id = p_player;
  return public.profile_bundle(p_player);
end $$;

-- ------------------------------------------------------------- store --

create or replace function public.buy_item(p_player uuid, p_item text)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_item public.catalog_items;
  v_gems integer;
  v_owned integer;
begin
  select * into v_item from public.catalog_items where item_id = p_item and active;
  if not found then raise exception 'unknown_item' using errcode = 'P0001'; end if;

  select gems into v_gems from public.players where id = p_player for update;
  if v_gems < v_item.price_gems then raise exception 'insufficient_gems' using errcode = 'P0001'; end if;

  if v_item.kind = 'skin' then
    select quantity into v_owned from public.inventory where player_id = p_player and item_id = p_item;
    if coalesce(v_owned, 0) > 0 then raise exception 'already_owned' using errcode = 'P0001'; end if;
  end if;

  update public.players set gems = gems - v_item.price_gems where id = p_player;
  insert into public.inventory (player_id, item_id, quantity) values (p_player, p_item, 1)
  on conflict (player_id, item_id) do update set quantity = public.inventory.quantity + 1;
  return public.wallet_json(p_player);
end $$;

create or replace function public.equip_skin(p_player uuid, p_skin text)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_premium boolean;
  v_owned integer;
begin
  if p_skin <> 'classic' then
    if not exists (select 1 from public.catalog_items where item_id = p_skin and kind = 'skin') then
      raise exception 'unknown_item' using errcode = 'P0001';
    end if;
    select premium into v_premium from public.players where id = p_player;
    select quantity into v_owned from public.inventory where player_id = p_player and item_id = p_skin;
    if not v_premium and coalesce(v_owned, 0) = 0 then
      raise exception 'not_owned' using errcode = 'P0001';
    end if;
  end if;
  update public.players set equipped_skin = p_skin where id = p_player;
  return public.wallet_json(p_player);
end $$;

-- ------------------------------------------------------- Google Play --

create or replace function public.grant_purchase(
  p_player uuid, p_token_hash text, p_product text, p_order text, p_is_test boolean)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_product public.iap_products;
  v_existing public.purchases;
begin
  select * into v_product from public.iap_products where product_id = p_product and active;
  if not found then raise exception 'unknown_product' using errcode = 'P0001'; end if;

  -- Idempotent per purchase token; a token can never be credited to two players.
  select * into v_existing from public.purchases where token_hash = p_token_hash for update;
  if found then
    if v_existing.player_id <> p_player then
      perform public.log_security_event(p_player, 'purchase_token_reuse', jsonb_build_object('product', p_product));
      raise exception 'purchase_token_reused' using errcode = 'P0001';
    end if;
    return public.wallet_json(p_player) || jsonb_build_object('status', 'already_granted', 'gemsGranted', 0);
  end if;

  insert into public.purchases (token_hash, player_id, product_id, order_id, state, gems_granted, is_test)
  values (p_token_hash, p_player, p_product, p_order, 'granted', v_product.gems, p_is_test);

  update public.players
     set gems = gems + v_product.gems,
         premium = premium or v_product.grants_premium
   where id = p_player;

  return public.wallet_json(p_player) || jsonb_build_object('status', 'granted', 'gemsGranted', v_product.gems);
end $$;

-- Called for refunds / chargebacks (Real-time Developer Notifications).
create or replace function public.revoke_purchase(p_token_hash text, p_reason text)
returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_purchase public.purchases;
  v_product public.iap_products;
begin
  select * into v_purchase from public.purchases where token_hash = p_token_hash and state = 'granted' for update;
  if not found then return; end if;
  select * into v_product from public.iap_products where product_id = v_purchase.product_id;

  update public.purchases set state = 'revoked', revoked_at = now() where token_hash = p_token_hash;
  update public.players
     set gems = greatest(0, gems - v_purchase.gems_granted),
         premium = case when v_product.grants_premium then false else premium end
   where id = v_purchase.player_id;
  perform public.log_security_event(v_purchase.player_id, 'purchase_revoked',
    jsonb_build_object('product', v_purchase.product_id, 'reason', p_reason));
end $$;

-- -------------------------------------------------------- game runs --

create or replace function public.start_game_session(
  p_player uuid, p_seed bigint, p_use_extra_life boolean, p_use_power_start boolean)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_premium boolean;
  v_lives integer := 5;
  v_session uuid;
begin
  select premium into v_premium from public.players where id = p_player for update;

  -- Only one live run per player; older ones are abandoned.
  update public.game_sessions set status = 'expired'
   where player_id = p_player and status = 'active';

  if p_use_extra_life then
    update public.inventory set quantity = quantity - 1
     where player_id = p_player and item_id = 'booster_extra_life' and quantity > 0;
    if not found then raise exception 'no_booster' using errcode = 'P0001'; end if;
    v_lives := v_lives + 1;
  end if;
  if p_use_power_start then
    update public.inventory set quantity = quantity - 1
     where player_id = p_player and item_id = 'booster_power_start' and quantity > 0;
    if not found then raise exception 'no_booster' using errcode = 'P0001'; end if;
  end if;
  if v_premium then v_lives := v_lives + 1; end if;

  insert into public.game_sessions (player_id, seed, start_lives, power_start)
  values (p_player, p_seed, v_lives, p_use_power_start)
  returning id into v_session;

  return jsonb_build_object('sessionId', v_session, 'seed', p_seed,
                            'startLives', v_lives, 'powerStart', p_use_power_start);
end $$;

create or replace function public.finish_game_session(
  p_player uuid, p_session uuid, p_score integer, p_final_tick integer, p_accepted boolean)
returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_session public.game_sessions;
  v_premium boolean;
  v_best integer;
  v_gems integer := 0;
begin
  select * into v_session from public.game_sessions
   where id = p_session and player_id = p_player for update;
  if not found or v_session.status <> 'active' then
    raise exception 'session_closed' using errcode = 'P0001';
  end if;

  if not p_accepted then
    update public.game_sessions set status = 'rejected', finished_at = now(), score = p_score, final_tick = p_final_tick
     where id = p_session;
    perform public.log_security_event(p_player, 'score_rejected',
      jsonb_build_object('session', p_session, 'claimed', p_score));
    return jsonb_build_object('rejected', true);
  end if;

  select premium, best_score into v_premium, v_best from public.players where id = p_player for update;
  v_gems := least(500, p_score / 20) * (case when v_premium then 2 else 1 end);

  update public.game_sessions
     set status = 'finished', finished_at = now(), score = p_score, final_tick = p_final_tick, gems_earned = v_gems
   where id = p_session;

  update public.players
     set gems = gems + v_gems,
         best_score = greatest(best_score, p_score),
         games_played = games_played + 1
   where id = p_player;

  return jsonb_build_object(
    'score', p_score,
    'gemsEarned', v_gems,
    'gems', (select gems from public.players where id = p_player),
    'bestScore', greatest(v_best, p_score),
    'newBest', p_score > v_best);
end $$;

create or replace function public.leaderboard(p_player uuid, p_limit integer)
returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('entries', coalesce(jsonb_agg(e order by (e->>'rank')::int), '[]'::jsonb))
  from (
    select jsonb_build_object(
      'rank', row_number() over (order by best_score desc, created_at),
      'displayName', display_name,
      'bestScore', best_score,
      'isMe', id = p_player) as e
    from public.players
    where not banned and best_score > 0
    order by best_score desc, created_at
    limit least(greatest(p_limit, 1), 100)
  ) t;
$$;

-- Only the edge function (service_role) may call any of the above.
do $$
declare f record;
begin
  for f in
    select p.oid::regprocedure as sig
      from pg_proc p join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public'
  loop
    execute format('revoke all on function %s from public, anon, authenticated', f.sig);
    execute format('grant execute on function %s to service_role', f.sig);
  end loop;
end $$;

grant usage on schema public to service_role;
grant select, insert, update, delete on all tables in schema public to service_role;
