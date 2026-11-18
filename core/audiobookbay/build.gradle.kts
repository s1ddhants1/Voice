plugins {
  id("voice.library")
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.logging.api)

  implementation(libs.coroutines.core)
  implementation(libs.coroutines.android)
  implementation(libs.okhttp)
  implementation(libs.jsoup)
}
