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
        title = { Text(S.chooseLanguageTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = S.chooseLanguageBody,
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
                Text(S.continueLabel)
            }
        },
    )
}
