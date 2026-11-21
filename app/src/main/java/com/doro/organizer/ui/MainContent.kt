package com.doro.organizer.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import com.doro.organizer.model.Perspective
import com.doro.organizer.ui.common.LocalAppSnackbar
import com.doro.organizer.ui.common.resolve
import com.doro.organizer.ui.notes.NoteListScreen
import com.doro.organizer.ui.navigation.NavigationState
import com.doro.organizer.ui.navigation.Navigator
import com.doro.organizer.ui.navigation.TabKey
import com.doro.organizer.ui.navigation.navigationSuiteTypeOf
import com.doro.organizer.ui.navigation.rememberNavigationState
import com.doro.organizer.ui.navigation.tabItem

/**
 * The app shell (ui.md § 2): the navigation suite around `NavDisplay`, composed only while the gate shows the content
 * (architecture.md § Rendering, invariant 2), inside the `DataEpochScope` of [dataEpoch].
 */
@Composable
fun MainContent(graph: UiGraph, dataEpoch: Int) {
    val navigation = rememberNavigationState(dataEpoch)
    val navigator = remember(navigation) { Navigator(navigation) }
    val appSnackbar = remember { SnackbarHostState() }
    AppMessagesEffect(graph, appSnackbar)
    val entries = navigation.rememberDisplayedEntries(
        entryProvider<NavKey> {
            entry<TabKey> { key ->
                NoteListScreen(
                    perspective = key.perspective,
                    leaveCount = navigation.leaveCount(key.perspective),
                    reselectCount = navigation.reselectCount(key.perspective),
                )
            }
        },
    )
    val compactWidth = !currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    NavigationSuiteScaffold(
        navigationSuiteItems = { Perspective.entries.forEach { tab(it, navigation, navigator) } },
        layoutType = navigationSuiteTypeOf(topIsTabRoot = navigation.topKey is TabKey, compactWidth = compactWidth),
    ) {
        CompositionLocalProvider(LocalAppSnackbar provides appSnackbar) {
            NavDisplay(entries = entries, onBack = navigator::back)
        }
    }
}

private fun NavigationSuiteScope.tab(
    p: Perspective,
    navigation: NavigationState,
    navigator: Navigator,
) {
    val item = p.tabItem()
    val selected = p == navigation.current
    item(
        selected = selected,
        onClick = { navigator.selectTab(p) },
        // Decorative: the label names the item (ui.md § 2.1).
        icon = { Icon(painterResource(if (selected) item.selectedIcon else item.icon), contentDescription = null) },
        label = { Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.testTag("tab_${p.name}"),
        alwaysShowLabel = true,
    )
}

/**
 * Shows the head of `AppMessages.pending` in [appSnackbar] and consumes it once `showSnackbar` returned (ui.md § 2.4).
 * Leaving composition before that cancels the effect without consuming, so the message shows again on return.
 */
@Composable
private fun AppMessagesEffect(graph: UiGraph, appSnackbar: SnackbarHostState) {
    val pending by graph.appMessages.pending.collectAsState()
    val head = pending.firstOrNull()
    val resources = LocalResources.current
    LaunchedEffect(head?.id) {
        if (head == null) return@LaunchedEffect
        appSnackbar.showSnackbar(
            message = resources.resolve(head.text, graph.appTime),
            duration = if (head.isError) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        graph.appMessages.consume(head.id)
    }
}
