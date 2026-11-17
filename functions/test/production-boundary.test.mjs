import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import test from "node:test";

const repositoryRoot = new URL("../../", import.meta.url);

test("the deployed Pairing entry enforces App Check with no way to turn it off", async () => {
  const entry = await readSource("supabase/functions/pairing/index.ts");

  assert.match(entry, /createPairingRequestHandler\(\{ enforceAppCheck: true \}\)/);
  assert.doesNotMatch(entry, /enforceAppCheck:\s*(false|[^t\s])/);
  assert.doesNotMatch(entry, /Deno\.env|process\.env/);
  assert.match(entry, /preferRest: true/);

  // Shared code takes the policy from its caller and never defaults it off.
  for (const shared of ["callable-protocol.ts", "pairing.ts", "admin-app.ts"]) {
    const contents = await readSource(`supabase/functions/_shared/${shared}`);
    assert.doesNotMatch(contents, /enforceAppCheck:\s*false/, shared);
    assert.doesNotMatch(contents, /\bDeno\b/, shared);
  }
});

test("the App Check-free local entries cannot reach a real project", async () => {
  const local = await readSource("supabase/functions/pairing-local/index.ts");
  assert.match(local, /FIRESTORE_EMULATOR_HOST/);
  assert.match(local, /throw new Error/);
  assert.match(local, /enforceAppCheck: false/);

  const host = await readSource("functions/emulator/src/server.ts");
  assert.match(host, /enforceAppCheck: false/);

  const config = await readSource("supabase/config.toml");
  assert.doesNotMatch(config, /pairing-local/);
});

test("only the pairing function turns Supabase JWT verification off", async () => {
  const config = await readSource("supabase/config.toml");
  const disabled = [...config.matchAll(/\[functions\.([^\]]+)\]\s*\n(?:[^\[]*?\n)?\s*verify_jwt\s*=\s*false/g)]
    .map((match) => match[1]);

  assert.deepEqual(disabled, ["pairing"]);
  assert.doesNotMatch(config, /verify_jwt\s*=\s*true/);
});

test("no Supabase project reference or secret is tracked", async () => {
  const candidates = [
    ...gitList(["ls-files"]),
    ...gitList(["ls-files", "--others", "--exclude-standard"]),
  ];

  for (const file of candidates) {
    assert.doesNotMatch(file, /^supabase\/\.(temp|branches)\//, file);
    assert.doesNotMatch(file, /(^|\/)\.env(\..*)?$/, file);
    assert.doesNotMatch(file, /service-?account.*\.json$/i, file);
  }
  const gitignore = await readSource(".gitignore");
  for (const ignored of ["supabase/.temp/", "supabase/.branches/", "supabase/functions/.env*"]) {
    assert.ok(gitignore.includes(ignored), ignored);
  }

  const secretShaped = /-----BEGIN [A-Z ]*PRIVATE KEY-----|"private_key"|\b[a-z]{20}\.supabase\.co\b/;
  for (const file of candidates.filter(
    (name) => name.startsWith("supabase/") || name === "README.md",
  )) {
    assert.doesNotMatch(await readSource(file), secretShaped, file);
  }
});

test("firebase.json ships no Functions and the emulator config has none either", async () => {
  const [productionConfig, emulatorConfig] = await Promise.all([
    readConfig("firebase.json"),
    readConfig("firebase.emulator.json"),
  ]);

  assert.equal(productionConfig.functions, undefined);
  assert.equal(productionConfig.firestore?.rules, "firestore.rules");
  assert.equal(emulatorConfig.functions, undefined);
  assert.deepEqual(
    Object.keys(emulatorConfig.emulators).filter((name) => name !== "ui" && name !== "singleProjectMode").sort(),
    ["auth", "firestore"],
  );
});

test("Cloud Functions are gone from the Functions package", async () => {
  const packageJson = JSON.parse(await readSource("functions/package.json"));

  assert.equal(packageJson.dependencies["firebase-functions"], undefined);
  assert.equal(packageJson.main, undefined);
  assert.match(packageJson.scripts["test:emulator"], /--only auth,firestore/);
  assert.doesNotMatch(packageJson.scripts["test:emulator"], /functions,/);
});

function gitList(args) {
  return execFileSync("git", args, { cwd: repositoryRoot, encoding: "utf8" })
    .split("\n")
    .filter(Boolean);
}

async function readSource(relativePath) {
  return readFile(new URL(relativePath, repositoryRoot), "utf8");
}

async function readConfig(fileName) {
  return JSON.parse(await readSource(fileName));
}
