import assert from "node:assert/strict";
import { after, before, beforeEach, test } from "node:test";

import { deleteApp as deleteAdminApp, getApps, initializeApp as initializeAdminApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { deleteApp, initializeApp } from "firebase/app";
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  GoogleAuthProvider,
  signInWithCredential,
  updateProfile,
} from "firebase/auth";
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallableFromURL,
} from "firebase/functions";

const projectId = "demo-duit";
const [functionsHost = "127.0.0.1", functionsPort = "5001"] =
  (process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001").split(":");
const pairingBaseUrl = process.env.PAIRING_BASE_URL ?? "http://127.0.0.1:5002";
const authHost = process.env.FIREBASE_AUTH_EMULATOR_HOST ?? "127.0.0.1:9099";
const firestoreHost = process.env.FIRESTORE_EMULATOR_HOST ?? "127.0.0.1:8080";

let adminApp;
let adminDatabase;
const clientApps = [];
let identitySequence = 0;

before(() => {
  adminApp = initializeAdminApp({ projectId }, "pairing-functions-test");
  adminDatabase = getFirestore(adminApp);
});

beforeEach(async () => {
  await Promise.all([
    clearFirestore(),
    fetch(`http://${authHost}/emulator/v1/projects/${projectId}/accounts`, {
      method: "DELETE",
    }),
  ]);
});

test("Pairing callables require Firebase Authentication", async () => {
  const signedOut = createSignedOutClient();

  await rejectsWithCode(
    () => call(signedOut, "createPairInvite", {}),
    "functions/unauthenticated",
  );
  await rejectsWithCode(
    () => call(signedOut, "getPairingStatus", {}),
    "functions/unauthenticated",
  );
});

test("an approved Firebase identity that did not sign in with Google cannot use Pairing", async () => {
  const passwordIdentity = await createPasswordIdentity("Password Example");
  await approve(passwordIdentity.uid);

  await rejectsWithCode(
    () => call(passwordIdentity, "createPairInvite", {}),
    "functions/permission-denied",
  );
});

after(async () => {
  await Promise.all(clientApps.splice(0).map((app) => deleteApp(app)));
  if (getApps().includes(adminApp)) {
    await deleteAdminApp(adminApp);
  }
});

test("an approved unpaired Member can create a short-lived Pair Invite", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  await approve(alice.uid);

  const beforeCreation = Date.now();
  const result = await call(alice, "createPairInvite", {});

  assert.match(result.code, /^[0-9A-HJKMNP-TV-Z]{8}$/);
  assert.ok(result.expiresAtEpochMillis >= beforeCreation + 14 * 60_000);
  assert.ok(result.expiresAtEpochMillis <= beforeCreation + 16 * 60_000);

  const invites = await adminDatabase
    .collection("pairInvites")
    .where("creatorUid", "==", alice.uid)
    .get();
  assert.equal(invites.size, 1);
  assert.match(invites.docs[0].id, /^[a-f0-9]{64}$/);
  assert.equal(invites.docs[0].data().status, "OPEN");
  assert.equal(Object.hasOwn(invites.docs[0].data(), "code"), false);
});

