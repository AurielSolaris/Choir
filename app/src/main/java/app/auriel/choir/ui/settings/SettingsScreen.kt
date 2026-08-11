// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auriel.choir.BuildConfig
import app.auriel.choir.R
import app.auriel.choir.data.settings.LyricsProviderId
import app.auriel.choir.data.settings.ProviderSettings
import app.auriel.choir.playback.FfmpegSupport
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.components.ChoirHeader
import app.auriel.choir.ui.components.RowDivider
import app.auriel.choir.ui.components.SectionLabel
import app.auriel.choir.ui.theme.LocalChoirColors
import org.koin.androidx.compose.koinViewModel

/**
 * Settings.
 *
 * Deliberately small, and one section of it carries real weight: turning on
 * online lyrics is the user changing what Choir is allowed to do. That decision
 * gets stated plainly rather than buried — what is sent, to whom, and when.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val colors = LocalChoirColors.current
    val settings by viewModel.lyrics.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        ChoirHeader(title = stringResource(R.string.settings_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomPadding),
        ) {
            SectionLabel(stringResource(R.string.settings_lyrics))

            SwitchRow(
                title = stringResource(R.string.settings_lyrics_online),
                subtitle = stringResource(R.string.settings_lyrics_online_body),
                checked = settings.onlineEnabled,
                onCheckedChange = viewModel::setOnlineEnabled,
            )

            // Everything below only matters once the switch is on, and showing
            // it before then would suggest Choir is already doing something.
            AnimatedVisibility(visible = settings.onlineEnabled) {
                Column {
                    Disclosure(stringResource(R.string.settings_lyrics_disclosure))

                    SwitchRow(
                        title = stringResource(R.string.settings_lyrics_unmetered),
                        subtitle = stringResource(R.string.settings_lyrics_unmetered_body),
                        checked = settings.unmeteredOnly,
                        onCheckedChange = viewModel::setUnmeteredOnly,
                    )

                    RowDivider()

                    // Drawn in the order they are asked in, so the list on
                    // screen is the behaviour rather than a description of it.
                    val order = settings.orderedProviders
                    order.forEachIndexed { position, id ->
                        ProviderBlock(
                            id = id,
                            settings = settings.provider(id),
                            onEnabledChange = { viewModel.setProviderEnabled(id, it) },
                            onApiKeyChange = { viewModel.setProviderKey(id, it) },
                            onBaseUrlChange = { viewModel.setProviderUrl(id, it) },
                            canMoveUp = position > 0,
                            canMoveDown = position < order.lastIndex,
                            onMove = { by -> viewModel.moveProvider(id, by) },
                        )
                    }

                    Disclosure(stringResource(R.string.settings_lyrics_order_body))

                    RowDivider()

                    ActionRow(
                        title = stringResource(R.string.settings_lyrics_clear_cache),
                        subtitle = stringResource(
                            R.string.settings_lyrics_cache_size,
                            cacheBytes / 1024,
                        ),
                        onClick = viewModel::clearCache,
                    )

                    Disclosure(stringResource(R.string.provider_genius_note))
                }
            }

            SectionLabel(stringResource(R.string.settings_formats))
            Entry(
                label = stringResource(R.string.settings_formats_decoder),
                value = stringResource(
                    if (FfmpegSupport.isAvailable) {
                        R.string.settings_formats_decoder_present
                    } else {
                        R.string.settings_formats_decoder_absent
                    },
                ),
            )
            Disclosure(stringResource(R.string.settings_formats_body))

            SectionLabel(stringResource(R.string.settings_privacy))
            Disclosure(stringResource(R.string.settings_privacy_body))

            SectionLabel(stringResource(R.string.settings_about))
            Entry(
                label = stringResource(R.string.settings_version),
                value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
            Entry(
                label = stringResource(R.string.settings_licence),
                value = stringResource(R.string.settings_licence_value),
            )
            Entry(
                label = stringResource(R.string.settings_source),
                value = stringResource(R.string.settings_source_value),
            )
            Disclosure(stringResource(R.string.settings_attribution))
        }
    }
}

/**
 * One lyric service: whether to ask it, what it needs, and where it sits in the
 * asking order.
 *
 * Which fields appear is decided from the id rather than passed in, so adding a
 * provider is one entry here and one in the enum — there is no third place to
 * forget.
 */
