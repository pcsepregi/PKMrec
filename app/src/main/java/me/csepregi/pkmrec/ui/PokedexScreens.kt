package me.csepregi.pkmrec.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.pokedex.NameMatcher
import me.csepregi.pkmrec.pokedex.PokemonName
import me.csepregi.pkmrec.pokedex.PokemonNames
import me.csepregi.pkmrec.tcg.PokedexList
import me.csepregi.pkmrec.tcg.PokedexLookupResult
import me.csepregi.pkmrec.tcg.TcgCard
import me.csepregi.pkmrec.tcg.TcgError
import me.csepregi.pkmrec.tcg.TcgErrorKind
import me.csepregi.pkmrec.update.UpdateManager

/** Pokédex search: type a name, pick one of the corrected suggestions. */
@Composable
fun PokedexSearchScreen(onBack: () -> Unit, onPick: (PokemonName) -> Unit) {
    val context = LocalContext.current
    var names by remember { mutableStateOf<List<PokemonName>>(emptyList()) }
    LaunchedEffect(Unit) { names = withContext(Dispatchers.IO) { PokemonNames.all(context) } }
    var query by rememberSaveable { mutableStateOf("") }
    val suggestions = remember(query, names) { NameMatcher.suggest(query, names) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
            Text(stringResource(R.string.pokedex_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.pokedex_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { suggestions.firstOrNull()?.let(onPick) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            when {
                query.isBlank() -> Text(
                    stringResource(R.string.pokedex_search_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                suggestions.isEmpty() -> Text(stringResource(R.string.pokedex_no_match), style = MaterialTheme.typography.bodyMedium)
                else -> suggestions.forEach { name ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onPick(name) }) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                "#%04d".format(name.number),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

sealed interface PokedexResultState {
    data object Loading : PokedexResultState
    data object UnknownPokemon : PokedexResultState
    data object NotLoggedIn : PokedexResultState
    data class Error(val error: TcgError) : PokedexResultState
    data class Loaded(val cards: List<TcgCard>) : PokedexResultState
}

class PokedexResultViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<PokedexResultState>(PokedexResultState.Loading)
    val state: StateFlow<PokedexResultState> = _state.asStateFlow()

    private val _pokemon = MutableStateFlow<PokemonName?>(null)
    val pokemon: StateFlow<PokemonName?> = _pokemon.asStateFlow()

    private var number: Int? = null

    fun start(number: Int) {
        if (this.number == number) return
        this.number = number
        load()
    }

    fun retry() = load()

    private fun load() {
        val n = number ?: return
        _state.value = PokedexResultState.Loading
        viewModelScope.launch {
            val pokemon = withContext(Dispatchers.IO) { PokemonNames.byNumber(getApplication(), n) }
            _pokemon.value = pokemon
            if (pokemon == null) {
                _state.value = PokedexResultState.UnknownPokemon
                return@launch
            }
            _state.value = when (val result = PokedexList.lookupPokemon(getApplication(), pokemon.number, pokemon.slug)) {
                PokedexLookupResult.NotLoggedIn -> PokedexResultState.NotLoggedIn
                is PokedexLookupResult.Error -> PokedexResultState.Error(result.error)
                is PokedexLookupResult.Found -> PokedexResultState.Loaded(result.perPokemon.flatMap { it.cards })
                PokedexLookupResult.NotAPokemon -> PokedexResultState.Loaded(emptyList())
            }
        }
    }
}

/** The Pokedex list's card(s) for the chosen Pokémon, with every detail TCG Collector shows. */
@Composable
fun PokedexResultScreen(number: Int, onBack: () -> Unit, onLogin: () -> Unit) {
    val vm: PokedexResultViewModel = viewModel()
    val state by vm.state.collectAsState()
    val pokemon by vm.pokemon.collectAsState()
    LaunchedEffect(number) { vm.start(number) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.pokedex_new_search)) }
            pokemon?.let {
                Text(it.display, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }

            when (val s = state) {
                PokedexResultState.Loading -> CenteredColumn {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.lookup_pokedex_loading))
                }
                PokedexResultState.UnknownPokemon -> CenteredColumn {
                    Text(stringResource(R.string.pokedex_no_match), textAlign = TextAlign.Center)
                }
                PokedexResultState.NotLoggedIn -> CenteredColumn {
                    Text(stringResource(R.string.lookup_pokedex_not_logged_in), textAlign = TextAlign.Center)
                    Button(onClick = onLogin) { Text(stringResource(R.string.login)) }
                    OutlinedButton(onClick = { vm.retry() }) { Text(stringResource(R.string.retry)) }
                }
                is PokedexResultState.Error -> CenteredColumn {
                    Text(stringResource(R.string.lookup_pokedex_error, tcgErrorText(s.error)), textAlign = TextAlign.Center)
                    Button(onClick = { vm.retry() }) { Text(stringResource(R.string.retry)) }
                    if (s.error.kind == TcgErrorKind.SITE_CHANGED) {
                        val context = LocalContext.current
                        OutlinedButton(onClick = { UpdateManager.checkNow(context) }) {
                            Text(stringResource(R.string.settings_check_update))
                        }
                    }
                }
                is PokedexResultState.Loaded -> {
                    Text(stringResource(R.string.lookup_pokedex_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (s.cards.isEmpty()) {
                        Text(stringResource(R.string.pokedex_none_in_list), style = MaterialTheme.typography.bodyLarge)
                    }
                    s.cards.forEachIndexed { index, card ->
                        if (index > 0) HorizontalDivider()
                        PokedexCard(card)
                    }
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pokedex_new_search)) }
                }
            }
        }
    }
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun PokedexCard(card: TcgCard) {
    val context = LocalContext.current
    var zoomed by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.clickable(enabled = card.imageUrl != null) { zoomed = true }) {
                CardImage(card, width = 240)
            }
        }
        Text(card.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (card.energyTypes.isNotEmpty()) DetailRow(stringResource(R.string.pokedex_type), card.energyTypes.joinToString(", "))
                card.hp?.let { DetailRow(stringResource(R.string.pokedex_hp), it.toString()) }
                DetailRow(stringResource(R.string.pokedex_set), card.expansionName + (card.expansionCode?.let { " ($it)" } ?: ""))
                if (card.number.isNotBlank()) DetailRow(stringResource(R.string.pokedex_number), card.number)
                card.rarity?.let { DetailRow(stringResource(R.string.pokedex_rarity), it) }
            }
        }
        CardmarketPriceSection(card, reverseHolo = false)
        if (card.variants.isNotEmpty()) {
            Text(stringResource(R.string.pokedex_variants), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            card.variants.forEach { variant ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(variant.name, style = MaterialTheme.typography.bodyLarge)
                        if (variant.ownedOtherLanguages > 0) {
                            Text(
                                stringResource(R.string.lookup_other_languages, variant.ownedOtherLanguages),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = if (variant.isOwned) stringResource(R.string.pokedex_owned_count, variant.ownedEnglish) else "✗",
                        color = if (variant.isOwned) VerdictColors.Owned else VerdictColors.NotOwned,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        TextButton(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(card.url))) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.lookup_open_tcg)) }
    }

    if (zoomed && card.imageUrl != null) {
        Dialog(onDismissRequest = { zoomed = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                modifier = Modifier.fillMaxSize().clickable { zoomed = false }.padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = card.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(CARD_IMAGE_ASPECT)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
