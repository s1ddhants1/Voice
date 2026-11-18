plugins {
  id("voice.feature")
}

dependencies {
  implementation(projects.core.torrent)
  implementation(projects.core.audiobookbay)
  implementation(projects.core.data.api)
  implementation(projects.core.playback.api)

  implementation(libs.coil)
  implementation(libs.lifecycle.compose)
  implementation(libs.compose.material3)
}
