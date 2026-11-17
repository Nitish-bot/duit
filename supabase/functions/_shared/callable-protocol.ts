import { getAppCheck } from "firebase-admin/app-check";
import { getAuth } from "firebase-admin/auth";

import { adminApp } from "./admin-app.ts";
import {
  PairingError,
  type PairingErrorCode,
  type PairingHandlerName,
  pairingHandlers,
  type PairingRequest,
} from "./pairing.ts";

// Implements the Firebase callable wire protocol (POST {data}, Bearer ID token,
// X-Firebase-AppCheck) so the Firebase client SDK can call this handler through
// getHttpsCallableFromUrl / httpsCallableFromURL unchanged.

export interface CallableVerifiers {
  verifyAppCheck(token: string): Promise<unknown>;
  verifyIdToken(
    token: string,
  ): Promise<NonNullable<PairingRequest["auth"]>["token"] & { uid: string }>;
}

export interface CallablePolicy {
  enforceAppCheck: boolean;
}

export type PairingRequestHandler = (request: Request) => Promise<Response>;

// Status strings and HTTP statuses of the callable protocol.
const PROTOCOL_ERRORS: Record<PairingErrorCode, { http: number; status: string }> = {
  aborted: { http: 409, status: "ABORTED" },
  "already-exists": { http: 409, status: "ALREADY_EXISTS" },
  cancelled: { http: 499, status: "CANCELLED" },
  "data-loss": { http: 500, status: "DATA_LOSS" },
  "deadline-exceeded": { http: 504, status: "DEADLINE_EXCEEDED" },
  "failed-precondition": { http: 400, status: "FAILED_PRECONDITION" },
  internal: { http: 500, status: "INTERNAL" },
  "invalid-argument": { http: 400, status: "INVALID_ARGUMENT" },
  "not-found": { http: 404, status: "NOT_FOUND" },
  "out-of-range": { http: 400, status: "OUT_OF_RANGE" },
  "permission-denied": { http: 403, status: "PERMISSION_DENIED" },
  "resource-exhausted": { http: 429, status: "RESOURCE_EXHAUSTED" },
  unauthenticated: { http: 401, status: "UNAUTHENTICATED" },
  unavailable: { http: 503, status: "UNAVAILABLE" },
  unimplemented: { http: 501, status: "UNIMPLEMENTED" },
  unknown: { http: 500, status: "UNKNOWN" },
};

export function firebaseCallableVerifiers(): CallableVerifiers {
  return {
    verifyAppCheck: (token) => getAppCheck(adminApp()).verifyToken(token),
    verifyIdToken: (token) => getAuth(adminApp()).verifyIdToken(token),
  };
}

export function createPairingRequestHandler(
  policy: CallablePolicy,
  verifiers: CallableVerifiers = firebaseCallableVerifiers(),
): PairingRequestHandler {
  return async (request) => {
    try {
      return await handle(request, policy, verifiers);
    } catch (error) {
      if (error instanceof PairingError) {
        return errorResponse(error.code, error.message);
      }
      // Never leak internal detail to the caller, and never let a rejection
      // escape: an unhandled rejection kills the edge worker.
      console.error("Pairing request failed", error);
      return errorResponse("internal", "INTERNAL");
    }
  };
}

async function handle(
  request: Request,
  policy: CallablePolicy,
  verifiers: CallableVerifiers,
): Promise<Response> {
  const handlerName = routeName(request);
  if (request.method !== "POST") {
    return jsonResponse(405, {
      error: { message: "Only POST is supported.", status: "INVALID_ARGUMENT" },
    });
  }
  if (handlerName === null) {
    return errorResponse("not-found", "Unknown Pairing operation.");
  }

  if (policy.enforceAppCheck) {
    const appCheckToken = request.headers.get("X-Firebase-AppCheck");
    if (appCheckToken === null || appCheckToken === "") {
      throw new PairingError("unauthenticated", "Unauthenticated");
    }
    try {
      await verifiers.verifyAppCheck(appCheckToken);
    } catch {
      // Any failure means the token is not trustworthy; never inspect its text.
      throw new PairingError("unauthenticated", "Unauthenticated");
    }
  }

  const auth = await verifyAuthorization(
    request.headers.get("Authorization"),
    verifiers,
  );
  const data = await readData(request);
  const result = await pairingHandlers[handlerName]({ auth, data });
  return jsonResponse(200, { result });
}

async function verifyAuthorization(
  header: string | null,
  verifiers: CallableVerifiers,
): Promise<PairingRequest["auth"]> {
  if (header === null) {
    return undefined;
  }
  const match = /^Bearer (.+)$/.exec(header);
  if (match === null || match[1] === undefined) {
    throw new PairingError("unauthenticated", "Unauthenticated");
  }
  try {
    const decoded = await verifiers.verifyIdToken(match[1]);
    return { token: decoded, uid: decoded.uid };
  } catch {
    throw new PairingError("unauthenticated", "Unauthenticated");
  }
}

async function readData(request: Request): Promise<unknown> {
  if (!(request.headers.get("Content-Type") ?? "").startsWith("application/json")) {
    throw new PairingError("invalid-argument", "Expected a JSON request.");
  }
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    throw new PairingError("invalid-argument", "Expected a JSON request.");
  }
  if (body === null || typeof body !== "object" || Array.isArray(body)) {
    throw new PairingError("invalid-argument", "Expected a JSON object.");
  }
  return (body as Record<string, unknown>).data;
}

function routeName(request: Request): PairingHandlerName | null {
  const segments = new URL(request.url).pathname.split("/").filter(Boolean);
  const name = segments[segments.length - 1];
  return name !== undefined && Object.hasOwn(pairingHandlers, name)
    ? (name as PairingHandlerName)
    : null;
}

function errorResponse(code: PairingErrorCode, message: string): Response {
  const { http, status } = PROTOCOL_ERRORS[code];
  return jsonResponse(http, { error: { message, status } });
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    headers: { "Content-Type": "application/json" },
    status,
  });
}
