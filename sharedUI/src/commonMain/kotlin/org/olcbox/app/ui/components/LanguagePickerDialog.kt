package org.olcbox.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.olcbox.app.i18n.AppLanguage
import org.olcbox.app.i18n.AppLocale
import org.olcbox.app.i18n.S

@Composable
fun LanguagePickerDialog(
    onLanguageChosen: (AppLanguage) -> Unit,
) {
    var selected by remember { mutableStateOf(AppLocale.current) }

    AlertDialog(
        onDismissRequest = { /* first launch — must choose */ },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Hardcoded bilingual title — must not depend on AppLocale (language not chosen yet).
                Text("Choose language")
                Text("\u0412\u044b\u0431\u0435\u0440\u0438\u0442\u0435 \u044f\u0437\u044b\u043a")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Which language should the app use?\n" +
                        "\u041d\u0430 \u043a\u0430\u043a\u043e\u043c \u044f\u0437\u044b\u043a\u0435 \u043f\u043e\u043a\u0430\u0437\u044b\u0432\u0430\u0442\u044c \u0438\u043d\u0442\u0435\u0440\u0444\u0435\u0439\u0441?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { selected = AppLanguage.Russian },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = S.russian,
                        color = if (selected == AppLanguage.Russian) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                OutlinedButton(
                    onClick = { selected = AppLanguage.English },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = S.english,
                        color = if (selected == AppLanguage.English) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    AppLocale.set(selected)
                    onLanguageChosen(selected)
                },
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Text("Continue / \u041f\u0440\u043e\u0434\u043e\u043b\u0436\u0438\u0442\u044c")
            }
        },
    )
}
