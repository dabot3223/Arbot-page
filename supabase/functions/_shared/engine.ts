// TypeScript port of android/core/src/main/kotlin/com/arbot/core/engine/GameEngine.kt.
// The server never trusts a client score: it replays the recorded input log
// with the server-issued seed and uses the score it computes itself.
// Keep this file in lock-step with the Kotlin engine; the shared fixture
// shared/fixtures/engine-vectors.json is checked by both test suites.

export const RULES = {
  COLS: 20,
  ROWS: 11,
  CELLS: 220,
  START_CELL: 20,
  TICK_MS: 10,
  COIN_EVERY: 500,
  ENERGY_EVERY: 1_400,
  MINES_EVERY: 3_000,
  BOT_BASE_INTERVAL: 57,
  BOT_MIN_INTERVAL: 30,
  BOT_SPEEDUP_PER_100: 3,
  POWER_TICKS: 500,
  INVULN_TICKS: 150,
  ENERGY_FOR_POWER: 3,
  MINE_ATTEMPTS: 81,
  BOT_SPAWN_MIN_DISTANCE: 8,
  BOT_SPAWN_ATTEMPTS: 64,
  MAX_TICKS: 180_000,
  MAX_INPUTS: 20_000,
  COIN_POINTS: 5,
  ENERGY_POINTS: 10,
  BOT_POINTS: 25,
  BASE_LIVES: 5,
} as const;

const R = RULES;
// UP, RIGHT, DOWN, LEFT – same order and codes as the Kotlin Direction enum.
const DIRS: ReadonlyArray<readonly [number, number]> = [[0, -1], [1, 0], [0, 1], [-1, 0]];

const row = (c: number) => Math.floor(c / R.COLS);
const col = (c: number) => c % R.COLS;
const manhattan = (a: number, b: number) => Math.abs(row(a) - row(b)) + Math.abs(col(a) - col(b));

export class Mulberry32 {
  private s: number;
  constructor(seed: number) {
    this.s = seed | 0;
  }
  nextUInt(): number {
    this.s = (this.s + 0x6d2b79f5) | 0;
    let t = this.s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return (t ^ (t >>> 14)) >>> 0;
  }
  nextInt(bound: number): number {
    return this.nextUInt() % bound;
  }
}

export interface Snapshot {
  tick: number;
  score: number;
  lives: number;
  player: number;
  bot: number;
  coin: number;
  energy: number;
  energyCount: number;
  powerTicks: number;
  invulnTicks: number;
  gameOver: boolean;
  mines: number[];
}

export class GameEngine {
  private rng: Mulberry32;
  mines = new Array<boolean>(R.CELLS).fill(false);
  player: number = R.START_CELL;
  bot = -1;
  coin = -1;
  energy = -1;
  lives: number;
  score = 0;
  energyCount = 0;
  powerTicks = 0;
  invulnTicks = 0;
  tick = 0;
  gameOver = false;
  private botTimer = 0;

  constructor(seed: number, startLives: number, powerStart: boolean) {
    this.rng = new Mulberry32(seed);
    this.lives = startLives;
    this.placeMines();
    this.coin = this.randomFreeCell();
    this.energy = this.randomFreeCell();
    this.bot = this.randomFarCell();
    if (powerStart) this.powerTicks = R.POWER_TICKS;
  }

  botInterval(): number {
    return Math.max(R.BOT_MIN_INTERVAL, R.BOT_BASE_INTERVAL - Math.floor(this.score / 100) * R.BOT_SPEEDUP_PER_100);
  }

  advanceTo(target: number) {
    while (this.tick < target && !this.gameOver) this.step();
  }

  step() {
    if (this.gameOver) return;
    this.tick++;
    if (this.powerTicks > 0) this.powerTicks--;
    if (this.invulnTicks > 0) this.invulnTicks--;
    if (this.tick % R.COIN_EVERY === 0) {
      this.coin = -1;
      this.coin = this.randomFreeCell();
    }
    if (this.tick % R.ENERGY_EVERY === 0) {
      this.energy = -1;
      this.energy = this.randomFreeCell();
    }
    if (this.tick % R.MINES_EVERY === 0) this.placeMines();

    this.botTimer++;
    if (this.botTimer >= this.botInterval()) {
      this.botTimer = 0;
      this.moveBot();
      this.checkBotCollision();
    }
    if (!this.gameOver && this.tick >= R.MAX_TICKS) this.gameOver = true;
  }

