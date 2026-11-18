package voice.core.audiobookbay

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

const val DEFAULT_AUDIOBOOKBAY_URL: String = "https://audiobookbay.lu"

@Inject
@SingleIn(AppScope::class)
class AudioBookBayClient(
  private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .followRedirects(true)
    .build(),
  initialBaseUrl: String = DEFAULT_AUDIOBOOKBAY_URL,
) {
  var baseUrl: String = normalizeBaseUrl(initialBaseUrl)
    get() = field.removeSuffix("/")
    set(value) {
      field = normalizeBaseUrl(value)
    }

  companion object {
    fun normalizeBaseUrl(url: String): String {
      val trimmed = url.trim().removeSuffix("/")
      if (trimmed.isBlank()) return DEFAULT_AUDIOBOOKBAY_URL
      return if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        "https://$trimmed"
      } else {
        trimmed
      }
    }
  }

  private suspend fun fetchHtml(path: String): String = withContext(Dispatchers.IO) {
    val url = if (path.startsWith("/")) "$baseUrl$path" else path
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
      .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
      .header("Accept-Language", "en-US,en;q=0.5")
      .build()

    okHttpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IOException("HTTP ${response.code} fetching $url")
      }
      response.body.string()
    }
  }

  suspend fun searchAudiobooks(
    query: String,
    page: Int = 1,
  ): SearchResult {
    val abbPage1 = (page - 1) * 2 + 1
    val abbPage2 = (page - 1) * 2 + 2
    val encoded = withContext(Dispatchers.IO) { URLEncoder.encode(query, "UTF-8") }

    val path1 = if (abbPage1 > 1) "/page/$abbPage1/?s=$encoded" else "/?s=$encoded"
    val path2 = "/page/$abbPage2/?s=$encoded"

    val html1 = fetchHtml(path1)
    val abbTotalPages = AudioBookBayParser.parseTotalPages(html1)
    val results = AudioBookBayParser.parseSearchResults(html1, baseUrl).toMutableList()

    if (abbTotalPages >= abbPage2) {
      runCatching { fetchHtml(path2) }.getOrNull()?.let { html2 ->
        results.addAll(AudioBookBayParser.parseSearchResults(html2, baseUrl))
      }
    }

    val unique = results.distinctBy { it.id }
    val totalPages = maxOf(1, (abbTotalPages + 1) / 2)
    return SearchResult(results = unique, page = page, totalPages = totalPages)
  }

  suspend fun getCategoryAudiobooks(
    categorySlug: String,
    page: Int = 1,
  ): SearchResult {
    val aliases = mapOf(
      "sci-fi" to "sci-fi",
      "scifi" to "sci-fi",
      "science-fiction" to "sci-fi",
      "biographies" to "autobiography-biographies",
      "biography" to "autobiography-biographies",
      "thriller" to "crime",
      "fiction" to "general-fiction",
    )
    val resolvedSlug = aliases[categorySlug.lowercase()] ?: categorySlug

    val abbPage1 = (page - 1) * 2 + 1
    val abbPage2 = (page - 1) * 2 + 2

    val path1 = if (abbPage1 > 1) "/audio-books/type/$resolvedSlug/page/$abbPage1/" else "/audio-books/type/$resolvedSlug/"
    val path2 = "/audio-books/type/$resolvedSlug/page/$abbPage2/"

    val html1 = fetchHtml(path1)
    val abbTotalPages = AudioBookBayParser.parseTotalPages(html1)
    val results = AudioBookBayParser.parseSearchResults(html1, baseUrl).toMutableList()

    if (abbTotalPages >= abbPage2) {
      runCatching { fetchHtml(path2) }.getOrNull()?.let { html2 ->
        results.addAll(AudioBookBayParser.parseSearchResults(html2, baseUrl))
      }
    }

    val unique = results.distinctBy { it.id }
    val totalPages = maxOf(1, (abbTotalPages + 1) / 2)
    return SearchResult(results = unique, page = page, totalPages = totalPages)
  }

  suspend fun getAudiobookDetail(slug: String): AudiobookDetail? {
    val cleanSlug = slug.removePrefix("/").removeSuffix("/").substringAfterLast("/")
    val path = if (slug.startsWith("http")) slug else "/abss/$cleanSlug/"
    val html = fetchHtml(path)
    return AudioBookBayParser.parseAudiobookDetail(html, cleanSlug, baseUrl)
  }
}
