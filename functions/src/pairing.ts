import { createHash, randomInt } from "node:crypto";

import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import {
  type CallableRequest,
  type FunctionsErrorCode,
  HttpsError,
  onCall,
} from "firebase-functions/v2/https";

const INVITE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const INVITE_LENGTH = 8;
const INVITE_TTL_MILLIS = 15 * 60_000;
const CREATE_RATE_WINDOW_MILLIS = 15 * 60_000;
const MAX_INVITES_PER_WINDOW = 3;
const ACCEPT_RATE_WINDOW_MILLIS = 5 * 60_000;
const ACCEPT_COOLDOWN_MILLIS = 5 * 60_000;
const MAX_FAILED_ACCEPTS_PER_WINDOW = 5;

interface PairingCallablePolicy {
  enforceAppCheck: boolean;
}

interface MemberIdentity {
  displayName: string;
  photoUrl: string | null;
}

interface AuthenticatedMember {
  uid: string;
  identity: MemberIdentity;
}

interface CreatePairInviteResult {
  code: string;
  expiresAtEpochMillis: number;
}

interface PairingStatusResult {
  pairId: string;
  partnerIdentity: MemberIdentity;
  state: "ACTIVE" | "PENDING_CONFIRMATION";
}

type InviteLookup =
  | {
      creatorIdentity: MemberIdentity;
      creatorUid: string;
      kind: "OPEN";
    }
  | {
      code: FunctionsErrorCode;
      kind: "REJECTED";
      message: string;
    };

type GetPairingStatusResult =
  | { state: "UNPAIRED" }
  | (PairingStatusResult & {
      partnerConfirmed: boolean;
      selfConfirmed: boolean;
    });

export function createPairingCallables(policy: PairingCallablePolicy) {
  const callableOptions = {
    enforceAppCheck: policy.enforceAppCheck,
    region: "us-central1",
  } as const;

  return {
    acceptPairInvite: onCall(callableOptions, acceptPairInvite),
    confirmPair: onCall(callableOptions, confirmPair),
    createPairInvite: onCall(callableOptions, createPairInvite),
    getPairingStatus: onCall(callableOptions, getPairingStatus),
  };
}

async function confirmPair(
  request: CallableRequest<unknown>,
): Promise<
  PairingStatusResult & { partnerConfirmed: boolean; selfConfirmed: boolean }
