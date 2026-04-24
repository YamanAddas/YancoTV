package com.yancotv.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yancotv.shared.getPlatform

/**
 * MK.0 smoke-test screen. Confirms:
 *  - Compose + KMP wiring compiles and runs.
 *  - The shared `Platform` expect/actual is reachable from Android.
 *  - TV vs phone branching is live at the UI layer.
 */
@Composable
fun HelloScreen(isTv: Boolean) {
    val platformName = getPlatform().name
    val mode = if (isTv) "TV mode" else "Phone mode"

    if (isTv) {
        TvHello(platformName = platformName, mode = mode)
    } else {
        PhoneHello(platformName = platformName, mode = mode)
    }
}

@Composable
private fun PhoneHello(
    platformName: String,
    mode: String,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Text(
                text = "Hello YancoTV",
                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            )
            androidx.compose.material3.Text(text = platformName)
            androidx.compose.material3.Text(text = mode)
        }
    }
}

@Composable
private fun TvHello(
    platformName: String,
    mode: String,
) {
    Box(modifier = Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.tv.material3.Text(
                text = "Hello YancoTV",
                style = androidx.tv.material3.MaterialTheme.typography.headlineLarge,
            )
            androidx.tv.material3.Text(text = platformName)
            androidx.tv.material3.Text(text = mode)
        }
    }
}
