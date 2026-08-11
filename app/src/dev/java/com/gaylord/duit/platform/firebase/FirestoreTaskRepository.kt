package com.gaylord.duit.platform.firebase

import android.util.Log
import com.gaylord.duit.data.repository.TaskRepository
import com.gaylord.duit.domain.model.MemberActionMoment
import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.domain.model.RecurrenceRule
import com.gaylord.duit.domain.model.RecurringSeries
import com.gaylord.duit.domain.model.RecurringSeriesCreation
import com.gaylord.duit.domain.model.SeriesRevision
import com.gaylord.duit.domain.model.SeriesRevisionIdentifier
import com.gaylord.duit.domain.model.StandaloneTask
import com.gaylord.duit.domain.model.StandaloneTaskCreation
import com.gaylord.duit.domain.model.SubtaskIdentifier
import com.gaylord.duit.domain.model.SubtaskTemplate
import com.gaylord.duit.domain.model.TaskIdentifier
import com.gaylord.duit.domain.model.TaskReminderConfiguration
import com.gaylord.duit.domain.model.TaskRevision
import com.gaylord.duit.domain.model.TaskRevisionIdentifier
import com.gaylord.duit.domain.model.TaskVisibility
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.tasks.await

/**
 * Development Firestore persistence for the first standalone-Task slice.
 *
 * The provider is deliberately evaluated when each operation or listener starts. Sign-out can
 * therefore stop listeners, clear the old Firestore instance's persistence, and install a fresh
 * instance without retaining the previous Member's cache through this repository.
 */
