# Duit

Duit is an Android app for individuals to manage their own days and optionally Pair with one Partner to share selected Tasks and encourage each other without monitoring or engagement pressure.

## Prerequisites

- JDK 21. The repository pins this version in `.java-version` because the local Firestore Emulator requires Java 21 or newer. Android source compatibility and Kotlin/JVM output remain targeted to Java 17.

  On Apple Silicon macOS, one way to install and select it for the current terminal is:

  ```sh
  brew install openjdk@21
  export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
  ```
- Android SDK Platform 36, Android SDK Build-Tools 35.0.0, and Platform-Tools. AGP 8.13 uses Build-Tools 35.0.0 by default. Install them from Android Studio's SDK Manager, or with the Android command-line tools:

  ```sh
  sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
  ```

  Make the SDK available through `ANDROID_HOME` or Android Studio's generated `local.properties`.
  If Homebrew installed the command-line tools under `/opt/homebrew/share/android-commandlinetools`,
  make `adb` available in the current terminal with:

  ```sh
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
  export PATH="$ANDROID_HOME/platform-tools:$PATH"
  ```
- Node.js 22 and npm. The repository pins Node in `.nvmrc`, and the Functions package declares the same runtime.

## Clean checkout

From a fresh clone, install the pinned Node runtime and the Functions dependencies:

```sh
git clone git@github.com:Nitish-bot/duit.git
cd duit
nvm install
nvm use
npm ci --prefix functions
```

The Gradle wrapper is committed; the first `./gradlew` command downloads the pinned Gradle distribution and Android dependencies.

## Android

Run the focused `DuitWorkspace` unit tests while developing:

```sh
./gradlew :app:testDevDebugUnitTest --tests com.gaylord.duit.workspace.DuitWorkspaceTest
```

Run all local Android unit-test variants and lint:

```sh
./gradlew test
./gradlew lint
```

With a device or Android Emulator connected, run the Compose UI tests for primary navigation and 200% text scaling:

```sh
./gradlew :app:connectedDevDebugAndroidTest
```

On a physical phone, unlock it first and enable **Keep screen on while charging** for the test run. The Compose host Activity must remain resumed while instrumentation is running.

Build the installable development APK:

```sh
./gradlew :app:assembleDevDebug
```

The APK is written to `app/build/outputs/apk/dev/debug/app-dev-debug.apk`. With a device or Android Emulator connected, install it with:

```sh
./gradlew :app:installDevDebug
```

## Development Firebase

Production Firebase is deliberately not required during development. Use a separate development Firebase project and configure only the `dev` flavor:

1. In Firebase, register an Android app with package name `com.gaylord.duit.dev`.
2. Print the current machine's debug signing fingerprints:

   ```sh
   ./gradlew :app:signingReport
   ```

   Add both the SHA-1 and SHA-256 values for the `devDebug` variant to that Firebase Android app. Each developer machine may have a different debug certificate.
3. Enable Google as a Firebase Authentication sign-in provider and create the development Firestore database.
4. Download that Android app's `google-services.json` to `app/src/dev/google-services.json`. This path is ignored by Git. Never add a production configuration or point the `prod` flavor at this development project.
   Optionally add `duit.pairingBackendUrl=https://<project-ref>.supabase.co/functions/v1/pairing` to the ignored `local.properties` (or pass `-Pduit.pairingBackendUrl=...`) to point the `dev` flavor at the Pairing Edge Function; `http://10.0.2.2:54321/functions/v1/pairing-local` works against a local serve. It must be `https://` (or an `http://` 10.0.2.2/127.0.0.1/localhost address) and end in `/functions/v1/pairing` for the deployed function. Leave it unset and Pairing reports an unavailable backend. The `prod` flavor has no Pairing backend.
5. Sign the Firebase CLI into the Google account that owns only the development project, then deploy just the Firestore Rules. Replace the placeholder with the project ID shown in the development `google-services.json` or Firebase Console:

   ```sh
   ./functions/node_modules/.bin/firebase login
   ./functions/node_modules/.bin/firebase deploy --config firebase.json \
     --only firestore:rules --project <development-project-id>
   ```

   This does not deploy Functions and must never target the production project.
6. Build and install `devDebug`, then sign in once. The first sign-in creates a Firebase Authentication user, but it does not grant access to Duit data.
7. Copy the Firebase UID shown on Duit's **Approval needed** screen. The same UID also appears under **Authentication → Users** in Firebase Console.
8. In Firestore, create document `/memberApprovals/<UID>` with one Boolean field named `approved` set to `true`. Approval records are administered in Firebase Console; Android clients can inspect only their own record and can never list or modify approvals.
9. Return to Duit and tap **Retry**. The app then performs its first server hydration. A completed read of an empty workspace is a valid new Member workspace and enables private Task creation.

Repeat steps 6–9 for any additional development Member. Approving another UID requires no app, Rules, or Functions redeployment. Pairing remains optional; each approved Member owns a private workspace whether paired or not.

## Pairing backend (Supabase Edge Function) and App Check

Pairing's server-authoritative logic runs as a Supabase Edge Function (see [ADR-0007](docs/adr/0007-host-pairing-on-supabase-edge-functions.md)). It keeps the Firebase callable protocol, verifies the Firebase ID token and App Check token, and runs the Firestore transactions with the Admin SDK. Firebase hosting, Authentication, Rules and the free plan are unchanged; there is no Supabase database. The deployed function is `supabase/functions/pairing`; only that function disables Supabase JWT verification. Never target a production project from these steps.

One-time, human-owned development setup:

