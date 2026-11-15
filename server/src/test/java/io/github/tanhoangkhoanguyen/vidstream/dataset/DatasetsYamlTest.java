package io.github.tanhoangkhoanguyen.vidstream.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DatasetsYamlTest {

  static String dataset(String name, String folder) {
    return "- {name: %s, box_folder_id: \"%s\", clip_seconds: 12, min_last_clip_seconds: 5, fps: 30, chunk_clips: 50}\n"
        .formatted(name, folder);
  }

  @Test
  void parsesDatasets() {
    assertThat(DatasetsYaml.parse(dataset("a", "1") + dataset("b", "2")))
        .containsExactly(
            new DatasetSpec("a", "1", 12, 5, 30, 50), new DatasetSpec("b", "2", 12, 5, 30, 50));
  }

  @Test
  void parsesEmptyList() {
    assertThat(DatasetsYaml.parse("[]")).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          - {name: a}                                                                                                   | Missing creator property
          - {name: a, box_folder_id: "1", clip_seconds: 12, min_last_clip_seconds: 5, fps: 30, chunk_clips: 50, clip_second: 1} | Unrecognized property
          - {name: a, box_folder_id: "1", clip_seconds: ~, min_last_clip_seconds: 5, fps: 30, chunk_clips: 50}           | clip_seconds
          - {name: a, box_folder_id: "1", clip_seconds: 4, min_last_clip_seconds: 4, fps: 30, chunk_clips: 50}           | clip_seconds must be 5-60, got 4
          """)
  void rejectsBadEntry(String yaml, String expected) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DatasetsYaml.parse(yaml))
        .withMessageStartingWith("datasets.yml: ")
        .withMessageContaining(expected);
  }

  @Test
  void rejectsDuplicateName() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DatasetsYaml.parse(dataset("a", "1") + dataset("a", "2")))
        .withMessage("datasets.yml: duplicate name a");
  }

  @Test
  void rejectsDuplicateFolder() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> DatasetsYaml.parse(dataset("a", "1") + dataset("b", "1")))
        .withMessage("datasets.yml: duplicate box_folder_id 1");
  }
}
