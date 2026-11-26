package com.doro.organizer.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doro.organizer.R
import com.doro.organizer.ui.common.NoPersonalizedLearning

/**
 * The top app bar of a perspective (ui.md § 2.2, DEC-51): the title, the Search action and the Settings action.
 * Pinned: the container tints while the list is scrolled. With [onTitleClick] (Individual and Project with an entity
 * chosen, ui.md § 4.2) the title is one button with a dropdown arrow, named by [titleClickLabel]; with [entityMenu]
 * (the same case) the overflow menu of that entity follows Settings (DEC-113).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PerspectiveTopBar(
    title: String,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    onTitleClick: (() -> Unit)? = null,
    titleClickLabel: String? = null,
    entityMenu: EntityMenuActions? = null,
) {
    TopAppBar(
        title = {
            if (onTitleClick == null) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = titleClickLabel, role = Role.Button, onClick = onTitleClick),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null)
                }
            }
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.action_search))
            }
            IconButton(onClick = onSettings, modifier = Modifier.testTag("action_settings")) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.action_settings))
            }
            entityMenu?.let { EntityMenu(it) }
        },
        scrollBehavior = scrollBehavior,
    )
}

/**
 * The search bar that replaces the top app bar (ui.md § 3.3): its own composable rather than a `TopAppBar` title,
 * whose fixed height would clip the field at 200 % font. With [focusOnStart] it takes focus and shows the keyboard,
 * which the Search action asks for and a restored bar does not; [onFocusedOnStart] reports that this happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchBar(
    text: String,
    focusOnStart: Boolean,
    onFocusedOnStart: () -> Unit,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (focusOnStart) {
            focus.requestFocus()
            keyboard?.show()
            onFocusedOnStart()
        }
    }
    Surface(color = TopAppBarDefaults.topAppBarColors().containerColor) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_close_search))
            }
            // Always wrapped: the keyboard must never learn what is searched (DEC-71, Q11).
            NoPersonalizedLearning {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .testTag("search_field"),
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            onSubmit()
                        },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
            if (text.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_clear_search))
                }
            }
        }
    }
}
