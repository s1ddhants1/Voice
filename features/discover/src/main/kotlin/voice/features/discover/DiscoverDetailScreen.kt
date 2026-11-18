package voice.features.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import voice.core.audiobookbay.AudiobookDetail
import voice.core.audiobookbay.AudiobookFile
import voice.core.common.rootGraphAs
import voice.core.torrent.TorrentStreamState
import voice.core.ui.R as UiR

@Composable
fun DiscoverDetailScreen(slug: String) {
  val graph = rootGraphAs<DiscoverGraph>()
  val viewModel = retain(slug) {
    graph.discoverDetailViewModelFactory.create(slug)
  }
  val state by viewModel.state.collectAsState()

  DiscoverDetailContent(
    state = state,
    onStartStream = viewModel::startStream,
    onBack = viewModel::onBackClick,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoverDetailContent(
  state: DiscoverDetailViewState,
  onStartStream: (chapterIndex: Int, fileName: String?) -> Unit,
  onBack: () -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = state.detail?.title ?: "Book Details",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
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
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
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
              text = "Loading book details...",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        state.errorMessage != null && state.detail == null -> {
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
            OutlinedButton(onClick = onBack) {
              Text("Go Back")
            }
          }
        }

        state.detail != null -> {
          val detail = state.detail
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            // Header Info: Cover + Title + Metadata
            item {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
              ) {
                AsyncImage(
                  model = detail.cover,
                  contentDescription = detail.title,
                  modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(8.dp))
                    .shadow(4.dp, RoundedCornerShape(8.dp)),
                  contentScale = ContentScale.Crop,
                  error = painterResource(UiR.drawable.album_art),
                )
                Column(
                  modifier = Modifier.weight(1f),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Text(
                    text = detail.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                  )
                  if (!detail.author.isNullOrBlank()) {
                    Text(
                      text = "By ${detail.author}",
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.primary,
                    )
                  }
                  if (!detail.narrator.isNullOrBlank()) {
                    Text(
                      text = "Narrated by ${detail.narrator}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  if (!detail.duration.isNullOrBlank()) {
                    Text(
                      text = "Length: ${detail.duration}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                  ) {
                    val format = detail.format
                    if (!format.isNullOrBlank()) {
                      Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                      ) {
                        Text(
                          text = format,
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.onSecondaryContainer,
                          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                      }
                    }
                    if (detail.seeders > 0) {
                      Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFE8F5E9),
                      ) {
                        Text(
                          text = "${detail.seeders} seeders",
                          style = MaterialTheme.typography.labelSmall,
                          color = Color(0xFF2E7D32),
                          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                      }
                    }
                  }
                }
              }
            }

            // Primary "Stream Audiobook" Button
            item {
              val streamState = state.torrentStreamState
              val isConnectingOrBuffering = state.isStartingStream ||
                streamState is TorrentStreamState.Connecting ||
                streamState is TorrentStreamState.FetchingMetadata ||
                streamState is TorrentStreamState.Buffering

              Button(
                onClick = { onStartStream(0, null) },
                enabled = !isConnectingOrBuffering && detail.magnetUri.isNotBlank(),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
                ),
              ) {
                if (isConnectingOrBuffering) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                  )
                  Spacer(modifier = Modifier.width(10.dp))
                  val statusText = when (streamState) {
                    is TorrentStreamState.FetchingMetadata -> "Fetching torrent metadata..."
                    is TorrentStreamState.Buffering -> "Buffering ${(streamState.progress * 100).toInt()}% (${streamState.peers} peers)..."
                    else -> "Connecting to peers..."
                  }
                  Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleSmall,
                  )
                } else {
                  Icon(
                    painter = painterResource(UiR.drawable.ic_play_arrow),
                    contentDescription = "Stream",
                    modifier = Modifier.size(20.dp),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = "Stream Audiobook",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                }
              }
            }

            // Expandable Description
            if (detail.description.isNotBlank()) {
              item {
                DescriptionSection(description = detail.description)
              }
            }

            // Chapters / Files Section
            val audioFiles = detail.files.filter { it.isAudio }.ifEmpty { detail.files }
            if (audioFiles.isNotEmpty()) {
              item {
                Text(
                  text = "Chapters & Audio Files (${audioFiles.size})",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(top = 8.dp),
                )
              }

              itemsIndexed(audioFiles) { index, file ->
                AudioFileItem(
                  file = file,
                  index = index,
                  onClick = { onStartStream(file.index, file.name) },
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
private fun DescriptionSection(description: String) {
  var expanded by remember { mutableStateOf(false) }

  Card(
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(
        text = "Synopsis",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
      )
      if (description.length > 200) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = if (expanded) "Show Less" else "Read More",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.clickable { expanded = !expanded },
        )
      }
    }
  }
}

@Composable
private fun AudioFileItem(
  file: AudiobookFile,
  index: Int,
  onClick: () -> Unit,
) {
  Card(
    shape = RoundedCornerShape(8.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
    ),
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "${index + 1}.",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.width(32.dp),
        )
        Column {
          Text(
            text = file.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val duration = file.duration
            if (!duration.isNullOrBlank()) {
              Text(
                text = duration,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            val size = file.size
            if (!size.isNullOrBlank()) {
              Text(
                text = size,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
      IconButton(onClick = onClick) {
        Icon(
          painter = painterResource(UiR.drawable.ic_play_arrow),
          contentDescription = "Play Chapter",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}
