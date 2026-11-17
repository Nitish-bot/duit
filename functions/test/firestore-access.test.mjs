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
  deleteDoc,
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

describe("Shared View access", () => {
  const view = (extra = {}) => ({
    ownerUid: "member-a",
    pairId: "pair-1",
    partnerUid: "member-b",
    shareGeneration: "gen-1",
    title: "Water the plants",
    ...extra,
  });
  const viewPath = "pairShares/pair-1/owners/member-a/tasks/task-1";
  const completionPath = `${viewPath}/grants/gen-1/completions/2026-11-17`;

  async function seedPair() {
    await seedApproval("member-a");
    await seedApproval("member-b");
    await seedApproval("member-c");
    await testEnvironment.withSecurityRulesDisabled(async (context) => {
      const database = context.firestore();
      await setDoc(doc(database, "pairMemberships/member-a"), {
        pairId: "pair-1", partnerUid: "member-b", state: "ACTIVE",
      });
      await setDoc(doc(database, "pairMemberships/member-b"), {
        pairId: "pair-1", partnerUid: "member-a", state: "ACTIVE",
      });
    });
  }

  test("the Owner publishes and the current Partner reads, read-only", async () => {
    await seedPair();
    await assertSucceeds(setDoc(doc(memberDatabase("member-a"), viewPath), view()));
    await assertSucceeds(setDoc(doc(memberDatabase("member-a"), completionPath), {
      partnerUid: "member-b", generation: "gen-1", date: "2026-11-17",
    }));

    const partner = memberDatabase("member-b");
    assert.equal((await assertSucceeds(getDoc(doc(partner, viewPath)))).data()?.title, "Water the plants");
    await assertSucceeds(getDoc(doc(partner, completionPath)));
    await assertSucceeds(getDocs(collection(partner, "pairShares/pair-1/owners/member-a/tasks")));
    await assertFails(setDoc(doc(partner, viewPath), view({ title: "Changed" })));
    await assertFails(deleteDoc(doc(partner, viewPath)));
  });

  test("outsiders, other Pairs, and the signed-out cannot read or list", async () => {
    await seedPair();
    await assertSucceeds(setDoc(doc(memberDatabase("member-a"), viewPath), view()));

    for (const database of [
      memberDatabase("member-c"),
      testEnvironment.unauthenticatedContext().firestore(),
    ]) {
      await assertFails(getDoc(doc(database, viewPath)));
      await assertFails(getDocs(collection(database, "pairShares/pair-1/owners/member-a/tasks")));
    }
  });

  test("the Owner cannot publish outside an active Pair or into someone else's path", async () => {
    await seedPair();
    await assertFails(setDoc(doc(memberDatabase("member-c"), viewPath), view({ ownerUid: "member-c" })));
    await assertFails(setDoc(doc(memberDatabase("member-a"), viewPath), view({ partnerUid: "member-c" })));
    await assertFails(setDoc(doc(memberDatabase("member-a"), viewPath), view({ pairId: "pair-2" })));
  });

  test("a former Partner loses access when the Pair ends", async () => {
    await seedPair();
    await assertSucceeds(setDoc(doc(memberDatabase("member-a"), viewPath), view()));
    await testEnvironment.withSecurityRulesDisabled(async (context) => {
      await deleteDoc(doc(context.firestore(), "pairMemberships/member-b"));
    });
    await assertFails(getDoc(doc(memberDatabase("member-b"), viewPath)));
  });

  test("revoking removes access, and a re-share generation hides prior completions", async () => {
    await seedPair();
    const owner = memberDatabase("member-a");
    await assertSucceeds(setDoc(doc(owner, viewPath), view()));
    await assertSucceeds(setDoc(doc(owner, completionPath), { partnerUid: "member-b" }));
    await assertSucceeds(deleteDoc(doc(owner, viewPath)));
    const partner = memberDatabase("member-b");
    assert.equal((await assertSucceeds(getDoc(doc(partner, viewPath)))).exists(), false);

    // Even if an old completion document lingers, the new generation hides it.
    await testEnvironment.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), completionPath), { partnerUid: "member-b" });
    });
    await assertSucceeds(setDoc(doc(owner, viewPath), view({ shareGeneration: "gen-2" })));
    await assertFails(getDoc(doc(partner, completionPath)));
  });

  test("Partners cannot read canonical Owner data", async () => {
    await seedPair();
    await assertSucceeds(setDoc(doc(memberDatabase("member-a"), "users/member-a/tasks/t"), { visibility: "PRIVATE" }));
    await assertFails(getDoc(doc(memberDatabase("member-b"), "users/member-a/tasks/t")));
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