internal class FirestoreTaskRepository(
    private val firestoreProvider: () -> FirebaseFirestore,
    private val writeFailureReporter: (TaskIdentifier, Exception) -> Unit =
        { taskIdentifier, failure ->
            Log.e(
                FirestoreTaskRepositoryLogTag,
                "Firestore rejected Task ${taskIdentifier.value}",
                failure,
            )
        },
) : TaskRepository {
    override suspend fun putStandaloneTaskIfAbsent(
        creation: StandaloneTaskCreation,
    ): StandaloneTaskCreation {
        val firestore = firestoreProvider()
        val paths = FirestoreTaskPaths(firestore, creation.task.ownerIdentifier)
        val taskReference = paths.task(creation.task.identifier)

        readCachedCreationOrNull(
            paths = paths,
            taskReference = taskReference,
        )?.let { return it }

        val documents = FirestoreTaskCodec.encode(creation)
        val writeBatch = firestore.batch()
        writeBatch.set(taskReference, documents.task)
        writeBatch.set(
            paths.revision(
                taskIdentifier = creation.task.identifier,
                revisionIdentifier = creation.initialRevision.identifier,
            ),
            documents.revision,
        )
        documents.reminder?.let { reminder ->
            writeBatch.set(
                paths.reminder(creation.task.identifier),
                reminder,
            )
        }
        val commit = writeBatch.commit()

        return awaitLocallyVisibleWrite(
            commit = commit,
            taskIdentifier = creation.task.identifier,
            observeValue = {
                observeStandaloneTask(
                    ownerIdentifier = creation.task.ownerIdentifier,
                    taskIdentifier = creation.task.identifier,
                )
            },
        )
    }

    private suspend fun <Value : Any> awaitLocallyVisibleWrite(
        commit: com.google.android.gms.tasks.Task<Void>,
        taskIdentifier: TaskIdentifier,
        observeValue: () -> Flow<Value?>,
    ): Value =
        coroutineScope {
            val rejection = CompletableDeferred<Exception>()
            commit.addOnFailureListener { failure ->
                writeFailureReporter(taskIdentifier, failure)
                rejection.complete(failure)
            }
            val localValue =
                async(start = CoroutineStart.UNDISPATCHED) {
                    observeValue().filterNotNull().first()
                }

            try {
                select {
                    localValue.onAwait { it }
                    rejection.onAwait { throw it }
                }
            } finally {
                localValue.cancel()
            }
        }

    override suspend fun putRecurringSeriesIfAbsent(
        creation: RecurringSeriesCreation,
    ): RecurringSeriesCreation {
        val firestore = firestoreProvider()
        val paths = FirestoreTaskPaths(firestore, creation.series.ownerIdentifier)
        val taskReference = paths.task(creation.series.identifier)

        readCachedRecurringSeriesOrNull(
            paths = paths,
            taskReference = taskReference,
        )?.let { return it }

        val documents = FirestoreRecurringSeriesCodec.encode(creation)
        val writeBatch = firestore.batch()
        writeBatch.set(taskReference, documents.task)
        writeBatch.set(
            paths.seriesRevision(
                taskIdentifier = creation.series.identifier,
                revisionIdentifier = creation.initialRevision.identifier,
            ),
            documents.revision,
        )
        val commit = writeBatch.commit()

        return awaitLocallyVisibleWrite(
            commit = commit,
            taskIdentifier = creation.series.identifier,
            observeValue = {
                observeRecurringSeries(
                    ownerIdentifier = creation.series.ownerIdentifier,
                    taskIdentifier = creation.series.identifier,
                )
            },
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeStandaloneTasks(
        ownerIdentifier: MemberIdentifier,
        plannedDate: LocalDate,
    ): Flow<List<StandaloneTaskCreation>> =
        callbackFlow {
            val firestore = firestoreProvider()
            val paths = FirestoreTaskPaths(firestore, ownerIdentifier)
            val query =
                paths.tasks
                    .whereEqualTo(TaskKindField, StandaloneTaskKind)
                    .whereEqualTo(PlannedDateField, plannedDate.toString())
            val registration =
                query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    when {
                        error != null -> close(error)
                        snapshot != null -> trySend(TaskQuerySnapshot(paths, snapshot))
                    }
                }

            awaitClose { registration.remove() }
        }.mapLatest { (paths, snapshot) ->
            snapshot.documents
                .map { task ->
                    readCreation(
                        paths = paths,
                        taskSnapshot = task,
                        childSource = Source.CACHE,
                    )
                }.sortedWith(
                    compareBy<StandaloneTaskCreation>(
                        { it.task.createdAt.instant },
                        { it.task.identifier.value },
                    ),
                )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeStandaloneTask(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<StandaloneTaskCreation?> =
        callbackFlow {
            val firestore = firestoreProvider()
            val paths = FirestoreTaskPaths(firestore, ownerIdentifier)
            val registration =
                paths.task(taskIdentifier)
                    .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                        when {
                            error != null -> close(error)
                            snapshot != null -> trySend(TaskDocumentSnapshot(paths, snapshot))
                        }
                    }

            awaitClose { registration.remove() }
        }.mapLatest { (paths, snapshot) ->
            if (snapshot.exists() && snapshot.getString(TaskKindField) == StandaloneTaskKind) {
                readCreation(
                    paths = paths,
                    taskSnapshot = snapshot,
                    childSource = Source.CACHE,
                )
            } else {
                null
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
    ): Flow<List<RecurringSeriesCreation>> =
        callbackFlow {
            val firestore = firestoreProvider()
            val paths = FirestoreTaskPaths(firestore, ownerIdentifier)
            val query = paths.tasks.whereEqualTo(TaskKindField, RecurringSeriesTaskKind)
            val registration =
                query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    when {
                        error != null -> close(error)
                        snapshot != null -> trySend(TaskQuerySnapshot(paths, snapshot))
                    }
                }

            awaitClose { registration.remove() }
        }.mapLatest { (paths, snapshot) ->
            snapshot.documents
                .map { task ->
                    readRecurringSeries(
                        paths = paths,
                        taskSnapshot = task,
                        childSource = Source.CACHE,
                    )
                }.sortedWith(
                    compareBy<RecurringSeriesCreation>(
                        { it.series.createdAt.instant },
                        { it.series.identifier.value },
                    ),
                )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRecurringSeries(
        ownerIdentifier: MemberIdentifier,
        taskIdentifier: TaskIdentifier,
    ): Flow<RecurringSeriesCreation?> =
        callbackFlow {
            val firestore = firestoreProvider()
            val paths = FirestoreTaskPaths(firestore, ownerIdentifier)
            val registration =
                paths.task(taskIdentifier)
                    .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                        when {
                            error != null -> close(error)
                            snapshot != null -> trySend(TaskDocumentSnapshot(paths, snapshot))
                        }
                    }

            awaitClose { registration.remove() }
        }.mapLatest { (paths, snapshot) ->
            if (snapshot.exists() && snapshot.getString(TaskKindField) == RecurringSeriesTaskKind) {
                readRecurringSeries(
                    paths = paths,
                    taskSnapshot = snapshot,
                    childSource = Source.CACHE,
                )
            } else {
                null
            }
        }

    private suspend fun readCachedCreationOrNull(
        paths: FirestoreTaskPaths,
        taskReference: DocumentReference,
    ): StandaloneTaskCreation? {
        val snapshot =
            try {
                taskReference.get(Source.CACHE).await()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: FirebaseFirestoreException) {
                if (exception.code == FirebaseFirestoreException.Code.UNAVAILABLE) return null
                throw exception
            }

        if (!snapshot.exists()) return null
        check(snapshot.getString(TaskKindField) == StandaloneTaskKind) {
            "Task identity ${snapshot.id} already belongs to another Task kind"
        }
        return readCreation(
            paths = paths,
            taskSnapshot = snapshot,
            childSource = Source.CACHE,
        )
    }

    private suspend fun readCachedRecurringSeriesOrNull(
        paths: FirestoreTaskPaths,
        taskReference: DocumentReference,
    ): RecurringSeriesCreation? {
        val snapshot =
            try {
                taskReference.get(Source.CACHE).await()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: FirebaseFirestoreException) {
                if (exception.code == FirebaseFirestoreException.Code.UNAVAILABLE) return null
                throw exception
            }

        if (!snapshot.exists()) return null
        check(snapshot.getString(TaskKindField) == RecurringSeriesTaskKind) {
            "Task identity ${snapshot.id} already belongs to another Task kind"
        }
        return readRecurringSeries(
            paths = paths,
            taskSnapshot = snapshot,
            childSource = Source.CACHE,
        )
    }

    private suspend fun readCreation(
        paths: FirestoreTaskPaths,
        taskSnapshot: DocumentSnapshot,
        childSource: Source,
    ): StandaloneTaskCreation {
        val taskFields = taskSnapshot.requiredFields()
        val taskIdentifier = TaskIdentifier(taskFields.requiredString(TaskIdentifierField))
        check(taskSnapshot.id == taskIdentifier.value) {
            "Task document identity does not match its Firestore path"
        }
        check(taskFields.requiredString(OwnerIdentifierField) == paths.ownerIdentifier.value) {
            "Task owner does not match its Firestore owner path"
        }
        val revisionIdentifier =
            TaskRevisionIdentifier(taskFields.requiredString(ActiveRevisionIdentifierField))
        val revisionSnapshot =
            paths.revision(taskIdentifier, revisionIdentifier)
                .get(childSource)
                .await()
        check(revisionSnapshot.exists()) {
            "Task ${taskIdentifier.value} is missing its active revision"
        }

        val reminderFields =
            if (taskFields.requiredBoolean(HasReminderField)) {
                paths.reminder(taskIdentifier)
                    .get(childSource)
                    .await()
                    .also { reminder ->
                        check(reminder.exists()) {
                            "Task ${taskIdentifier.value} declares a missing reminder"
                        }
                    }.requiredFields()
            } else {
                null
            }

        return FirestoreTaskCodec.decode(
            task = taskFields,
            revision = revisionSnapshot.requiredFields(),
            reminder = reminderFields,
        )
    }

    private suspend fun readRecurringSeries(
        paths: FirestoreTaskPaths,
        taskSnapshot: DocumentSnapshot,
        childSource: Source,
    ): RecurringSeriesCreation {
        val taskFields = taskSnapshot.requiredFields()
        val taskIdentifier = TaskIdentifier(taskFields.requiredString(TaskIdentifierField))
        check(taskSnapshot.id == taskIdentifier.value) {
            "Task document identity does not match its Firestore path"
        }
        check(taskFields.requiredString(OwnerIdentifierField) == paths.ownerIdentifier.value) {
            "Task owner does not match its Firestore owner path"
        }
        val revisionIdentifier =
            SeriesRevisionIdentifier(taskFields.requiredString(ActiveRevisionIdentifierField))
        val revisionSnapshot =
            paths.seriesRevision(taskIdentifier, revisionIdentifier)
                .get(childSource)
                .await()
        check(revisionSnapshot.exists()) {
            "Recurring Series ${taskIdentifier.value} is missing its active revision"
        }

        return FirestoreRecurringSeriesCodec.decode(
            task = taskFields,
            revision = revisionSnapshot.requiredFields(),
        )
    }
}

private data class TaskQuerySnapshot(
    val paths: FirestoreTaskPaths,
    val snapshot: QuerySnapshot,
)

private data class TaskDocumentSnapshot(
    val paths: FirestoreTaskPaths,
    val snapshot: DocumentSnapshot,
)

private class FirestoreTaskPaths(
    firestore: FirebaseFirestore,
    val ownerIdentifier: MemberIdentifier,
) {
    private val owner =
        firestore.collection(UsersCollection)
            .document(ownerIdentifier.value)

    val tasks = owner.collection(TasksCollection)

    fun task(taskIdentifier: TaskIdentifier): DocumentReference =
        tasks.document(taskIdentifier.value)

    fun revision(
        taskIdentifier: TaskIdentifier,
        revisionIdentifier: TaskRevisionIdentifier,
    ): DocumentReference =
        task(taskIdentifier)
            .collection(RevisionsCollection)
            .document(revisionIdentifier.value)

    fun seriesRevision(
        taskIdentifier: TaskIdentifier,
        revisionIdentifier: SeriesRevisionIdentifier,
    ): DocumentReference =
        task(taskIdentifier)
            .collection(RevisionsCollection)
            .document(revisionIdentifier.value)

    fun reminder(taskIdentifier: TaskIdentifier): DocumentReference =
        owner.collection(TaskRemindersCollection)
            .document(taskIdentifier.value)
}

internal data class FirestoreStandaloneTaskDocuments(
    val task: Map<String, Any>,
    val revision: Map<String, Any>,
    val reminder: Map<String, Any>?,
)

internal data class FirestoreRecurringSeriesDocuments(
    val task: Map<String, Any>,
    val revision: Map<String, Any>,
)

internal object FirestoreRecurringSeriesCodec {
    fun encode(creation: RecurringSeriesCreation): FirestoreRecurringSeriesDocuments {
        require(creation.series.visibility == TaskVisibility.Private) {
            "The recurring Series slice persists Private Tasks only"
        }
        val daily = creation.initialRevision.recurrenceRule as? RecurrenceRule.Daily
            ?: error("Unsupported Recurrence Rule")
        val revision =
            mutableMapOf<String, Any>(
                SchemaVersionField to CurrentSchemaVersion,
                RevisionIdentifierField to creation.initialRevision.identifier.value,
                TaskIdentifierField to creation.initialRevision.taskIdentifier.value,
                TitleField to creation.initialRevision.title,
                SubtaskTemplateField to
                    creation.initialRevision.subtaskTemplate.map { subtask ->
                        mapOf(
                            SubtaskIdentifierField to subtask.identifier.value,
                            SubtaskLabelField to subtask.label,
                        )
                    },
                RecurrenceKindField to DailyRecurrenceKind,
                EveryDaysField to daily.everyDays.toLong(),
                StartsOnField to creation.initialRevision.startsOn.toString(),
                RecordedAtInstantMillisField to
                    creation.initialRevision.recordedAt.instant.toEpochMilli(),
                RecordedAtLocalDateField to
                    creation.initialRevision.recordedAt.localDate.toString(),
                RecordedAtZoneIdentifierField to creation.initialRevision.recordedAt.zoneId.id,
            )
        creation.initialRevision.endsOnInclusive?.let { endDate ->
            revision[EndsOnInclusiveField] = endDate.toString()
        }

        return FirestoreRecurringSeriesDocuments(
            task =
                mapOf(
                    SchemaVersionField to CurrentSchemaVersion,
                    TaskKindField to RecurringSeriesTaskKind,
                    TaskIdentifierField to creation.series.identifier.value,
                    OwnerIdentifierField to creation.series.ownerIdentifier.value,
                    ActiveRevisionIdentifierField to
                        creation.series.activeRevisionIdentifier.value,
                    VisibilityField to PrivateVisibility,
                    CreatedAtInstantMillisField to creation.series.createdAt.instant.toEpochMilli(),
                    CreatedAtLocalDateField to creation.series.createdAt.localDate.toString(),
                    CreatedAtZoneIdentifierField to creation.series.createdAt.zoneId.id,
                ),
            revision = revision,
        )
    }

    fun decode(
        task: Map<String, Any>,
        revision: Map<String, Any>,
    ): RecurringSeriesCreation {
        task.requireCurrentSchema()
        revision.requireCurrentSchema()
        require(task.requiredString(TaskKindField) == RecurringSeriesTaskKind) {
            "Unsupported Task kind"
        }
        require(task.requiredString(VisibilityField) == PrivateVisibility) {
            "Unsupported Task visibility"
        }
        require(revision.requiredString(RecurrenceKindField) == DailyRecurrenceKind) {
            "Unsupported Recurrence Rule"
        }

        val taskIdentifier = TaskIdentifier(task.requiredString(TaskIdentifierField))
        val revisionIdentifier =
            SeriesRevisionIdentifier(revision.requiredString(RevisionIdentifierField))
        require(task.requiredString(ActiveRevisionIdentifierField) == revisionIdentifier.value) {
            "Task active revision does not match the loaded revision"
        }
        require(taskIdentifier.value == revision.requiredString(TaskIdentifierField)) {
            "Revision belongs to another Task"
        }
        val everyDays = revision.requiredLong(EveryDaysField)
        require(everyDays <= Int.MAX_VALUE) {
            "Firestore Task field '$EveryDaysField' is too large"
        }

        return RecurringSeriesCreation(
            series =
                RecurringSeries(
                    identifier = taskIdentifier,
                    ownerIdentifier =
                        MemberIdentifier(task.requiredString(OwnerIdentifierField)),
                    activeRevisionIdentifier = revisionIdentifier,
                    visibility = TaskVisibility.Private,
                    createdAt =
                        MemberActionMoment(
                            instant =
                                Instant.ofEpochMilli(
                                    task.requiredLong(CreatedAtInstantMillisField),
                                ),
                            localDate =
                                LocalDate.parse(task.requiredString(CreatedAtLocalDateField)),
                            zoneId =
                                ZoneId.of(task.requiredString(CreatedAtZoneIdentifierField)),
                        ),
                ),
            initialRevision =
                SeriesRevision(
                    identifier = revisionIdentifier,
                    taskIdentifier = taskIdentifier,
                    title = revision.requiredString(TitleField),
                    subtaskTemplate =
                        revision.requiredMapList(SubtaskTemplateField).map { subtask ->
                            SubtaskTemplate(
                                identifier =
                                    SubtaskIdentifier(
                                        subtask.requiredString(SubtaskIdentifierField),
                                    ),
                                label = subtask.requiredString(SubtaskLabelField),
                            )
                        },
                    recurrenceRule = RecurrenceRule.Daily(everyDays.toInt()),
                    startsOn = LocalDate.parse(revision.requiredString(StartsOnField)),
                    endsOnInclusive =
                        revision.optionalString(EndsOnInclusiveField)?.let(LocalDate::parse),
                    recordedAt =
                        MemberActionMoment(
                            instant =
                                Instant.ofEpochMilli(
                                    revision.requiredLong(RecordedAtInstantMillisField),
                                ),
                            localDate =
                                LocalDate.parse(
                                    revision.requiredString(RecordedAtLocalDateField),
                                ),
                            zoneId =
                                ZoneId.of(
                                    revision.requiredString(RecordedAtZoneIdentifierField),
                                ),
                        ),
                ),
        )
    }
}

internal object FirestoreTaskCodec {
    fun encode(creation: StandaloneTaskCreation): FirestoreStandaloneTaskDocuments {
        require(creation.task.visibility == TaskVisibility.Private) {
            "The standalone Task slice persists Private Tasks only"
        }

        return FirestoreStandaloneTaskDocuments(
            task =
                mapOf(
                    SchemaVersionField to CurrentSchemaVersion,
                    TaskKindField to StandaloneTaskKind,
                    TaskIdentifierField to creation.task.identifier.value,
                    OwnerIdentifierField to creation.task.ownerIdentifier.value,
                    ActiveRevisionIdentifierField to creation.task.activeRevisionIdentifier.value,
                    VisibilityField to PrivateVisibility,
                    CreatedAtInstantMillisField to creation.task.createdAt.instant.toEpochMilli(),
                    CreatedAtLocalDateField to creation.task.createdAt.localDate.toString(),
                    CreatedAtZoneIdentifierField to creation.task.createdAt.zoneId.id,
                    PlannedDateField to creation.initialRevision.plannedDate.toString(),
                    HasReminderField to (creation.reminder != null),
                ),
            revision =
                mapOf(
                    SchemaVersionField to CurrentSchemaVersion,
                    RevisionIdentifierField to creation.initialRevision.identifier.value,
                    TaskIdentifierField to creation.initialRevision.taskIdentifier.value,
                    TitleField to creation.initialRevision.title,
                    PlannedDateField to creation.initialRevision.plannedDate.toString(),
                    EffectiveDateField to creation.initialRevision.effectiveDate.toString(),
                    RecordedAtInstantMillisField to
                        creation.initialRevision.recordedAt.instant.toEpochMilli(),
                    RecordedAtLocalDateField to
                        creation.initialRevision.recordedAt.localDate.toString(),
                    RecordedAtZoneIdentifierField to
                        creation.initialRevision.recordedAt.zoneId.id,
                ),
            reminder =
                creation.reminder?.let { reminder ->
                    mapOf(
                        SchemaVersionField to CurrentSchemaVersion,
                        TaskIdentifierField to reminder.taskIdentifier.value,
                        ReminderDateField to reminder.date.toString(),
                        ReminderLocalTimeField to reminder.time.toString(),
                        ReminderZoneIdentifierField to reminder.zoneId.id,
                    )
                },
        )
    }

    fun decode(
        task: Map<String, Any>,
        revision: Map<String, Any>,
        reminder: Map<String, Any>?,
    ): StandaloneTaskCreation {
        task.requireCurrentSchema()
        revision.requireCurrentSchema()
        reminder?.requireCurrentSchema()
        require(task.requiredString(TaskKindField) == StandaloneTaskKind) {
            "Unsupported Task kind"
        }
        require(task.requiredString(VisibilityField) == PrivateVisibility) {
            "Unsupported Task visibility"
        }

        val taskIdentifier = TaskIdentifier(task.requiredString(TaskIdentifierField))
        val revisionIdentifier =
            TaskRevisionIdentifier(revision.requiredString(RevisionIdentifierField))
        val plannedDate = LocalDate.parse(revision.requiredString(PlannedDateField))
        require(task.requiredString(ActiveRevisionIdentifierField) == revisionIdentifier.value) {
            "Task active revision does not match the loaded revision"
        }
        require(taskIdentifier.value == revision.requiredString(TaskIdentifierField)) {
            "Revision belongs to another Task"
        }
        require(task.requiredString(PlannedDateField) == plannedDate.toString()) {
            "Task planned-date index does not match its active revision"
        }
        require(task.requiredBoolean(HasReminderField) == (reminder != null)) {
            "Task reminder declaration does not match its reminder document"
        }

        val decodedReminder =
            reminder?.let { fields ->
                require(taskIdentifier.value == fields.requiredString(TaskIdentifierField)) {
                    "Reminder belongs to another Task"
                }
                TaskReminderConfiguration(
                    taskIdentifier = taskIdentifier,
                    date = LocalDate.parse(fields.requiredString(ReminderDateField)),
                    time = LocalTime.parse(fields.requiredString(ReminderLocalTimeField)),
                    zoneId = ZoneId.of(fields.requiredString(ReminderZoneIdentifierField)),
                )
            }

        return StandaloneTaskCreation(
            task =
                StandaloneTask(
                    identifier = taskIdentifier,
                    ownerIdentifier = MemberIdentifier(task.requiredString(OwnerIdentifierField)),
                    activeRevisionIdentifier =
                        TaskRevisionIdentifier(
                            task.requiredString(ActiveRevisionIdentifierField),
                        ),
                    visibility = TaskVisibility.Private,
                    createdAt =
                        MemberActionMoment(
                            instant =
                                Instant.ofEpochMilli(
                                    task.requiredLong(CreatedAtInstantMillisField),
                                ),
                            localDate =
                                LocalDate.parse(task.requiredString(CreatedAtLocalDateField)),
                            zoneId =
                                ZoneId.of(task.requiredString(CreatedAtZoneIdentifierField)),
                        ),
                ),
            initialRevision =
                TaskRevision(
                    identifier = revisionIdentifier,
                    taskIdentifier = taskIdentifier,
                    title = revision.requiredString(TitleField),
                    plannedDate = plannedDate,
                    effectiveDate = LocalDate.parse(revision.requiredString(EffectiveDateField)),
                    recordedAt =
                        MemberActionMoment(
                            instant =
                                Instant.ofEpochMilli(
                                    revision.requiredLong(RecordedAtInstantMillisField),
                                ),
                            localDate =
                                LocalDate.parse(
                                    revision.requiredString(RecordedAtLocalDateField),
                                ),
                            zoneId =
                                ZoneId.of(
                                    revision.requiredString(RecordedAtZoneIdentifierField),
                                ),
                        ),
                ),
            reminder = decodedReminder,
        )
    }
}

private fun DocumentSnapshot.requiredFields(): Map<String, Any> =
    data ?: error("Firestore document $id has no fields")

private fun Map<String, Any>.requireCurrentSchema() {
    require(requiredLong(SchemaVersionField) == CurrentSchemaVersion) {
        "Unsupported Firestore Task schema version"
    }
}

private fun Map<String, Any>.requiredString(field: String): String =
    (this[field] as? String)
        ?.takeIf(String::isNotBlank)
        ?: error("Firestore Task field '$field' must be a non-blank String")

private fun Map<String, Any>.optionalString(field: String): String? =
    when (val value = this[field]) {
        null -> null
        is String ->
            value.takeIf(String::isNotBlank)
                ?: error("Firestore Task field '$field' must be a non-blank String")
        else -> error("Firestore Task field '$field' must be a String")
    }

private fun Map<String, Any>.requiredMapList(field: String): List<Map<String, Any>> =
    (this[field] as? List<*>)
        ?.map { value ->
            val map = value as? Map<*, *>
                ?: error("Firestore Task field '$field' must contain only maps")
            map.entries.associate { (key, nestedValue) ->
                val stringKey = key as? String
                    ?: error("Firestore Task field '$field' map keys must be Strings")
                stringKey to
                    (nestedValue
                        ?: error("Firestore Task field '$field' map values must not be null"))
            }
        }
        ?: error("Firestore Task field '$field' must be a list")

private fun Map<String, Any>.requiredBoolean(field: String): Boolean =
    this[field] as? Boolean
        ?: error("Firestore Task field '$field' must be a Boolean")

private fun Map<String, Any>.requiredLong(field: String): Long =
    when (val value = this[field]) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> error("Firestore Task field '$field' must be an integer")
    }

private const val UsersCollection = "users"
private const val TasksCollection = "tasks"
private const val RevisionsCollection = "revisions"
private const val TaskRemindersCollection = "taskReminders"

private const val SchemaVersionField = "schemaVersion"
private const val CurrentSchemaVersion = 1L
private const val TaskKindField = "kind"
private const val StandaloneTaskKind = "STANDALONE"
private const val RecurringSeriesTaskKind = "RECURRING_SERIES"
private const val TaskIdentifierField = "taskId"
private const val OwnerIdentifierField = "ownerUid"
private const val ActiveRevisionIdentifierField = "activeRevisionId"
private const val RevisionIdentifierField = "revisionId"
private const val VisibilityField = "visibility"
private const val PrivateVisibility = "PRIVATE"
private const val CreatedAtInstantMillisField = "createdAtInstantMillis"
private const val CreatedAtLocalDateField = "createdAtLocalDate"
private const val CreatedAtZoneIdentifierField = "createdAtZoneId"
private const val PlannedDateField = "plannedDate"
private const val HasReminderField = "hasReminder"
private const val TitleField = "title"
private const val SubtaskTemplateField = "subtaskTemplate"
private const val SubtaskIdentifierField = "subtaskId"
private const val SubtaskLabelField = "label"
private const val RecurrenceKindField = "recurrenceKind"
private const val DailyRecurrenceKind = "DAILY"
private const val EveryDaysField = "everyDays"
private const val StartsOnField = "startsOn"
private const val EndsOnInclusiveField = "endsOnInclusive"
private const val EffectiveDateField = "effectiveDate"
private const val RecordedAtInstantMillisField = "recordedAtInstantMillis"
private const val RecordedAtLocalDateField = "recordedAtLocalDate"
private const val RecordedAtZoneIdentifierField = "recordedAtZoneId"
private const val ReminderDateField = "date"
private const val ReminderLocalTimeField = "localTime"
private const val ReminderZoneIdentifierField = "zoneId"
private const val FirestoreTaskRepositoryLogTag = "DuitTaskRepository"
