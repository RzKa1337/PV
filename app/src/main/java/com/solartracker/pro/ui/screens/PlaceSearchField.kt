package com.solartracker.pro.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solartracker.pro.R
import com.solartracker.pro.core.geo.PlaceErrorKind
import com.solartracker.pro.core.geo.PlaceSearchException
import com.solartracker.pro.core.geo.PlaceSearcher
import com.solartracker.pro.core.geo.PlaceSuggestion
import com.solartracker.pro.core.geo.PlaceSource
import com.solartracker.pro.core.shading.LatLon
import com.solartracker.pro.core.geo.PlaceText
import com.solartracker.pro.core.geo.SavedPlace
import com.solartracker.pro.core.geo.SuggestResult
import com.solartracker.pro.data.PlaceSearchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** What the suggestion panel shows. */
private sealed interface SearchUi {
    data object Idle : SearchUi
    data class Loading(val previous: List<PlaceSuggestion>) : SearchUi
    data class Results(val result: SuggestResult) : SearchUi
    data class Empty(val query: String) : SearchUi
    data class Failed(val kind: PlaceErrorKind) : SearchUi
}

private data class Request(val query: String, val retry: Int, val explicit: Int)

/**
 * Search-as-you-type for a city, region or address. Suggestions appear under the field after a short typing pause
 * ([PlaceSearcher.DEBOUNCE_MS]); a newer keystroke cancels the older request. The keyboard "search" key searches
 * at once (and also looks up addresses / postcodes). Picking a suggestion fetches its coordinates from the provider.
 */
@OptIn(FlowPreview::class)
@Composable
fun PlaceSearchField(
    recent: List<SavedPlace>,
    /** Current location – nearby results rank higher (nothing is filtered out). */
    near: LatLon? = null,
    onPick: (SavedPlace) -> Unit,
    onForget: (SavedPlace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember { PlaceSearchRepository(context) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var explicit by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf(repo.newSession()) }
    var ui by remember { mutableStateOf<SearchUi>(SearchUi.Idle) }
    var resolving by remember { mutableStateOf<String?>(null) }
    var resolveError by remember { mutableStateOf<PlaceErrorKind?>(null) }

    LaunchedEffect(Unit) {
        var handledExplicit = 0
        snapshotFlow { Request(query, retry, explicit) }
            .debounce { if (it.explicit != handledExplicit) 0L else PlaceSearcher.DEBOUNCE_MS }
            .collectLatest { req ->
                val now = req.explicit != handledExplicit
                handledExplicit = req.explicit
                if (PlaceText.normalize(req.query) == null) { ui = SearchUi.Idle; return@collectLatest }
                ui = SearchUi.Loading((ui as? SearchUi.Results)?.result?.suggestions ?: (ui as? SearchUi.Loading)?.previous.orEmpty())
                ui = try {
                    val r = if (now) repo.searchNow(req.query, session, near) else repo.suggest(req.query, session, near)
                    if (r.suggestions.isEmpty()) SearchUi.Empty(r.query) else SearchUi.Results(r)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PlaceSearchException) {
                    SearchUi.Failed(e.kind)
                } catch (e: Exception) {
                    SearchUi.Failed(PlaceErrorKind.NETWORK)
                }
            }
    }

    fun pick(s: PlaceSuggestion) {
        resolving = s.primary
        resolveError = null
        scope.launch {
            try {
                val place = repo.resolve(s, session)
                onPick(place)
                query = ""
                ui = SearchUi.Idle
                session = repo.newSession() // a session ends with the details call
                focus.clearFocus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PlaceSearchException) {
                resolveError = e.kind
            } catch (e: Exception) {
                resolveError = PlaceErrorKind.NETWORK
            } finally {
                resolving = null
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(PlaceText.MAX_LENGTH) },
            modifier = Modifier.fillMaxWidth().testTag("place_search_field"),
            label = { Text(stringResource(R.string.place_search_label)) },
            placeholder = { Text(stringResource(R.string.place_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                when {
                    ui is SearchUi.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    query.isNotEmpty() -> IconButton(onClick = { query = ""; ui = SearchUi.Idle }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.place_search_clear))
                    }
                    else -> Unit
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (PlaceText.normalize(query) != null) explicit++ }),
        )

        resolving?.let { name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.place_search_resolving, name), style = MaterialTheme.typography.bodySmall)
            }
        }
        resolveError?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        when (val s = ui) {
            SearchUi.Idle -> if (query.isBlank() && recent.isNotEmpty()) {
                RecentPanel(recent, onPick = { onPick(it); focus.clearFocus() }, onForget = onForget)
            }
            is SearchUi.Loading -> if (s.previous.isNotEmpty()) SuggestionPanel(s.previous, null, null) { pick(it) }
            is SearchUi.Results -> SuggestionPanel(s.result.suggestions, s.result, s.result.notice?.kind, onMore = { explicit++ }) { pick(it) }
            is SearchUi.Empty -> Column(Modifier.testTag("place_search_empty")) {
                Text(stringResource(R.string.place_search_empty, s.query), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { explicit++ }) { Text(stringResource(R.string.place_search_more)) }
            }
            is SearchUi.Failed -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("place_search_error")) {
                Text(errorText(s.kind), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.place_search_retry)) }
            }
        }
    }
}

@Composable
private fun errorText(kind: PlaceErrorKind): String = stringResource(
    when (kind) {
        PlaceErrorKind.NETWORK -> R.string.place_error_network
        PlaceErrorKind.QUOTA -> R.string.place_error_quota
        PlaceErrorKind.KEY_MISSING, PlaceErrorKind.KEY_REJECTED -> R.string.place_error_key
        PlaceErrorKind.BAD_RESPONSE -> R.string.place_error_response
    },
)

@Composable
private fun SuggestionPanel(
    items: List<PlaceSuggestion>,
    result: SuggestResult?,
    notice: PlaceErrorKind?,
    onMore: (() -> Unit)? = null,
    onPick: (PlaceSuggestion) -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            items.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                PlaceRow(Icons.Outlined.Place, highlight(s.primary, s.primaryMatches), s.secondary, Modifier.clickable { onPick(s) }.testTag("place_suggestion"))
            }
            result?.source?.let { src ->
                Text(
                    stringResource(R.string.place_search_source, src.label) +
                        (if (notice == PlaceErrorKind.KEY_REJECTED || notice == PlaceErrorKind.QUOTA) " · " + stringResource(R.string.place_search_fallback) else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (onMore != null && result?.source != PlaceSource.COORDINATES) {
                TextButton(onClick = onMore, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(stringResource(R.string.place_search_not_listed))
                }
            }
        }
    }
}

@Composable
private fun RecentPanel(recent: List<SavedPlace>, onPick: (SavedPlace) -> Unit, onForget: (SavedPlace) -> Unit) {
    Text(stringResource(R.string.place_recent), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            recent.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                PlaceRow(Icons.Outlined.History, AnnotatedString(p.name), p.detail, Modifier.clickable { onPick(p) }.testTag("place_recent")) {
                    IconButton(onClick = { onForget(p) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.place_recent_remove, p.name))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: AnnotatedString,
    subtitle: String,
    modifier: Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun highlight(text: String, ranges: List<IntRange>): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        append(text)
        ranges.forEach { r ->
            if (r.first >= 0 && r.last < text.length && r.first <= r.last) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color), r.first, r.last + 1)
            }
        }
    }
}