  move(dir: number) {
    if (this.gameOver) return;
    const [dc, dr] = DIRS[dir];
    let r = row(this.player) + dr;
    let c = col(this.player) + dc;
    r = (r + R.ROWS) % R.ROWS;
    c = (c + R.COLS) % R.COLS;
    this.player = r * R.COLS + c;

    if (this.mines[this.player] && this.invulnTicks === 0) {
      this.loseLife();
      return;
    }
    if (this.player === this.coin) {
      this.score += R.COIN_POINTS;
      this.coin = -1;
      this.coin = this.randomFreeCell();
    }
    if (this.player === this.energy) {
      this.score += R.ENERGY_POINTS;
      this.energyCount++;
      this.energy = -1;
      this.energy = this.randomFreeCell();
      if (this.energyCount >= R.ENERGY_FOR_POWER) {
        this.energyCount = 0;
        this.powerTicks = R.POWER_TICKS;
      }
    }
    this.checkBotCollision();
  }

  snapshot(): Snapshot {
    const mines: number[] = [];
    this.mines.forEach((m, i) => m && mines.push(i));
    return {
      tick: this.tick,
      score: this.score,
      lives: this.lives,
      player: this.player,
      bot: this.bot,
      coin: this.coin,
      energy: this.energy,
      energyCount: this.energyCount,
      powerTicks: this.powerTicks,
      invulnTicks: this.invulnTicks,
      gameOver: this.gameOver,
      mines,
    };
  }

  private checkBotCollision() {
    if (this.bot !== this.player || this.gameOver) return;
    if (this.powerTicks > 0) {
      this.score += R.BOT_POINTS;
      this.bot = -1;
      this.bot = this.randomFarCell();
    } else if (this.invulnTicks === 0) {
      this.loseLife();
    }
  }

  private loseLife() {
    this.lives--;
    this.energyCount = 0;
    this.powerTicks = 0;
    if (this.lives <= 0) {
      this.lives = 0;
      this.gameOver = true;
    } else {
      this.player = R.START_CELL;
      this.invulnTicks = R.INVULN_TICKS;
    }
  }

  private moveBot() {
    let br = row(this.bot);
    let bc = col(this.bot);
    const pr = row(this.player);
    const pc = col(this.player);
    if (this.powerTicks > 0) {
      let best = this.bot;
      let bestDist = manhattan(this.bot, this.player);
      for (const [dc, dr] of DIRS) {
        const nr = br + dr;
        const nc = bc + dc;
        if (nr < 0 || nr >= R.ROWS || nc < 0 || nc >= R.COLS) continue;
        const cell = nr * R.COLS + nc;
        const dist = manhattan(cell, this.player);
        if (dist > bestDist) {
          best = cell;
          bestDist = dist;
        }
      }
      this.bot = best;
    } else {
      if (br !== pr) br += Math.sign(pr - br);
      else if (bc !== pc) bc += Math.sign(pc - bc);
      this.bot = br * R.COLS + bc;
    }
  }

  private placeMines() {
    this.mines.fill(false);
    for (let i = 0; i < R.MINE_ATTEMPTS; i++) {
      const c = this.rng.nextInt(R.CELLS);
      if (
        c !== R.START_CELL && c !== this.coin && c !== this.energy && c !== this.bot &&
        manhattan(c, this.player) > 1
      ) this.mines[c] = true;
    }
  }

  private randomFreeCell(): number {
    while (true) {
      const c = this.rng.nextInt(R.CELLS);
      if (
        !this.mines[c] && c !== this.player && c !== this.coin && c !== this.energy &&
        c !== this.bot && c !== R.START_CELL
      ) return c;
    }
  }

  private randomFarCell(): number {
    let c = this.randomFreeCell();
    let attempts = 1;
    while (manhattan(c, this.player) < R.BOT_SPAWN_MIN_DISTANCE && attempts < R.BOT_SPAWN_ATTEMPTS) {
      c = this.randomFreeCell();
      attempts++;
    }
    return c;
  }
}

export class ReplayError extends Error {}

/** Validates and replays a flat [tick, dir, tick, dir, ...] input log. */
export function replay(
  seed: number,
  startLives: number,
  powerStart: boolean,
  inputs: unknown,
  finalTick: unknown,
): Snapshot {
  if (!Array.isArray(inputs) || inputs.length % 2 !== 0) throw new ReplayError("bad inputs");
  if (inputs.length / 2 > R.MAX_INPUTS) throw new ReplayError("too many inputs");
  if (!Number.isInteger(finalTick) || (finalTick as number) < 0 || (finalTick as number) > R.MAX_TICKS) {
    throw new ReplayError("bad finalTick");
  }
  const end = finalTick as number;
  const engine = new GameEngine(seed, startLives, powerStart);
  let previous = 0;
  for (let i = 0; i < inputs.length; i += 2) {
    const t = inputs[i];
    const d = inputs[i + 1];
    if (!Number.isInteger(t) || !Number.isInteger(d) || d < 0 || d > 3) throw new ReplayError("bad input");
    if (t < previous || t > end) throw new ReplayError("input ticks out of order");
    previous = t;
    engine.advanceTo(t);
    if (engine.gameOver) break;
    engine.move(d);
  }
  engine.advanceTo(end);
  return engine.snapshot();
}