> {
  const pairId = requirePairIdData(request.data);
  const member = requireAuthenticatedMember(request);
  const database = pairingDatabase();
  const now = Timestamp.now();

  const outcome = await database.runTransaction(async (transaction) => {
    const approvalReference = database.doc(`memberApprovals/${member.uid}`);
    const membershipReference = database.doc(`pairMemberships/${member.uid}`);
    const [approval, membership] = await transaction.getAll(
      approvalReference,
      membershipReference,
    );
    requireApproval(approval.data());
    requireMatchingMembership(membership.data(), pairId);

    const pairReference = database.doc(`pairs/${pairId}`);
    const pair = await transaction.get(pairReference);
    const pairData = pair.data();
    const members = asPairMembers(pairData?.members);
    if (
      (pairData?.state !== "PENDING_CONFIRMATION" &&
        pairData?.state !== "ACTIVE") ||
      members === null
    ) {
      throw new HttpsError("failed-precondition", "The Pair cannot be confirmed.");
    }
    const selfIndex = members.findIndex((candidate) => candidate.uid === member.uid);
    const partnerIndex = members.findIndex(
      (candidate) => candidate.uid !== member.uid,
    );
    if (selfIndex < 0 || partnerIndex < 0) {
      throw new HttpsError("permission-denied", "The Member is not in this Pair.");
    }
    const partner = members[partnerIndex];
    if (partner === undefined) {
      throw new HttpsError("data-loss", "The Pair has no Partner.");
    }
    const partnerApprovalReference = database.doc(
      `memberApprovals/${partner.uid}`,
    );
    const partnerMembershipReference = database.doc(
      `pairMemberships/${partner.uid}`,
    );
    const [partnerApproval, partnerMembership] = await transaction.getAll(
      partnerApprovalReference,
      partnerMembershipReference,
    );

    if (pairData.state === "PENDING_CONFIRMATION") {
      const confirmationExpiresAt = asTimestamp(pairData.confirmationExpiresAt);
      if (
        confirmationExpiresAt === null ||
        confirmationExpiresAt.toMillis() <= now.toMillis()
      ) {
        transaction.update(pairReference, {
          expiredAt: now,
          state: "EXPIRED",
          updatedAt: now,
        });
        transaction.delete(membershipReference);
        if (membershipBelongsToPair(partnerMembership.data(), pairId)) {
          transaction.delete(partnerMembershipReference);
        }
        return { kind: "EXPIRED" as const };
      }
    }

    requireApproval(partnerApproval.data());
    requireMatchingMembership(partnerMembership.data(), pairId);
    if (pairData.state === "ACTIVE") {
      return {
        kind: "STATUS" as const,
        status: pairingStatus(pairId, members[selfIndex]!, partner, "ACTIVE"),
      };
    }

    members[selfIndex] = { ...members[selfIndex]!, confirmed: true };
    const activates = members.every((candidate) => candidate.confirmed);
    const state = activates ? "ACTIVE" : "PENDING_CONFIRMATION";
    transaction.update(pairReference, {
      ...(activates ? { activatedAt: now } : {}),
      members,
      state,
      updatedAt: now,
    });
    if (activates) {
      transaction.update(membershipReference, { state: "ACTIVE", updatedAt: now });
      transaction.update(partnerMembershipReference, {
        state: "ACTIVE",
        updatedAt: now,
      });
    }

    return {
      kind: "STATUS" as const,
      status: pairingStatus(pairId, members[selfIndex]!, partner, state),
    };
  });

  if (outcome.kind === "EXPIRED") {
    throw new HttpsError(
      "deadline-exceeded",
      "The pending Pair confirmation has expired.",
    );
  }
  return outcome.status;
}

async function getPairingStatus(
  request: CallableRequest<unknown>,
): Promise<GetPairingStatusResult> {
  requireEmptyObject(request.data);
  const member = requireAuthenticatedMember(request);
  const database = pairingDatabase();
  const now = Timestamp.now();
  return database.runTransaction(async (transaction) => {
    const approvalReference = database.doc(`memberApprovals/${member.uid}`);
    const membershipReference = database.doc(`pairMemberships/${member.uid}`);
    const [approval, membership] = await transaction.getAll(
      approvalReference,
      membershipReference,
    );
    requireApproval(approval.data());
    if (!membership.exists) {
      return { state: "UNPAIRED" } as const;
    }

    const pairId = normalizedIdentityField(membership.data()?.pairId);
    if (pairId === null) {
      throw new HttpsError("data-loss", "The Pair membership is malformed.");
    }
    requireMatchingMembership(membership.data(), pairId);
    const pairReference = database.doc(`pairs/${pairId}`);
    const pair = await transaction.get(pairReference);
    const pairData = pair.data();
    const members = asPairMembers(pairData?.members);
    if (
      (pairData?.state !== "PENDING_CONFIRMATION" &&
        pairData?.state !== "ACTIVE") ||
      members === null
    ) {
      throw new HttpsError("data-loss", "The Pair is malformed.");
    }
    const self = members.find((candidate) => candidate.uid === member.uid);
    const partner = members.find((candidate) => candidate.uid !== member.uid);
    if (self === undefined || partner === undefined) {
      throw new HttpsError("permission-denied", "The Member is not in this Pair.");
    }

    const partnerApprovalReference = database.doc(
      `memberApprovals/${partner.uid}`,
    );
    const partnerMembershipReference = database.doc(
      `pairMemberships/${partner.uid}`,
    );
    const [partnerApproval, partnerMembership] = await transaction.getAll(
      partnerApprovalReference,
      partnerMembershipReference,
    );

    if (pairData.state === "PENDING_CONFIRMATION") {
      const confirmationExpiresAt = asTimestamp(pairData.confirmationExpiresAt);
      if (confirmationExpiresAt === null) {
        throw new HttpsError("data-loss", "The pending Pair has no expiry.");
      }
      if (confirmationExpiresAt.toMillis() <= now.toMillis()) {
        transaction.update(pairReference, {
          expiredAt: now,
          state: "EXPIRED",
          updatedAt: now,
        });
        transaction.delete(membershipReference);
        if (membershipBelongsToPair(partnerMembership.data(), pairId)) {
          transaction.delete(partnerMembershipReference);
        }
        return { state: "UNPAIRED" } as const;
      }
    }

    requireApproval(partnerApproval.data());
    requireMatchingMembership(partnerMembership.data(), pairId);

    return {
      pairId,
      partnerConfirmed: partner.confirmed,
      partnerIdentity: partner.identity,
      selfConfirmed: self.confirmed,
      state: pairData.state,
    };
  });
}

