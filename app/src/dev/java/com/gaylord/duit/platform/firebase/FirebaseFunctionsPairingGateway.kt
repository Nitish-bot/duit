package com.gaylord.duit.platform.firebase

import com.gaylord.duit.domain.pairing.PairInvite
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.pairing.PairingStatus
import com.gaylord.duit.domain.pairing.PartnerIdentity
import com.gaylord.duit.pairing.PairingCallFailure
import com.gaylord.duit.pairing.PairingCallResult
import com.gaylord.duit.pairing.PairingGateway
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.IOException
import java.net.URI
import java.util.concurrent.CancellationException
import kotlinx.coroutines.tasks.await

const val PairingFunctionsRegion = "us-central1"

/**
 * Pairing's development callable adapter.
 *
 * The Firebase Functions client is injected so application composition must derive it from the
 * named `duit-development` FirebaseApp. This adapter never looks up the default FirebaseApp.
 */
class FirebaseFunctionsPairingGateway internal constructor(
    private val client: PairingCallableClient,
) : PairingGateway {
    constructor(functions: FirebaseFunctions) : this(
        FirebaseSdkPairingCallableClient(functions),
    )

    override suspend fun getPairingStatus(): PairingCallResult<PairingStatus> =
        call(
            name = GetPairingStatusCallable,
            data = emptyMap(),
            decode = ::decodePairingStatus,
        )

    override suspend fun createPairInvite(): PairingCallResult<PairInvite> =
        call(
            name = CreatePairInviteCallable,
            data = emptyMap(),
            decode = ::decodePairInvite,
        )

    override suspend fun acceptPairInvite(
        code: String,
    ): PairingCallResult<PairingStatus.PendingConfirmation> =
        call(
            name = AcceptPairInviteCallable,
            data = mapOf("code" to code),
            decode = ::decodeAcceptedPair,
        )

    override suspend fun confirmPair(
        pairIdentifier: PairIdentifier,
    ): PairingCallResult<PairingStatus> =
        call(
            name = ConfirmPairCallable,
            data = mapOf("pairId" to pairIdentifier.value),
            decode = { response ->
                decodePairingStatus(response).also { status ->
                    if (status == PairingStatus.Unpaired) invalidPairingResponse()
                }
            },
        )

    private suspend fun <Value> call(
        name: String,
        data: Map<String, Any?>,
        decode: (Any?) -> Value,
    ): PairingCallResult<Value> =
        when (val response = client.call(name, data)) {
            is PairingCallResult.Failure -> response
            is PairingCallResult.Success ->
                try {
                    PairingCallResult.Success(decode(response.value))
                } catch (_: InvalidPairingResponseException) {
                    PairingCallResult.Failure(
                        PairingCallFailure.Recoverable(InvalidPairingResponseMessage),
                    )
                }
        }
}

internal fun interface PairingCallableClient {
    suspend fun call(
        name: String,
        data: Map<String, Any?>,
    ): PairingCallResult<Any?>
}

