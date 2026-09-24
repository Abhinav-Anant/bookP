package com.chmouel.liseur.ui.apa

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.apa.ApaDiscoverClient
import com.chmouel.liseur.data.apa.BookCard
import com.chmouel.liseur.data.apa.HomeRow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val rows: List<HomeRow> = emptyList(),
    val loading: Boolean = true,
    /** The last refresh failed; [rows] are what was loaded before, if anything. */
    val offline: Boolean = false,
    val query: String = "",
    val results: List<BookCard>? = null,
)

class HomeViewModel(
    private val client: ApaDiscoverClient,
    private val favorites: MutableStateFlow<Set<String>>,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val queries = MutableStateFlow("")

    init {
        refresh()
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            queries.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged().collect { query ->
                if (query.isBlank()) {
                    _state.update { it.copy(results = null) }
                } else {
                    client.search(query.trim()).onSuccess { found ->
                        if (queries.value == query) _state.update { it.copy(results = found) }
                    }
                }
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            client.home().fold(
                onSuccess = { page ->
                    favorites.value = page.favoriteIds
                    _state.update { it.copy(rows = page.rows, loading = false, offline = false) }
                },
                onFailure = { _state.update { it.copy(loading = false, offline = true) } },
            )
        }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
        queries.value = value
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                HomeViewModel(container.apaDiscover, container.apaFavorites)
            }
        }
    }
}
