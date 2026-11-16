package io.github.tanhoangkhoanguyen.vidstream.box;

import io.github.tanhoangkhoanguyen.vidstream.Fixtures;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Folder listings built from recorded entries: only IDs, extensions and hashes vary. */
public final class BoxPages {

  private static final JsonMapper JSON = new JsonMapper();

  private BoxPages() {}

  public static ObjectNode file(String id, String extension, String sha1) {
    return recorded("box/file.json").put("id", id).put("extension", extension).put("sha1", sha1);
  }

  public static ObjectNode folder(String id) {
    return ((ObjectNode) recorded("box/folder-items.json").get("entries").get(0)).put("id", id);
  }

  /** One page of a listing of {@code totalCount} entries. */
  public static String page(int totalCount, int offset, ObjectNode... entries) {
    var page = recorded("box/folder-items.json");
    page.put("total_count", totalCount).put("offset", offset).put("limit", entries.length);
    page.putArray("entries").addAll(List.of(entries));
    return page.toString();
  }

  private static ObjectNode recorded(String fixture) {
    return (ObjectNode) JSON.readTree(Fixtures.read(fixture));
  }
}
