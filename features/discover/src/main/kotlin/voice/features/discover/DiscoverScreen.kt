package voice.features.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import voice.core.audiobookbay.AudiobookSummary
import voice.core.audiobookbay.POPULAR_CATEGORIES
import voice.core.common.rootGraphAs
import voice.core.ui.R as UiR

@Composable
fun DiscoverScreen() {
  val graph = rootGraphAs<DiscoverGraph>()
  val viewModel = retain { graph.discoverViewModel }
  val state by viewModel.state.collectAsState()

  DiscoverContent(
    state = state,
    onQueryChange = viewModel::onQueryChange,
    onSearch = viewModel::search,
    onCategorySelect = viewModel::onCategorySelect,
    onBookClick = viewModel::onBookClick,
    onBack = viewModel::onBackClick,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoverContent(
  state: DiscoverViewState,
  onQueryChange: (String) -> Unit,
  onSearch: () -> Unit,
  onCategorySelect: (String) -> Unit,
  onBookClick: (String) -> Unit,
  onBack: () -> Unit,
) {
  val keyboardController = LocalSoftwareKeyboardController.current

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = "Discover Audiobooks",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              painter = painterResource(UiR.drawable.ic_arrow_back),
              contentDescription = "Back",
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        ),
      )
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    ) {
      // Search Bar
      OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search title, author, or series...") },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        leadingIcon = {
          Icon(
            painter = painterResource(UiR.drawable.ic_search),
            contentDescription = "Search",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
        trailingIcon = {
          if (state.query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange(""); onSearch() }) {
              Icon(
                painter = painterResource(UiR.drawable.ic_close),
                contentDescription = "Clear",
              )
            }
          }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
          onSearch = {
            keyboardController?.hide()
            onSearch()
          },
        ),
      )

      // Category filter chips row
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        POPULAR_CATEGORIES.forEach { category ->
          val isSelected = state.selectedCategory == category.slug && state.query.isBlank()
          FilterChip(
            selected = isSelected,
            onClick = {
              keyboardController?.hide()
              onCategorySelect(category.slug)
            },
            label = { Text(category.name) },
            shape = RoundedCornerShape(16.dp),
          )
        }
      }

      Spacer(modifier = Modifier.height(4.dp))

      // Content Area: Loading, Error, or Results Grid
      Box(
        modifier = Modifier
          .fillMaxSize()
          .weight(1f),
      ) {
        when {
          state.isLoading -> {
            Column(
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              CircularProgressIndicator()
              Spacer(modifier = Modifier.height(16.dp))
              Text(
                text = "Searching AudioBookBay...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          state.errorMessage != null -> {
            Column(
              modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
              )
              Spacer(modifier = Modifier.height(16.dp))
              Button(onClick = onSearch) {
                Text("Retry")
              }
            }
          }

          state.results.isEmpty() -> {
            Column(
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(
                text = "No audiobooks found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          else -> {
            LazyVerticalGrid(
              columns = GridCells.Fixed(2),
              contentPadding = PaddingValues(16.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalArrangement = Arrangement.spacedBy(16.dp),
              modifier = Modifier.fillMaxSize(),
            ) {
              items(state.results, key = { it.id }) { book ->
                AudiobookCard(
                  book = book,
                  onClick = { onBookClick(book.slug) },
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun AudiobookCard(
  book: AudiobookSummary,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ),
  ) {
    Column {
      AsyncImage(
        model = book.cover,
        contentDescription = book.title,
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(1f)
          .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
        contentScale = ContentScale.Crop,
        error = painterResource(UiR.drawable.album_art),
      )
      Column(modifier = Modifier.padding(10.dp)) {
        Text(
          text = book.title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        val author = book.author
        if (!author.isNullOrBlank()) {
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          val format = book.format
          if (!format.isNullOrBlank()) {
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = MaterialTheme.colorScheme.primaryContainer,
            ) {
              Text(
                text = format,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
              )
            }
          }
          if (book.seeders > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(6.dp)
                  .clip(RoundedCornerShape(3.dp))
                  .padding(end = 4.dp),
              )
              Text(
                text = "${book.seeders} seeds",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF2E7D32),
              )
            }
          }
        }
      }
    }
  }
}
