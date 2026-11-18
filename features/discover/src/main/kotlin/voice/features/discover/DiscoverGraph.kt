package voice.features.discover

import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.navigation.Destination
import voice.navigation.NavEntryProvider

@ContributesTo(AppScope::class)
interface DiscoverGraph {
  val discoverViewModel: DiscoverViewModel
  val discoverDetailViewModelFactory: DiscoverDetailViewModel.Factory
}

@BindingContainer
@ContributesTo(AppScope::class)
object DiscoverProvider {

  @Provides
  @IntoSet
  fun discoverNavEntryProvider(): NavEntryProvider<*> =
    NavEntryProvider<Destination.Discover> { key ->
      NavEntry(key) {
        DiscoverScreen()
      }
    }

  @Provides
  @IntoSet
  fun discoverDetailNavEntryProvider(): NavEntryProvider<*> =
    NavEntryProvider<Destination.DiscoverDetail> { key ->
      NavEntry(key) {
        DiscoverDetailScreen(slug = key.slug)
      }
    }
}
