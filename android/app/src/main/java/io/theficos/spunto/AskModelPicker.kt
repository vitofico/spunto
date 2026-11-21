package io.theficos.spunto

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import io.theficos.spunto.core.plugins.string
import kotlinx.serialization.json.*

internal data class AskModels(val default: String, val options: List<String>, val external: Boolean, val host: String, val externalModels: Set<String>) {
    fun leavesNetwork(chosen: String?): Boolean = if (chosen == null) external else chosen in externalModels
}

internal fun askModels(response: JsonObject): AskModels? {
    val default = response.string("default_model").takeIf(String::isNotBlank) ?: return null
    val models = (response["models"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) } ?: return null
    val external = (response["external"] as? JsonPrimitive)?.booleanOrNull ?: return null
    val host = response.string("host")
    val externalModels = (response["external_models"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: return null
    if ((external || externalModels.isNotEmpty()) && host.isBlank()) return null
    return AskModels(default, (listOf(default) + models).distinct(), external, host, externalModels)
}

@Composable internal fun AskModelPicker(models: AskModels, selected: String?, enabled: Boolean, choose: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    if (models.options.size <= 1) return
    val current = selected ?: models.default
    val modelLabel = stringResource(R.string.ask_chat_model)
    Box {
        AssistChip(
            modifier = Modifier.semantics { contentDescription = "$modelLabel, $current" },
            onClick = { expanded = true },
            enabled = enabled,
            label = { Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_expand), null, Modifier.size(16.dp)) },
            shape = MaterialTheme.shapes.extraLarge,
        )
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
            models.options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    choose(option.takeUnless { it == models.default })
                    expanded = false
                })
            }
        }
    }
}
