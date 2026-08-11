import {
  type HttpsFunction,
  onRequest,
} from "firebase-functions/v2/https";

// The root Functions project is built before this emulator-only package. Requiring
// its compiled module keeps the transaction code identical without making those
// callables deployable from the production entry point.
const { createPairingCallables } = require("../../lib/pairing.js") as {
  createPairingCallables: (policy: { enforceAppCheck: boolean }) => {
    acceptPairInvite: HttpsFunction;
    confirmPair: HttpsFunction;
    createPairInvite: HttpsFunction;
    getPairingStatus: HttpsFunction;
  };
};

// Firebase Emulator Suite has no App Check emulator. Production wiring must pass
// true; this local wrapper still exercises real Auth Emulator tokens and approval
// records, and the production entry point intentionally exports no callables yet.
const pairing = createPairingCallables({ enforceAppCheck: false });

export const acceptPairInvite = pairing.acceptPairInvite;
export const confirmPair = pairing.confirmPair;
export const createPairInvite = pairing.createPairInvite;
export const getPairingStatus = pairing.getPairingStatus;

export const emulatorReady = onRequest((_request, response) => {
  response.status(200).json({
    service: "duit-functions-emulator",
    status: "ok",
  });
});
