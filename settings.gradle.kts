plugins {
  // Downloads the Java toolchain when it is missing locally.
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories { mavenCentral() }
}

rootProject.name = "vidstream"

include("server")
