package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.content.CategoryNode
import com.yancotv.shared.content.CategoryTreeBuilder
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.content.PrefixCatalog
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Groups visibility manager. Type chip strip + LazyColumn list of groups,
 * each row a focus target with the new emerald [SettingsInlineSwitch].
 * Fits inside the Settings ContentPane scroll: the LazyColumn weight(1f)
 * binds the list to remaining vertical space, the chip strip stays
 * pinned at the top (same pattern as [SourcesScreen]).
 */
@Composable
fun SettingsGroupsTab(modifier: Modifier = Modifier, prefs: AppPreferences = koinInject(), repo: ContentRepository = koinInject()) {
    val hidden by prefs.hiddenGroupsFlow.collectAsState()
    val pinnedParentsByType by prefs.pinnedParentsFlow.collectAsState()
    val general by prefs.generalFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedType by rememberSaveable { mutableStateOf(ContentType.LIVE) }
    val groups = remember { mutableStateListOf<String>() }

    LaunchedEffect(selectedType) {
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching { repo.groups(selectedType) }.getOrElse { emptyList() }
            }
        groups.clear()
        groups.addAll(loaded.sorted())
    }

    // Compute available parent buckets for the selected type by running
    // the same tree-builder the rail uses on the un-sorted, un-hidden
    // group list. Single-child collapsed buckets are NOT pinnable as
    // parents (they're leaves now); we filter them out so the UI only
    // surfaces actionable rows. Re-derives on every recomposition that
    // affects the input — `groups` size + `hidden`. Cheap because the
    // parser + bucket build are linear in the group count.
    val pinnedForType = pinnedParentsByType[selectedType] ?: emptyList()
    val availableParents =
        remember(groups.toList(), hidden, pinnedForType) {
            val visibleGroups = groups.filterNot { it in hidden }
            CategoryTreeBuilder
                .build(visibleGroups, pinnedParentCodes = pinnedForType)
                .filterIsInstance<CategoryNode.Parent>()
        }

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp),
    ) {
        SettingsSection(
            title = "Groups",
            sub = "Hide categories you never watch — keeps the channel sidebar short. Changes take effect immediately.",
            right = {
                if (hidden.isNotEmpty()) {
                    SettingsOutlinedButton(
                        onClick = { scope.launch { prefs.clearHiddenGroups() } },
                        size = ButtonSize.Compact,
                    ) {
                        Text(text = "Show all (${hidden.size})")
                    }
                }
            },
        ) {
            SettingsRow(
                label = "Content type",
                hint = "Toggling switches the group list below to that catalog's category set.",
                content = {
                    SettingsChipRow(
                        options = ContentType.values().toList(),
                        selected = selectedType,
                        label = { typeChipLabel(it).uppercase() },
                        onSelect = { selectedType = it },
                    )
                },
            )
        }

        // MK.20 polish-sweep — Pin-a-bucket. Lives inside the existing
        // Settings → Categories tab so users have one place to manage all
        // group-level prefs. Visible only when smart grouping is on
        // (pinning a parent is meaningless if the rail is rendering a
        // flat list anyway). Parent rows show the resolved displayName,
        // the prefix code, and the child count. Pin toggle writes through
        // to AppPreferences.setParentPinned in pin-list order.
        if (general.smartGrouping && availableParents.isNotEmpty()) {
            SettingsSection(
                title = "Pinned categories",
                sub = "Pin language / region buckets to the top of the rail. Order follows the sequence you pin them in.",
                right = {
                    if (pinnedForType.isNotEmpty()) {
                        SettingsOutlinedButton(
                            onClick = { scope.launch { prefs.clearPinnedParents(selectedType) } },
                            size = ButtonSize.Compact,
                        ) {
                            Text(text = "Unpin all (${pinnedForType.size})")
                        }
                    }
                },
            ) {
                val palette = LocalYancoPalette.current
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.BackgroundRaised.copy(alpha = 0.5f))
                        .border(
                            width = 1.dp,
                            color = palette.BorderSubtle,
                            shape = RoundedCornerShape(12.dp),
                        ),
                ) {
                    availableParents.forEach { parent ->
                        val isPinned =
                            pinnedForType.any {
                                it == parent.prefixCode.lowercase() || it == parent.label.lowercase()
                            }
                        ParentPinRow(
                            label = parent.label,
                            prefixCode = parent.prefixCode,
                            kind = parent.kind,
                            childCount = parent.children.size,
                            pinned = isPinned,
                            onToggle = { shouldPin ->
                                scope.launch {
                                    prefs.setParentPinned(
                                        selectedType,
                                        parent.prefixCode,
                                        shouldPin,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        // Group list — single container card with hairline-divided rows so the
        // type-chip header above stays distinct and the list reads as ONE
        // grouped pane instead of N stacked cards (same fix the Sources tab
        // got after the user called the cards "horrible").
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No groups found for ${typeChipLabel(selectedType).lowercase()}. Sync a source first.",
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 12.sp,
                )
            }
        } else {
            val palette = LocalYancoPalette.current
            Column(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.BackgroundRaised.copy(alpha = 0.5f))
                    .border(
                        width = 1.dp,
                        color = palette.BorderSubtle,
                        shape = RoundedCornerShape(12.dp),
                    ),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(groups, key = { it }) { group ->
                        GroupRow(
                            name = group,
                            hidden = group in hidden,
                            onToggle = { isHidden ->
                                scope.launch { prefs.setGroupHidden(group, isHidden) }
                            },
                        )
                        // Hairline between rows.
                        Box(
                            modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp)
                                .background(palette.BorderSubtle)
                                .padding(top = 0.dp, bottom = 0.dp),
                        ) {
                            // Empty 1dp divider via height; keeps the modifier
                            // tower simple compared to building Divider().
                            Box(modifier = Modifier.fillMaxWidth().background(palette.BorderSubtle))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(name: String, hidden: Boolean, onToggle: (Boolean) -> Unit) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val rowBg = if (focused) palette.BackgroundElevated.copy(alpha = 0.6f) else Color.Transparent

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(rowBg)
            .border(
                width = if (focused) 1.5.dp else 0.dp,
                color = if (focused) palette.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(0.dp),
            )
            // MK.28.8 (MB-277) — Modifier.toggleable + Role.Switch per the
            // SettingsToggleRow MK.29.3 pattern, so TalkBack announces the
            // row's on/off state instead of a stateless "button". The switch
            // renders "visible" (checked = !hidden), so the toggleable value
            // matches that and onValueChange maps back to the hidden flag.
            .toggleable(
                value = !hidden,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = { visible -> onToggle(!visible) },
            )
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = if (hidden) palette.TextMuted else palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (hidden) "Hidden from sidebar and rails" else "Visible everywhere",
                color = palette.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        SettingsInlineSwitch(checked = !hidden)
    }
}

@Composable
private fun ParentPinRow(label: String, prefixCode: String, kind: PrefixCatalog.Kind, childCount: Int, pinned: Boolean, onToggle: (Boolean) -> Unit) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val rowBg = if (focused) palette.BackgroundElevated.copy(alpha = 0.6f) else Color.Transparent
    val kindLabel =
        when (kind) {
            PrefixCatalog.Kind.Language -> "language"
            PrefixCatalog.Kind.Region -> "region"
        }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(rowBg)
            .border(
                width = if (focused) 1.5.dp else 0.dp,
                color = if (focused) palette.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(0.dp),
            )
            // MK.28.8 (MB-277) — Modifier.toggleable + Role.Switch per the
            // SettingsToggleRow MK.29.3 pattern, so TalkBack announces the
            // pinned on/off state instead of a stateless "button".
            .toggleable(
                value = pinned,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (pinned) palette.Accent else palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$kindLabel · $prefixCode · $childCount channel${if (childCount == 1) "" else "s"}",
                color = palette.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        SettingsInlineSwitch(checked = pinned)
    }
}

private fun typeChipLabel(type: ContentType): String = when (type) {
    ContentType.LIVE -> "Live TV"
    ContentType.MOVIE -> "Movies"
    ContentType.SERIES -> "Series"
}