test("entering a Pair Invite creates one inactive two-Member match and consumes the code", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const bob = await createGoogleIdentity("Bob Example");
  await Promise.all([approve(alice.uid), approve(bob.uid)]);
  const invite = await call(alice, "createPairInvite", {});

  const accepted = await call(bob, "acceptPairInvite", { code: invite.code });

  assert.equal(accepted.state, "PENDING_CONFIRMATION");
  assert.deepEqual(accepted.partnerIdentity, {
    displayName: "Alice Example",
    photoUrl: null,
  });
  const pair = await adminDatabase.doc(`pairs/${accepted.pairId}`).get();
  assert.equal(pair.data()?.state, "PENDING_CONFIRMATION");
  assert.deepEqual(
    pair.data()?.members.map(({ uid, confirmed }) => ({ uid, confirmed })),
    [
      { uid: alice.uid, confirmed: false },
      { uid: bob.uid, confirmed: false },
    ],
  );
  const [aliceMembership, bobMembership] = await Promise.all([
    adminDatabase.doc(`pairMemberships/${alice.uid}`).get(),
    adminDatabase.doc(`pairMemberships/${bob.uid}`).get(),
  ]);
  assert.deepEqual(aliceMembership.data(), {
    createdAt: aliceMembership.data()?.createdAt,
    pairId: accepted.pairId,
    partnerUid: bob.uid,
    state: "PENDING_CONFIRMATION",
    updatedAt: aliceMembership.data()?.updatedAt,
  });
  assert.equal(bobMembership.data()?.pairId, accepted.pairId);
  assert.equal(bobMembership.data()?.partnerUid, alice.uid);
  assert.equal(bobMembership.data()?.state, "PENDING_CONFIRMATION");
  const storedInvite = await adminDatabase
    .collection("pairInvites")
    .where("creatorUid", "==", alice.uid)
    .get();
  assert.equal(storedInvite.docs[0].data().status, "CONSUMED");
  assert.equal(storedInvite.docs[0].data().consumedByUid, bob.uid);
});

test("only each pending participant receives the Partner identity through Pairing status", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const bob = await createGoogleIdentity("Bob Example");
  const carol = await createGoogleIdentity("Carol Example");
  await Promise.all([approve(alice.uid), approve(bob.uid), approve(carol.uid)]);
  const invite = await call(alice, "createPairInvite", {});
  const accepted = await call(bob, "acceptPairInvite", { code: invite.code });

  const [aliceStatus, bobStatus, carolStatus] = await Promise.all([
    call(alice, "getPairingStatus", {}),
    call(bob, "getPairingStatus", {}),
    call(carol, "getPairingStatus", {}),
  ]);

  assert.deepEqual(aliceStatus, {
    pairId: accepted.pairId,
    partnerConfirmed: false,
    partnerIdentity: { displayName: "Bob Example", photoUrl: null },
    selfConfirmed: false,
    state: "PENDING_CONFIRMATION",
  });
  assert.deepEqual(bobStatus, {
    pairId: accepted.pairId,
    partnerConfirmed: false,
    partnerIdentity: { displayName: "Alice Example", photoUrl: null },
    selfConfirmed: false,
    state: "PENDING_CONFIRMATION",
  });
  assert.deepEqual(carolStatus, { state: "UNPAIRED" });
});

test("a Pair activates atomically only after both Members explicitly confirm", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const bob = await createGoogleIdentity("Bob Example");
  await Promise.all([approve(alice.uid), approve(bob.uid)]);
  const invite = await call(alice, "createPairInvite", {});
  const accepted = await call(bob, "acceptPairInvite", { code: invite.code });

  const firstConfirmation = await call(alice, "confirmPair", {
    pairId: accepted.pairId,
  });
  assert.equal(firstConfirmation.state, "PENDING_CONFIRMATION");
  assert.equal(firstConfirmation.selfConfirmed, true);
  assert.equal(firstConfirmation.partnerConfirmed, false);
  assert.equal(
    (await adminDatabase.doc(`pairs/${accepted.pairId}`).get()).data()?.state,
    "PENDING_CONFIRMATION",
  );

  const secondConfirmation = await call(bob, "confirmPair", {
    pairId: accepted.pairId,
  });
  assert.deepEqual(secondConfirmation, {
    pairId: accepted.pairId,
    partnerConfirmed: true,
    partnerIdentity: { displayName: "Alice Example", photoUrl: null },
    partnerUid: alice.uid,
    selfConfirmed: true,
    state: "ACTIVE",
  });

  const [pair, aliceMembership, bobMembership, aliceStatus] = await Promise.all([
    adminDatabase.doc(`pairs/${accepted.pairId}`).get(),
    adminDatabase.doc(`pairMemberships/${alice.uid}`).get(),
    adminDatabase.doc(`pairMemberships/${bob.uid}`).get(),
    call(alice, "getPairingStatus", {}),
  ]);
  assert.equal(pair.data()?.state, "ACTIVE");
  assert.equal(pair.data()?.members.length, 2);
  assert.equal(aliceMembership.data()?.state, "ACTIVE");
  assert.equal(bobMembership.data()?.state, "ACTIVE");
  assert.deepEqual(aliceStatus, {
    pairId: accepted.pairId,
    partnerConfirmed: true,
    partnerIdentity: { displayName: "Bob Example", photoUrl: null },
    partnerUid: bob.uid,
    selfConfirmed: true,
    state: "ACTIVE",
  });
});

