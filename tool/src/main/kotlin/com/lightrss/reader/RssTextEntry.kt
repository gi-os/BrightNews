package com.lightrss.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Text entry backed by the phone's own keyboard, with a paste button.
 *
 * Feed addresses are long and are almost always already on a clipboard somewhere, so the Light
 * keyboard is the slowest possible way to enter one. This screen uses the system IME and a PASTE
 * button instead, and keeps a KEYS button that hands the same [TextFieldState] to the Light
 * keyboard for anyone who prefers it, or for a device with no system IME installed.
 */
@Composable
fun SystemTextEntry(
    title: String,
    state: TextFieldState,
    submitLabel: String,
    hint: String,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    onUseLightKeys: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    val colors = LightThemeTokens.colors

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = onBack),
            center = LightTopBarCenter.Text(title),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.TopStart,
        ) {
            BasicTextField(
                state = state,
                textStyle = LightThemeTokens.typography.paragraph.copy(color = colors.content),
                cursorBrush = SolidColor(colors.content),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    autoCorrectEnabled = false,
                ),
                onKeyboardAction = { onSubmit(state.text) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                decorator = { field ->
                    if (state.text.isEmpty()) {
                        LightText(text = hint, variant = LightTextVariant.Paragraph, lighten = true)
                    }
                    field()
                },
            )
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(
                    text = "PASTE",
                    onClick = {
                        val pasted = clipboard.getText()?.text?.trim()
                        if (!pasted.isNullOrEmpty()) state.setTextAndPlaceCursorAtEnd(pasted)
                    },
                ),
                LightBarButton.Text("KEYS", onClick = onUseLightKeys),
                LightBarButton.Text(submitLabel, onClick = { onSubmit(state.text) }),
            ),
        )
    }
}
