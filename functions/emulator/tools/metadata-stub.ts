// LOCAL ONLY. Run with Deno: `deno run -A functions/emulator/tools/metadata-stub.ts`.
//
// firebase-admin's Firestore REST transport (preferRest, the only transport the
// Supabase Edge runtime has) always asks Application Default Credentials for a
// token, even against the emulator. Pointing GCE_METADATA_HOST at this stub makes
// that succeed with a dummy token, which the Firestore emulator ignores.
const port = Number(Deno.env.get("METADATA_STUB_PORT") ?? "8099");
const headers = { "Metadata-Flavor": "Google" };

Deno.serve({ hostname: "0.0.0.0", port }, (request) => {
  const path = new URL(request.url).pathname;
  if (path.endsWith("/token")) {
    return Response.json(
      { access_token: "owner", expires_in: 3600, token_type: "Bearer" },
      { headers },
    );
  }
  if (path.endsWith("/project-id")) {
    return new Response("demo-duit", { headers });
  }
  return new Response("{}", {
    headers: { ...headers, "Content-Type": "application/json" },
  });
});