test("active Pair status requires continuing approvals and reciprocal membership locks", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const bob = await createGoogleIdentity("Bob Example");
  await Promise.all([approve(alice.uid), approve(bob.uid)]);
  const invite = await call(alice, "createPairInvite", {});
  const accepted = await call(bob, "acceptPairInvite", { code: invite.code });
  await call(alice, "confirmPair", { pairId: accepted.pairId });
  await call(bob, "confirmPair", { pairId: accepted.pairId });

  await adminDatabase.doc(`pairMemberships/${alice.uid}`).update({ state: "INVALID" });
  await rejectsWithCode(
    () => call(alice, "getPairingStatus", {}),
    "functions/failed-precondition",
  );
  await adminDatabase.doc(`pairMemberships/${alice.uid}`).update({ state: "ACTIVE" });

  await approve(bob.uid, false);
  await rejectsWithCode(
    () => call(alice, "getPairingStatus", {}),
    "functions/permission-denied",
  );
  await rejectsWithCode(
    () => call(alice, "confirmPair", { pairId: accepted.pairId }),
    "functions/permission-denied",
  );

  await approve(bob.uid);
  await adminDatabase.doc(`pairMemberships/${bob.uid}`).delete();
  await rejectsWithCode(
    () => call(alice, "getPairingStatus", {}),
    "functions/failed-precondition",
  );
  await rejectsWithCode(
    () => call(alice, "confirmPair", { pairId: accepted.pairId }),
    "functions/failed-precondition",
  );
});

test("failed code guesses persist a cooldown that also blocks a later valid code", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const mallory = await createGoogleIdentity("Mallory Example");
  await Promise.all([approve(alice.uid), approve(mallory.uid)]);
  const invite = await call(alice, "createPairInvite", {});

  await rejectsWithCode(
    () => call(mallory, "acceptPairInvite", { code: "not a code" }),
    "functions/invalid-argument",
  );
  for (const code of ["00000000", "00000001", "00000002", "00000003"]) {
    await rejectsWithCode(
      () => call(mallory, "acceptPairInvite", { code }),
      "functions/failed-precondition",
    );
  }
  await rejectsWithCode(
    () => call(mallory, "acceptPairInvite", { code: invite.code }),
    "functions/resource-exhausted",
  );

  const rateLimit = await adminDatabase
    .doc(`inviteRateLimits/${mallory.uid}`)
    .get();
  assert.equal(rateLimit.data()?.acceptFailedCount, 5);
  assert.ok(rateLimit.data()?.acceptBlockedUntil.toMillis() > Date.now());
  assert.equal(
    (await adminDatabase.doc(`pairMemberships/${mallory.uid}`).get()).exists,
    false,
  );
});

