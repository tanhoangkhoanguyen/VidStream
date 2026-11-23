package io.github.tanhoangkhoanguyen.vidstream.worker;

import org.springframework.core.ResolvableType;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/** Work and results cross the {@link JobSource} as JSON, in process as over HTTPS. */
public final class WireJson {

  public static final JsonMapper MAPPER = new JsonMapper();

  private WireJson() {}

  /**
   * The JSON type of {@code impl}'s type argument {@code index} of {@code generic}, e.g. a
   * JobExecutor's work type. Resolved once, at startup: a missing one fails it.
   */
  public static JavaType typeArgument(Object impl, Class<?> generic, int index) {
    var argument = ResolvableType.forClass(generic, impl.getClass()).getGeneric(index);
    if (argument.resolve() == null)
      throw new IllegalStateException(
          impl.getClass().getName() + " does not name the type arguments of " + generic.getName());
    return MAPPER.constructType(argument.getType());
  }
}
