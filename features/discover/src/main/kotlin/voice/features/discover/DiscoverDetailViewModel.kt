package voice.features.discover

import android.content.Context
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Clock
import voice.core.audiobookbay.AudioBookBayClient
import voice.core.audiobookbay.AudioBookBayParser
import voice.core.audiobookbay.AudiobookDetail
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookMetadata
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.ChapterRepo
import voice.core.data.store.CurrentBookStore
import voice.core.logging.api.Logger
import voice.core.playback.PlayerController
import voice.core.torrent.TorrentStatus
import voice.core.torrent.TorrentStreamEngine
import voice.core.torrent.TorrentStreamState
import voice.navigation.Destination
import voice.navigation.Navigator

data class DiscoverDetailViewState(
  val detail: AudiobookDetail? = null,
  val isLoading: Boolean = true,
  val errorMessage: String? = null,
  val isStartingStream: Boolean = false,
  val torrentStatus: TorrentStatus = TorrentStatus(),
  val torrentStreamState: TorrentStreamState = TorrentStreamState.Idle,
)

@AssistedInject
class DiscoverDetailViewModel(
  private val context: Context,
  private val client: AudioBookBayClient,
  private val torrentEngine: TorrentStreamEngine,
  private val bookContentRepo: BookContentRepo,
  private val chapterRepo: ChapterRepo,
  private val currentBookStore: CurrentBookStore,
  private val player: PlayerController,
  private val navigator: Navigator,
  @Assisted private val slug: String,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private val _state = MutableStateFlow(DiscoverDetailViewState())
  val state: StateFlow<DiscoverDetailViewState> = _state

  init {
    loadDetail()
    scope.launch {
      torrentEngine.status.collect { status ->
        _state.value = _state.value.copy(
          torrentStatus = status,
          torrentStreamState = status.state,
        )
      }
    }
  }

  private fun loadDetail() {
    scope.launch {
      _state.value = _state.value.copy(isLoading = true, errorMessage = null)
      try {
        val detail = client.getAudiobookDetail(slug)
        if (detail == null) {
          _state.value = _state.value.copy(
            isLoading = false,
            errorMessage = "Book details not found",
          )
        } else {
          _state.value = _state.value.copy(
            isLoading = false,
            detail = detail,
          )
        }
      } catch (e: Exception) {
        Logger.w(e, "Failed to load detail for $slug")
        _state.value = _state.value.copy(
          isLoading = false,
          errorMessage = "Could not fetch book details: ${e.message}",
        )
      }
    }
  }

  fun startStream(chapterIndex: Int = 0, fileName: String? = null) {
    val detail = _state.value.detail ?: return
    if (detail.magnetUri.isBlank()) {
      _state.value = _state.value.copy(errorMessage = "No magnet link available for this audiobook")
      return
    }

    scope.launch {
      _state.value = _state.value.copy(isStartingStream = true)
      try {
        val initialStreamUrl = torrentEngine.streamFromMagnet(
          magnetUri = detail.magnetUri,
          chapterIndex = chapterIndex,
          fileName = fileName,
        )

        val bookId = BookId("stream:${detail.id}")
        val audioFiles = detail.files.filter { it.isAudio }.ifEmpty { detail.files }
        val chapterList = audioFiles.mapIndexed { index, file ->
          val chapterUrl = torrentEngine.getStreamUrl(file.index)
          val parsedTrackDuration = AudioBookBayParser.parseDurationToMillis(file.duration)
          val totalDurationMs = AudioBookBayParser.parseDurationToMillis(detail.duration) ?: 3600000L
          val durationMs = parsedTrackDuration ?: (totalDurationMs / audioFiles.size.coerceAtLeast(1))
          Chapter(
            id = ChapterId(chapterUrl),
            name = file.name,
            duration = durationMs.coerceAtLeast(1000L),
            fileLastModified = Clock.System.now(),
            fileSize = AudioBookBayParser.parseSizeInBytes(file.size) ?: 0L,
            markData = emptyList(),
          )
        }

        val coverFile = withContext(Dispatchers.IO) {
          try {
            val coverDir = File(context.filesDir, "bookCovers").also { it.mkdirs() }
            val dest = File(coverDir, "stream_${detail.id.hashCode()}.jpg")
            if ((!dest.exists() || dest.length() == 0L) && detail.cover.isNotBlank()) {
              java.net.URL(detail.cover).openStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
              }
            }
            if (dest.exists() && dest.length() > 0) dest else null
          } catch (e: Exception) {
            Logger.w(e, "Failed to download cover art for ${detail.title}")
            null
          }
        }

        val initialChapter = chapterList.getOrNull(chapterIndex) ?: chapterList.first()
        val bookContent = BookContent(
          id = bookId,
          playbackSpeed = 1.0f,
          skipSilence = false,
          isActive = true,
          lastPlayedAt = Clock.System.now(),
          addedAt = Clock.System.now(),
          chapters = chapterList.map { it.id },
          currentChapter = initialChapter.id,
          positionInChapter = 0L,
          gain = 0.0f,
          metadata = BookMetadata(
            name = detail.title,
            author = detail.author,
            album = detail.title,
            title = detail.title,
            genre = detail.categories.firstOrNull(),
            narrator = detail.narrator,
            series = null,
            part = null,
            cover = coverFile,
            fileLastModified = Clock.System.now(),
            noEmbeddedCover = true,
          ),
          serverLibraryId = null,
        )

        for (c in chapterList) {
          chapterRepo.put(c)
        }
        bookContentRepo.put(bookContent)
        currentBookStore.updateData { bookId }

        _state.value = _state.value.copy(isStartingStream = false)
        navigator.goTo(Destination.Playback(bookId))
      } catch (e: Exception) {
        Logger.w(e, "Error starting stream for $slug")
        _state.value = _state.value.copy(
          isStartingStream = false,
          errorMessage = "Stream failed to start: ${e.message}",
        )
      }
    }
  }

  fun onBackClick() {
    navigator.goBack()
  }

  @AssistedFactory
  fun interface Factory {
    fun create(slug: String): DiscoverDetailViewModel
  }
}