test("an expired pending Pair cannot activate and releases both membership locks", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const bob = await createGoogleIdentity("Bob Example");
  await Promise.all([approve(alice.uid), approve(bob.uid)]);
  const invite = await call(alice, "createPairInvite", {});
  const accepted = await call(bob, "acceptPairInvite", { code: invite.code });
  await adminDatabase.doc(`pairs/${accepted.pairId}`).update({
    confirmationExpiresAt: Timestamp.fromMillis(Date.now() - 1_000),
  });

  await rejectsWithCode(
    () => call(alice, "confirmPair", { pairId: accepted.pairId }),
    "functions/deadline-exceeded",
  );

  const [pair, aliceMembership, bobMembership] = await Promise.all([
    adminDatabase.doc(`pairs/${accepted.pairId}`).get(),
    adminDatabase.doc(`pairMemberships/${alice.uid}`).get(),
    adminDatabase.doc(`pairMemberships/${bob.uid}`).get(),
  ]);
  assert.equal(pair.data()?.state, "EXPIRED");
  assert.equal(aliceMembership.exists, false);
  assert.equal(bobMembership.exists, false);
});

test("Pairing status expires a stale pending Pair so both Members can retry", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const bob = await createGoogleIdentity("Bob Example");
  await Promise.all([approve(alice.uid), approve(bob.uid)]);
  const invite = await call(alice, "createPairInvite", {});
  const accepted = await call(bob, "acceptPairInvite", { code: invite.code });
  await adminDatabase.doc(`pairs/${accepted.pairId}`).update({
    confirmationExpiresAt: Timestamp.fromMillis(Date.now() - 1_000),
  });

  assert.deepEqual(await call(alice, "getPairingStatus", {}), {
    state: "UNPAIRED",
  });
  assert.deepEqual(await call(bob, "getPairingStatus", {}), {
    state: "UNPAIRED",
  });
  const [aliceRetry, bobRetry] = await Promise.all([
    call(alice, "createPairInvite", {}),
    call(bob, "createPairInvite", {}),
  ]);
  assert.match(aliceRetry.code, /^[0-9A-HJKMNP-TV-Z]{8}$/);
  assert.match(bobRetry.code, /^[0-9A-HJKMNP-TV-Z]{8}$/);
  const [pair, memberships] = await Promise.all([
    adminDatabase.doc(`pairs/${accepted.pairId}`).get(),
    adminDatabase.collection("pairMemberships").get(),
  ]);
  assert.equal(pair.data()?.state, "EXPIRED");
  assert.equal(memberships.size, 0);
});

test("self, expired, and replayed Pair Invite codes are rejected without stray membership", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const bob = await createGoogleIdentity("Bob Example");
  const carol = await createGoogleIdentity("Carol Example");
  await Promise.all([approve(alice.uid), approve(bob.uid), approve(carol.uid)]);

  const selfInvite = await call(alice, "createPairInvite", {});
  await rejectsWithCode(
    () => call(alice, "acceptPairInvite", { code: selfInvite.code }),
    "functions/failed-precondition",
  );
  assert.equal(
    (await adminDatabase.doc(`pairMemberships/${alice.uid}`).get()).exists,
    false,
  );

  const inviteDocument = (
    await adminDatabase
      .collection("pairInvites")
      .where("creatorUid", "==", alice.uid)
      .where("status", "==", "OPEN")
      .get()
  ).docs[0];
  await inviteDocument.ref.update({
    expiresAt: Timestamp.fromMillis(Date.now() - 1_000),
  });
  await rejectsWithCode(
    () => call(bob, "acceptPairInvite", { code: selfInvite.code }),
    "functions/deadline-exceeded",
  );
  assert.equal(
    (await adminDatabase.doc(`pairMemberships/${bob.uid}`).get()).exists,
    false,
  );

  const freshInvite = await call(alice, "createPairInvite", {});
  await call(bob, "acceptPairInvite", { code: freshInvite.code });
  await rejectsWithCode(
    () => call(carol, "acceptPairInvite", { code: freshInvite.code }),
    "functions/failed-precondition",
  );
  assert.equal(
    (await adminDatabase.doc(`pairMemberships/${carol.uid}`).get()).exists,
    false,
  );
});

