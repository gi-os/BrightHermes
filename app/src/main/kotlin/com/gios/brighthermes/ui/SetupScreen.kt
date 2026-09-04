package com.gios.brighthermes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gios.brighthermes.Prefs
import kotlinx.coroutines.launch

/**
 * Where the gateway is and how to prove it is you. Two fields, one attempt. The server is
 * prefilled with Gio's because that is the phone this was written for; anyone else types theirs.
 * Nothing is kept until `/deck` answers with the token, so a typo cannot half-configure the app.
 */
@Composable
fun SetupScreen(type: Type, initialServer: String, onTry: suspend (server: String, token: String) -> String?) {
    var server by remember { mutableStateOf(initialServer.ifBlank { Prefs.DEFAULT_SERVER }) }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun go() {
        if (busy || token.isBlank()) return
        busy = true
        error = null
        scope.launch {
            error = onTry(server, token)
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding().padding(Grid)) {
        Text("BrightHermes", style = type.title, color = Ink.Content)
        Spacer(Modifier.height(6.dp))
        Text(
            "A glance at the deck, a word to June. Point this at your gateway.",
            style = type.body,
            color = Ink.Secondary,
        )
        Spacer(Modifier.height(Grid * 2))

        Field("SERVER", server, type, onChange = { server = it }, imeAction = ImeAction.Next, keyboard = KeyboardType.Uri)
        Spacer(Modifier.height(Grid))
        Field(
            "TOKEN", token, type,
            onChange = { token = it },
            imeAction = ImeAction.Done,
            onDone = ::go,
            transform = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(Grid * 2))

        Text(
            if (busy) "Connecting…" else "Connect",
            style = type.body,
            color = if (token.isBlank() || busy) Ink.Secondary else Ink.Content,
            modifier = Modifier.clickable(enabled = !busy && token.isNotBlank(), onClick = ::go).padding(vertical = 6.dp),
        )
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = type.small, color = Ink.Secondary)
        }
        Spacer(Modifier.height(Grid * 2))
        Text(
            "The token is BRIGHTHERMES_TOKEN in the gateway's .env. It stays on this phone.",
            style = type.label,
            color = Ink.Secondary,
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    type: Type,
    onChange: (String) -> Unit,
    imeAction: ImeAction,
    keyboard: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
    transform: VisualTransformation = VisualTransformation.None,
) {
    Column(Modifier.fillMaxWidth(0.8f)) {
        Text(label, style = type.label, color = Ink.Secondary)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = type.body.copy(color = Ink.Content),
            cursorBrush = SolidColor(Ink.Content),
            singleLine = true,
            visualTransformation = transform,
            keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = keyboard, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onDone() }, onNext = null),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )
        Box(Modifier.fillMaxWidth().height(3.dp).background(Ink.Content))
    }
}
