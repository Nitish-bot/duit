# Host Pairing on a Supabase Edge Function

**Status:** Accepted. Supersedes the hosting half of [ADR-0005](0005-use-minimal-functions-and-fcm-for-pairing-and-cheers.md); its server-authority rules still stand.

On 2026-11-15 the user declined to enable the Blaze plan on the development Firebase project, which blocked every Cloud Function and with them real Pairing acceptance (#7) and its dependents (#8, #10). The first reaction was that Firebase itself was ending. It is not: Google is closing only Firebase Studio (March 2027) and Firebase Extensions, and Duit uses neither. Firestore, Firebase Authentication, Security Rules, App Check and FCM are unaffected.

Decision: Firestore, Authentication, Rules, App Check and the offline cache stay exactly as they are, and ADR-0001 stands. Only the server-authoritative Pairing logic moves, from Cloud Functions to a Supabase Edge Function on the free plan. No Supabase database, auth or client SDK is used. The function keeps the Firebase callable wire protocol, so the Android client calls it with `getHttpsCallableFromUrl` and keeps its error decoding. It verifies the Firebase ID token and the App Check token, then runs the same Firestore transactions through the Firebase Admin SDK with a dedicated service account held as the `FIREBASE_SERVICE_ACCOUNT` secret. Firestore reaches the function over REST because the Edge runtime has no gRPC.

The handler lives in `supabase/functions/_shared` and has two hosts. The deployed `pairing` entry always enforces App Check and has no switch to turn it off. The Firebase Emulator Suite has no App Check emulator, so the emulator suite drives the identical handler from a plain Node host with enforcement off, and a second function, `pairing-local`, serves it on the Deno runtime for local runs against the emulators. `pairing-local` refuses to boot unless a Firestore emulator is configured, so it cannot touch a real project; it must never be deployed, so always deploy by name (`supabase functions deploy pairing`). Only `pairing` disables Supabase JWT verification, because its `Authorization` header carries a Firebase token that the function verifies itself.

Consequences:

- Supabase pauses free projects after about a week of low activity, and a paused project returns errors until resumed by hand. Android shows this as the existing connection-required state. This is acceptable for development and must be revisited before production (#34, #36).
- A long-lived service-account key now lives outside Google. It belongs to a dedicated account with only Cloud Datastore User, Firebase Authentication Viewer and Firebase App Check Token Verifier, is never committed, and is rotated on exposure.
- Firestore triggers do not exist off Cloud Functions, so Cheer fan-out (#10) becomes a client-invoked edge call that revalidates the Pair, Share Grant and target and uses the idempotent `cheerDeliveries` ledger. Sending FCM is free and works from the same service account. The design stays in #10.
- Cloud Functions packaging, `firebase.development.json` and the `firebase-functions` dependency are removed, and the Blaze requirement no longer applies to development.
