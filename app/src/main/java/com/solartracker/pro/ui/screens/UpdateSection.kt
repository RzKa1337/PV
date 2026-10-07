package com.solartracker.pro.ui.screens

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.solartracker.pro.core.update.CheckInterval
import com.solartracker.pro.core.update.LogLevel
import com.solartracker.pro.core.update.UpdateChannel
import com.solartracker.pro.core.update.UpdateConfig
import com.solartracker.pro.core.update.UpdateState
import com.solartracker.pro.ui.UpdateViewModel
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.update.UpdateStatus
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object UpdateTags {
    const val STATUS = "update_status"
    const val CHECK = "update_check"
}

private val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun UpdateSection(viewModel: UpdateViewModel = viewModel(factory = UpdateViewModel.Factory)) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val manager = viewModel.manager
    val context = LocalContext.current

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    SectionCard {
        Text(stringResource(R.string.upd_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.upd_installed, manager.currentVersion.toString()), style = MaterialTheme.typography.bodyMedium)
        snapshot?.state?.lastCheck?.let {
            Text(stringResource(R.string.upd_last_check, timeFormat.format(it)), style = MaterialTheme.typography.bodySmall)
        }

        StatusBlock(
            status = status,
            onCheck = { askNotificationsOnce(); manager.checkNow() },
            onDownload = manager::download,
            onCancel = manager::cancelDownload,
            onInstall = manager::install,
            onOpenPermission = { context.startActivity(manager.permissionSettingsIntent()) },
            onSnooze = manager::snooze,
            onSkip = manager::skip,
        )

        snapshot?.let { s ->
            IgnoredVersions(s.state, manager::clearIgnored)
            ConfigEditor(s.config, viewModel::saveConfig)
        }
        UpdateLogView(log.map { Triple(it.time, it.level, it.message) })
    }
}

@Composable
private fun StatusBlock(
    status: UpdateStatus,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onOpenPermission: () -> Unit,
    onSnooze: (String) -> Unit,
    onSkip: (String) -> Unit,
) {
    val text = when (status) {
        UpdateStatus.Idle -> stringResource(R.string.upd_idle)
        UpdateStatus.Checking -> stringResource(R.string.upd_checking)
        is UpdateStatus.UpToDate -> stringResource(R.string.upd_up_to_date)
        is UpdateStatus.Available -> stringResource(R.string.upd_available, status.candidate.version.toString(), (mb(status.candidate.apk.size)).toString())
        is UpdateStatus.Downloading -> stringResource(R.string.upd_downloading, status.candidate.version.toString()) +
            (status.progress?.let { p -> p.fraction?.let { "${(it * 100).toInt()}%" } ?: mb(p.downloadedBytes) } ?: stringResource(R.string.upd_connecting)) +
            (status.progress?.attempt?.takeIf { it > 1 }?.let { stringResource(R.string.upd_attempt, it.toString()) } ?: "")
        is UpdateStatus.ReadyToInstall -> stringResource(R.string.upd_ready, status.download.candidate.version.toString())
        is UpdateStatus.NeedsInstallPermission -> stringResource(R.string.upd_needs_permission)
        is UpdateStatus.Installing -> stringResource(R.string.upd_installing, status.version.toString())
        is UpdateStatus.NotInstallable -> stringResource(R.string.upd_not_installable, status.version.toString(), status.reason)
        is UpdateStatus.Error -> stringResource(R.string.upd_error, status.message)
    }
    val isError = status is UpdateStatus.Error || status is UpdateStatus.NotInstallable
    Text(
        text,
        modifier = Modifier.testTag(UpdateTags.STATUS),
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
    )
    if (status is UpdateStatus.Downloading) {
        val fraction = status.progress?.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    val notes = when (status) {
        is UpdateStatus.Available -> status.candidate.release.body
        is UpdateStatus.ReadyToInstall -> status.download.candidate.release.body
        else -> ""
    }
    if (notes.isNotBlank()) {
        Text(notes.take(800), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (status) {
            is UpdateStatus.Available -> {
                Button(onClick = onDownload) { Text(stringResource(R.string.upd_download)) }
                OutlinedButton(onClick = { onSnooze(status.candidate.version.toString()) }) { Text(stringResource(R.string.upd_later)) }
                TextButton(onClick = { onSkip(status.candidate.version.toString()) }) { Text(stringResource(R.string.upd_skip)) }
            }
            is UpdateStatus.Downloading -> OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.upd_cancel)) }
            is UpdateStatus.ReadyToInstall -> {
                Button(onClick = onInstall) { Text(stringResource(R.string.upd_install)) }
                OutlinedButton(onClick = { onSnooze(status.download.candidate.version.toString()) }) { Text(stringResource(R.string.upd_later)) }
                TextButton(onClick = { onSkip(status.download.candidate.version.toString()) }) { Text(stringResource(R.string.upd_skip)) }
            }
            is UpdateStatus.NeedsInstallPermission -> {
                Button(onClick = onOpenPermission) { Text(stringResource(R.string.upd_allow)) }
                FilledTonalButton(onClick = onInstall) { Text(stringResource(R.string.upd_install)) }
            }
            UpdateStatus.Checking, is UpdateStatus.Installing -> Unit
            else -> FilledTonalButton(onClick = onCheck, modifier = Modifier.testTag(UpdateTags.CHECK)) { Text(stringResource(R.string.upd_check_now)) }
        }
    }
}

