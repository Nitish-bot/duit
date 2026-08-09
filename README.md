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

## Functions and Firebase Emulator Suite

Type-check the Functions package:

```sh
npm --prefix functions run typecheck
```

Run its automated test. This builds the Functions package, starts both the Functions and Firestore Emulators for the local-only `demo-duit` project, checks the emulator-only Function plus Member Approval/owner-isolation Rules, and shuts the emulators down:

```sh
npm --prefix functions test
```

With an authorized Android device or Emulator connected, run the Android Firestore repository conformance test separately:

```sh
npm --prefix functions run test:android-repository
```

That command starts a dedicated local Firestore Emulator, installs only the repository test, and temporarily configures `adb reverse` so the device can reach it. Its permissive test Rules exist only to seed malformed documents and must never be deployed. Ordinary `connectedDevDebugAndroidTest` runs skip this class unless the dedicated command opts in.

For an interactive emulator session, keep this running:

```sh
npm --prefix functions run emulators:start
```

Then run the smoke test from another terminal:

```sh
npm --prefix functions run test:smoke
```

The Emulator UI is available at `http://127.0.0.1:4000`. Stop the session with Ctrl-C.

The bootstrap workflow uses Firebase's `demo-duit` project ID and requires no production Firebase credentials. Do not add production credentials to run these commands.
