import assert from "node:assert/strict";
import test from "node:test";

const projectId = "demo-duit";
const functionsHost = process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001";
const endpoint = `http://${functionsHost}/${projectId}/us-central1/emulatorReady`;

test("Functions Emulator reports ready without production credentials", async () => {
  const response = await fetch(endpoint);

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    service: "duit-functions-emulator",
    status: "ok",
  });
});
