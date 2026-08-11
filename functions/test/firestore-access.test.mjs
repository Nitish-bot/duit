import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { after, before, beforeEach, describe, test } from "node:test";

import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  collection,
  doc,
  getDoc,
  getDocs,
  setDoc,
} from "firebase/firestore";

const projectId = "demo-duit";
const [firestoreHost = "127.0.0.1", firestorePort = "8080"] =
  (process.env.FIRESTORE_EMULATOR_HOST ?? "127.0.0.1:8080").split(":");
const rules = await readFile(new URL("../../firestore.rules", import.meta.url), "utf8");

let testEnvironment;

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    projectId,
    firestore: {
      host: firestoreHost,
      port: Number(firestorePort),
      rules,
    },
  });
});

beforeEach(async () => {
  await testEnvironment.clearFirestore();
});

after(async () => {
  await testEnvironment.cleanup();
});

describe("Member Approval", () => {
  test("a signed-out client cannot read approval or workspace data", async () => {
    const database = testEnvironment.unauthenticatedContext().firestore();

    await assertFails(getDoc(doc(database, "memberApprovals/member-a")));
    await assertFails(getDoc(doc(database, "users/member-a/workspace/main")));
  });

  test("a signed-in identity can inspect only their own approval", async () => {
    await seedApproval("member-a");
    await seedApproval("member-b");
    const database = memberDatabase("member-a");

    const ownApproval = await assertSucceeds(
      getDoc(doc(database, "memberApprovals/member-a")),
    );

    assert.equal(ownApproval.data()?.approved, true);
    await assertFails(getDoc(doc(database, "memberApprovals/member-b")));
    await assertFails(getDocs(collection(database, "memberApprovals")));
  });

  test("clients cannot create or change Member Approvals", async () => {
    const database = memberDatabase("member-a");

    await assertFails(
      setDoc(doc(database, "memberApprovals/member-a"), { approved: true }),
    );
  });
});

describe("owner workspace isolation", () => {
  test("an unapproved identity cannot read or write their own workspace", async () => {
    const database = memberDatabase("member-a");
    const workspace = doc(database, "users/member-a/workspace/main");

    await assertFails(getDoc(workspace));
    await assertFails(setDoc(workspace, { schemaVersion: 1 }));
  });

  test("an approved Member can read and write only their own workspace", async () => {
    await seedApproval("member-a");
    await seedApproval("member-b");
    const database = memberDatabase("member-a");
    const ownWorkspace = doc(database, "users/member-a/workspace/main");
    const otherWorkspace = doc(database, "users/member-b/workspace/main");

    await assertSucceeds(setDoc(ownWorkspace, { schemaVersion: 1 }));
    const snapshot = await assertSucceeds(getDoc(ownWorkspace));

    assert.equal(snapshot.data()?.schemaVersion, 1);
    await assertFails(getDoc(otherWorkspace));
    await assertFails(setDoc(otherWorkspace, { schemaVersion: 1 }));
  });

  test("another approved Member cannot read a Task without an explicit Share Grant", async () => {
    await seedApproval("member-a");
    await seedApproval("member-b");
    const ownerDatabase = memberDatabase("member-a");
    const otherDatabase = memberDatabase("member-b");
    const taskPath = "users/member-a/tasks/private-task";

    await assertSucceeds(
      setDoc(doc(ownerDatabase, taskPath), {
        ownerUid: "member-a",
        visibility: "PRIVATE",
      }),
    );
    await assertFails(getDoc(doc(otherDatabase, taskPath)));
  });

  test("another approved Member cannot query or deep-link into a private Daily Series", async () => {
    await seedApproval("member-a");
    await seedApproval("member-b");
    const ownerDatabase = memberDatabase("member-a");
    const otherDatabase = memberDatabase("member-b");
    const taskPath = "users/member-a/tasks/private-daily";
    const revisionPath = `${taskPath}/revisions/revision-1`;

    await assertSucceeds(
      setDoc(doc(ownerDatabase, taskPath), {
        kind: "RECURRING_SERIES",
        ownerUid: "member-a",
        visibility: "PRIVATE",
      }),
    );
    await assertSucceeds(
      setDoc(doc(ownerDatabase, revisionPath), {
        recurrenceKind: "DAILY",
        title: "Private Daily Task",
      }),
    );

    await assertFails(
      getDocs(collection(otherDatabase, "users/member-a/tasks")),
    );
    await assertFails(getDoc(doc(otherDatabase, revisionPath)));
  });

  test("a false approval does not unlock the workspace", async () => {
    await seedApproval("member-a", false);
    const database = memberDatabase("member-a");

    await assertFails(getDoc(doc(database, "users/member-a/workspace/main")));
  });
});

function memberDatabase(uid) {
  return testEnvironment.authenticatedContext(uid).firestore();
}

async function seedApproval(uid, approved = true) {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `memberApprovals/${uid}`), { approved });
  });
}
