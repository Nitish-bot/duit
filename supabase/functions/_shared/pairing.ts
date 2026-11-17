import { createHash, randomInt } from "node:crypto";

import { getAuth } from "firebase-admin/auth";
import {
  type DocumentData,
  type DocumentReference,
  getFirestore,
  Timestamp,
  type Transaction,
} from "firebase-admin/firestore";

import { adminApp } from "./admin-app.ts";

const INVITE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const INVITE_LENGTH = 8;
const INVITE_TTL_MILLIS = 15 * 60_000;
const CREATE_RATE_WINDOW_MILLIS = 15 * 60_000;
const MAX_INVITES_PER_WINDOW = 3;
const ACCEPT_RATE_WINDOW_MILLIS = 5 * 60_000;
const ACCEPT_COOLDOWN_MILLIS = 5 * 60_000;
const MAX_FAILED_ACCEPTS_PER_WINDOW = 5;

export type PairingErrorCode =
  | "aborted"
  | "already-exists"
  | "cancelled"
  | "data-loss"
  | "deadline-exceeded"
  | "failed-precondition"
  | "internal"
  | "invalid-argument"
  | "not-found"
  | "out-of-range"
  | "permission-denied"
  | "resource-exhausted"
  | "unauthenticated"
  | "unavailable"
  | "unimplemented"
  | "unknown";

// A rejection the Pairing callable protocol reports to the client with its code.
export class PairingError extends Error {
  constructor(
    readonly code: PairingErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "PairingError";
  }
}

// The verified caller of one Pairing request; `auth` is absent when no ID token
// was sent.
export interface PairingRequest {
  auth:
    | {
        token: { firebase?: { sign_in_provider?: string } | null };
        uid: string;
      }
    | undefined;
  data: unknown;
}

interface GoogleDisplayIdentity {
  displayName: string;
  photoUrl: string | null;
}

interface AuthenticatedGoogleIdentity {
  uid: string;
  displayIdentity: GoogleDisplayIdentity;
}

interface CreatePairInviteResult {
  code: string;
  expiresAtEpochMillis: number;
}

interface PairingStatusResult {
  pairId: string;
  partnerIdentity: GoogleDisplayIdentity;
  state: "ACTIVE" | "PENDING_CONFIRMATION";
}

type InviteLookup =
  | {
      creatorIdentity: GoogleDisplayIdentity;
      creatorUid: string;
      kind: "OPEN";
    }
  | {
      code: PairingErrorCode;
      kind: "REJECTED";
      message: string;
    };

type GetPairingStatusResult =
  | { state: "UNPAIRED" }
  | (PairingStatusResult & {
      partnerConfirmed: boolean;
      selfConfirmed: boolean;
    });

interface PendingPairExpiry {
  memberMembershipReference: DocumentReference;
  now: Timestamp;
  pairId: string;
  pairReference: DocumentReference;
  partnerMembershipData: DocumentData | undefined;
  partnerMembershipReference: DocumentReference;
}

export const pairingHandlers = {
  acceptPairInvite,
  confirmPair,
  createPairInvite,
  getPairingStatus,
};

export type PairingHandlerName = keyof typeof pairingHandlers;

async function confirmPair(
  request: PairingRequest,
): Promise<
  PairingStatusResult & { partnerConfirmed: boolean; selfConfirmed: boolean }
> {
  const pairId = requirePairIdData(request.data);
  const authenticatedIdentity = await requireAuthenticatedGoogleIdentity(request);
  const database = pairingDatabase();
  const now = Timestamp.now();

  const outcome = await database.runTransaction(async (transaction) => {
    const approvalReference = database.doc(
      `memberApprovals/${authenticatedIdentity.uid}`,
    );
    const membershipReference = database.doc(
      `pairMemberships/${authenticatedIdentity.uid}`,
    );
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
      throw new PairingError("failed-precondition", "The Pair cannot be confirmed.");
    }
    const selfIndex = members.findIndex(
      (candidate) => candidate.uid === authenticatedIdentity.uid,
    );
    const partnerIndex = members.findIndex(
      (candidate) => candidate.uid !== authenticatedIdentity.uid,
    );
    if (selfIndex < 0 || partnerIndex < 0) {
      throw new PairingError("permission-denied", "The Member is not in this Pair.");
    }
    const partner = members[partnerIndex];
    if (partner === undefined) {
      throw new PairingError("data-loss", "The Pair has no Partner.");
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
        expirePendingPair(transaction, {
          memberMembershipReference: membershipReference,
          now,
          pairId,
          pairReference,
          partnerMembershipData: partnerMembership.data(),
          partnerMembershipReference,
        });
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
    throw new PairingError(
      "deadline-exceeded",
      "The pending Pair confirmation has expired.",
    );
  }
  return outcome.status;
}

async function getPairingStatus(
  request: PairingRequest,
): Promise<GetPairingStatusResult> {
  requireEmptyObject(request.data);
  const authenticatedIdentity = await requireAuthenticatedGoogleIdentity(request);
  const database = pairingDatabase();
  const now = Timestamp.now();
  return database.runTransaction(async (transaction) => {
    const approvalReference = database.doc(
      `memberApprovals/${authenticatedIdentity.uid}`,
    );
    const membershipReference = database.doc(
      `pairMemberships/${authenticatedIdentity.uid}`,
    );
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
      throw new PairingError("data-loss", "The Pair membership is malformed.");
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
      throw new PairingError("data-loss", "The Pair is malformed.");
    }
    const self = members.find(
      (candidate) => candidate.uid === authenticatedIdentity.uid,
    );
    const partner = members.find(
      (candidate) => candidate.uid !== authenticatedIdentity.uid,
    );
    if (self === undefined || partner === undefined) {
      throw new PairingError("permission-denied", "The Member is not in this Pair.");
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
        throw new PairingError("data-loss", "The pending Pair has no expiry.");
      }
      if (confirmationExpiresAt.toMillis() <= now.toMillis()) {
        expirePendingPair(transaction, {
          memberMembershipReference: membershipReference,
          now,
          pairId,
          pairReference,
          partnerMembershipData: partnerMembership.data(),
          partnerMembershipReference,
        });
        return { state: "UNPAIRED" } as const;
      }
    }

    requireApproval(partnerApproval.data());
    requireMatchingMembership(partnerMembership.data(), pairId);

    return {
      pairId,
      partnerConfirmed: partner.confirmed,
      partnerIdentity: partner.identity,
      ...(pairData.state === "ACTIVE" ? { partnerUid: partner.uid } : {}),
      selfConfirmed: self.confirmed,
      state: pairData.state,
    };
  });
}

