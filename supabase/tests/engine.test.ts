import { assertEquals, assertThrows } from "jsr:@std/assert@1";
import { GameEngine, Mulberry32, replay, ReplayError } from "../functions/_shared/engine.ts";

const fixture = JSON.parse(
  await Deno.readTextFile(new URL("../../shared/fixtures/engine-vectors.json", import.meta.url)),
);

Deno.test("mulberry32 matches the Kotlin implementation", () => {
  const r = new Mulberry32(fixture.rng.seed);
  for (const v of fixture.rng.values) assertEquals(r.nextUInt(), v);
});

Deno.test("replay is bit-identical to the Kotlin engine", () => {
  for (const sc of fixture.scenarios) {
    const snap = replay(sc.seed, sc.startLives, sc.powerStart, sc.inputs, sc.finalTick);
    assertEquals(snap, sc.expected, `seed ${sc.seed}`);
  }
});

Deno.test("replay rejects malformed or out-of-order logs", () => {
  assertThrows(() => replay(1, 5, false, [10, 0, 5, 1], 100), ReplayError);
  assertThrows(() => replay(1, 5, false, [10, 7], 100), ReplayError);
  assertThrows(() => replay(1, 5, false, [10], 100), ReplayError);
  assertThrows(() => replay(1, 5, false, [1.5, 0], 100), ReplayError);
  assertThrows(() => replay(1, 5, false, [], 10_000_000), ReplayError);
  assertThrows(() => replay(1, 5, false, "nope", 100), ReplayError);
});

Deno.test("a forged score cannot survive replay", () => {
  const sc = fixture.scenarios[2];
  const forged = [...sc.inputs];
  for (let i = 1; i < forged.length; i += 2) forged[i] = (forged[i] + 2) % 4;
  const snap = replay(sc.seed, sc.startLives, sc.powerStart, forged, sc.finalTick);
  if (snap.score === sc.expected.score && snap.player === sc.expected.player) {
    throw new Error("tampered input log produced the same outcome");
  }
});

Deno.test("engine starts in a safe state", () => {
  for (let seed = 0; seed < 200; seed++) {
    const e = new GameEngine(seed, 5, false);
    if (e.mines[20]) throw new Error("mine on start cell");
  }
});
