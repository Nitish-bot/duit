import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const functionsRoot = fileURLToPath(new URL("../", import.meta.url));
const emulatorPort = "8081";
const reverseSpecification = `tcp:${emulatorPort}`;
const androidTestClass =
  "com.gaylord.duit.platform.firebase.FirestoreTaskRepositoryEmulatorTest";

const adb = resolveAdb();
const firebase = path.join(functionsRoot, "node_modules", ".bin", "firebase");
const devices = await connectedDevices(adb);

if (devices.length === 0) {
  throw new Error(
    "No authorized Android device or Emulator is connected. Start one before running this test.",
  );
}

const configuredDevices = [];

try {
  for (const serial of devices) {
    await run(adb, ["-s", serial, "reverse", reverseSpecification, reverseSpecification]);
    configuredDevices.push(serial);
  }

  await run(
    firebase,
    [
      "emulators:exec",
      "--config",
      path.join(repositoryRoot, "firebase.android-test.json"),
      "--project",
      "demo-duit-android-repository",
      "--only",
      "firestore",
      `./gradlew :app:connectedDevDebugAndroidTest ` +
        `-Pandroid.testInstrumentationRunnerArguments.class=${androidTestClass} ` +
        `-Pandroid.testInstrumentationRunnerArguments.runFirestoreRepositoryEmulator=true`,
    ],
    { cwd: repositoryRoot },
  );
} finally {
  for (const serial of configuredDevices) {
    await run(
      adb,
      ["-s", serial, "reverse", "--remove", reverseSpecification],
      { allowFailure: true },
    );
  }
}

function resolveAdb() {
  const androidSdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
  const sdkAdb = androidSdk && path.join(androidSdk, "platform-tools", "adb");

  return sdkAdb && existsSync(sdkAdb) ? sdkAdb : "adb";
}

async function connectedDevices(adbExecutable) {
  const output = await run(adbExecutable, ["devices"], { captureOutput: true });

  return output
    .split(/\r?\n/u)
    .slice(1)
    .map((line) => line.split("\t", 2))
    .filter(([, state]) => state === "device")
    .map(([serial]) => serial);
}

function run(
  executable,
  args,
  { allowFailure = false, captureOutput = false, cwd = repositoryRoot } = {},
) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, {
      cwd,
      env: process.env,
      stdio: captureOutput ? ["ignore", "pipe", "inherit"] : "inherit",
    });
    let output = "";

    child.stdout?.setEncoding("utf8");
    child.stdout?.on("data", (chunk) => {
      output += chunk;
    });
    child.on("error", reject);
    child.on("exit", (code, signal) => {
      if (code === 0 || allowFailure) {
        resolve(output);
      } else {
        reject(
          new Error(
            `${executable} exited with ${signal ? `signal ${signal}` : `code ${code}`}`,
          ),
        );
      }
    });
  });
}
