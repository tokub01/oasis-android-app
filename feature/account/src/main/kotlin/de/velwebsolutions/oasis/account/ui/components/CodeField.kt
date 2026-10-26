package de.velwebsolutions.oasis.account.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisRadius
import de.velwebsolutions.oasis.design.theme.OasisType

/**
 * The field for a six-digit code of an SMS or e-mail, in the look of `OasisTextField`: digits keyboard and the autofill
 * type „SMS code“, so keyboards and password managers offer the code. Spaces are fine (the server ignores them).
 */
@Composable
internal fun OneTimeCodeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null
) {
    val shape = RoundedCornerShape(OasisRadius.XS.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = OasisType.SmallBold, color = OasisColors.Ink)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = OasisType.Body.copy(color = OasisColors.Ink),
            cursorBrush = SolidColor(OasisColors.Night),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    contentType = ContentType.SmsOtpCode
                },
            decorationBox = { inner ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(OasisColors.Field, shape)
                        .border(if (error == null) 1.dp else 2.dp, if (error == null) OasisColors.FieldLine else OasisColors.SunText, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) { inner() }
            }
        )
        if (error != null) FieldError(error)
    }
}
