import assert from "node:assert/strict";
import test from "node:test";

const pairingBaseUrl = process.env.PAIRING_BASE_URL ?? "http://127.0.0.1:5002";

test("the Pairing host reports ready without production credentials", async () => {
  const response = await fetch(new URL("ready", `${pairingBaseUrl}/`));

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    service: "duit-pairing-host",
    status: "ok",
  });
});