1. Create a Supabase account and one free project, then run `supabase login` and `supabase link` from the repository root. The project reference lands in the ignored `supabase/.temp`.
2. In the development Google Cloud project, create a dedicated service account with only Cloud Datastore User, Firebase Authentication Viewer and Firebase App Check Token Verifier. Download its key, store it as a secret with `supabase secrets set FIREBASE_SERVICE_ACCOUNT="$(cat <key.json>)"`, and delete the local copy. Never commit it; rotate it if it is ever exposed.
3. Put the function URL in the ignored `local.properties` as `duit.pairingBackendUrl=https://<project-ref>.supabase.co/functions/v1/pairing`. CI and one-off builds can pass `-Pduit.pairingBackendUrl=...` instead. The build rejects any other shape.
4. Register the App Check debug token. Build and run `devDebug`, open Together, and read the token from the device logs:

   ```sh
   adb logcat -s DebugAppCheckProvider
   ```

   The log line appears only once. If you missed it, read the stored token without logcat: `adb shell run-as com.gaylord.duit.dev cat shared_prefs/com.google.firebase.appcheck.debug.store.*.xml`. Treat the value as a secret and never commit it.

   In Firebase Console, open **Security → App Check → Apps**, find `com.gaylord.duit.dev`, choose **Manage debug tokens**, and register it ([Firebase's Android debug-provider instructions](https://firebase.google.com/docs/app-check/android/debug-provider)). Keep the token private, never commit it, and revoke it if it is exposed.
5. Verify locally, then deploy by name. Never run `supabase functions deploy` without the name, because `pairing-local` must not be deployed:

   ```sh
   npm --prefix functions run typecheck
   npm --prefix functions test
   supabase functions deploy pairing
   ```

   If Docker bundling fails with "Operation not permitted" (for example while colima mounts `$HOME` read-only), add `--use-api` to deploy without Docker.

A paused free project returns errors until it is resumed in the Supabase dashboard; Android shows the connection-required state meanwhile. With no `duit.pairingBackendUrl`, the `dev` app reports Pairing as unavailable, and the `prod` flavor has no Pairing backend.

Running the function locally on a phone: Android blocks cleartext HTTP by default and the app has no network security configuration, so a plain `http://` address to a local serve will not work from a device without adding a dev-only network security config (not part of this repository; `adb reverse` does not change that). Prefer testing against the deployed development function from a phone, and use the emulator suite for local runs.

## Functions and Firebase Emulator Suite

Type-check the Functions package:

```sh
npm --prefix functions run typecheck
```

Run its automated test. This builds the Pairing host, starts the Auth and Firestore Emulators for the local-only `demo-duit` project plus a Node host serving the same Pairing handler (App Check off, because the Emulator Suite has no App Check emulator), runs the Pairing cases and the Member Approval/owner-isolation Rules checks, and shuts everything down:

```sh
npm --prefix functions test
```

With an authorized Android device or Emulator connected, run the Android Firestore repository conformance test separately:

```sh
npm --prefix functions run test:android-repository
```

That command starts a dedicated local Firestore Emulator, builds the test APK once, and invokes only the seven repository methods directly. For a physical device on the same trusted network, the harness binds the Emulator to the matching host interface so Firestore can reconnect without depending on a long-lived USB tunnel. Android emulators and devices without a matching network fall back to a temporary `adb reverse` route. Set `DUIT_ANDROID_FIRESTORE_HOST` to override the detected host, or set it to `127.0.0.1` to force `adb reverse`. Keep a physical device out of its system low-storage state before running this suite.

The harness installs and removes `com.gaylord.duit.dev` between repository methods to guarantee isolated local persistence. If that development app is already installed, the harness stops before changing it because removal can discard signed-in state and unsynchronized Tasks. After preserving or synchronizing that data, explicitly authorize the reset with `DUIT_ANDROID_REPOSITORY_ALLOW_DEV_APP_RESET=1`. The dedicated Emulator binds only for the duration of the test; its permissive test Rules exist only to seed malformed documents and must never be deployed. Ordinary `connectedDevDebugAndroidTest` runs skip this class unless the dedicated command opts in.

For an interactive emulator session, keep this running:

```sh
npm --prefix functions run emulators:start
```

Then run the smoke test from another terminal:

```sh
npm --prefix functions run test:smoke
```

The Emulator UI is available at `http://127.0.0.1:4000`. Stop the session with Ctrl-C.

To exercise the Deno Edge runtime itself against the emulators (optional): install `supabase`, `deno`, `colima` and `docker` with Homebrew and run `colima start --memory 4` (a project outside `$HOME` also needs `--mount <dir>:w`). Start only the needed containers with `supabase start -x gotrue,realtime,storage-api,imgproxy,mailpit,postgrest,postgres-meta,studio,logflare,vector,supavisor` (the first pull is about 3 GB). Start the emulators bound to `0.0.0.0` using a temporary copy of `firebase.emulator.json`. Run `npm --prefix functions run deno:metadata-stub`, a fake GCE metadata server, because the Firestore REST transport asks for a token even against the emulator. Write an untracked env file containing `FIRESTORE_EMULATOR_HOST=host.docker.internal:8080`, `FIREBASE_AUTH_EMULATOR_HOST=host.docker.internal:9099`, `GCLOUD_PROJECT=demo-duit` and `GCE_METADATA_HOST=host.docker.internal:8099`, then run `supabase functions serve --no-verify-jwt --env-file <file>`. Point the suite at the App Check-free `pairing-local` function:

```sh
FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099 GCLOUD_PROJECT=demo-duit \
  PAIRING_BASE_URL=http://127.0.0.1:54321/functions/v1/pairing-local \
  node --test functions/test/pairing-functions.test.mjs
```

The bootstrap workflow uses Firebase's `demo-duit` project ID and requires no production Firebase credentials. Do not add production credentials to run these commands.