async function acceptPairInvite(
  request: PairingRequest,
): Promise<PairingStatusResult> {
  const acceptingIdentity = await requireAuthenticatedGoogleIdentity(request);
  const database = pairingDatabase();
  const normalizedCode = normalizeInviteCode(extractPairInviteCode(request.data));
  const inviteDocumentId =
    normalizedCode === null ? "invalid-format" : hashInviteCode(normalizedCode);
  const now = Timestamp.now();
  const pairReference = database.collection("pairs").doc();

  const outcome = await database.runTransaction(async (transaction) => {
    const acceptingApprovalReference = database.doc(
      `memberApprovals/${acceptingIdentity.uid}`,
    );
    const acceptingMembershipReference = database.doc(
      `pairMemberships/${acceptingIdentity.uid}`,
    );
    const rateLimitReference = database.doc(
      `inviteRateLimits/${acceptingIdentity.uid}`,
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
    if (inviteData.creatorUid === acceptingIdentity.uid) {
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
      pairMember(acceptingIdentity.uid, acceptingIdentity.displayIdentity),
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
      membershipLock(pairReference.id, acceptingIdentity.uid, now),
    );
    transaction.create(
      acceptingMembershipReference,
      membershipLock(pairReference.id, inviteData.creatorUid, now),
    );
    transaction.update(inviteReference, {
      consumedAt: now,
      consumedByUid: acceptingIdentity.uid,
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
    throw new PairingError(outcome.code, outcome.message);
  }
  return {
    pairId: pairReference.id,
    partnerIdentity: outcome.partnerIdentity,
    state: "PENDING_CONFIRMATION",
  };
}

async function createPairInvite(
  request: PairingRequest,
): Promise<CreatePairInviteResult> {
  requireEmptyObject(request.data);
  const authenticatedIdentity = await requireAuthenticatedGoogleIdentity(request);
  const database = pairingDatabase();
  const code = generateInviteCode();
  const inviteHash = hashInviteCode(code);
  const now = Timestamp.now();
  const expiresAt = Timestamp.fromMillis(now.toMillis() + INVITE_TTL_MILLIS);

  await database.runTransaction(async (transaction) => {
    const approvalReference = database.doc(
      `memberApprovals/${authenticatedIdentity.uid}`,
    );
    const membershipReference = database.doc(
      `pairMemberships/${authenticatedIdentity.uid}`,
    );
    const rateLimitReference = database.doc(
      `inviteRateLimits/${authenticatedIdentity.uid}`,
    );
    const inviteReference = database.doc(`pairInvites/${inviteHash}`);
    const [approval, membership, rateLimit, invite] = await transaction.getAll(
      approvalReference,
      membershipReference,
      rateLimitReference,
      inviteReference,
    );

    requireApproval(approval.data());
    if (membership.exists) {
      throw new PairingError(
        "failed-precondition",
        "A Member with Pair membership cannot create a Pair Invite.",
      );
    }
    if (invite.exists) {
      throw new PairingError("aborted", "Please retry Pair Invite creation.");
    }

    const rate = nextCreateRate(rateLimit.data(), now);
    transaction.set(rateLimitReference, rate, { merge: true });
    transaction.create(inviteReference, {
      creatorIdentity: authenticatedIdentity.displayIdentity,
      creatorUid: authenticatedIdentity.uid,
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

async function requireAuthenticatedGoogleIdentity(
  request: PairingRequest,
): Promise<AuthenticatedGoogleIdentity> {
  if (request.auth === undefined) {
    throw new PairingError("unauthenticated", "Sign in to use Pairing.");
  }

  const firebaseClaims = request.auth.token.firebase;
  if (
    firebaseClaims === null ||
    typeof firebaseClaims !== "object" ||
    firebaseClaims.sign_in_provider !== "google.com"
  ) {
    throw new PairingError(
      "permission-denied",
      "Pairing requires a Google-authenticated identity.",
    );
  }

  // Token claims `name` and `picture` are editable by the Member through
  // updateProfile, so the Partner identity comes from the Google provider entry.
  const userRecord = await getAuth(adminApp()).getUser(request.auth.uid);
  const googleProvider = userRecord.providerData.find(
    (provider) => provider.providerId === "google.com",
  );
  if (googleProvider === undefined) {
    throw new PairingError(
      "permission-denied",
      "Pairing requires a Google-authenticated identity.",
    );
  }

  const displayName = normalizedIdentityField(googleProvider.displayName);
  if (displayName === null) {
    throw new PairingError(
      "failed-precondition",
      "The signed-in Google identity has no display name.",
    );
  }

  return {
    uid: request.auth.uid,
    displayIdentity: {
      displayName,
      photoUrl: googlePhotoUrl(googleProvider.photoURL),
    },
  };
}

function requireApproval(data: FirebaseFirestore.DocumentData | undefined): void {
  if (data?.approved !== true) {
    throw new PairingError(
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
    throw new PairingError("invalid-argument", "Expected an empty request.");
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
    throw new PairingError("invalid-argument", "A Pair ID is required.");
  }
  const pairId = normalizedIdentityField((data as Record<string, unknown>).pairId);
  if (pairId === null || pairId.includes("/")) {
    throw new PairingError("invalid-argument", "The Pair ID is invalid.");
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
  const creatorIdentity = asGoogleDisplayIdentity(data.creatorIdentity);
  if (creatorUid === null || creatorIdentity === null) {
    throw new PairingError("data-loss", "The Pair Invite is malformed.");
  }
  return { creatorIdentity, creatorUid, kind: "OPEN" };
}

function requireNoMembership(exists: boolean): void {
  if (exists) {
    throw new PairingError(
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
    throw new PairingError(
      "failed-precondition",
      "The Member does not have this Pair membership.",
    );
  }
}

function expirePendingPair(
  transaction: Transaction,
  expiry: PendingPairExpiry,
): void {
  transaction.update(expiry.pairReference, {
    expiredAt: expiry.now,
    state: "EXPIRED",
    updatedAt: expiry.now,
  });
  transaction.delete(expiry.memberMembershipReference);
  if (membershipBelongsToPair(expiry.partnerMembershipData, expiry.pairId)) {
    transaction.delete(expiry.partnerMembershipReference);
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

function asGoogleDisplayIdentity(value: unknown): GoogleDisplayIdentity | null {
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

function pairMember(uid: string, identity: GoogleDisplayIdentity) {
  return {
    confirmed: false,
    identity,
    uid,
  };
}

function asPairMembers(
  value: unknown,
): Array<{
  confirmed: boolean;
  identity: GoogleDisplayIdentity;
  uid: string;
}> | null {
  if (!Array.isArray(value) || value.length !== 2) {
    return null;
  }
  const members = value.map((candidate) => {
    if (candidate === null || typeof candidate !== "object") {
      return null;
    }
    const fields = candidate as Record<string, unknown>;
    const uid = normalizedIdentityField(fields.uid);
    const identity = asGoogleDisplayIdentity(fields.identity);
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
    identity: GoogleDisplayIdentity;
    uid: string;
  }>;
}

function pairingStatus(
  pairId: string,
  self: { confirmed: boolean; identity: GoogleDisplayIdentity; uid: string },
  partner: { confirmed: boolean; identity: GoogleDisplayIdentity; uid: string },
  state: "ACTIVE" | "PENDING_CONFIRMATION",
) {
  return {
    pairId,
    partnerConfirmed: partner.confirmed,
    partnerIdentity: partner.identity,
    // Only an active Pair needs the Partner's Member identifier: the Owner names it when
    // publishing a Shared View so Firestore Rules can bind the grant to the current Pair.
    ...(state === "ACTIVE" ? { partnerUid: partner.uid } : {}),
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

function googlePhotoUrl(value: unknown): string | null {
  const candidate = normalizedIdentityField(value);
  if (candidate === null) {
    return null;
  }
  try {
    const url = new URL(candidate);
    const host = url.hostname.toLowerCase();
    const isGoogleHost =
      host === "googleusercontent.com" || host.endsWith(".googleusercontent.com");
    return url.protocol === "https:" && isGoogleHost ? candidate : null;
  } catch {
    return null;
  }
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
    throw new PairingError(
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
    throw new PairingError(
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
  return getFirestore(adminApp());
}