test("unapproved identities cannot create or enter Pair Invites", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const bob = await createGoogleIdentity("Bob Example");
  await rejectsWithCode(
    () => call(alice, "createPairInvite", {}),
    "functions/permission-denied",
  );

  await approve(alice.uid);
  const invite = await call(alice, "createPairInvite", {});
  await rejectsWithCode(
    () => call(bob, "acceptPairInvite", { code: invite.code }),
    "functions/permission-denied",
  );

  await Promise.all([approve(alice.uid, false), approve(bob.uid)]);
  await rejectsWithCode(
    () => call(bob, "acceptPairInvite", { code: invite.code }),
    "functions/permission-denied",
  );
  assert.equal((await adminDatabase.collection("pairs").get()).size, 0);
  assert.equal((await adminDatabase.collection("pairMemberships").get()).size, 0);
});

test("already-paired and third Members cannot join or confirm another Pair", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const bob = await createGoogleIdentity("Bob Example");
  const carol = await createGoogleIdentity("Carol Example");
  await Promise.all([approve(alice.uid), approve(bob.uid), approve(carol.uid)]);
  const aliceInvite = await call(alice, "createPairInvite", {});
  const aliceAndBob = await call(bob, "acceptPairInvite", {
    code: aliceInvite.code,
  });
  await call(alice, "confirmPair", { pairId: aliceAndBob.pairId });
  await call(bob, "confirmPair", { pairId: aliceAndBob.pairId });

  await rejectsWithCode(
    () => call(alice, "createPairInvite", {}),
    "functions/failed-precondition",
  );
  const carolInvite = await call(carol, "createPairInvite", {});
  await rejectsWithCode(
    () => call(alice, "acceptPairInvite", { code: carolInvite.code }),
    "functions/failed-precondition",
  );
  await rejectsWithCode(
    () => call(carol, "confirmPair", { pairId: aliceAndBob.pairId }),
    "functions/failed-precondition",
  );
  assert.deepEqual(await call(carol, "getPairingStatus", {}), {
    state: "UNPAIRED",
  });
  assert.equal((await adminDatabase.collection("pairs").get()).size, 1);
  assert.equal((await adminDatabase.collection("pairMemberships").get()).size, 2);
});

test("Pair Invite creation is bounded by a persisted per-Member rate limit", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  await approve(alice.uid);

  await call(alice, "createPairInvite", {});
  await call(alice, "createPairInvite", {});
  await call(alice, "createPairInvite", {});
  await rejectsWithCode(
    () => call(alice, "createPairInvite", {}),
    "functions/resource-exhausted",
  );

  const rateLimit = await adminDatabase
    .doc(`inviteRateLimits/${alice.uid}`)
    .get();
  assert.equal(rateLimit.data()?.createCount, 3);
  assert.equal(
    (await adminDatabase.collection("pairInvites").where("creatorUid", "==", alice.uid).get())
      .size,
    3,
  );
});

test("simultaneous acceptance gives one inviter exactly one pending Partner", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const bob = await createGoogleIdentity("Bob Example");
  const carol = await createGoogleIdentity("Carol Example");
  await Promise.all([approve(alice.uid), approve(bob.uid), approve(carol.uid)]);
  const invite = await call(alice, "createPairInvite", {});

  const outcomes = await Promise.allSettled([
    call(bob, "acceptPairInvite", { code: invite.code }),
    call(carol, "acceptPairInvite", { code: invite.code }),
  ]);

  assert.equal(outcomes.filter(({ status }) => status === "fulfilled").length, 1);
  const rejection = outcomes.find(({ status }) => status === "rejected");
  assert.equal(rejection?.reason.code, "functions/failed-precondition");
  const pairs = await adminDatabase.collection("pairs").get();
  const memberships = await adminDatabase.collection("pairMemberships").get();
  assert.equal(pairs.size, 1);
  assert.equal(pairs.docs[0].data().members.length, 2);
  assert.equal(memberships.size, 2);
  assert.equal(
    memberships.docs.filter((document) => document.id === alice.uid).length,
    1,
  );
});