@Composable
private fun IgnoredVersions(state: UpdateState, onClear: () -> Unit) {
    val ignored = state.skippedVersions + state.badVersions + listOfNotNull(state.snoozedVersion)
    if (ignored.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.upd_ignored, ignored.sorted().joinToString()),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) { Text(stringResource(R.string.upd_clear)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigEditor(config: UpdateConfig, onSave: suspend (UpdateConfig) -> List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) stringResource(R.string.upd_hide_settings) else stringResource(R.string.upd_settings)) }
    if (!expanded) return

    var draft by remember(config) { mutableStateOf(config) }
    var errors by remember(config) { mutableStateOf(emptyList<String>()) }
    var saved by remember(config) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SwitchRow(stringResource(R.string.upd_auto_check), draft.enabled) { draft = draft.copy(enabled = it) }
    Text(stringResource(R.string.upd_repo_title), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(draft.owner, { draft = draft.copy(owner = it.trim()) }, label = { Text(stringResource(R.string.upd_owner)) }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(draft.repo, { draft = draft.copy(repo = it.trim()) }, label = { Text(stringResource(R.string.upd_repo)) }, singleLine = true, modifier = Modifier.weight(1f))
    }
    Text(stringResource(R.string.upd_channel), style = MaterialTheme.typography.labelLarge)
    Segmented(listOf(UpdateChannel.STABLE to stringResource(R.string.upd_stable), UpdateChannel.BETA to "Beta"), draft.channel) { draft = draft.copy(channel = it) }
    Text(stringResource(R.string.upd_frequency), style = MaterialTheme.typography.labelLarge)
    Segmented(
        listOf(CheckInterval.HOURS_6 to "6 h", CheckInterval.HOURS_12 to "12 h", CheckInterval.DAILY to stringResource(R.string.upd_day), CheckInterval.WEEKLY to stringResource(R.string.upd_week), CheckInterval.MANUAL to stringResource(R.string.upd_manual)),
        draft.interval,
    ) { draft = draft.copy(interval = it) }
    SwitchRow(stringResource(R.string.upd_auto_download), draft.autoDownload) { draft = draft.copy(autoDownload = it) }
    SwitchRow(stringResource(R.string.upd_wifi_only), draft.wifiOnly) { draft = draft.copy(wifiOnly = it) }
    SwitchRow(
        stringResource(R.string.upd_auto_install),
        draft.autoInstall,
        stringResource(R.string.upd_auto_install_hint),
    ) { draft = draft.copy(autoInstall = it) }
    OutlinedTextField(
        draft.token,
        { draft = draft.copy(token = it.trim()) },
        label = { Text(stringResource(R.string.upd_token)) },
        supportingText = { Text(stringResource(R.string.upd_token_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (saved) Text(stringResource(R.string.upd_saved), style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = { scope.launch { errors = onSave(draft); saved = errors.isEmpty() } },
        enabled = draft != config,
    ) { Text(stringResource(R.string.save)) }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun UpdateLogView(entries: List<Triple<java.time.Instant, LogLevel, String>>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.upd_log, entries.size.toString())) }
    if (!expanded) return
    if (entries.isEmpty()) Text(stringResource(R.string.upd_no_entries), style = MaterialTheme.typography.bodySmall)
    entries.takeLast(60).asReversed().forEach { (time, level, message) ->
        Text(
            "${timeFormat.format(time)} ${level.name.first()} $message",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = when (level) {
                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun mb(bytes: Long) = if (bytes < 0) "?" else "%.1f MB".format(bytes / 1_048_576.0)
