package com.gaylord.duit.platform.firebase

import com.gaylord.duit.domain.pairing.PairInvite
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.pairing.PairingStatus
import com.gaylord.duit.domain.pairing.PartnerIdentity
import com.gaylord.duit.pairing.PairingCallFailure
import com.gaylord.duit.pairing.PairingCallResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FirebaseFunctionsPairingGatewayTest {
    @Test
    fun `create Pair Invite maps the exact callable contract`() =
        runTest {
            val client =
                RecordingPairingCallableClient(
                    PairingCallResult.Success(
                        mapOf(
                            "code" to "7K3M9P2Q",
                            "expiresAtEpochMillis" to 1_786_406_100_000L,
                        ),
                    ),
                )
            val gateway = FirebaseFunctionsPairingGateway(client)

            val result = gateway.createPairInvite()

            assertEquals(
                CallableObservation(
                    result =
                        PairingCallResult.Success(
                            PairInvite("7K3M9P2Q", 1_786_406_100_000L),
                        ),
                    calls = listOf(CallableCall("createPairInvite", emptyMap())),
                ),
                CallableObservation(result, client.calls),
            )
        }

    @Test
    fun `accept Pair Invite sends only the code and returns unconfirmed Partner review`() =
        runTest {
            val client =
                RecordingPairingCallableClient(
                    PairingCallResult.Success(
                        mapOf(
                            "pairId" to "pair-alex-sam",
                            "partnerIdentity" to
                                mapOf(
                                    "displayName" to "Sam Rivera",
                                    "photoUrl" to null,
                                ),
                            "state" to "PENDING_CONFIRMATION",
                        ),
                    ),
                )
            val gateway = FirebaseFunctionsPairingGateway(client)

            val result = gateway.acceptPairInvite("7K3M9P2Q")

            assertEquals(
                CallableObservation(
                    result =
                        PairingCallResult.Success(
                            PairingStatus.PendingConfirmation(
                                pairIdentifier = PairIdentifier("pair-alex-sam"),
                                partnerIdentity = PartnerIdentity("Sam Rivera", null),
                                selfConfirmed = false,
                                partnerConfirmed = false,
                            ),
                        ),
                    calls =
                        listOf(
                            CallableCall(
                                "acceptPairInvite",
                                mapOf("code" to "7K3M9P2Q"),
                            ),
                        ),
                ),
                CallableObservation(result, client.calls),
            )
        }

    @Test
    fun `status and confirmation preserve both confirmation flags and active identity`() =
        runTest {
            val partnerIdentity =
                mapOf(
                    "displayName" to "Sam Rivera",
                    "photoUrl" to "https://lh3.googleusercontent.com/a/sam",
                )
            val client =
                RecordingPairingCallableClient(
                    PairingCallResult.Success(
                        mapOf(
                            "pairId" to "pair-alex-sam",
                            "partnerConfirmed" to true,
                            "partnerIdentity" to partnerIdentity,
                            "selfConfirmed" to false,
                            "state" to "PENDING_CONFIRMATION",
                        ),
                    ),
                    PairingCallResult.Success(
                        mapOf(
                            "pairId" to "pair-alex-sam",
                            "partnerConfirmed" to true,
                            "partnerIdentity" to partnerIdentity,
                            "selfConfirmed" to true,
                            "state" to "ACTIVE",
                        ),
                    ),
                )
            val gateway = FirebaseFunctionsPairingGateway(client)

            val status = gateway.getPairingStatus()
            val confirmation = gateway.confirmPair(PairIdentifier("pair-alex-sam"))

            assertEquals(
                listOf(
                    PairingCallResult.Success(
                        PairingStatus.PendingConfirmation(
                            pairIdentifier = PairIdentifier("pair-alex-sam"),
                            partnerIdentity =
                                PartnerIdentity(
                                    "Sam Rivera",
                                    "https://lh3.googleusercontent.com/a/sam",
                                ),
                            selfConfirmed = false,
                            partnerConfirmed = true,
                        ),
                    ),
                    PairingCallResult.Success(
                        PairingStatus.Active(
                            PairIdentifier("pair-alex-sam"),
                            PartnerIdentity(
                                "Sam Rivera",
                                "https://lh3.googleusercontent.com/a/sam",
                            ),
                        ),
                    ),
                ),
                listOf(status, confirmation),
            )
            assertEquals(
                listOf(
                    CallableCall("getPairingStatus", emptyMap()),
                    CallableCall(
                        "confirmPair",
                        mapOf("pairId" to "pair-alex-sam"),
                    ),
                ),
                client.calls,
            )
        }

    @Test
    fun `callable failures propagate and malformed success stays recoverable`() =
        runTest {
            val connectionFailure =
                PairingCallResult.Failure(PairingCallFailure.ConnectionRequired)
            val client =
                RecordingPairingCallableClient(
                    connectionFailure,
                    PairingCallResult.Success(mapOf("state" to "ACTIVE")),
                )
            val gateway = FirebaseFunctionsPairingGateway(client)

            assertEquals(connectionFailure, gateway.getPairingStatus())
            assertEquals(
                PairingCallResult.Failure(
                    PairingCallFailure.Recoverable(
                        "Duit received an invalid Pairing response.",
                    ),
                ),
                gateway.getPairingStatus(),
            )
        }

    @Test
    fun `transport failures wrapped by the Functions SDK require a connection`() {
        assertEquals(
            PairingCallFailure.ConnectionRequired,
            firebaseFunctionsFailure(
                codeName = "INTERNAL",
                message = "INTERNAL",
                cause = IOException("Unable to resolve host"),
            ),
        )
        assertEquals(
            PairingCallFailure.ConnectionRequired,
            firebaseFunctionsFailure(
                codeName = "DEADLINE_EXCEEDED",
                message = "DEADLINE_EXCEEDED",
                cause = IOException("timeout"),
            ),
        )
    }

    @Test
    fun `server-authored rejections forward their message`() {
        listOf(
            "INVALID_ARGUMENT",
            "FAILED_PRECONDITION",
            "PERMISSION_DENIED",
            "RESOURCE_EXHAUSTED",
            "ALREADY_EXISTS",
            "ABORTED",
            "OUT_OF_RANGE",
        ).forEach { codeName ->
            assertEquals(
                codeName,
                PairingCallFailure.Recoverable("That Pair Invite has expired."),
                firebaseFunctionsFailure(
                    codeName = codeName,
                    message = "That Pair Invite has expired.",
                    cause = null,
                ),
            )
        }
    }

    @Test
    fun `raw SDK failures and an undeployed Pairing backend show no raw text`() {
        listOf("INTERNAL", "UNKNOWN", "UNAUTHENTICATED", "DATA_LOSS", "CANCELLED", "NOT_FOUND").forEach { codeName ->
            assertEquals(
                codeName,
                PairingCallFailure.Recoverable(null),
                firebaseFunctionsFailure(
                    codeName = codeName,
                    message = "raw sdk text",
                    cause = null,
                ),
            )
        }
        assertEquals(
            PairingCallFailure.Recoverable(null),
            IllegalStateException("raw sdk text").toPairingCallFailure(),
        )
    }

    @Test
    fun `only Google-hosted https photos are kept as Partner identity`() =
        runTest {
            val photos =
                listOf(
                    "https://lh3.googleusercontent.com/a/sam" to "https://lh3.googleusercontent.com/a/sam",
                    "https://googleusercontent.com/a/sam" to "https://googleusercontent.com/a/sam",
                    "http://lh3.googleusercontent.com/a/sam" to null,
                    "https://evil.example/sam.png" to null,
                    "https://googleusercontent.com.evil.example/a" to null,
                    "https://notgoogleusercontent.com/a" to null,
                    "not a url" to null,
                )
            val client =
                RecordingPairingCallableClient(
                    *photos
                        .map { (photo, _) ->
                            PairingCallResult.Success(
                                mapOf(
                                    "pairId" to "pair-alex-sam",
                                    "partnerIdentity" to
                                        mapOf("displayName" to "Sam Rivera", "photoUrl" to photo),
                                    "state" to "PENDING_CONFIRMATION",
                                ),
                            )
                        }.toTypedArray(),
                )
            val gateway = FirebaseFunctionsPairingGateway(client)

            val decoded =
                photos.map {
                    val result = gateway.acceptPairInvite("7K3M9P2Q")
                    (result as PairingCallResult.Success).value.partnerIdentity.photoUrl
                }

            assertEquals(photos.map { it.second }, decoded)
        }

    @Test
    fun `callable URLs append each name to the backend URL without doubling slashes`() {
        val base = "https://abcdefghij.supabase.co/functions/v1/pairing"
        listOf(base, "$base/", "$base//").forEach { backendUrl ->
            assertEquals(
                backendUrl,
                listOf(
                    "$base/createPairInvite",
                    "$base/acceptPairInvite",
                    "$base/confirmPair",
                    "$base/getPairingStatus",
                ),
                listOf(
                    "createPairInvite",
                    "acceptPairInvite",
                    "confirmPair",
                    "getPairingStatus",
                ).map { pairingCallableUrl(backendUrl, it) },
            )
        }
    }

    @Test
    fun `an empty backend URL makes every Pairing call an unavailable backend`() =
        runTest {
            val gateway =
                FirebaseFunctionsPairingGateway(
                    pairingCallableClient(backendUrl = "") {
                        error("Firebase Functions must not be created without a backend URL")
                    },
                )
            val unavailable = PairingCallResult.Failure(PairingCallFailure.Recoverable(null))

            assertEquals(
                listOf(unavailable, unavailable, unavailable, unavailable),
                listOf(
                    gateway.getPairingStatus(),
                    gateway.createPairInvite(),
                    gateway.acceptPairInvite("7K3M9P2Q"),
                    gateway.confirmPair(PairIdentifier("pair-alex-sam")),
                ),
            )
        }
}

private data class CallableObservation<out Value>(
    val result: PairingCallResult<Value>,
    val calls: List<CallableCall>,
)

private data class CallableCall(
    val name: String,
    val data: Map<String, Any?>,
)

private class RecordingPairingCallableClient(
    vararg results: PairingCallResult<Any?>,
) : PairingCallableClient {
    private val results = ArrayDeque(results.toList())
    val calls = mutableListOf<CallableCall>()

    override suspend fun call(
        name: String,
        data: Map<String, Any?>,
    ): PairingCallResult<Any?> {
        calls += CallableCall(name, data)
        return results.removeFirst()
    }
}
