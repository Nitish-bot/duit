import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const repositoryRoot = new URL("../../", import.meta.url);

test("the emulator readiness probe is absent from the production Functions boundary", async () => {
  const productionFunctions = await import("../lib/index.js");

  assert.equal(Object.hasOwn(productionFunctions, "emulatorReady"), false);
  for (const callable of [
    "acceptPairInvite",
    "confirmPair",
    "createPairInvite",
    "getPairingStatus",
  ]) {
    assert.equal(Object.hasOwn(productionFunctions, callable), false);
  }
});

test("production and emulator configurations use distinct Functions sources", async () => {
  const [productionConfig, emulatorConfig] = await Promise.all([
    readConfig("firebase.json"),
    readConfig("firebase.emulator.json"),
  ]);

  const productionFunctions = functionsDefinition(productionConfig);
  const emulatorFunctions = functionsDefinition(emulatorConfig);

  assert.equal(productionFunctions?.source, "functions");
  assert.ok(productionFunctions?.ignore?.includes("emulator"));
  assert.equal(emulatorFunctions?.source, "functions/emulator");
});

async function readConfig(fileName) {
  const contents = await readFile(new URL(fileName, repositoryRoot), "utf8");
  return JSON.parse(contents);
}

function functionsDefinition(config) {
  return Array.isArray(config.functions)
    ? config.functions[0]
    : config.functions;
}
