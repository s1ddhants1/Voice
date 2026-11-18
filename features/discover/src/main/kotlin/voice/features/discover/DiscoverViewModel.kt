package voice.features.discover

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import voice.core.audiobookbay.AudioBookBayClient
import voice.core.audiobookbay.AudiobookSummary
import voice.core.logging.api.Logger
import voice.navigation.Destination
import voice.navigation.Navigator

data class DiscoverViewState(
  val query: String = "",
  val selectedCategory: String = "sci-fi",
  val isLoading: Boolean = false,
  val results: List<AudiobookSummary> = emptyList(),
  val errorMessage: String? = null,
  val page: Int = 1,
  val totalPages: Int = 1,
)

@Inject
@SingleIn(AppScope::class)
class DiscoverViewModel(
  private val client: AudioBookBayClient,
  private val navigator: Navigator,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var currentFetchJob: Job? = null

  private val _state = MutableStateFlow(DiscoverViewState())
  val state: StateFlow<DiscoverViewState> = _state

  init {
    loadCategory("sci-fi")
  }

  fun onCategorySelect(categorySlug: String) {
    if (_state.value.selectedCategory == categorySlug && _state.value.query.isBlank()) return
    _state.value = _state.value.copy(
      selectedCategory = categorySlug,
      query = "",
      page = 1,
    )
    loadCategory(categorySlug)
  }

  fun onQueryChange(query: String) {
    _state.value = _state.value.copy(query = query)
  }

  fun search() {
    val q = _state.value.query.trim()
    if (q.isBlank()) {
      loadCategory(_state.value.selectedCategory)
      return
    }
    currentFetchJob?.cancel()
    currentFetchJob = scope.launch {
      _state.value = _state.value.copy(isLoading = true, errorMessage = null)
      try {
        val result = client.searchAudiobooks(q, page = 1)
        _state.value = _state.value.copy(
          isLoading = false,
          results = result.results,
          page = result.page,
          totalPages = result.totalPages,
        )
      } catch (e: Exception) {
        Logger.w(e, "Search failed for: $q")
        _state.value = _state.value.copy(
          isLoading = false,
          errorMessage = "Could not fetch search results: ${e.message}",
        )
      }
    }
  }

  fun loadCategory(categorySlug: String) {
    currentFetchJob?.cancel()
    currentFetchJob = scope.launch {
      _state.value = _state.value.copy(isLoading = true, errorMessage = null)
      try {
        val result = client.getCategoryAudiobooks(categorySlug, page = 1)
        _state.value = _state.value.copy(
          isLoading = false,
          results = result.results,
          page = result.page,
          totalPages = result.totalPages,
        )
      } catch (e: Exception) {
        Logger.w(e, "Category load failed for: $categorySlug")
        _state.value = _state.value.copy(
          isLoading = false,
          errorMessage = "Could not load category: ${e.message}",
        )
      }
    }
  }

  fun onBookClick(slug: String) {
    navigator.goTo(Destination.DiscoverDetail(slug))
  }

  fun onBackClick() {
    navigator.goBack()
  }
}
