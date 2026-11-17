import { cert, getApps, initializeApp } from "firebase-admin/app";

// Deployed: the dedicated service account arrives as the FIREBASE_SERVICE_ACCOUNT
// secret. Local emulators: no secret, so default initialization reads
// GCLOUD_PROJECT and the *_EMULATOR_HOST variables.
export function adminApp() {
  const existing = getApps()[0];
  if (existing !== undefined) {
    return existing;
  }
  const serviceAccount = process.env.FIREBASE_SERVICE_ACCOUNT;
  return serviceAccount === undefined || serviceAccount.trim() === ""
    ? initializeApp()
    : initializeApp({ credential: cert(JSON.parse(serviceAccount)) });
}
