package com.gaylord.duit.feature.together

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.gaylord.duit.R
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest

@Composable
fun TogetherScreen(
    modifier: Modifier = Modifier,
    state: TogetherState = TogetherState.Loading,
    onEvent: (TogetherEvent) -> Unit = {},
) {
    var inviteCode by rememberSaveable { mutableStateOf("") }
    val currentOnEvent by rememberUpdatedState(onEvent)

    LifecycleStartEffect(Unit) {
        currentOnEvent(TogetherEvent.Started)
        onStopOrDispose { currentOnEvent(TogetherEvent.Stopped) }
    }

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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.destination_together),
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = stringResource(R.string.together_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        when (state) {
            TogetherState.Loading -> {
                item {
                    val progressDescription =
                        stringResource(R.string.together_loading_progress_description)
                    TogetherStateCard {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = progressDescription
                            },
                        )
                        Text(
                            text = stringResource(R.string.together_loading_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            TogetherState.Unpaired -> {
                item {
                    TogetherStateCard {
                        Text(
                            text = stringResource(R.string.together_unpaired_title),
                            modifier = Modifier.semantics { heading() },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.together_unpaired_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.together_pairing_privacy),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { onEvent(TogetherEvent.CreateInvite) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.together_create_invite))
                        }
                        OutlinedTextField(
                            value = inviteCode,
                            onValueChange = { inviteCode = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(PairInviteCodeInputTag),
                            label = { Text(stringResource(R.string.together_invite_code_label)) },
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                onEvent(TogetherEvent.AcceptInvite(inviteCode.trim()))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            enabled = inviteCode.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.together_enter_code))
                        }
                    }
                }
            }
            is TogetherState.PartnerIdentityReview -> {
                item {
                    TogetherStateCard {
                        Text(
                            text = stringResource(R.string.together_review_title),
                            modifier = Modifier.semantics { heading() },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = state.partnerIdentity.displayName,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        state.partnerIdentity.photoUrl?.let { photoUrl ->
                            val platformContext = LocalPlatformContext.current
                            val photoRequest =
                                remember(platformContext, photoUrl) {
                                    ImageRequest.Builder(platformContext)
                                        .data(photoUrl)
                                        .memoryCachePolicy(CachePolicy.DISABLED)
                                        .diskCachePolicy(CachePolicy.DISABLED)
                                        .build()
                                }
                            AsyncImage(
                                model = photoRequest,
                                contentDescription =
                                    stringResource(
                                        R.string.together_partner_photo_description,
                                        state.partnerIdentity.displayName,
                                    ),
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Text(
                            text = stringResource(R.string.together_review_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.partnerAlreadyConfirmed) {
                            Text(
                                text = stringResource(R.string.together_partner_confirmed),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = stringResource(R.string.together_pairing_privacy),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { onEvent(TogetherEvent.ConfirmPartnerIdentity) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.together_confirm_partner))
                        }
                    }
                }
            }
            is TogetherState.InviteReady -> {
                item {
                    TogetherStateCard {
                        Text(
                            text = stringResource(R.string.together_invite_ready_title),
                            modifier = Modifier.semantics { heading() },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = state.invite.code,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Text(
                            text = stringResource(R.string.together_invite_ready_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.together_pairing_privacy),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            is TogetherState.WaitingForPartner -> {
                item {
                    TogetherStateCard {
                        Text(
                            text =
                                stringResource(
                                    R.string.together_waiting_title,
                                    state.partnerIdentity.displayName,
                                ),
                            modifier = Modifier.semantics { heading() },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.together_waiting_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.together_pairing_privacy),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            is TogetherState.ActivePair -> {
                item {
                    TogetherStateCard {
                        Text(
                            text =
                                stringResource(
                                    R.string.together_active_title,
                                    state.partnerIdentity.displayName,
                                ),
                            modifier = Modifier.semantics { heading() },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.together_active_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            TogetherState.ConnectionRequired -> {
                item {
                    TogetherFailureCard(
                        title = stringResource(R.string.together_connection_required_title),
                        body = stringResource(R.string.together_connection_required_body),
                        onRetry = { onEvent(TogetherEvent.Retry) },
                    )
                }
            }
            is TogetherState.RecoverableError -> {
                item {
                    TogetherFailureCard(
                        title = stringResource(R.string.together_recoverable_error_title),
                        body =
                            state.message
                                ?: stringResource(R.string.together_recoverable_error_body),
                        onRetry = { onEvent(TogetherEvent.Retry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TogetherFailureCard(
    title: String,
    body: String,
    onRetry: () -> Unit,
) {
    TogetherStateCard {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.together_retry))
        }
    }
}

@Composable
private fun TogetherStateCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
        }
    }
}

const val PairInviteCodeInputTag = "pair-invite-code-input"
