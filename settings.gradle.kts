plugins {
  // Downloads the Java toolchain when it is missing locally.
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vidstream"

include("server")
