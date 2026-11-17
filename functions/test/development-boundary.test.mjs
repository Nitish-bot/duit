import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";

const requireFromFunctions = createRequire(new URL("../package.json", import.meta.url));
const { createPairingRequestHandler } = requireFromFunctions(
  "./emulator/lib/supabase/functions/_shared/callable-protocol.js",
);

const baseUrl = "https://example.test/functions/v1/pairing";

function handlerWith(policy, verifiers = {}) {
  return createPairingRequestHandler(policy, {
    verifyAppCheck: async () => ({}),
    verifyIdToken: async () => {
      throw new Error("no ID token expected");
    },
    ...verifiers,
  });
}

function post(operation, { body = { data: {} }, headers = {}, method = "POST" } = {}) {
  return new Request(`${baseUrl}/${operation}`, {
    body: method === "POST" ? JSON.stringify(body) : undefined,
    headers: { "Content-Type": "application/json", ...headers },
    method,
  });
}

async function outcome(response) {
  return { body: await response.json(), status: response.status };
}

test("enforced App Check rejects a missing token before anything else runs", async () => {
  const handler = handlerWith({ enforceAppCheck: true });

  const { body, status } = await outcome(await handler(post("createPairInvite")));

  assert.equal(status, 401);
  assert.equal(body.error.status, "UNAUTHENTICATED");
});

test("enforced App Check treats every verification failure as unauthenticated", async () => {
  for (const message of ["Decoding App Check token failed", "InvalidCharacterError", "anything"]) {
    const handler = handlerWith(
      { enforceAppCheck: true },
      { verifyAppCheck: async () => { throw new Error(message); } },
    );
    const { body, status } = await outcome(
      await handler(post("createPairInvite", { headers: { "X-Firebase-AppCheck": "bogus" } })),
    );
    assert.equal(status, 401, message);
    assert.equal(body.error.status, "UNAUTHENTICATED", message);
    assert.doesNotMatch(JSON.stringify(body), new RegExp(message), message);
  }
});

test("a verified App Check token reaches the handler, which still needs a signed-in Member", async () => {
  const handler = handlerWith({ enforceAppCheck: true });

  const { body, status } = await outcome(
    await handler(post("createPairInvite", { headers: { "X-Firebase-AppCheck": "ok" } })),
  );

  assert.equal(status, 401);
  assert.equal(body.error.message, "Sign in to use Pairing.");
});

test("an invalid ID token is unauthenticated and a malformed header never reaches the handler", async () => {
  const handler = handlerWith({ enforceAppCheck: false });

  for (const authorization of ["Bearer bad", "Basic abc"]) {
    const { body, status } = await outcome(
      await handler(post("getPairingStatus", { headers: { Authorization: authorization } })),
    );
    assert.equal(status, 401, authorization);
    assert.equal(body.error.status, "UNAUTHENTICATED", authorization);
  }
});

test("protocol errors use the callable protocol statuses", async () => {
  const handler = handlerWith({ enforceAppCheck: false });

  const get = await outcome(await handler(post("createPairInvite", { method: "GET" })));
  assert.equal(get.status, 405);
  const unknown = await outcome(await handler(post("deleteEverything")));
  assert.equal(unknown.status, 404);
  assert.equal(unknown.body.error.status, "NOT_FOUND");
  const invalid = await outcome(await handler(post("createPairInvite", { body: { data: { extra: 1 } } })));
  assert.equal(invalid.status, 400);
  assert.equal(invalid.body.error.status, "INVALID_ARGUMENT");
  const notJson = await handler(
    new Request(`${baseUrl}/createPairInvite`, { body: "x", headers: { "Content-Type": "text/plain" }, method: "POST" }),
  );
  assert.equal(notJson.status, 400);
});

test("an unexpected failure returns INTERNAL with no detail and does not escape", async () => {
  const handler = handlerWith({ enforceAppCheck: false });
  const brokenRequest = {
    headers: {
      get() {
        throw new Error("secret internal detail");
      },
    },
    method: "POST",
    url: `${baseUrl}/getPairingStatus`,
  };
  const originalError = console.error;
  console.error = () => undefined;
  try {
    const { body, status } = await outcome(await handler(brokenRequest));
    assert.equal(status, 500);
    assert.deepEqual(body, { error: { message: "INTERNAL", status: "INTERNAL" } });
  } finally {
    console.error = originalError;
  }
});
