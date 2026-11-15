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
  implementation(libs.spring.boot.starter.webmvc)
  implementation(libs.spring.boot.starter.actuator)
  implementation(libs.spring.boot.starter.flyway)
  runtimeOnly(libs.micrometer.registry.prometheus)
  runtimeOnly(libs.flyway.database.postgresql)
  runtimeOnly(libs.postgresql)

  testImplementation(libs.spring.boot.starter.webmvc.test)
  testImplementation(libs.spring.boot.starter.jdbc.test)
  testImplementation(libs.spring.boot.testcontainers)
  testImplementation(libs.testcontainers.postgresql)
  testImplementation(libs.archunit.junit5)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// `check` fails on unformatted code; `spotlessApply` fixes it.
spotless { java { googleJavaFormat(libs.versions.google.java.format.get()) } }

// `*Test`: unit, no I/O. `*IT`: containers, needs Docker.
tasks.test {
  useJUnitPlatform()
  exclude("**/*IT.class")
}

val integrationTest =
  tasks.register<Test>("integrationTest") {
    description = "Runs *IT tests against containers."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/*IT.class")
    shouldRunAfter(tasks.test)
  }

// Coverage counts unit and integration tests together.
tasks.withType<JacocoReportBase>().configureEach {
  executionData(tasks.test.get(), integrationTest.get())
}

tasks.jacocoTestCoverageVerification {
  finalizedBy(tasks.jacocoTestReport)
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

tasks.check { dependsOn(integrationTest, tasks.jacocoTestCoverageVerification) }

// Only the Boot jar ships, under a fixed name for the Dockerfile; the version is inside.
tasks.jar { enabled = false }

tasks.bootJar { archiveFileName = "vidstream.jar" }