private class FirebaseSdkPairingCallableClient(
    private val functions: FirebaseFunctions,
) : PairingCallableClient {
    override suspend fun call(
        name: String,
        data: Map<String, Any?>,
    ): PairingCallResult<Any?> =
        try {
            PairingCallResult.Success(
                functions.getHttpsCallable(name).call(data).await().data,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PairingCallResult.Failure(error.toPairingCallFailure())
        }
}

internal fun Exception.toPairingCallFailure(): PairingCallFailure {
    if (this is FirebaseNetworkException) return PairingCallFailure.ConnectionRequired
    if (this is FirebaseFunctionsException) {
        return firebaseFunctionsFailure(
            codeName = code.name,
            message = message,
            cause = cause,
        )
    }

    return if (causeSequence().any { cause -> cause is IOException }) {
        PairingCallFailure.ConnectionRequired
    } else {
        PairingCallFailure.Recoverable(null)
    }
}

internal fun firebaseFunctionsFailure(
    codeName: String,
    message: String?,
    cause: Throwable?,
): PairingCallFailure {
    if (codeName == UnavailableFunctionCodeName) {
        return PairingCallFailure.ConnectionRequired
    }

    // The Android Functions SDK wraps OkHttp transport failures in INTERNAL or
    // DEADLINE_EXCEEDED. Preserve real callable/server failures as recoverable, but surface an
    // IOException-backed failure as the Together-specific offline state.
    if (cause?.causeSequence()?.any { nestedCause -> nestedCause is IOException } == true) {
        return PairingCallFailure.ConnectionRequired
    }

    // Only server-authored callable rejections carry user-presentable text. Raw SDK text from
    // INTERNAL, UNKNOWN, UNAUTHENTICATED and other codes falls back to the screen's own copy.
    return PairingCallFailure.Recoverable(
        message.takeIf { codeName in ServerAuthoredFunctionCodeNames },
    )
}

private fun Throwable.causeSequence(): Sequence<Throwable> =
    generateSequence(this) { cause -> cause.cause?.takeUnless { it === cause } }

private fun decodePairInvite(response: Any?): PairInvite {
    val fields = response.fields()
    return PairInvite(
        code = fields.nonBlankString("code"),
        expiresAtEpochMillis = fields.epochMillis("expiresAtEpochMillis"),
    )
}

private fun decodeAcceptedPair(response: Any?): PairingStatus.PendingConfirmation {
    val fields = response.fields()
    if (fields["state"] != "PENDING_CONFIRMATION") invalidPairingResponse()
    return PairingStatus.PendingConfirmation(
        pairIdentifier = PairIdentifier(fields.nonBlankString("pairId")),
        partnerIdentity = fields.partnerIdentity(),
        selfConfirmed = false,
        partnerConfirmed = false,
    )
}

private fun decodePairingStatus(response: Any?): PairingStatus {
    val fields = response.fields()
    return when (fields["state"]) {
        "UNPAIRED" -> PairingStatus.Unpaired
        "PENDING_CONFIRMATION" ->
            PairingStatus.PendingConfirmation(
                pairIdentifier = PairIdentifier(fields.nonBlankString("pairId")),
                partnerIdentity = fields.partnerIdentity(),
                selfConfirmed = fields.boolean("selfConfirmed"),
                partnerConfirmed = fields.boolean("partnerConfirmed"),
            )
        "ACTIVE" ->
            PairingStatus.Active(
                pairIdentifier = PairIdentifier(fields.nonBlankString("pairId")),
                partnerIdentity = fields.partnerIdentity(),
            )
        else -> invalidPairingResponse()
    }
}

private fun Map<*, *>.partnerIdentity(): PartnerIdentity {
    val fields = this["partnerIdentity"].fields()
    val rawPhotoUrl = fields["photoUrl"]
    val photoUrl =
        when (rawPhotoUrl) {
            null -> null
            is String -> rawPhotoUrl.trim().takeIf(String::isNotEmpty)?.takeIf(::isGooglePhotoUrl)
            else -> invalidPairingResponse()
        }
    return PartnerIdentity(
        displayName = fields.nonBlankString("displayName"),
        photoUrl = photoUrl,
    )
}

private fun isGooglePhotoUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        (host == GooglePhotoHost || host.endsWith(".$GooglePhotoHost"))
}

private fun Any?.fields(): Map<*, *> = this as? Map<*, *> ?: invalidPairingResponse()

private fun Map<*, *>.nonBlankString(name: String): String =
    (this[name] as? String)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: invalidPairingResponse()

private fun Map<*, *>.epochMillis(name: String): Long {
    val value = (this[name] as? Number)?.toDouble() ?: invalidPairingResponse()
    val epochMillis = value.toLong()
    if (!value.isFinite() || value != epochMillis.toDouble() || epochMillis <= 0) {
        invalidPairingResponse()
    }
    return epochMillis
}

private fun Map<*, *>.boolean(name: String): Boolean =
    this[name] as? Boolean ?: invalidPairingResponse()

private fun invalidPairingResponse(): Nothing = throw InvalidPairingResponseException()

private class InvalidPairingResponseException : IllegalArgumentException()

private const val CreatePairInviteCallable = "createPairInvite"
private const val AcceptPairInviteCallable = "acceptPairInvite"
private const val ConfirmPairCallable = "confirmPair"
private const val GetPairingStatusCallable = "getPairingStatus"
private const val GooglePhotoHost = "googleusercontent.com"
private val ServerAuthoredFunctionCodeNames =
    setOf(
        "INVALID_ARGUMENT",
        "FAILED_PRECONDITION",
        "PERMISSION_DENIED",
        "RESOURCE_EXHAUSTED",
        "ALREADY_EXISTS",
        "NOT_FOUND",
        "ABORTED",
        "OUT_OF_RANGE",
    )
private const val UnavailableFunctionCodeName = "UNAVAILABLE"
private const val InvalidPairingResponseMessage =
    "Duit received an invalid Pairing response."
