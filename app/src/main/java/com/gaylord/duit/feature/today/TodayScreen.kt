package com.gaylord.duit.feature.today

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gaylord.duit.R
import com.gaylord.duit.designsystem.BotanicalMark
import com.gaylord.duit.workspace.AgendaTask
import com.gaylord.duit.workspace.CommandIdentifier
import com.gaylord.duit.workspace.PersonalAgenda
import java.time.LocalDate
import java.util.UUID

@Composable
fun TodayScreen(
    modifier: Modifier = Modifier,
    personalAgenda: PersonalAgenda = EmptyPersonalAgenda,
    onAddPrivateTaskToToday: (CommandIdentifier, String, (Boolean) -> Unit) -> Unit =
        { _, _, result ->
            result(false)
        },
) {
    var taskTitle by rememberSaveable { mutableStateOf("") }
    var pendingCommandIdentifier by rememberSaveable { mutableStateOf<String?>(null) }
    var submissionInProgress by remember { mutableStateOf(false) }
    val normalizedTitle = taskTitle.trim()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 24.dp,
            top = 24.dp,
            end = 24.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            TodayHeading()
        }

        item {
            AddPrivateTaskCard(
                title = taskTitle,
                onTitleChange = { taskTitle = it },
                enabled = !submissionInProgress && pendingCommandIdentifier == null,
                canAdd = normalizedTitle.isNotEmpty() && !submissionInProgress,
                onAdd = {
                    val submittedTitle = normalizedTitle
                    val commandIdentifier =
                        pendingCommandIdentifier
                            ?.let(::CommandIdentifier)
                            ?: CommandIdentifier(UUID.randomUUID().toString()).also { identifier ->
                                pendingCommandIdentifier = identifier.value
                            }
                    submissionInProgress = true
                    onAddPrivateTaskToToday(commandIdentifier, submittedTitle) { added ->
                        submissionInProgress = false
                        if (added && taskTitle.trim() == submittedTitle) {
                            taskTitle = ""
                            pendingCommandIdentifier = null
                        }
                    }
                },
            )
        }

        if (personalAgenda.tasks.isEmpty()) {
            item {
                TodayEmptyState()
            }
        } else {
            items(
                items = personalAgenda.tasks,
                key = { task -> task.identifier.value },
            ) { task ->
                AgendaTaskCard(task)
            }
        }
    }
}

@Composable
private fun TodayHeading() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.destination_today),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = stringResource(R.string.today_intro),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun AddPrivateTaskCard(
    title: String,
    onTitleChange: (String) -> Unit,
    enabled: Boolean,
    canAdd: Boolean,
    onAdd: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TodayTaskTitleInputTag),
                enabled = enabled,
                label = { Text(stringResource(R.string.today_task_title_label)) },
                supportingText = { Text(stringResource(R.string.today_task_private_note)) },
                singleLine = true,
            )
            Button(
                onClick = onAdd,
                enabled = canAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.today_add_task))
            }
        }
    }
}

internal const val TodayTaskTitleInputTag = "today-private-task-title"

@Composable
private fun AgendaTaskCard(task: AgendaTask) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Text(
            text = task.title,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun TodayEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BotanicalMark(modifier = Modifier.size(112.dp))
            Text(
                text = stringResource(R.string.today_empty_title),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.today_empty_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

internal val EmptyPersonalAgenda =
    PersonalAgenda(
        date = LocalDate.MIN,
        tasks = emptyList(),
    )
