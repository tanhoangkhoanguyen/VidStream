package io.github.tanhoangkhoanguyen.vidstream;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packagesOf = VidStreamApplication.class,
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule noPackageCycles =
      slices()
          .matching("io.github.tanhoangkhoanguyen.vidstream.(*)..")
          .should()
          .beFreeOfCycles()
          // No sub-packages yet; drop once the server skeleton adds them.
          .allowEmptyShould(true);
}