async function acceptPairInvite(
  request: CallableRequest<unknown>,
): Promise<PairingStatusResult> {
  const acceptingMember = requireAuthenticatedMember(request);
  const database = pairingDatabase();
  const normalizedCode = normalizeInviteCode(extractPairInviteCode(request.data));
  const inviteDocumentId =
    normalizedCode === null ? "invalid-format" : hashInviteCode(normalizedCode);
  const now = Timestamp.now();
  const pairReference = database.collection("pairs").doc();

  const outcome = await database.runTransaction(async (transaction) => {
    const acceptingApprovalReference = database.doc(
      `memberApprovals/${acceptingMember.uid}`,
    );
    const acceptingMembershipReference = database.doc(
      `pairMemberships/${acceptingMember.uid}`,
    );
    const rateLimitReference = database.doc(
      `inviteRateLimits/${acceptingMember.uid}`,
    );
    const inviteReference = database.doc(`pairInvites/${inviteDocumentId}`);
    const [acceptingApproval, acceptingMembership, rateLimit, invite] =
      await transaction.getAll(
        acceptingApprovalReference,
        acceptingMembershipReference,
        rateLimitReference,
        inviteReference,
      );

    requireApproval(acceptingApproval.data());
    requireAcceptRateAvailable(rateLimit.data(), now);
    if (normalizedCode === null) {
      transaction.set(
        rateLimitReference,
        nextFailedAcceptRate(rateLimit.data(), now),
        { merge: true },
      );
      return {
        code: "invalid-argument" as const,
        kind: "REJECTED" as const,
        message: "The Pair Invite code is invalid.",
      };
    }
    requireNoMembership(acceptingMembership.exists);
    const inviteData = inspectOpenInvite(invite.data(), now);
    if (inviteData.kind === "REJECTED") {
      transaction.set(
        rateLimitReference,
        nextFailedAcceptRate(rateLimit.data(), now),
        { merge: true },
      );
      return inviteData;
    }
    if (inviteData.creatorUid === acceptingMember.uid) {
      transaction.set(
        rateLimitReference,
        nextFailedAcceptRate(rateLimit.data(), now),
        { merge: true },
      );
      return {
        code: "failed-precondition" as const,
        kind: "REJECTED" as const,
        message: "A Member cannot accept their own Pair Invite.",
      };
    }

    const creatingApprovalReference = database.doc(
      `memberApprovals/${inviteData.creatorUid}`,
    );
    const creatingMembershipReference = database.doc(
      `pairMemberships/${inviteData.creatorUid}`,
    );
    const [creatingApproval, creatingMembership] = await transaction.getAll(
      creatingApprovalReference,
      creatingMembershipReference,
    );
    requireApproval(creatingApproval.data());
    requireNoMembership(creatingMembership.exists);

    const confirmationExpiresAt = Timestamp.fromMillis(
      now.toMillis() + INVITE_TTL_MILLIS,
    );
    const members = [
      pairMember(inviteData.creatorUid, inviteData.creatorIdentity),
      pairMember(acceptingMember.uid, acceptingMember.identity),
    ];
    transaction.create(pairReference, {
      confirmationExpiresAt,
      createdAt: now,
      members,
      state: "PENDING_CONFIRMATION",
      updatedAt: now,
    });
    transaction.create(
      creatingMembershipReference,
      membershipLock(pairReference.id, acceptingMember.uid, now),
    );
    transaction.create(
      acceptingMembershipReference,
      membershipLock(pairReference.id, inviteData.creatorUid, now),
    );
    transaction.update(inviteReference, {
      consumedAt: now,
      consumedByUid: acceptingMember.uid,
      pairId: pairReference.id,
      status: "CONSUMED",
    });
    transaction.set(
      rateLimitReference,
      {
        acceptBlockedUntil: null,
        acceptFailedCount: 0,
        acceptWindowStartedAt: now,
        updatedAt: now,
      },
      { merge: true },
    );
    return {
      kind: "ACCEPTED" as const,
      partnerIdentity: inviteData.creatorIdentity,
    };
  });

  if (outcome.kind === "REJECTED") {
    throw new HttpsError(outcome.code, outcome.message);
  }
  return {
    pairId: pairReference.id,
    partnerIdentity: outcome.partnerIdentity,
    state: "PENDING_CONFIRMATION",
  };
}