test("simultaneous Pair Invites give one accepting Member exactly one pending Partner", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const bob = await createGoogleIdentity("Bob Example");
  const carol = await createGoogleIdentity("Carol Example");
  await Promise.all([approve(alice.uid), approve(bob.uid), approve(carol.uid)]);
  const [aliceInvite, carolInvite] = await Promise.all([
    call(alice, "createPairInvite", {}),
    call(carol, "createPairInvite", {}),
  ]);

  const outcomes = await Promise.allSettled([
    call(bob, "acceptPairInvite", { code: aliceInvite.code }),
    call(bob, "acceptPairInvite", { code: carolInvite.code }),
  ]);

  assert.equal(outcomes.filter(({ status }) => status === "fulfilled").length, 1);
  const rejection = outcomes.find(({ status }) => status === "rejected");
  assert.equal(rejection?.reason.code, "functions/failed-precondition");
  const [pairs, memberships, bobMembership] = await Promise.all([
    adminDatabase.collection("pairs").get(),
    adminDatabase.collection("pairMemberships").get(),
    adminDatabase.doc(`pairMemberships/${bob.uid}`).get(),
  ]);
  assert.equal(pairs.size, 1);
  assert.equal(memberships.size, 2);
  assert.equal(bobMembership.exists, true);
  assert.equal(pairs.docs[0].data().members.length, 2);
});

test("two independent Pairs can activate concurrently without membership crossing", async () => {
  const alice = await createGoogleIdentity("Alice Example");
  const bob = await createGoogleIdentity("Bob Example");
  const carol = await createGoogleIdentity("Carol Example");
  const dave = await createGoogleIdentity("Dave Example");
  await Promise.all(
    [alice, bob, carol, dave].map(({ uid }) => approve(uid)),
  );
  const [aliceInvite, carolInvite] = await Promise.all([
    call(alice, "createPairInvite", {}),
    call(carol, "createPairInvite", {}),
  ]);
  const [aliceAndBob, carolAndDave] = await Promise.all([
    call(bob, "acceptPairInvite", { code: aliceInvite.code }),
    call(dave, "acceptPairInvite", { code: carolInvite.code }),
  ]);

  await Promise.all([
    call(alice, "confirmPair", { pairId: aliceAndBob.pairId }),
    call(bob, "confirmPair", { pairId: aliceAndBob.pairId }),
    call(carol, "confirmPair", { pairId: carolAndDave.pairId }),
    call(dave, "confirmPair", { pairId: carolAndDave.pairId }),
  ]);

  const [pairs, memberships, aliceStatus, carolStatus] = await Promise.all([
    adminDatabase.collection("pairs").where("state", "==", "ACTIVE").get(),
    adminDatabase.collection("pairMemberships").where("state", "==", "ACTIVE").get(),
    call(alice, "getPairingStatus", {}),
    call(carol, "getPairingStatus", {}),
  ]);
  assert.equal(pairs.size, 2);
  assert.equal(memberships.size, 4);
  assert.notEqual(aliceAndBob.pairId, carolAndDave.pairId);
  assert.equal(aliceStatus.partnerIdentity.displayName, "Bob Example");
  assert.equal(carolStatus.partnerIdentity.displayName, "Dave Example");
  assert.deepEqual(
    new Set(pairs.docs.map((pair) => pair.data().members.map(({ uid }) => uid).sort().join("|"))),
    new Set([
      [alice.uid, bob.uid].sort().join("|"),
      [carol.uid, dave.uid].sort().join("|"),
    ]),
  );
});

test("a Partner sees the Google identity even when the Member edits profile claims", async () => {
  const googlePhoto = "https://lh3.googleusercontent.com/a/alice";
  const alice = await createGoogleIdentity("Alice Example", googlePhoto);
  const bob = await createGoogleIdentity("Bob Example");
  await Promise.all([approve(alice.uid), approve(bob.uid)]);

  await updateProfile(alice.user, {
    displayName: "Mallory Edited",
    photoURL: "https://evil.example/tracker.png",
  });
  await alice.user.getIdToken(true);
  const invite = await call(alice, "createPairInvite", {});
  const accepted = await call(bob, "acceptPairInvite", { code: invite.code });

  assert.equal(accepted.partnerIdentity.displayName, "Alice Example");
  assert.equal(accepted.partnerIdentity.photoUrl, googlePhoto);
});

