package me.csepregi.pkmrec.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import me.csepregi.pkmrec.pokedex.PokemonNames
import me.csepregi.pkmrec.tcg.MyCards
import me.csepregi.pkmrec.tcg.MyCardsResult
import me.csepregi.pkmrec.tcg.TcgCard
import me.csepregi.pkmrec.tcg.TcgError
import me.csepregi.pkmrec.tcg.TcgErrorKind
import me.csepregi.pkmrec.update.UpdateManager

sealed interface MyCardsState {
    data object Loading : MyCardsState
    data object NotLoggedIn : MyCardsState
    data class Error(val error: TcgError) : MyCardsState
    data class Loaded(val cards: List<TcgCard>) : MyCardsState
}

class MyCardsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<MyCardsState>(MyCardsState.Loading)
    val state: StateFlow<MyCardsState> = _state.asStateFlow()

    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()

    private var key: Pair<Int, String>? = null

    fun start(number: Int, slug: String) {
        if (key == number to slug) return
        key = number to slug
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { runCatching { PokemonNames.byNumber(getApplication(), number) }.getOrNull() }
            _title.value = name?.display ?: "#%04d %s".format(number, slug.replaceFirstChar { it.uppercase() })
        }
        load()
    }

    fun retry() = load()

    private fun load() {
        val (number, slug) = key ?: return
        _state.value = MyCardsState.Loading
        viewModelScope.launch {
            _state.value = when (val result = MyCards.lookup(getApplication(), number, slug)) {
                is MyCardsResult.Found -> MyCardsState.Loaded(result.cards)
                MyCardsResult.NotLoggedIn -> MyCardsState.NotLoggedIn
                is MyCardsResult.Error -> MyCardsState.Error(result.error)
            }
        }
    }
}

/** "Összes lapom": every card of one Pokémon in the user's collection, two per row, newest first. */
@Composable
fun MyCardsScreen(number: Int, slug: String, listCardIds: Set<Int>, onBack: () -> Unit, onLogin: () -> Unit) {
    val vm: MyCardsViewModel = viewModel()
    val state by vm.state.collectAsState()
    val title by vm.title.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(number, slug) { vm.start(number, slug) }

    Scaffold { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
                    Text(stringResource(R.string.my_cards_title), style = MaterialTheme.typography.titleMedium)
                    title?.let { Text(it, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    (state as? MyCardsState.Loaded)?.let { loaded ->
                        if (loaded.cards.isNotEmpty()) {
                            Text(
                                stringResource(R.string.my_cards_count, loaded.cards.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            when (val s = state) {
                MyCardsState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Message {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.my_cards_loading))
                    }
                }
                MyCardsState.NotLoggedIn -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Message {
                        Text(stringResource(R.string.lookup_pokedex_not_logged_in), textAlign = TextAlign.Center)
                        Button(onClick = onLogin) { Text(stringResource(R.string.login)) }
                        OutlinedButton(onClick = { vm.retry() }) { Text(stringResource(R.string.retry)) }
                    }
                }
                is MyCardsState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Message {
                        Text(stringResource(R.string.my_cards_error, tcgErrorText(s.error)), textAlign = TextAlign.Center)
                        Button(onClick = { vm.retry() }) { Text(stringResource(R.string.retry)) }
                        if (s.error.kind == TcgErrorKind.BLOCKED) {
                            OutlinedButton(onClick = onLogin) { Text(stringResource(R.string.open_tcg_collector)) }
                        }
                        if (s.error.kind == TcgErrorKind.SITE_CHANGED) {
                            OutlinedButton(onClick = { UpdateManager.checkNow(context) }) {
                                Text(stringResource(R.string.settings_check_update))
                            }
                        }
                    }
                }
                is MyCardsState.Loaded -> {
                    if (s.cards.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Message { Text(stringResource(R.string.my_cards_none), textAlign = TextAlign.Center) }
                        }
                    }
                    items(s.cards, key = { it.id }) { card ->
                        CardTile(card, inList = card.id in listCardIds) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(card.url)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardTile(card: TcgCard, inList: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_IMAGE_ASPECT)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (card.imageUrl != null) {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = card.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    card.name,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            (if (inList) "★ " else "") + card.expansionName + (card.expansionCode?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (inList) VerdictColors.Owned else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.number.isNotBlank()) {
            Text(card.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Message(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}