async function createPairInvite(
  request: CallableRequest<unknown>,
): Promise<CreatePairInviteResult> {
  requireEmptyObject(request.data);
  const member = requireAuthenticatedMember(request);
  const database = pairingDatabase();
  const code = generateInviteCode();
  const inviteHash = hashInviteCode(code);
  const now = Timestamp.now();
  const expiresAt = Timestamp.fromMillis(now.toMillis() + INVITE_TTL_MILLIS);

  await database.runTransaction(async (transaction) => {
    const approvalReference = database.doc(`memberApprovals/${member.uid}`);
    const membershipReference = database.doc(`pairMemberships/${member.uid}`);
    const rateLimitReference = database.doc(`inviteRateLimits/${member.uid}`);
    const inviteReference = database.doc(`pairInvites/${inviteHash}`);
    const [approval, membership, rateLimit, invite] = await transaction.getAll(
      approvalReference,
      membershipReference,
      rateLimitReference,
      inviteReference,
    );

    requireApproval(approval.data());
    if (membership.exists) {
      throw new HttpsError(
        "failed-precondition",
        "A Member with Pair membership cannot create a Pair Invite.",
      );
    }
    if (invite.exists) {
      throw new HttpsError("aborted", "Please retry Pair Invite creation.");
    }

    const rate = nextCreateRate(rateLimit.data(), now);
    transaction.set(rateLimitReference, rate, { merge: true });
    transaction.create(inviteReference, {
      creatorIdentity: member.identity,
      creatorUid: member.uid,
      createdAt: now,
      expiresAt,
      status: "OPEN",
    });
  });

  return {
    code,
    expiresAtEpochMillis: expiresAt.toMillis(),
  };
}

function requireAuthenticatedMember(
  request: CallableRequest<unknown>,
): AuthenticatedMember {
  if (request.auth === undefined) {
    throw new HttpsError("unauthenticated", "Sign in to use Pairing.");
  }

  const firebaseClaims = request.auth.token.firebase;
  if (
    firebaseClaims === null ||
    typeof firebaseClaims !== "object" ||
    firebaseClaims.sign_in_provider !== "google.com"
  ) {
    throw new HttpsError(
      "permission-denied",
      "Pairing requires a Google-authenticated identity.",
    );
  }

  const displayName = normalizedIdentityField(request.auth.token.name);
  if (displayName === null) {
    throw new HttpsError(
      "failed-precondition",
      "The signed-in Google identity has no display name.",
    );
  }

  return {
    uid: request.auth.uid,
    identity: {
      displayName,
      photoUrl: normalizedIdentityField(request.auth.token.picture),
    },
  };
}

function requireApproval(data: FirebaseFirestore.DocumentData | undefined): void {
  if (data?.approved !== true) {
    throw new HttpsError(
      "permission-denied",
      "Administrator Member Approval is required.",
    );
  }
}

function requireEmptyObject(data: unknown): void {
  if (
    data === null ||
    typeof data !== "object" ||
    Array.isArray(data) ||
    Object.keys(data).length !== 0
  ) {
    throw new HttpsError("invalid-argument", "Expected an empty request.");
  }
}

