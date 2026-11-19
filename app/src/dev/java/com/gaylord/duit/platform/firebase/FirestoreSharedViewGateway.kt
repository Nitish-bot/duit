package com.gaylord.duit.platform.firebase

import android.util.Log
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.ShareGeneration
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.pairing.PairIdentifier
import com.gaylord.duit.domain.sharing.PartnerSharedDailyTask
import com.gaylord.duit.domain.sharing.SharedDailyView
import com.gaylord.duit.domain.sharing.SharedViewReference
import com.gaylord.duit.sharing.SharedViewGateway
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.time.LocalDate
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Development Firestore store for Shared Views under `pairShares/{pairId}/owners/{ownerUid}`.
 *
 * Writes are handed to Firestore's offline queue and deliberately not awaited: a Shared-to-Private
 * change must take effect for the Owner immediately, and the queued delete reaches the server when
 * the device reconnects. Firestore applies one client's writes in order, so a publish followed by
 * completions followed by a revoke stays consistent.
 */
internal class FirestoreSharedViewGateway(
    private val firestoreProvider: () -> FirebaseFirestore,
    private val writeFailureReporter: (String, Exception) -> Unit =
        { operation, failure ->
            Log.e(LogTag, "Firestore rejected Shared View $operation", failure)
        },
) : SharedViewGateway {
    override suspend fun publish(view: SharedDailyView) {
        val reference = view.reference
        enqueue("publish", firestoreProvider().task(reference).set(SharedViewCodec.encode(view)))
    }

    override suspend fun revoke(reference: SharedViewReference) {
        val firestore = firestoreProvider()
        val task = firestore.task(reference)
        val completions = task.completions(reference.shareGeneration)
        // The task document is what grants read access, so it goes first and unconditionally.
        enqueue("revoke", task.delete())
        // Completions are unreadable once the task document is gone; removing them is best effort
        // from whatever the local cache holds.
        try {
            completions.get(Source.CACHE).await().documents.forEach { document ->
                enqueue("revoke completion", document.reference.delete())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            writeFailureReporter("revoke completions", failure)
        }
    }

    override suspend fun setCompletedDate(
        reference: SharedViewReference,
        date: LocalDate,
    ) {
        enqueue(
            "completion",
            firestoreProvider()
                .task(reference)
                .completions(reference.shareGeneration)
                .document(date.toString())
                .set(SharedViewCodec.encodeCompletion(reference, date)),
        )
    }

    override suspend fun clearCompletedDate(
        reference: SharedViewReference,
        date: LocalDate,
    ) {
        enqueue(
            "completion removal",
            firestoreProvider()
                .task(reference)
                .completions(reference.shareGeneration)
                .document(date.toString())
                .delete(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePartnerSharedViews(
        pairIdentifier: PairIdentifier,
        partnerIdentifier: MemberIdentifier,
    ): Flow<List<PartnerSharedDailyTask>> =
        documents(
            firestoreProvider()
                .collection(PairSharesCollection)
                .document(pairIdentifier.value)
                .collection(OwnersCollection)
                .document(partnerIdentifier.value)
                .collection(TasksCollection),
        ).flatMapLatest { snapshots ->
            val views =
                snapshots.mapNotNull { snapshot ->
                    runCatching { SharedViewCodec.decode(snapshot) }.getOrNull()
                }
            if (views.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    views.map { view ->
                        documents(
                            snapshots
                                .first { it.id == view.reference.taskIdentifier.value }
                                .reference
                                .completions(view.reference.shareGeneration),
                        ).map { completions ->
                            PartnerSharedDailyTask(
                                view = view,
                                completedDates =
                                    completions
                                        .mapNotNull { runCatching { LocalDate.parse(it.id) }.getOrNull() }
                                        .toSet(),
                            )
                        }
                    },
                ) { tasks -> tasks.toList() }
            }
            // A revoked, expired, or not-yet-granted read is an empty Together, never an error.
        }.catch { emit(emptyList()) }
            .map { tasks -> tasks.sortedBy { it.view.title.lowercase() } }

    private fun documents(collection: CollectionReference): Flow<List<DocumentSnapshot>> =
        callbackFlow {
            val registration =
                collection.addSnapshotListener { snapshot, error ->
                    when {
                        error != null -> close(error)
                        snapshot != null -> trySend(snapshot.documents)
                    }
                }
            awaitClose { registration.remove() }
        }

    private fun enqueue(
        operation: String,
        write: Task<Void>,
    ) {
        write.addOnFailureListener { failure -> writeFailureReporter(operation, failure) }
    }
}

private fun FirebaseFirestore.task(reference: SharedViewReference): DocumentReference =
    collection(PairSharesCollection)
        .document(reference.pairIdentifier.value)
        .collection(OwnersCollection)
        .document(reference.ownerIdentifier.value)
        .collection(TasksCollection)
        .document(reference.taskIdentifier.value)

private fun DocumentReference.completions(generation: ShareGeneration): CollectionReference =
    collection(GrantsCollection)
        .document(generation.value)
        .collection(CompletionsCollection)

/** Only the redacted fields below ever reach the server. */
internal object SharedViewCodec {
    fun encode(view: SharedDailyView): Map<String, Any> {
        val reference = view.reference
        val fields =
            mutableMapOf<String, Any>(
                TaskIdentifierField to reference.taskIdentifier.value,
                OwnerIdentifierField to reference.ownerIdentifier.value,
                PairIdentifierField to reference.pairIdentifier.value,
                PartnerIdentifierField to reference.partnerIdentifier.value,
                ShareGenerationField to reference.shareGeneration.value,
                TitleField to view.title,
                SubtaskLabelsField to view.subtaskLabels,
                EveryDaysField to view.everyDays.toLong(),
                StartsOnField to view.startsOn.toString(),
                SharedOnField to view.sharedOn.toString(),
            )
        view.endsOnInclusive?.let { fields[EndsOnInclusiveField] = it.toString() }
        return fields
    }

    fun encodeCompletion(
        reference: SharedViewReference,
        date: LocalDate,
    ): Map<String, Any> =
        mapOf(
            PartnerIdentifierField to reference.partnerIdentifier.value,
            ShareGenerationField to reference.shareGeneration.value,
            DateField to date.toString(),
        )

    fun decode(snapshot: DocumentSnapshot): SharedDailyView = decode(snapshot.id, snapshot.data ?: error("Missing Shared View"))

    fun decode(
        documentId: String,
        fields: Map<String, Any?>,
    ): SharedDailyView {
        val taskIdentifier = fields.string(TaskIdentifierField)
        require(taskIdentifier == documentId) { "Shared View does not match its path" }
        return SharedDailyView(
            reference =
                SharedViewReference(
                    ownerIdentifier = MemberIdentifier(fields.string(OwnerIdentifierField)),
                    pairIdentifier = PairIdentifier(fields.string(PairIdentifierField)),
                    partnerIdentifier = MemberIdentifier(fields.string(PartnerIdentifierField)),
                    taskIdentifier = TaskIdentifier(taskIdentifier),
                    shareGeneration = ShareGeneration(fields.string(ShareGenerationField)),
                ),
            title = fields.string(TitleField),
            subtaskLabels =
                (fields[SubtaskLabelsField] as? List<*>)
                    ?.map { it as? String ?: error("Subtask label must be a String") }
                    .orEmpty(),
            everyDays = (fields[EveryDaysField] as? Number)?.toInt() ?: error("Missing interval"),
            startsOn = LocalDate.parse(fields.string(StartsOnField)),
            endsOnInclusive = (fields[EndsOnInclusiveField] as? String)?.let(LocalDate::parse),
            sharedOn = LocalDate.parse(fields.string(SharedOnField)),
        )
    }

    private fun Map<String, Any?>.string(field: String): String =
        (this[field] as? String)?.takeIf(String::isNotBlank)
            ?: error("Shared View field '$field' must be a non-blank String")
}

private const val PairSharesCollection = "pairShares"
private const val OwnersCollection = "owners"
private const val TasksCollection = "tasks"
private const val GrantsCollection = "grants"
private const val CompletionsCollection = "completions"
private const val TaskIdentifierField = "taskId"
private const val OwnerIdentifierField = "ownerUid"
private const val PairIdentifierField = "pairId"
private const val PartnerIdentifierField = "partnerUid"
private const val ShareGenerationField = "shareGeneration"
private const val TitleField = "title"
private const val SubtaskLabelsField = "subtaskLabels"
private const val EveryDaysField = "everyDays"
private const val StartsOnField = "startsOn"
private const val EndsOnInclusiveField = "endsOnInclusive"
private const val SharedOnField = "sharedOn"
private const val DateField = "date"
private const val LogTag = "DuitSharedViews"
