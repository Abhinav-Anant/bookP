package com.chmouel.liseur.ui.apa

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.chmouel.liseur.R
import com.chmouel.liseur.data.apa.BookCard
import java.text.NumberFormat

@Composable
fun ApaHomeScreen(viewModel: HomeViewModel, onOpenBook: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text(stringResource(R.string.apa_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val results = state.results
        when {
            results != null -> SearchResults(results, onOpenBook)
            state.rows.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            state.rows.isEmpty() -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(if (state.offline) R.string.apa_home_offline else R.string.apa_home_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = viewModel::refresh) { Text(stringResource(R.string.apa_retry)) }
            }
            else -> PullToRefreshBox(isRefreshing = state.loading, onRefresh = viewModel::refresh) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (state.offline) {
                        item {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(
                                    stringResource(R.string.apa_home_offline),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp, 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    items(state.rows, key = { it.key }) { row ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(rowTitle(row.key)),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(row.books, key = { it.id }) { book ->
                                    BookCardView(book, Modifier.width(120.dp)) { onOpenBook(book.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(results: List<BookCard>, onOpenBook: (String) -> Unit) {
    if (results.isEmpty()) {
        Text(
            stringResource(R.string.apa_search_empty),
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(results, key = { it.id }) { book -> BookCardView(book) { onOpenBook(book.id) } }
    }
}

@Composable
fun BookCardView(book: BookCard, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BookCover(book.coverUrl, book.title, Modifier.fillMaxWidth())
        Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        book.author?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            priceLabel(book),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
fun BookCover(url: String?, title: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Text(
                title,
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun priceLabel(book: BookCard): String =
    if (book.isFree) stringResource(R.string.apa_price_free) else stringResource(R.string.apa_price_inr, rupees(book.priceInr))

fun rupees(amount: Double): String =
    NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("en-IN")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = if (amount % 1.0 == 0.0) 0 else 2
    }.format(amount)

private fun rowTitle(key: String): Int = when (key) {
    "featured" -> R.string.apa_row_featured
    "editors_pick" -> R.string.apa_row_editors_pick
    "trending" -> R.string.apa_row_trending
    "new" -> R.string.apa_row_new
    "free" -> R.string.apa_row_free
    "wishlist" -> R.string.apa_row_wishlist
    else -> R.string.apa_row_featured
}