function extractPairInviteCode(data: unknown): string | null {
  if (
    data === null ||
    typeof data !== "object" ||
    Array.isArray(data) ||
    Object.keys(data).length !== 1 ||
    typeof (data as Record<string, unknown>).code !== "string"
  ) {
    return null;
  }
  return (data as Record<string, string>).code;
}

function requirePairIdData(data: unknown): string {
  if (
    data === null ||
    typeof data !== "object" ||
    Array.isArray(data) ||
    Object.keys(data).length !== 1
  ) {
    throw new HttpsError("invalid-argument", "A Pair ID is required.");
  }
  const pairId = normalizedIdentityField((data as Record<string, unknown>).pairId);
  if (pairId === null || pairId.includes("/")) {
    throw new HttpsError("invalid-argument", "The Pair ID is invalid.");
  }
  return pairId;
}

function normalizeInviteCode(code: string | null): string | null {
  if (code === null) {
    return null;
  }
  const normalized = code.trim().toUpperCase().replaceAll("-", "");
  if (
    normalized.length !== INVITE_LENGTH ||
    [...normalized].some((character) => !INVITE_ALPHABET.includes(character))
  ) {
    return null;
  }
  return normalized;
}

function inspectOpenInvite(
  data: FirebaseFirestore.DocumentData | undefined,
  now: Timestamp,
): InviteLookup {
  if (data === undefined || data.status !== "OPEN") {
    return {
      code: "failed-precondition",
      kind: "REJECTED",
      message: "The Pair Invite is unavailable or was already used.",
    };
  }
  const expiresAt = asTimestamp(data.expiresAt);
  if (expiresAt === null || expiresAt.toMillis() <= now.toMillis()) {
    return {
      code: "deadline-exceeded",
      kind: "REJECTED",
      message: "The Pair Invite has expired.",
    };
  }
  const creatorUid = normalizedIdentityField(data.creatorUid);
  const creatorIdentity = asMemberIdentity(data.creatorIdentity);
  if (creatorUid === null || creatorIdentity === null) {
    throw new HttpsError("data-loss", "The Pair Invite is malformed.");
  }
  return { creatorIdentity, creatorUid, kind: "OPEN" };
}

function requireNoMembership(exists: boolean): void {
  if (exists) {
    throw new HttpsError(
      "failed-precondition",
      "The Member already has a Pair or pending Pair confirmation.",
    );
  }
}

function requireMatchingMembership(
  data: FirebaseFirestore.DocumentData | undefined,
  pairId: string,
): void {
  if (
    data?.pairId !== pairId ||
    (data?.state !== "PENDING_CONFIRMATION" && data?.state !== "ACTIVE")
  ) {
    throw new HttpsError(
      "failed-precondition",
      "The Member does not have this Pair membership.",
    );
  }
}

function membershipBelongsToPair(
  data: FirebaseFirestore.DocumentData | undefined,
  pairId: string,
): boolean {
  return (
    data?.pairId === pairId &&
    (data?.state === "PENDING_CONFIRMATION" || data?.state === "ACTIVE")
  );
}

function asMemberIdentity(value: unknown): MemberIdentity | null {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const fields = value as Record<string, unknown>;
  const displayName = normalizedIdentityField(fields.displayName);
  const photoUrl =
    fields.photoUrl === null ? null : normalizedIdentityField(fields.photoUrl);
  return displayName === null || (fields.photoUrl !== null && photoUrl === null)
    ? null
    : { displayName, photoUrl };
}

function pairMember(uid: string, identity: MemberIdentity) {
  return {
    confirmed: false,
    identity,
    uid,
  };
}

function asPairMembers(
  value: unknown,
): Array<{ confirmed: boolean; identity: MemberIdentity; uid: string }> | null {
  if (!Array.isArray(value) || value.length !== 2) {
    return null;
  }
  const members = value.map((candidate) => {
    if (candidate === null || typeof candidate !== "object") {
      return null;
    }
    const fields = candidate as Record<string, unknown>;
    const uid = normalizedIdentityField(fields.uid);
    const identity = asMemberIdentity(fields.identity);
    return uid === null || identity === null || typeof fields.confirmed !== "boolean"
      ? null
      : { confirmed: fields.confirmed, identity, uid };
  });
  if (
    members.some((member) => member === null) ||
    members[0]?.uid === members[1]?.uid
  ) {
    return null;
  }
  return members as Array<{
    confirmed: boolean;
    identity: MemberIdentity;
    uid: string;
  }>;
}

