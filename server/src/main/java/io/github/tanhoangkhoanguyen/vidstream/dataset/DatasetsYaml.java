package io.github.tanhoangkhoanguyen.vidstream.dataset;

import java.util.HashSet;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Parses {@code datasets.yml}: a list of datasets with snake_case keys. */
public final class DatasetsYaml {

  // Strict: a typo, a missing key or a null fails instead of silently becoming 0.
  private static final YAMLMapper MAPPER =
      YAMLMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .enable(
              DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
              DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();

  private DatasetsYaml() {}

  public static List<DatasetSpec> parse(String yaml) {
    List<DatasetSpec> specs;
    try {
      specs = MAPPER.readValue(yaml, new TypeReference<>() {});
    } catch (JacksonException e) {
      // The spec's own message when its validation failed; Jackson's otherwise.
      var cause = e.getCause() instanceof IllegalArgumentException iae ? iae : e;
      throw new IllegalArgumentException("datasets.yml: " + cause.getMessage(), e);
    }
    var names = new HashSet<String>();
    var folders = new HashSet<String>();
    for (var spec : specs) {
      if (!names.add(spec.name())) {
        throw new IllegalArgumentException("datasets.yml: duplicate name " + spec.name());
      }
      if (!folders.add(spec.boxFolderId())) {
        throw new IllegalArgumentException(
            "datasets.yml: duplicate box_folder_id " + spec.boxFolderId());
      }
    }
    return specs;
  }
}
