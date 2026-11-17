// Starts the Node Pairing host (build first), waits until it answers /ready, runs
// the given command, then stops the host. With --serve there is no command: the
// host just stays up until interrupted (used by `npm run emulators:start`).
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const hostPath = fileURLToPath(
  new URL("./lib/functions/emulator/src/server.js", import.meta.url),
);
const port = process.env.PAIRING_HOST_PORT ?? "5002";
const readyUrl = `http://127.0.0.1:${port}/ready`;
const args = process.argv.slice(2);
const serve = args[0] === "--serve";
const command = serve ? [] : args;

const host = spawn(process.execPath, [hostPath], {
  env: { ...process.env, PAIRING_HOST_PORT: port },
  stdio: "inherit",
});
let hostExited = false;
host.on("exit", () => {
  hostExited = true;
});

async function waitUntilReady() {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (hostExited) throw new Error("The Pairing host exited before it was ready.");
    try {
      if ((await fetch(readyUrl)).ok) return;
    } catch {
      // not listening yet
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`The Pairing host did not answer ${readyUrl}.`);
}

function stopHost() {
  if (!hostExited) host.kill("SIGTERM");
}
process.on("SIGINT", () => {
  stopHost();
  process.exit(130);
});
process.on("SIGTERM", () => {
  stopHost();
  process.exit(143);
});

try {
  await waitUntilReady();
  if (serve) {
    await new Promise((resolve) => host.on("exit", resolve));
    process.exitCode = 0;
  } else {
    const child = spawn(command[0], command.slice(1), { stdio: "inherit" });
    process.exitCode = await new Promise((resolve) =>
      child.on("exit", (code, signal) => resolve(code ?? (signal ? 1 : 0))),
    );
  }
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
} finally {
  stopHost();
}
