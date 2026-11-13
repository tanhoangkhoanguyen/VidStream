plugins {
  java
  alias(libs.plugins.spring.boot)
}

group = "io.github.tanhoangkhoanguyen"

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
  // Gradle platform instead of the dependency-management plugin: same BOM, less build logic.
  implementation(platform(libs.spring.boot.bom))
  implementation(libs.spring.boot.starter)

  testImplementation(libs.spring.boot.starter.test)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

// Only the Boot jar ships.
tasks.jar { enabled = false }
