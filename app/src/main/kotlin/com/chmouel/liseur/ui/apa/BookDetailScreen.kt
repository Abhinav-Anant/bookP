package com.chmouel.liseur.ui.apa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.data.apa.BookDetail
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.reader.ReaderActivity
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApaBookDetailScreen(
    viewModel: BookDetailViewModel,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val local by viewModel.localBook.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val paymentMessage = stringResource(R.string.apa_detail_buy_soon)
    val failedMessage = stringResource(R.string.apa_detail_failed)
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(
            when (message) {
                DetailMessage.PAYMENT_REQUIRED -> paymentMessage
                DetailMessage.FAILED -> failedMessage
            },
        )
        viewModel.messageShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val detail = state.detail
        when {
            detail != null -> DetailBody(
                detail = detail,
                local = local,
                adding = state.adding,
                onGet = viewModel::get,
                onDownload = viewModel::download,
                onRead = {
                    local?.openableUri()?.let { uri ->
                        context.startActivity(ReaderActivity.intent(context, uri, local!!.url))
                    }
                },
                onWishlist = viewModel::toggleWishlist,
                onFavorite = viewModel::toggleFavorite,
                onOpenBook = onOpenBook,
                modifier = Modifier.padding(padding),
            )
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.apa_detail_load_failed))
                Button(onClick = viewModel::load) { Text(stringResource(R.string.apa_retry)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(
    detail: BookDetail,
    local: Book?,
    adding: Boolean,
    onGet: () -> Unit,
    onDownload: () -> Unit,
    onRead: () -> Unit,
    onWishlist: () -> Unit,
    onFavorite: () -> Unit,
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = detail.card
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BookCover(card.coverUrl, card.title, Modifier.width(128.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(card.title, style = MaterialTheme.typography.headlineSmall)
                detail.subtitle?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                card.author?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(priceLabel(card), style = MaterialTheme.typography.labelLarge)
            }
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryAction(detail, local, adding, onGet, onDownload, onRead)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = detail.wishlist,
                    onClick = onWishlist,
                    label = { Text(stringResource(R.string.apa_detail_wishlist)) },
                    leadingIcon = {
                        Icon(
                            if (detail.wishlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                FilterChip(
                    selected = detail.favorite,
                    onClick = onFavorite,
                    label = { Text(stringResource(R.string.apa_detail_favorite)) },
                    leadingIcon = {
                        Icon(
                            if (detail.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }

        detail.description?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (card.genres.isNotEmpty()) {
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                card.genres.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Fact(R.string.apa_detail_publisher, detail.publisher)
            Fact(R.string.apa_detail_language, card.language?.let { Locale.forLanguageTag(it).displayLanguage })
            Fact(R.string.apa_detail_pages, detail.pages?.toString())
            Fact(R.string.apa_detail_published, detail.publishedOn)
            Fact(R.string.apa_detail_isbn, detail.isbn)
            Fact(R.string.apa_detail_formats, detail.formats.joinToString(" · ") { it.uppercase() }.ifEmpty { null })
        }

        detail.authorBio?.let {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.apa_detail_about_author), style = MaterialTheme.typography.titleMedium)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (detail.moreByAuthor.isNotEmpty()) {
            Text(
                stringResource(R.string.apa_detail_more_by_author),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(detail.moreByAuthor, key = { it.id }) { book ->
                    BookCardView(book, Modifier.width(110.dp)) { onOpenBook(book.id) }
                }
            }
        }
    }
}

@Composable
private fun PrimaryAction(
    detail: BookDetail,
    local: Book?,
    adding: Boolean,
    onGet: () -> Unit,
    onDownload: () -> Unit,
    onRead: () -> Unit,
) {
    val full = Modifier.fillMaxWidth()
    when {
        adding || local?.downloadState == DownloadState.QUEUED || local?.downloadState == DownloadState.DOWNLOADING ->
            OutlinedButton(onClick = {}, enabled = false, modifier = full) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.apa_detail_downloading), Modifier.padding(start = 8.dp))
            }
        local?.downloadState == DownloadState.DOWNLOADED ->
            Button(onClick = onRead, modifier = full) { Text(stringResource(R.string.apa_detail_read)) }
        detail.inLibrary && local != null ->
            Button(onClick = onDownload, modifier = full) { Text(stringResource(R.string.apa_detail_download)) }
        detail.inLibrary || detail.card.isFree ->
            Button(onClick = onGet, modifier = full) {
                Text(stringResource(if (detail.inLibrary) R.string.apa_detail_download else R.string.apa_detail_get))
            }
        else -> Button(onClick = onGet, modifier = full) {
            Text(stringResource(R.string.apa_detail_buy, rupees(detail.card.priceInr)))
        }
    }
}

@Composable
private fun Fact(label: Int, value: String?) {
    if (value.isNullOrBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