function pairingStatus(
  pairId: string,
  self: { confirmed: boolean; identity: MemberIdentity; uid: string },
  partner: { confirmed: boolean; identity: MemberIdentity; uid: string },
  state: "ACTIVE" | "PENDING_CONFIRMATION",
) {
  return {
    pairId,
    partnerConfirmed: partner.confirmed,
    partnerIdentity: partner.identity,
    selfConfirmed: self.confirmed,
    state,
  };
}

function membershipLock(pairId: string, partnerUid: string, now: Timestamp) {
  return {
    createdAt: now,
    pairId,
    partnerUid,
    state: "PENDING_CONFIRMATION",
    updatedAt: now,
  };
}

function normalizedIdentityField(value: unknown): string | null {
  if (typeof value !== "string") {
    return null;
  }
  const normalized = value.trim();
  return normalized.length === 0 ? null : normalized;
}

function generateInviteCode(): string {
  let code = "";
  for (let index = 0; index < INVITE_LENGTH; index += 1) {
    code += INVITE_ALPHABET[randomInt(INVITE_ALPHABET.length)];
  }
  return code;
}

function hashInviteCode(code: string): string {
  return createHash("sha256").update(code, "utf8").digest("hex");
}

function nextCreateRate(
  data: FirebaseFirestore.DocumentData | undefined,
  now: Timestamp,
): FirebaseFirestore.DocumentData {
  const previousWindow = asTimestamp(data?.createWindowStartedAt);
  const withinWindow =
    previousWindow !== null &&
    now.toMillis() - previousWindow.toMillis() < CREATE_RATE_WINDOW_MILLIS;
  const previousCount = withinWindow ? asNonNegativeInteger(data?.createCount) : 0;
  if (previousCount >= MAX_INVITES_PER_WINDOW) {
    throw new HttpsError(
      "resource-exhausted",
      "Too many Pair Invites were created. Try again later.",
    );
  }

  return {
    createCount: previousCount + 1,
    createWindowStartedAt: withinWindow ? previousWindow : now,
    updatedAt: now,
  };
}

function requireAcceptRateAvailable(
  data: FirebaseFirestore.DocumentData | undefined,
  now: Timestamp,
): void {
  const blockedUntil = asTimestamp(data?.acceptBlockedUntil);
  if (blockedUntil !== null && blockedUntil.toMillis() > now.toMillis()) {
    throw new HttpsError(
      "resource-exhausted",
      "Too many Pair Invite attempts. Try again later.",
    );
  }
}

function nextFailedAcceptRate(
  data: FirebaseFirestore.DocumentData | undefined,
  now: Timestamp,
): FirebaseFirestore.DocumentData {
  const previousWindow = asTimestamp(data?.acceptWindowStartedAt);
  const withinWindow =
    previousWindow !== null &&
    now.toMillis() - previousWindow.toMillis() < ACCEPT_RATE_WINDOW_MILLIS;
  const previousCount = withinWindow
    ? asNonNegativeInteger(data?.acceptFailedCount)
    : 0;
  const failedCount = previousCount + 1;
  return {
    acceptBlockedUntil:
      failedCount >= MAX_FAILED_ACCEPTS_PER_WINDOW
        ? Timestamp.fromMillis(now.toMillis() + ACCEPT_COOLDOWN_MILLIS)
        : null,
    acceptFailedCount: failedCount,
    acceptWindowStartedAt: withinWindow ? previousWindow : now,
    updatedAt: now,
  };
}

function asTimestamp(value: unknown): Timestamp | null {
  return value instanceof Timestamp ? value : null;
}

function asNonNegativeInteger(value: unknown): number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0
    ? value
    : 0;
}

function pairingDatabase() {
  if (getApps().length === 0) {
    initializeApp();
  }
  return getFirestore();
}
