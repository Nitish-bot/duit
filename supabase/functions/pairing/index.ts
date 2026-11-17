import { initializeFirestore } from "firebase-admin/firestore";

import { adminApp } from "../_shared/admin-app.ts";
import { createPairingRequestHandler } from "../_shared/callable-protocol.ts";

// The Edge runtime has no gRPC, so Firestore must use REST. This runs before any
// request: the shared transactions then receive this instance from getFirestore().
initializeFirestore(adminApp(), { preferRest: true });

// The deployed entry always enforces App Check; there is deliberately no switch.
Deno.serve(createPairingRequestHandler({ enforceAppCheck: true }));
