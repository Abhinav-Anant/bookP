package com.chmouel.liseur.ui.apa

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.apa.AddResult
import com.chmouel.liseur.data.apa.ApaDiscoverClient
import com.chmouel.liseur.data.apa.BookDetail
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.remote.RemoteCatalogRepository
import com.chmouel.liseur.data.remote.ServerKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DetailMessage { PAYMENT_REQUIRED, FAILED }

data class BookDetailState(
    val detail: BookDetail? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val adding: Boolean = false,
    val message: DetailMessage? = null,
)

/**
 * One book's page. "Get" adds it to the reader's cloud library, pulls the
 * catalog so the book lands on the shelf as a remote book, and starts the
 * download — from there it is an ordinary Liseur book.
 */
class BookDetailViewModel(
    private val bookId: String,
    private val client: ApaDiscoverClient,
    private val catalog: RemoteCatalogRepository,
    private val bookDao: BookDao,
    private val downloads: BookDownloadRepository,
    private val favorites: MutableStateFlow<Set<String>>,
) : ViewModel() {

    private val _state = MutableStateFlow(BookDetailState())
    val state: StateFlow<BookDetailState> = _state.asStateFlow()

    private val bookUrl = ServerKind.KOMGA.remoteUrl(bookId)

    /** The shelf copy of this book, once it is in the local library. */
    val localBook: StateFlow<Book?> = bookDao.observeAll()
        .map { books -> books.firstOrNull { it.url == bookUrl } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            client.detail(bookId).fold(
                onSuccess = { d -> _state.update { it.copy(detail = d, loading = false) } },
                onFailure = { _state.update { it.copy(loading = false, loadFailed = true) } },
            )
        }
    }

    fun get() {
        if (_state.value.adding) return
        _state.update { it.copy(adding = true, message = null) }
        viewModelScope.launch {
            val message = when (client.addToLibrary(bookId)) {
                AddResult.ADDED -> {
                    _state.update { s -> s.copy(detail = s.detail?.copy(inLibrary = true)) }
                    catalog.refresh()
                    bookDao.getByUrl(bookUrl)?.let { downloads.enqueue(it) }
                    null
                }
                AddResult.PAYMENT_REQUIRED -> DetailMessage.PAYMENT_REQUIRED
                AddResult.FAILED -> DetailMessage.FAILED
            }
            _state.update { it.copy(adding = false, message = message) }
        }
    }

    /** Downloads a book that is on the shelf but not on the device. */
    fun download() {
        val book = localBook.value ?: return
        viewModelScope.launch { downloads.enqueue(book) }
    }

    fun toggleWishlist() = toggle(wishlist = true)

    fun toggleFavorite() = toggle(wishlist = false)

    private fun toggle(wishlist: Boolean) {
        val detail = _state.value.detail ?: return
        val updated = if (wishlist) detail.copy(wishlist = !detail.wishlist) else detail.copy(favorite = !detail.favorite)
        _state.update { it.copy(detail = updated) }
        viewModelScope.launch {
            val result = if (wishlist) {
                client.setFlags(bookId, wishlist = updated.wishlist)
            } else {
                client.setFlags(bookId, favorite = updated.favorite)
            }
            if (result.isFailure) {
                // Put the switch back where the server has it.
                _state.update { it.copy(detail = detail, message = DetailMessage.FAILED) }
            } else if (!wishlist) {
                favorites.update { if (updated.favorite) it + bookId else it - bookId }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(bookId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                BookDetailViewModel(
                    bookId = bookId,
                    client = container.apaDiscover,
                    catalog = container.remoteCatalog,
                    bookDao = container.database.bookDao(),
                    downloads = container.bookDownloads,
                    favorites = container.apaFavorites,
                )
            }
        }
    }
}
