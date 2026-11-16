package io.github.tanhoangkhoanguyen.vidstream.auth;

import java.util.SortedSet;
import java.util.TreeSet;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** One {@code DATASET_<name>} authority per dataset the user may enter, granted at sign-in. */
public final class DatasetAuthorities {

  private static final String PREFIX = "DATASET_";

  private DatasetAuthorities() {}

  public static GrantedAuthority of(String dataset) {
    return new SimpleGrantedAuthority(PREFIX + dataset);
  }

  /** Dataset names, sorted. */
  public static SortedSet<String> names(Authentication auth) {
    var names = new TreeSet<String>();
    for (var a : auth.getAuthorities()) {
      var authority = a.getAuthority();
      if (authority != null && authority.startsWith(PREFIX))
        names.add(authority.substring(PREFIX.length()));
    }
    return names;
  }
}
