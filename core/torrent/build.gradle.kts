plugins {
  id("voice.library")
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.logging.api)
  implementation(projects.core.data.api)

  implementation(libs.coroutines.core)
  implementation(libs.coroutines.android)

  implementation(libs.libtorrent4j)
  implementation(libs.libtorrent4j.android.arm64)
  implementation(libs.libtorrent4j.android.arm)
  implementation(libs.libtorrent4j.android.x86)
  implementation(libs.libtorrent4j.android.x64)
}
