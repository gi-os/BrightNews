package com.lightrss.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.lightrss.reader.hw.WheelKeys
import com.lightrss.reader.hw.WheelScroll
import com.lightrss.reader.report.ReportContext
import com.lightrss.reader.report.Reports
import com.lightrss.reader.report.ShakeToReport
import com.lightrss.reader.report.Symptom
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * What went wrong, once you have shaken the phone.
 *
 * LightNews raises a Material bottom sheet here. This app has no Material in it and a Light SDK
 * tool has no window to float one over, so the same five choices are a screen of rows in the
 * house style, with the note behind its own row and CANCEL / SEND in the bar. It assumes typing
 * on this phone is expensive: a row is a complete report on its own and the note is optional.
 * But the note is also the only part that carries anything the build table cannot, so it takes
 * the headline in the issue title whenever it is filled in.
 *
 * SEND closes the screen at once. `Reports.submit` writes the report to disk before it tries the
 * network, so there is nothing here that can fail in a way the screen would need to say.
 */
class ReportScreen(
    sealedActivity: SealedLightActivity,
    private val filesDir: File,
    private val packageName: String,
    /** The screen that was showing when the phone was shaken, read before this one was pushed. */
    private val where: String = ReportContext.screen,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private var symptom by mutableStateOf(Symptom.Other)
    private var note by mutableStateOf("")
    private var typing by mutableStateOf(false)
    // Outlives the screen on purpose: the queue flush is IO that must not be cancelled by the
    // screen closing, and the screen closes the instant SEND is pressed.
    private val sending = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenDestroy() {
        ShakeToReport.closed()
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            if (typing) NoteEntry() else Choices()
        }
    }

    @Composable
    private fun Choices() {
        val scroll = rememberScrollState()
        WheelKeys()
        WheelScroll(scroll)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Something wrong?"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                scrollState = scroll,
            ) {
                Column {
                    LightText(
                        text = "Pick what happened and it goes to the tracker with the build " +
                            "details attached. A note helps, but is not required.",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(
                            horizontal = SIDE_MARGIN_UNITS.gridUnitsAsDp(),
                            vertical = ROW_PADDING_UNITS.gridUnitsAsDp(),
                        ),
                    )
                    HairlineDivider()
                    Symptom.entries.forEach { option ->
                        ChoiceRow(
                            title = option.row,
                            detail = option.label,
                            selected = symptom == option,
                            onClick = { symptom = option },
                        )
                    }
                    SettingsRow(
                        title = if (note.isBlank()) "ADD A NOTE" else "NOTE",
                        detail = note.ifBlank { "What were you doing? (optional)" },
                        onClick = { typing = true },
                    )
                    LightText(
                        text = if (Reports.canSend()) {
                            "Goes to the private light-reports tracker, with the last crash attached " +
                                "if there was one."
                        } else {
                            "This build has no reporting key, so it will wait on the phone until one does."
                        },
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(
                            horizontal = SIDE_MARGIN_UNITS.gridUnitsAsDp(),
                            vertical = ROW_PADDING_UNITS.gridUnitsAsDp(),
                        ),
                    )
                }
            }
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text("CANCEL", onClick = { goBack() }),
                    LightBarButton.Text("SEND", onClick = ::send),
                ),
            )
        }
    }

    @Composable
    private fun NoteEntry() {
        val input = rememberTextFieldState(note)
        val keyboard = rememberKeyboardOptions()
        var lightKeys by rememberSaveable { mutableStateOf(false) }
        val done: (CharSequence) -> Unit = { text ->
            note = text.toString()
            typing = false
        }
        if (lightKeys) {
            LightTextInputEditor(
                title = "Note",
                keyboardOptionsFlow = keyboard,
                state = input,
                onSubmit = done,
                onBack = { lightKeys = false },
                submitIcon = LightIcons.ACCEPT,
                showBackButton = true,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SystemTextEntry(
                title = "Note",
                state = input,
                submitLabel = "DONE",
                hint = "What were you doing?",
                onSubmit = done,
                onBack = { typing = false },
                onUseLightKeys = { lightKeys = true },
            )
        }
    }

    private fun send() {
        val chosen = symptom
        val text = note
        val dir = filesDir
        val pkg = packageName
        val screen = where
        goBack()
        sending.launch {
            runCatching {
                val crash = CrashLog.read(dir)
                val report = Reports.compose(
                    symptom = chosen,
                    note = text,
                    screen = screen,
                    crash = crash,
                    filesDir = dir,
                    packageName = pkg,
                )
                Reports.submit(dir, report)
            }
        }
    }
}

/** A settings-style row with a mark at the end: on for the chosen one, off for the rest. */
@Composable
private fun ChoiceRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClickLabel = title, role = Role.RadioButton) { onClick() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = SIDE_MARGIN_UNITS.gridUnitsAsDp(),
                    vertical = ROW_PADDING_UNITS.gridUnitsAsDp(),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                LightText(title, LightTextVariant.Paragraph)
                LightText(
                    detail,
                    LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.35f.gridUnitsAsDp()),
                )
            }
            LightIcon(
                icon = if (selected) LightIcons.SELECT_ON else LightIcons.SELECT_OFF,
                contentDescription = if (selected) "Selected" else null,
            )
        }
        HairlineDivider()
    }
}
