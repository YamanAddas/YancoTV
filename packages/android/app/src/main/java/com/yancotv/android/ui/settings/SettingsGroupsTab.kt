package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Groups visibility manager. Providers often push 400+ category groups;
 * hiding the noise is the single biggest daily-use quality-of-life knob
 * for a personal catalog. Hidden groups disappear from the sidebar filter
 * without touching the underlying channel rows, so toggling back just
 * brings them back — no re-sync required.
 *
 * Filters across Live / Movies / Series are managed in one place with a
 * type-selector row so the user never has to remember which tab hides
 * which category.
 */
@Composable
fun SettingsGroupsTab(
    modifier: Modifier = Modifier,
    prefs: AppPreferences = koinInject(),
    repo: ContentRepository = koinInject(),
) {
    val hidden by prefs.hiddenGroupsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedType by rememberSaveable { mutableStateOf(ContentType.LIVE) }
    val groups = remember { mutableStateListOf<String>() }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(selectedType) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { repo.groups(selectedType) }.getOrElse { emptyList() }
        }
        groups.clear()
        groups.addAll(loaded.sorted())
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Groups",
            color = YancoPalette.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Hide categories you never watch — keeps the channel sidebar short. Changes take effect immediately.",
            color = YancoPalette.TextMuted,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (type in ContentType.values()) {
                TypeChip(
                    label = typeChipLabel(type),
                    selected = selectedType == type,
                    onClick = { selectedType = type },
                )
            }
            Spacer(modifier = Modifier.fillMaxWidth(0.02f))
            if (hidden.isNotEmpty()) {
                TypeChip(
                    label = "Show all (${hidden.size})",
                    selected = false,
                    onClick = { scope.launch { prefs.clearHiddenGroups() } },
                )
            }
        }

        val visible = remember(groups.toList(), query) {
            if (query.isBlank()) groups.toList()
            else groups.filter { it.contains(query, ignoreCase = true) }
        }

        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No groups found for ${typeChipLabel(selectedType).lowercase()}. Sync a source first.",
                    color = YancoPalette.TextMuted,
                    fontSize = 12.sp,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(visible, key = { it }) { group ->
                GroupRow(
                    name = group,
                    hidden = group in hidden,
                    onToggle = { isHidden -> scope.launch { prefs.setGroupHidden(group, isHidden) } },
                )
            }
        }
    }
}

@Composable
private fun GroupRow(name: String, hidden: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(YancoPalette.BackgroundRaised)
            .clickable { onToggle(!hidden) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = name,
            color = if (hidden) YancoPalette.TextMuted else YancoPalette.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Text(
            text = if (hidden) "Hidden" else "Visible",
            color = YancoPalette.TextMuted,
            fontSize = 11.sp,
        )
        Switch(
            checked = !hidden,
            onCheckedChange = { checked -> onToggle(!checked) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = YancoPalette.Accent,
                checkedTrackColor = YancoPalette.Accent.copy(alpha = 0.4f),
                uncheckedThumbColor = YancoPalette.TextMuted,
                uncheckedTrackColor = YancoPalette.BackgroundHover,
            ),
        )
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) YancoPalette.Accent.copy(alpha = 0.22f) else YancoPalette.BackgroundDeep
    val fg = if (selected) YancoPalette.TextPrimary else YancoPalette.TextMuted
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun typeChipLabel(type: ContentType): String = when (type) {
    ContentType.LIVE -> "Live TV"
    ContentType.MOVIE -> "Movies"
    ContentType.SERIES -> "Series"
}
