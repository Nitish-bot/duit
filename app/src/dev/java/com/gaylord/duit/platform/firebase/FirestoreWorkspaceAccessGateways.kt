package com.gaylord.duit.platform.firebase

import com.gaylord.duit.domain.model.MemberIdentifier
import com.gaylord.duit.workspace.AuthenticatedIdentity
import com.gaylord.duit.workspace.MemberApproval
import com.gaylord.duit.workspace.MemberApprovalFailure
import com.gaylord.duit.workspace.MemberApprovalGateway
import com.gaylord.duit.workspace.OwnerWorkspaceHydration
import com.gaylord.duit.workspace.OwnerWorkspaceHydrationFailure
import com.gaylord.duit.workspace.OwnerWorkspaceHydrationGateway
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.util.concurrent.CancellationException
import kotlinx.coroutines.tasks.await

internal class FirestoreMemberApprovalGateway(
    private val firestore: () -> FirebaseFirestore,
) : MemberApprovalGateway {
    override suspend fun approvalFor(identity: AuthenticatedIdentity): MemberApproval =
        try {
            val approval =
                firestore()
                    .collection(MemberApprovalsCollection)
                    .document(identity.uid)
                    .get(Source.SERVER)
                    .await()
            if (approval.exists() && approval.getBoolean(ApprovedField) == true) {
                MemberApproval.Approved
            } else {
                MemberApproval.Required
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            MemberApproval.Failed(MemberApprovalFailure.ServerUnavailable)
        }
}

internal class FirestoreOwnerWorkspaceHydrationGateway(
    private val firestore: () -> FirebaseFirestore,
) : OwnerWorkspaceHydrationGateway {
    override suspend fun hydrate(memberIdentifier: MemberIdentifier): OwnerWorkspaceHydration =
        try {
            val owner =
                firestore()
                    .collection(UsersCollection)
                    .document(memberIdentifier.value)

            OwnerRootCollectionNames.forEach { collectionName ->
                val documents = owner.collection(collectionName).serverDocuments()
                when (collectionName) {
                    TasksCollection -> hydrateTaskChildren(documents)
                    TaskRemindersCollection -> hydrateTaskReminderChildren(documents)
                }
            }

            OwnerWorkspaceHydration.Complete
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            OwnerWorkspaceHydration.Failed(OwnerWorkspaceHydrationFailure.ServerUnavailable)
        }

    private suspend fun hydrateTaskChildren(tasks: List<DocumentSnapshot>) {
        tasks.forEach { task ->
            TaskChildCollectionNames.forEach { childCollection ->
                task.reference.collection(childCollection).serverDocuments()
            }
        }
    }

    private suspend fun hydrateTaskReminderChildren(reminders: List<DocumentSnapshot>) {
        reminders.forEach { reminder ->
            TaskReminderChildCollectionNames.forEach { childCollection ->
                reminder.reference.collection(childCollection).serverDocuments()
            }
        }
    }

    private suspend fun CollectionReference.serverDocuments(): List<DocumentSnapshot> =
        get(Source.SERVER).await().documents
}

private const val MemberApprovalsCollection = "memberApprovals"
private const val ApprovedField = "approved"
private const val UsersCollection = "users"
private const val TasksCollection = "tasks"
private const val TaskRemindersCollection = "taskReminders"

private val OwnerRootCollectionNames =
    listOf(
        "workspace",
        "pairProfile",
        TasksCollection,
        TaskRemindersCollection,
        "dailyPlans",
        "settings",
        "syncDiagnostics",
    )

private val TaskChildCollectionNames =
    listOf(
        "revisions",
        "occurrenceStates",
        "exceptions",
        "pauseSpans",
        "sharedDefinitions",
        "sharedExceptions",
        "sharedCompletions",
        "cheerTargets",
        "cheers",
    )

private val TaskReminderChildCollectionNames =
    listOf(
        "revisions",
        "occurrenceOverrides",
    )
