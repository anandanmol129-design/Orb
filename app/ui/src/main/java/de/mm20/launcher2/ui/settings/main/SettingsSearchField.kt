package de.mm20.launcher2.ui.settings.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.ui.R

/** The text field that replaces the main settings title while searching. Focused on open. */
@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val textStyle = MaterialTheme.typography.titleLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .focusRequester(focusRequester),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_search),
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
    LaunchedEffect(Unit) {
        // Only on a fresh search: coming back from a result keeps the keyboard down.
        if (query.isEmpty()) focusRequester.requestFocus()
    }
}
