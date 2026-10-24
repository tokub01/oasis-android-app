package de.velwebsolutions.oasis.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType

/** Keyboard presets of the iOS field modifiers (e-mail, username, password, one-time code, no capitalisation). */
object OasisKeyboard {
    val Email = KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
    val Username = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
    val Password = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    val OneTimeCode = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    val NoCapitalization = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
    val Sentences = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
}

/** A labelled text field in the web's style with an optional error and hint below it. */
@Composable
fun OasisTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    prompt: String? = null,
    error: String? = null,
    hint: String? = null,
    isSecure: Boolean = false,
    /** Makes the field grow vertically within this many lines (bio, house rules, edits). */
    lines: IntRange? = null,
    keyboardOptions: KeyboardOptions = if (isSecure) OasisKeyboard.Password else OasisKeyboard.Sentences,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(OasisRadius.XS.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = OasisType.SmallBold, color = OasisColors.Ink)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = OasisType.Body.copy(color = OasisColors.Ink),
            cursorBrush = SolidColor(OasisColors.Night),
            singleLine = lines == null,
            minLines = lines?.first ?: 1,
            maxLines = lines?.last ?: 1,
            visualTransformation = if (isSecure) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(OasisColors.Field, shape)
                        .border(if (error == null) 1.dp else 2.dp, if (error == null) OasisColors.FieldLine else OasisColors.SunText, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (value.isEmpty() && prompt != null) {
                        Text(prompt, style = OasisType.Body, color = OasisColors.Muted)
                    }
                    inner()
                }
            },
        )
        if (error != null) {
            FieldError(error)
        } else if (hint != null) {
            Text(hint, style = OasisType.Caption, color = OasisColors.Muted)
        }
    }
}

/** The red message below an invalid field. */
@Composable
fun FieldError(message: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = OasisColors.SunText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(message, style = OasisType.Caption, color = OasisColors.SunText)
    }
}

/** A toggle row with title and explanation, as in the settings and privacy screens. */
@Composable
fun SettingToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = OasisType.BodyBold, color = OasisColors.Ink)
            if (detail != null) Text(detail, style = OasisType.Small, color = OasisColors.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = OasisColors.Night,
                checkedThumbColor = OasisColors.OnNight,
                uncheckedTrackColor = OasisColors.Dune,
                uncheckedBorderColor = OasisColors.DuneLine,
                uncheckedThumbColor = OasisColors.FieldLine,
            ),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
