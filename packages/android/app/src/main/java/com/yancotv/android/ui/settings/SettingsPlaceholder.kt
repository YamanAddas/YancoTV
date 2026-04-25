package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType

/**
 * Shared scaffolding for tab bodies that don't have shipped content yet.
 * Keeps the visual language consistent with the designed tabs while making
 * it obvious to a reader what will replace it and when.
 *
 * [kicker] is the MK milestone label rendered above the title; [body] is
 * the short justification of what the tab will host.
 */
@Composable
fun SettingsPlaceholder(
    kicker: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xxl, vertical = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        // MB-109 fallback focusable lives at SettingsScreen.ContentPane
        // scope, not here — keeping it inside this Column with a
        // spacedBy() gap pushed every kicker down by Space.md, so the
        // first heading on every placeholder tab (and About/Shortcuts)
        // stopped lining up with the breadcrumb above. Hoisted up.
        Text(
            text = kicker.uppercase(),
            color = LocalYancoPalette.current.Accent,
            style = YancoType.Overline,
        )
        Text(
            text = title,
            color = LocalYancoPalette.current.TextPrimary,
            style = YancoType.DisplayS,
        )
        // Thin accent gradient divider — mirrors the Section rule from
        // pages/settings-tabs.jsx so the body reads as "this section starts here".
        Spacer(
            modifier =
                Modifier
                    .width(72.dp)
                    .height(2.dp)
                    .clip(YancoShapes.ChipBevel)
                    .background(
                        Brush.horizontalGradient(
                            listOf(LocalYancoPalette.current.Accent, LocalYancoPalette.current.AccentDeep),
                        ),
                    ),
        )
        Spacer(modifier = Modifier.height(Space.sm))
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(YancoShapes.CutCornerCardSmall)
                    .background(LocalYancoPalette.current.BackgroundRaised)
                    .border(1.dp, LocalYancoPalette.current.BorderSubtle, YancoShapes.CutCornerCardSmall)
                    .padding(horizontal = Space.xxl, vertical = Space.lg),
        ) {
            Text(
                text = "Pending implementation",
                color = LocalYancoPalette.current.TextSecondary,
                style = YancoType.LabelStrong,
            )
            Spacer(modifier = Modifier.height(Space.xs))
            Text(
                text = body,
                color = LocalYancoPalette.current.TextMuted,
                style = YancoType.BodyLong,
            )
        }
    }
}
