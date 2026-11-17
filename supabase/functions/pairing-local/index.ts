import { initializeFirestore } from "firebase-admin/firestore";

import { adminApp } from "../_shared/admin-app.ts";
import { createPairingRequestHandler } from "../_shared/callable-protocol.ts";

// LOCAL ONLY: serves the Pairing handler with App Check enforcement off so the
// emulator suite can drive the Deno runtime (the Firebase Emulator Suite has no
// App Check emulator). Never deploy this function: it refuses to boot unless the
// Firestore emulator is configured, so it cannot touch a real project.
if (Deno.env.get("FIRESTORE_EMULATOR_HOST") === undefined) {
  throw new Error("pairing-local requires FIRESTORE_EMULATOR_HOST.");
}

initializeFirestore(adminApp(), { preferRest: true });

Deno.serve(createPairingRequestHandler({ enforceAppCheck: false }));
