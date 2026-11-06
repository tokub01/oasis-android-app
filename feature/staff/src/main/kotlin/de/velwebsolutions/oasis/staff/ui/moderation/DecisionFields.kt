package de.velwebsolutions.oasis.staff.ui.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.components.FieldError
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.pressable
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.staff.api.Labelled
import de.velwebsolutions.oasis.staff.api.OutcomeOption
import de.velwebsolutions.oasis.staff.api.StatementOfReasons
import de.velwebsolutions.oasis.staff.api.SuspensionOption
import de.velwebsolutions.oasis.staff.ui.components.RadioList

/**
 * The statement of reasons (DSA Art. 17), as the web's partial decision-fields: measure (with the hint under "Warnung"
 * and "Konto gesperrt", the latter disabled with its reason while the account was not warned), the duration of a lock,
 * ground, rule, facts and the explanation the affected person reads. Shared by the case decision and the "overturned"
 * branch of a complaint review.
 */
@Composable
fun DecisionFields(
    statement: StatementOfReasons,
    onChange: (StatementOfReasons) -> Unit,
    outcomes: List<OutcomeOption>,
    suspension: SuspensionOption,
    grounds: List<Labelled>,
    errors: Map<String, String>,
    prefix: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutcomeList(
            outcomes,
            suspension,
            statement.outcome,
            { outcome ->
                // The duration belongs to "Konto gesperrt" only; another measure drops it.
                val days = statement.suspensionDays.takeIf { outcome == OutcomeOption.ACCOUNT_SUSPENDED }
                onChange(statement.copy(outcome = outcome, suspensionDays = days))
            },
            "$prefix.outcome",
            errors["outcome"]
        )
        if (statement.isSuspension) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RadioList(
                    "Dauer der Sperre",
                    suspension.days.map { Labelled("$it", "$it Tage") },
                    statement.suspensionDays?.toString().orEmpty(),
                    { onChange(statement.copy(suspensionDays = it.toIntOrNull())) },
                    "$prefix.days",
                    errors["suspension_days"]
                )
                Text(
                    "Die Sperre endet danach von selbst. Die Dauer steht in der Begründung (Art. 17 Abs. 3 lit. a DSA).",
                    style = OasisType.Caption,
                    color = OasisColors.Muted
                )
            }
        }
        RadioList("Grundlage", grounds, statement.ground, { onChange(statement.copy(ground = it)) }, "$prefix.ground", errors["ground"])
        OasisTextField(
            "Regel oder Rechtsvorschrift",
            statement.ruleReference,
            { onChange(statement.copy(ruleReference = it)) },
            Modifier.testTag("$prefix.rule"),
            error = errors["rule_reference"],
            hint = "Zum Beispiel „Community-Regel 2: Kein Hass“ oder „§ 185 StGB“."
        )
        OasisTextField(
            "Festgestellte Tatsachen",
            statement.facts,
            { onChange(statement.copy(facts = it)) },
            Modifier.testTag("$prefix.facts"),
            error = errors["facts"],
            lines = 4..10
        )
        OasisTextField(
            "Begründung für die Betroffenen",
            statement.explanation,
            { onChange(statement.copy(explanation = it)) },
            Modifier.testTag("$prefix.explanation"),
            error = errors["explanation"],
            hint = "Klar und respektvoll. Dieser Text geht per E-Mail an die betroffene Person und ist Teil der Begründung nach Art. 17 " +
                "DSA.",
            lines = 5..12
        )
    }
}

/**
 * "Maßnahme": one row per measure with the web's hint under "Warnung" and "Konto gesperrt". While the account was not
 * warned (and the case is not severe), "Konto gesperrt" cannot be chosen and says why ("Nicht wählbar. …").
 */
@Composable
private fun OutcomeList(
    outcomes: List<OutcomeOption>,
    suspension: SuspensionOption,
    selection: String,
    onSelect: (String) -> Unit,
    tag: String,
    error: String?
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Maßnahme", style = OasisType.BodyBold, color = OasisColors.Ink, modifier = Modifier.semantics { heading() })
        outcomes.forEach { option ->
            val blocked = option.isSuspension && !suspension.allowed
            val selected = selection == option.value
            val rowColor = if (blocked) OasisColors.Muted else OasisColors.Ink
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 44.dp)
                    .pressable({ onSelect(option.value) }, enabled = !blocked)
                    .semantics {
                        this.selected = selected
                        if (blocked) disabled()
                    }
                    .testTag("$tag.${option.value}"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (blocked) OasisColors.Muted else OasisColors.Night,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(option.label, style = OasisType.Body, color = rowColor)
                    when {
                        blocked -> Text(
                            "Nicht wählbar. ${suspension.blockedReason.orEmpty()}".trim(),
                            style = OasisType.Small,
                            color = OasisColors.Ink
                        )

                        option.hint != null -> Text(option.hint, style = OasisType.Small, color = OasisColors.Muted)
                    }
                }
            }
        }
        if (error != null) FieldError(error)
    }
}