@Composable
private fun ProviderBlock(
    id: LyricsProviderId,
    settings: ProviderSettings,
    onEnabledChange: (Boolean) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
) {
    val needsKey = id == LyricsProviderId.MUSIXMATCH || id == LyricsProviderId.CUSTOM
    val needsUrl = id == LyricsProviderId.CUSTOM

    SwitchRow(
        title = stringResource(id.titleRes),
        subtitle = stringResource(id.bodyRes),
        checked = settings.enabled,
        onCheckedChange = onEnabledChange,
        leading = {
            MoveControls(
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMove = onMove,
            )
        },
    )

    AnimatedVisibility(visible = settings.enabled) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            if (needsUrl) {
                SettingField(
                    value = settings.baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = stringResource(R.string.provider_base_url),
                    keyboardType = KeyboardType.Uri,
                )
            }
            if (needsKey) {
                SettingField(
                    value = settings.apiKey,
                    onValueChange = onApiKeyChange,
                    label = stringResource(R.string.provider_api_key),
                    keyboardType = KeyboardType.Password,
                )
            }
        }
    }
}

/**
 * Two arrows, dimmed at the ends of the list.
 *
 * Not a drag handle: Settings is an ordinary scrolling column, and Choir's
 * reordering reads item positions out of a `LazyListState` that does not exist
 * here. Arrows also survive being tapped by someone who cannot hold a drag.
 */
@Composable
private fun MoveControls(canMoveUp: Boolean, canMoveDown: Boolean, onMove: (Int) -> Unit) {
    val colors = LocalChoirColors.current

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = { onMove(-1) },
            enabled = canMoveUp,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = ChoirIcons.MoveUp,
                contentDescription = stringResource(R.string.cd_ask_sooner),
                tint = if (canMoveUp) colors.onBackground else colors.divider,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(
            onClick = { onMove(1) },
            enabled = canMoveDown,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = ChoirIcons.MoveDown,
                contentDescription = stringResource(R.string.cd_ask_later),
                tint = if (canMoveDown) colors.onBackground else colors.divider,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private val LyricsProviderId.titleRes: Int
    get() = when (this) {
        LyricsProviderId.LRCLIB -> R.string.provider_lrclib
        LyricsProviderId.NETEASE -> R.string.provider_netease
        LyricsProviderId.MUSIXMATCH -> R.string.provider_musixmatch
        LyricsProviderId.CUSTOM -> R.string.provider_custom
    }

private val LyricsProviderId.bodyRes: Int
    get() = when (this) {
        LyricsProviderId.LRCLIB -> R.string.provider_lrclib_body
        LyricsProviderId.NETEASE -> R.string.provider_netease_body
        LyricsProviderId.MUSIXMATCH -> R.string.provider_musixmatch_body
        LyricsProviderId.CUSTOM -> R.string.provider_custom_body
    }

@Composable
private fun SettingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
) {
    val colors = LocalChoirColors.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.background,
            unfocusedContainerColor = colors.background,
            focusedTextColor = colors.onBackground,
            unfocusedTextColor = colors.onBackground,
            cursorColor = colors.onBackground,
            focusedIndicatorColor = colors.onBackground,
            unfocusedIndicatorColor = colors.divider,
            focusedLabelColor = colors.muted,
            unfocusedLabelColor = colors.muted,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = LocalChoirColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        Spacer(Modifier.width(16.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // Monochrome, like everything else: the state reads from fill and
            // position, never from hue.
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.onBackground,
                checkedBorderColor = colors.onBackground,
                uncheckedThumbColor = colors.muted,
                uncheckedTrackColor = colors.background,
                uncheckedBorderColor = colors.divider,
            ),
        )
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = LocalChoirColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground,
        )
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

@Composable
private fun Entry(label: String, value: String) {
    val colors = LocalChoirColors.current

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

/** Prose that explains a choice, set apart from the controls it describes. */
@Composable
private fun Disclosure(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalChoirColors.current.muted,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
