plugins {
  java
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spotless)
  jacoco
}

group = "io.github.tanhoangkhoanguyen"

fun git(vararg args: String): Provider<String> =
  providers
    .exec {
      commandLine("git", *args)
      isIgnoreExitValue = true
    }
    .standardOutput
    .asText
    .map { it.trim().ifEmpty { null } }

// Tag v1.2.0 -> 1.2.0; no tag -> 0.0.0-SNAPSHOT+<sha>. "-dirty" marks uncommitted changes.
version =
  git("describe", "--tags", "--dirty", "--match", "v[0-9]*")
    .map { it.removePrefix("v") }
    .orElse(git("describe", "--always", "--dirty").map { "0.0.0-SNAPSHOT+$it" })
    .get()

// No timestamp: the same commit gives the same jar.
springBoot { buildInfo { excludes = setOf("time") } }

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
  // Gradle platform instead of the dependency-management plugin: same BOM, less build logic.
  implementation(platform(libs.spring.boot.bom))
  implementation(libs.spring.boot.starter)

  testImplementation(libs.spring.boot.starter.test)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// `check` fails on unformatted code; `spotlessApply` fixes it.
spotless { java { googleJavaFormat(libs.versions.google.java.format.get()) } }

tasks.test {
  useJUnitPlatform()
  finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestCoverageVerification {
  // Left out of the gate. A pattern that matches nothing fails the build.
  val excludes = listOf("**/VidStreamApplication.class")
  val classes = sourceSets.main.get().output.classesDirs
  classDirectories.setFrom(classes.asFileTree.matching { exclude(excludes) })
  violationRules {
    rule {
      limit {
        counter = "LINE"
        minimum = "0.80".toBigDecimal()
      }
      limit {
        counter = "BRANCH"
        minimum = "0.60".toBigDecimal()
      }
    }
  }
  doFirst {
    val stale = excludes.filter { p -> classes.asFileTree.matching { include(p) }.isEmpty }
    if (stale.isNotEmpty()) throw GradleException("Coverage excludes match nothing: $stale")
  }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

// Only the Boot jar ships.
tasks.jar { enabled = false }
