package de.velwebsolutions.oasis.staff.ui.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.staff.api.Labelled
import de.velwebsolutions.oasis.staff.api.StatementOfReasons
import de.velwebsolutions.oasis.staff.ui.components.RadioList

/**
 * The statement of reasons (DSA Art. 17), as the web's partial decision-fields: measure, ground, rule, facts and the
 * explanation the affected person reads. Shared by the case decision and the "overturned" branch of a complaint review.
 */
@Composable
fun DecisionFields(
    statement: StatementOfReasons,
    onChange: (StatementOfReasons) -> Unit,
    outcomes: List<Labelled>,
    grounds: List<Labelled>,
    errors: Map<String, String>,
    prefix: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RadioList("Maßnahme", outcomes, statement.outcome, { onChange(statement.copy(outcome = it)) }, "$prefix.outcome", errors["outcome"])
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
