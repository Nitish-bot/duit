import { createServer, type IncomingMessage, type ServerResponse } from "node:http";

import { createPairingRequestHandler } from "../../../supabase/functions/_shared/callable-protocol.ts";

// Node host for the same Pairing handler the Supabase Edge Function serves, used
// by the Firebase Emulator Suite tests. The Emulator Suite has no App Check
// emulator, so enforcement is off here; the deployed Deno entry never turns it off.
const host = process.env.PAIRING_HOST ?? "127.0.0.1";
const port = Number(process.env.PAIRING_HOST_PORT ?? "5002");
const handle = createPairingRequestHandler({ enforceAppCheck: false });

const server = createServer((request, response) => {
  void respond(request, response);
});

async function respond(
  incoming: IncomingMessage,
  outgoing: ServerResponse,
): Promise<void> {
  try {
    if (incoming.method === "GET" && incoming.url === "/ready") {
      outgoing.writeHead(200, { "Content-Type": "application/json" });
      outgoing.end(
        JSON.stringify({ service: "duit-pairing-host", status: "ok" }),
      );
      return;
    }
    const hasBody = incoming.method !== "GET" && incoming.method !== "HEAD";
    const headers = new Headers();
    for (const [name, value] of Object.entries(incoming.headers)) {
      if (value !== undefined) {
        headers.set(name, Array.isArray(value) ? value.join(", ") : value);
      }
    }
    const webRequest = new Request(`http://${host}:${port}${incoming.url ?? "/"}`, {
      body: hasBody ? Buffer.concat(await collect(incoming)) : undefined,
      headers,
      method: incoming.method,
    });
    const webResponse = await handle(webRequest);
    outgoing.writeHead(
      webResponse.status,
      Object.fromEntries(webResponse.headers),
    );
    outgoing.end(Buffer.from(await webResponse.arrayBuffer()));
  } catch (error) {
    console.error("Pairing host failed", error);
    outgoing.writeHead(500, { "Content-Type": "application/json" });
    outgoing.end(JSON.stringify({ error: { message: "INTERNAL", status: "INTERNAL" } }));
  }
}

async function collect(incoming: IncomingMessage): Promise<Buffer[]> {
  const chunks: Buffer[] = [];
  for await (const chunk of incoming) {
    chunks.push(chunk as Buffer);
  }
  return chunks;
}

server.listen(port, host, () => {
  console.log(`Pairing host listening on http://${host}:${port}`);
});

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    server.close(() => process.exit(0));
  });
}