test("a non-Google photo is never returned as Partner identity", async () => {
  const alice = await createGoogleIdentity(
    "Alice Example",
    "https://evil.example/tracker.png",
  );
  const bob = await createGoogleIdentity("Bob Example");
  await Promise.all([approve(alice.uid), approve(bob.uid)]);
  const invite = await call(alice, "createPairInvite", {});
  const accepted = await call(bob, "acceptPairInvite", { code: invite.code });

  assert.deepEqual(accepted.partnerIdentity, {
    displayName: "Alice Example",
    photoUrl: null,
  });
});

async function createGoogleIdentity(displayName, picture = undefined) {
  identitySequence += 1;
  const app = initializeApp(
    {
      apiKey: "demo-api-key",
      authDomain: `${projectId}.firebaseapp.com`,
      projectId,
    },
    `pairing-member-${identitySequence}`,
  );
  clientApps.push(app);

  const auth = getAuth(app);
  connectAuthEmulator(auth, `http://${authHost}`, { disableWarnings: true });
  const credential = await signInWithCredential(
    auth,
    GoogleAuthProvider.credential(
      JSON.stringify({
        email: `member-${identitySequence}@example.test`,
        email_verified: true,
        name: displayName,
        ...(picture === undefined ? {} : { picture }),
        sub: `google-member-${identitySequence}`,
      }),
    ),
  );
  await credential.user.getIdToken(true);

  const functions = getFunctions(app, "us-central1");
  connectFunctionsEmulator(functions, functionsHost, Number(functionsPort));

  return {
    app,
    functions,
    uid: credential.user.uid,
    user: credential.user,
  };
}

async function createPasswordIdentity(displayName) {
  identitySequence += 1;
  const app = initializeApp(
    {
      apiKey: "demo-api-key",
      authDomain: `${projectId}.firebaseapp.com`,
      projectId,
    },
    `pairing-password-member-${identitySequence}`,
  );
  clientApps.push(app);
  const auth = getAuth(app);
  connectAuthEmulator(auth, `http://${authHost}`, { disableWarnings: true });
  const credential = await createUserWithEmailAndPassword(
    auth,
    `password-member-${identitySequence}@example.test`,
    "test-password",
  );
  await updateProfile(credential.user, { displayName });
  await credential.user.getIdToken(true);
  const functions = getFunctions(app, "us-central1");
  connectFunctionsEmulator(functions, functionsHost, Number(functionsPort));
  return { app, functions, uid: credential.user.uid };
}

function createSignedOutClient() {
  identitySequence += 1;
  const app = initializeApp(
    {
      apiKey: "demo-api-key",
      authDomain: `${projectId}.firebaseapp.com`,
      projectId,
    },
    `pairing-signed-out-${identitySequence}`,
  );
  clientApps.push(app);
  const functions = getFunctions(app, "us-central1");
  connectFunctionsEmulator(functions, functionsHost, Number(functionsPort));
  return { app, functions };
}

async function approve(uid, approved = true) {
  await adminDatabase.doc(`memberApprovals/${uid}`).set({ approved });
}

async function call(client, functionName, data) {
  const callable = httpsCallableFromURL(
    client.functions,
    `${pairingBaseUrl}/${functionName}`,
  );
  const result = await callable(data);
  return result.data;
}

async function rejectsWithCode(operation, code) {
  await assert.rejects(operation, (error) => {
    assert.equal(error.code, code);
    return true;
  });
}

async function clearFirestore() {
  const response = await fetch(
    `http://${firestoreHost}/emulator/v1/projects/${projectId}/databases/(default)/documents`,
    { method: "DELETE" },
  );
  assert.equal(response.ok, true, await response.text());
}
