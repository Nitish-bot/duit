import { createPairingCallables } from "../../src/pairing.js";

// This package is deployed only through firebase.development.json. The default
// production entry remains empty, while the local emulator uses its own wrapper
// with App Check disabled.
const pairing = createPairingCallables({ enforceAppCheck: true });

export const acceptPairInvite = pairing.acceptPairInvite;
export const confirmPair = pairing.confirmPair;
export const createPairInvite = pairing.createPairInvite;
export const getPairingStatus = pairing.getPairingStatus;
