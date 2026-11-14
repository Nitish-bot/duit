import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { dirname, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repositoryRoot = new URL("../../", import.meta.url);
const requireFromFunctions = createRequire(new URL("../package.json", import.meta.url));
const pairingCallableNames = [
  "acceptPairInvite",
  "confirmPair",
  "createPairInvite",
  "getPairingStatus",
];

test("development deployment uses a distinct Functions source", async () => {
  const [developmentConfig, productionConfig] = await Promise.all([
    readConfig("firebase.development.json"),
    readConfig("firebase.json"),
  ]);

  const developmentFunctions = functionsDefinition(developmentConfig);
  const productionFunctions = functionsDefinition(productionConfig);

  assert.equal(developmentFunctions?.source, "functions/development");
  assert.equal(developmentFunctions?.codebase, "development-only");
  assert.notEqual(developmentFunctions?.source, productionFunctions?.source);
});

test("development deployment carries no Firestore Rules of its own", async () => {
  const [developmentConfig, productionConfig] = await Promise.all([
    readConfig("firebase.development.json"),
    readConfig("firebase.json"),
  ]);

  // Member Approval stays administrator-controlled in every environment:
  // the development project uses the same approval-gated Rules as production.
  assert.equal(developmentConfig.firestore, undefined);
  assert.equal(productionConfig.firestore?.rules, "firestore.rules");
});

test("the development Functions package is self-contained after its build", async () => {
  const developmentConfig = await readConfig("firebase.development.json");
  const developmentFunctions = functionsDefinition(developmentConfig);
  assert.equal(
    developmentFunctions?.predeploy?.[0],
    'npm --prefix "$RESOURCE_DIR" run build',
  );
  for (const ignoredPath of [
    ".git",
    ".runtimeconfig.json",
    "firebase-debug.log",
    "firebase-debug.*.log",
    "node_modules",
  ]) {
    assert.ok(developmentFunctions?.ignore?.includes(ignoredPath), ignoredPath);
  }

  const sourceUrl = new URL(`${developmentFunctions.source}/`, repositoryRoot);
  const sourcePath = fileURLToPath(sourceUrl);
  const packageJson = JSON.parse(
    await readFile(new URL("package.json", sourceUrl), "utf8"),
  );
  const entryPath = fileURLToPath(new URL(packageJson.main, sourceUrl));
  const runtimeImports = await collectRuntimeImports(entryPath, sourcePath);

  assert.equal(isInside(sourcePath, entryPath), true, entryPath);
  assert.ok(runtimeImports.relative.length > 0);
  for (const importedPath of runtimeImports.relative) {
    assert.equal(isInside(sourcePath, importedPath), true, importedPath);
  }
  for (const packageName of runtimeImports.packages) {
    assert.ok(packageJson.dependencies?.[packageName], packageName);
  }
});

test("development exports only App Check-enforced Pairing callables", async () => {
  const developmentConfig = await readConfig("firebase.development.json");
  const developmentFunctions = functionsDefinition(developmentConfig);
  const sourceUrl = new URL(`${developmentFunctions.source}/`, repositoryRoot);
  const sourcePath = fileURLToPath(sourceUrl);
  const packageJson = JSON.parse(
    await readFile(new URL("package.json", sourceUrl), "utf8"),
  );
  const entryPath = fileURLToPath(new URL(packageJson.main, sourceUrl));
  const pairingPath = resolve(sourcePath, "lib/src/pairing.js");
  const httpsModule = requireFromFunctions("firebase-functions/v2/https");
  const originalOnCall = httpsModule.onCall;
  const observedOptions = [];

  httpsModule.onCall = (options) => {
    observedOptions.push(options);
    return () => undefined;
  };
  delete requireFromFunctions.cache[entryPath];
  delete requireFromFunctions.cache[pairingPath];

  try {
    const developmentExports = requireFromFunctions(entryPath);

    assert.deepEqual(Object.keys(developmentExports).sort(), pairingCallableNames);
    assert.equal(observedOptions.length, pairingCallableNames.length);
    for (const options of observedOptions) {
      assert.equal(options.enforceAppCheck, true);
      assert.equal(options.region, "us-central1");
    }
  } finally {
    httpsModule.onCall = originalOnCall;
    delete requireFromFunctions.cache[entryPath];
    delete requireFromFunctions.cache[pairingPath];
  }
});

test("the local emulator keeps App Check enforcement disabled", () => {
  const entryPath = fileURLToPath(
    new URL("../emulator/lib/index.js", import.meta.url),
  );
  const pairingPath = fileURLToPath(
    new URL("../lib/pairing.js", import.meta.url),
  );
  const httpsModule = requireFromFunctions("firebase-functions/v2/https");
  const originalOnCall = httpsModule.onCall;
  const observedOptions = [];

  httpsModule.onCall = (options) => {
    observedOptions.push(options);
    return () => undefined;
  };
  delete requireFromFunctions.cache[entryPath];
  delete requireFromFunctions.cache[pairingPath];

  try {
    const emulatorExports = requireFromFunctions(entryPath);

    for (const callableName of pairingCallableNames) {
      assert.equal(typeof emulatorExports[callableName], "function");
    }
    assert.equal(observedOptions.length, pairingCallableNames.length);
    for (const options of observedOptions) {
      assert.equal(options.enforceAppCheck, false);
    }
  } finally {
    httpsModule.onCall = originalOnCall;
    delete requireFromFunctions.cache[entryPath];
    delete requireFromFunctions.cache[pairingPath];
  }
});

test("the Functions package gates the development deployment boundary", async () => {
  const packageJson = JSON.parse(
    await readFile(new URL("../package.json", import.meta.url), "utf8"),
  );

  assert.equal(
    packageJson.scripts["build:development"],
    "tsc --project development/tsconfig.json",
  );
  assert.match(packageJson.scripts.build, /build:development/);
  assert.match(packageJson.scripts.typecheck, /development\/tsconfig\.json/);
  assert.match(
    packageJson.scripts["test:boundaries"],
    /development-boundary\.test\.mjs/,
  );
});

test("the documented deploy command targets only the development codebase", async () => {
  const readme = await readFile(new URL("README.md", repositoryRoot), "utf8");
  const instructions = readme
    .split("## Development Functions and App Check\n")[1]
    ?.split("\n## ")[0];

  assert.ok(instructions);
  assert.match(instructions, /Manage debug tokens/);
  assert.match(instructions, /--config firebase\.development\.json/);
  assert.match(instructions, /--only functions:development-only/);
  assert.match(instructions, /--project <development-project-id>/);
  assert.doesNotMatch(instructions, /--config firebase\.json/);
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

async function collectRuntimeImports(entryPath, sourcePath) {
  const pending = [entryPath];
  const visited = new Set();
  const relativeImports = [];
  const packageImports = new Set();

  while (pending.length > 0) {
    const filePath = pending.pop();
    if (visited.has(filePath)) continue;
    visited.add(filePath);

    const contents = await readFile(filePath, "utf8");
    for (const match of contents.matchAll(/\brequire\(["']([^"']+)["']\)/g)) {
      const specifier = match[1];
      if (specifier.startsWith(".")) {
        const importedPath = resolve(dirname(filePath), specifier);
        relativeImports.push(importedPath);
        assert.equal(isInside(sourcePath, importedPath), true, importedPath);
        pending.push(importedPath);
      } else if (!specifier.startsWith("node:")) {
        packageImports.add(packageName(specifier));
      }
    }
  }

  return {
    packages: packageImports,
    relative: relativeImports,
  };
}

function isInside(parentPath, candidatePath) {
  const relativePath = relative(parentPath, candidatePath);
  return relativePath !== ".." && !relativePath.startsWith(`..${sep}`);
}

function packageName(specifier) {
  const segments = specifier.split("/");
  return specifier.startsWith("@")
    ? `${segments[0]}/${segments[1]}`
    : segments[0];
}
