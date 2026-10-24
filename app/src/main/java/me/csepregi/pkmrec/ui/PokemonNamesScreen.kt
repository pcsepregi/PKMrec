package me.csepregi.pkmrec.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.pokedex.PokemonName
import me.csepregi.pkmrec.pokedex.PokemonNames

/** The Pokémon name list used for name correction, with a filter by name or Pokédex number. */
@Composable
fun PokemonNamesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var names by remember { mutableStateOf<List<PokemonName>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { names = withContext(Dispatchers.IO) { PokemonNames.all(context) } }

    val query = filter.trim()
    val shown = if (query.isEmpty()) names else names.filter {
        it.name.contains(query, ignoreCase = true) || it.number.toString() == query.trimStart('#', '0')
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp)) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
            Text(
                stringResource(R.string.names_title) + " (${names.size})",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                label = { Text(stringResource(R.string.names_filter)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.number }) { name ->
                    Text(name.display, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}
