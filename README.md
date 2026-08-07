# Duit

Duit is an Android app for two people to manage their own days, share selected Tasks, and encourage each other without monitoring or engagement pressure.

## Prerequisites

- JDK 21. The repository pins this version in `.java-version` because the local Firestore Emulator requires Java 21 or newer. Android source compatibility and Kotlin/JVM output remain targeted to Java 17.
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

## Functions and Firebase Emulator Suite

Type-check the Functions package:

```sh
npm --prefix functions run typecheck
```

Run its automated test. This builds the Functions package, starts both the Functions and Firestore Emulators for the local-only `demo-duit` project, runs the smoke test, and shuts the emulators down:

```sh
npm --prefix functions test
```

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
