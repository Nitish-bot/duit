import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { networkInterfaces, tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const functionsRoot = fileURLToPath(new URL("../", import.meta.url));
const scriptPath = fileURLToPath(import.meta.url);
const baseFirebaseConfig = path.join(
  repositoryRoot,
  "firebase.android-test.json",
);
const emulatorPort = "8081";
const reverseSpecification = `tcp:${emulatorPort}`;
const deviceLoopbackHost = "127.0.0.1";
const firestoreHostEnvironmentVariable = "DUIT_ANDROID_FIRESTORE_HOST";
const firestoreHostInstrumentationArgument =
  "firestoreRepositoryEmulatorHost";
const androidTestMethodEnvironmentVariable =
  "DUIT_ANDROID_REPOSITORY_TEST_METHOD";
const allowDevAppResetEnvironmentVariable =
  "DUIT_ANDROID_REPOSITORY_ALLOW_DEV_APP_RESET";
const androidTestClass =
  "com.gaylord.duit.platform.firebase.FirestoreTaskRepositoryEmulatorTest";
const androidTestRunner =
  "com.gaylord.duit.dev.test/androidx.test.runner.AndroidJUnitRunner";
const runConnectedTestsArgument = "--run-connected-tests";
const targetPackages = ["com.gaylord.duit.dev", "com.gaylord.duit.dev.test"];
const applicationApk = path.join(
  repositoryRoot,
  "app/build/outputs/apk/dev/debug/app-dev-debug.apk",
);
const androidTestApk = path.join(
  repositoryRoot,
  "app/build/outputs/apk/androidTest/dev/debug/app-dev-debug-androidTest.apk",
);
const androidTestMethods = [
  "completedOccurrenceStateIsImmediateOfflineAndSurvivesCleanServerHydration",
  "occurrenceCommandsStaySingularThroughOfflineRestartAndCleanHydration",
  "offlineCreationSurvivesRepositoryReconstructionAndReplaysAtTheOwnerPaths",
  "offlineDailySeriesSurvivesReconstructionAndSynchronizesWithoutReplacement",
  "cachedStandaloneTaskIdentityCannotBeOverwrittenByADailySeries",
  "fractionalFirestoreIntegerIsRejectedThroughTheRepository",
  "signOutWithNoPendingWritesStillRequiresTheServer",
];
const adbCommandTimeoutMillis = 10_000;
const androidTestMethodTimeoutMillis = 120_000;
const androidTestBuildTimeoutMillis = 10 * 60_000;
const childTerminationGraceMillis = 5_000;
const androidRepositoryHarnessTimeoutMillis = 30 * 60_000;

const adb = resolveAdb();
const firebase = path.join(functionsRoot, "node_modules", ".bin", "firebase");
const gradle = path.join(repositoryRoot, "gradlew");

if (process.argv.includes(runConnectedTestsArgument)) {
  await runConnectedRepositoryTests();
} else {
  await runWithFirestoreEmulator();
}

async function runWithFirestoreEmulator() {
  const devices = await requireConnectedDevices();
  await ensureDevAppResetIsAllowed(devices);
  const firestoreHost = await selectFirestoreHost(devices);
  const firebaseConfig = await firebaseConfigForHost(firestoreHost);
  const connectedTestCommand =
    `${shellQuote(process.execPath)} ${shellQuote(scriptPath)} ` +
    runConnectedTestsArgument;
  const environment = {
    ...process.env,
    [firestoreHostEnvironmentVariable]: firestoreHost,
  };

  console.log(
    firestoreHost === deviceLoopbackHost
      ? "Using adb reverse for the Android Firestore Emulator connection."
      : `Using direct Android Firestore Emulator host ${firestoreHost}.`,
  );

  try {
    await run(
      firebase,
      [
        "emulators:exec",
        "--config",
        firebaseConfig.path,
        "--project",
        "demo-duit-android-repository",
        "--only",
        "firestore",
        connectedTestCommand,
      ],
      {
        cwd: repositoryRoot,
        environment,
        timeoutMillis: androidRepositoryHarnessTimeoutMillis,
      },
    );
  } finally {
    // The connected child normally removes both its packages and route. Only repeat the
    // non-destructive route cleanup here: if the child never installed the APKs, uninstalling from
    // the outer process could erase a development app installed while the harness was running.
    try {
      await cleanupConnectedDevicesAfterHarness(devices);
    } finally {
      await firebaseConfig.cleanup();
    }
  }
}

async function runConnectedRepositoryTests() {
  // Firebase emulators:exec starts this child only after Firestore reports ready. Configure the
  // device route here, rather than before starting the emulator, so the route is never presented
  // as ready while its host endpoint is absent.
  const devices = await requireConnectedDevices();
  const firestoreHost = configuredFirestoreHost();
  const usesDirectConnection = firestoreHost !== deviceLoopbackHost;
  const methods = selectedAndroidTestMethods();

  const failures = [];

  try {
    await ensureDevAppResetIsAllowed(devices);
    await cleanupTestPackages(devices);
    await run(
      gradle,
      [":app:assembleDevDebug", ":app:assembleDevDebugAndroidTest"],
      {
        cwd: repositoryRoot,
        timeoutMillis: androidTestBuildTimeoutMillis,
      },
    );
    if (usesDirectConnection) {
      await removeReverseForDevices(devices);
    }

    for (const serial of devices) {
      for (const method of methods) {
        try {
          if (!usesDirectConnection) {
            await resetReverseForDevices([serial]);
          }
          await installTestPackages(serial);
          await runAndroidTestMethod(serial, method, firestoreHost);
        } catch (failure) {
          failures.push({ serial, method, failure });
          console.error(`Android repository test failed on ${serial}: ${method}`);
          console.error(failure);
        } finally {
          await cleanupTestPackages([serial]);
        }
      }
    }
  } finally {
    await removeReverseForDevices(devices);
  }

  if (failures.length > 0) {
    throw new AggregateError(
      failures.map(({ failure }) => failure),
      `${failures.length} of ${methods.length * devices.length} Android repository tests failed: ` +
        failures.map(({ serial, method }) => `${serial}#${method}`).join(", "),
    );
  }
}

function selectedAndroidTestMethods() {
  const selectedMethod =
    process.env[androidTestMethodEnvironmentVariable]?.trim();
  if (!selectedMethod) return androidTestMethods;
  if (!androidTestMethods.includes(selectedMethod)) {
    throw new Error(
      `${androidTestMethodEnvironmentVariable} must name one of: ` +
        androidTestMethods.join(", "),
    );
  }

  return [selectedMethod];
}

async function requireConnectedDevices() {
  const devices = await connectedDevices(adb);

  if (devices.length === 0) {
    throw new Error(
      "No authorized Android device or Emulator is connected. Start one before running this test.",
    );
  }

  return devices;
}

function configuredFirestoreHost() {
  const configuredHost =
    process.env[firestoreHostEnvironmentVariable]?.trim() ??
    deviceLoopbackHost;

  if (!/^[A-Za-z0-9.-]+$/u.test(configuredHost)) {
    throw new Error(
      `${firestoreHostEnvironmentVariable} must be an IPv4 address or hostname.`,
    );
  }
  if (configuredHost === "0.0.0.0") {
    throw new Error(
      `${firestoreHostEnvironmentVariable} must name one host interface, not 0.0.0.0.`,
    );
  }

  return configuredHost;
}

async function selectFirestoreHost(devices) {
  if (process.env[firestoreHostEnvironmentVariable]?.trim()) {
    return configuredFirestoreHost();
  }
  if (devices.some((serial) => serial.startsWith("emulator-"))) {
    return deviceLoopbackHost;
  }

  const deviceNetworks = await Promise.all(
    devices.map((serial) => androidDeviceIpv4Networks(serial)),
  );
  const hostCandidates = localIpv4Networks();
  const matchingHost = hostCandidates.find((candidate) =>
    deviceNetworks.every((networks) =>
      networks.some((network) => networksOverlap(candidate, network)),
    ),
  );

  if (matchingHost) return matchingHost.address;

  console.warn(
    "No host network interface matches every connected device; falling back to adb reverse.",
  );
  return deviceLoopbackHost;
}

async function androidDeviceIpv4Networks(serial) {
  const output = await run(
    adb,
    ["-s", serial, "shell", "ip", "-o", "-4", "addr", "show", "up", "scope", "global"],
    {
      allowFailure: true,
      captureOutput: true,
      timeoutMillis: adbCommandTimeoutMillis,
    },
  );

  return [...output.matchAll(/\binet\s+(\d+(?:\.\d+){3})\/(\d+)\b/gu)]
    .map(([, address, prefixLength]) => ({
      address,
      prefixLength: Number(prefixLength),
    }))
    .filter(({ prefixLength }) => prefixLength >= 0 && prefixLength <= 32);
}

function localIpv4Networks() {
  return Object.values(networkInterfaces())
    .flatMap((addresses) => addresses ?? [])
    .filter(
      (address) =>
        (address.family === "IPv4" || address.family === 4) &&
        !address.internal &&
        address.cidr,
    )
    .map((address) => ({
      address: address.address,
      prefixLength: Number(address.cidr.split("/", 2)[1]),
    }))
    .filter(({ prefixLength }) => Number.isInteger(prefixLength))
    .sort((left, right) => left.address.localeCompare(right.address));
}

function networksOverlap(left, right) {
  return (
    sameIpv4Network(left.address, right.address, left.prefixLength) &&
    sameIpv4Network(left.address, right.address, right.prefixLength)
  );
}

function sameIpv4Network(left, right, prefixLength) {
  const mask =
    prefixLength === 0 ? 0 : (0xffffffff << (32 - prefixLength)) >>> 0;
  return (ipv4Number(left) & mask) === (ipv4Number(right) & mask);
}

function ipv4Number(address) {
  return address
    .split(".")
    .map(Number)
    .reduce((value, octet) => ((value << 8) | octet) >>> 0, 0);
}

async function firebaseConfigForHost(firestoreHost) {
  if (firestoreHost === deviceLoopbackHost) {
    return { path: baseFirebaseConfig, cleanup: async () => {} };
  }

  const temporaryDirectory = await mkdtemp(
    path.join(tmpdir(), "duit-firestore-android-"),
  );

  try {
    const config = JSON.parse(await readFile(baseFirebaseConfig, "utf8"));
    config.firestore.rules = path.resolve(
      repositoryRoot,
      config.firestore.rules,
    );
    config.firestore.indexes = path.resolve(
      repositoryRoot,
      config.firestore.indexes,
    );
    config.emulators.firestore.host = firestoreHost;

    const generatedConfig = path.join(temporaryDirectory, "firebase.json");
    await writeFile(generatedConfig, `${JSON.stringify(config, null, 2)}\n`);

    return {
      path: generatedConfig,
      cleanup: () => rm(temporaryDirectory, { recursive: true, force: true }),
    };
  } catch (failure) {
    await rm(temporaryDirectory, { recursive: true, force: true });
    throw failure;
  }
}

async function resetReverseForDevices(devices) {
  for (const serial of devices) {
    await run(
      adb,
      ["-s", serial, "reverse", "--remove", reverseSpecification],
      {
        allowFailure: true,
        timeoutMillis: adbCommandTimeoutMillis,
      },
    );
    await run(
      adb,
      ["-s", serial, "reverse", reverseSpecification, reverseSpecification],
      { timeoutMillis: adbCommandTimeoutMillis },
    );
  }
}

async function removeReverseForDevices(devices) {
  for (const serial of devices) {
    await run(
      adb,
      ["-s", serial, "reverse", "--remove", reverseSpecification],
      {
        allowFailure: true,
        captureOutput: true,
        timeoutMillis: adbCommandTimeoutMillis,
      },
    );
  }
}

async function installTestPackages(serial) {
  for (const apk of [applicationApk, androidTestApk]) {
    await run(adb, ["-s", serial, "install", "-r", "-t", apk], {
      captureOutput: true,
      timeoutMillis: androidTestMethodTimeoutMillis,
    });
  }
}

async function runAndroidTestMethod(serial, method, firestoreHost) {
  const output = await run(
    adb,
    [
      "-s",
      serial,
      "shell",
      "am",
      "instrument",
      "-w",
      "-r",
      "-e",
      "class",
      `${androidTestClass}#${method}`,
      "-e",
      "runFirestoreRepositoryEmulator",
      "true",
      "-e",
      firestoreHostInstrumentationArgument,
      firestoreHost,
      androidTestRunner,
    ],
    {
      captureOutput: true,
      timeoutMillis: androidTestMethodTimeoutMillis,
    },
  );

  process.stdout.write(output);
  if (!output.includes("OK (1 test)") || output.includes("FAILURES!!!")) {
    throw new Error(
      `Android instrumentation did not report one passing test for ${method}.`,
    );
  }
}

async function cleanupTestPackages(devices) {
  for (const serial of devices) {
    for (const packageName of targetPackages) {
      await run(
        adb,
        ["-s", serial, "shell", "am", "force-stop", packageName],
        {
          allowFailure: true,
          timeoutMillis: adbCommandTimeoutMillis,
        },
      );
      await run(
        adb,
        ["-s", serial, "uninstall", packageName],
        {
          allowFailure: true,
          captureOutput: true,
          timeoutMillis: adbCommandTimeoutMillis,
        },
      );
    }
  }
}

async function cleanupConnectedDevicesAfterHarness(devices) {
  await removeReverseForDevices(devices);
}

async function ensureDevAppResetIsAllowed(devices) {
  if (process.env[allowDevAppResetEnvironmentVariable] === "1") return;

  const devicesWithInstalledDevApp = [];
  for (const serial of devices) {
    const output = await run(
      adb,
      ["-s", serial, "shell", "pm", "path", targetPackages[0]],
      {
        captureOutput: true,
        timeoutMillis: adbCommandTimeoutMillis,
      },
    );
    if (output.split(/\r?\n/u).some((line) => line.startsWith("package:"))) {
      devicesWithInstalledDevApp.push(serial);
    }
  }

  if (devicesWithInstalledDevApp.length > 0) {
    throw new Error(
      `The repository harness resets ${targetPackages[0]}, which is already installed on: ` +
        `${devicesWithInstalledDevApp.join(", ")}. Preserve or synchronize its data, then ` +
        `set ${allowDevAppResetEnvironmentVariable}=1 to authorize the reset.`,
    );
  }
}

function resolveAdb() {
  const androidSdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
  const sdkAdb = androidSdk && path.join(androidSdk, "platform-tools", "adb");

  return sdkAdb && existsSync(sdkAdb) ? sdkAdb : "adb";
}

async function connectedDevices(adbExecutable) {
  const output = await run(adbExecutable, ["devices"], {
    captureOutput: true,
    timeoutMillis: adbCommandTimeoutMillis,
  });

  return output
    .split(/\r?\n/u)
    .slice(1)
    .map((line) => line.split("\t", 2))
    .filter(([, state]) => state === "device")
    .map(([serial]) => serial);
}

function shellQuote(value) {
  return `'${value.replaceAll("'", `'\\''`)}'`;
}

function run(
  executable,
  args,
  {
    allowFailure = false,
    captureOutput = false,
    cwd = repositoryRoot,
    environment = process.env,
    timeoutMillis,
  } = {},
) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, {
      cwd,
      detached: process.platform !== "win32",
      env: environment,
      stdio: captureOutput ? ["ignore", "pipe", "pipe"] : "inherit",
    });
    let output = "";
    let errorOutput = "";
    let settled = false;
    let timedOut = false;
    let terminateTimer;
    let killTimer;

    const settle = (failure) => {
      if (settled) return;
      settled = true;
      clearTimeout(terminateTimer);
      clearTimeout(killTimer);

      if (failure && !allowFailure) {
        reject(failure);
      } else {
        resolve(output);
      }
    };

    child.stdout?.setEncoding("utf8");
    child.stdout?.on("data", (chunk) => {
      output += chunk;
    });
    child.stderr?.setEncoding("utf8");
    child.stderr?.on("data", (chunk) => {
      errorOutput += chunk;
    });
    child.on("error", (failure) => settle(failure));
    child.on("exit", (code, signal) => {
      if (timedOut) {
        settle(
          new Error(
            `${executable} exceeded its ${timeoutMillis} ms host timeout` +
              commandOutputDetails(output, errorOutput),
          ),
        );
      } else if (code === 0) {
        settle();
      } else {
        settle(
          new Error(
            `${executable} exited with ${signal ? `signal ${signal}` : `code ${code}`}` +
              commandOutputDetails(output, errorOutput),
          ),
        );
      }
    });

    if (timeoutMillis !== undefined) {
      terminateTimer = setTimeout(() => {
        timedOut = true;
        killChildProcessGroup(child, "SIGTERM");
        killTimer = setTimeout(() => {
          killChildProcessGroup(child, "SIGKILL");
          settle(
            new Error(
              `${executable} exceeded its ${timeoutMillis} ms host timeout` +
                commandOutputDetails(output, errorOutput),
            ),
          );
        }, childTerminationGraceMillis);
      }, timeoutMillis);
    }
  });
}

function commandOutputDetails(output, errorOutput) {
  const details = [output, errorOutput]
    .map((value) => value.trim())
    .filter(Boolean)
    .join("\n");
  return details ? `\n${details}` : "";
}

function killChildProcessGroup(child, signal) {
  if (child.pid === undefined) return;

  if (process.platform !== "win32") {
    try {
      process.kill(-child.pid, signal);
      return;
    } catch {
      // Fall back to the direct child when it has already left its process group.
    }
  }

  try {
    child.kill(signal);
  } catch {
    // The child may have exited between the timeout and this best-effort termination.
  }
}
