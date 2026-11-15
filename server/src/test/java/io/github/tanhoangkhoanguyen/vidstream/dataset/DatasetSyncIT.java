package io.github.tanhoangkhoanguyen.vidstream.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.tanhoangkhoanguyen.vidstream.PostgresTestConfig;
import io.github.tanhoangkhoanguyen.vidstream.config.VidStreamProperties;
import io.github.tanhoangkhoanguyen.vidstream.config.VidStreamProperties.Role;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.simple.JdbcClient;

@JdbcTest(properties = "vidstream.role=web")
@Import(PostgresTestConfig.class)
class DatasetSyncIT {

  static final DatasetSpec A = new DatasetSpec("a", "1", 12, 5, 30, 50);
  static final DatasetSpec B = new DatasetSpec("b", "2", 12, 5, 30, 50);

  @Autowired JdbcClient jdbc;
  DatasetSync sync;

  @BeforeEach
  void setUp() {
    sync =
        new DatasetSync(
            jdbc,
            new VidStreamProperties(Role.WEB, new ByteArrayResource(new byte[0]), null, null));
  }

  List<String> rows() {
    return jdbc.sql("SELECT id || ':' || name || ':' || active FROM datasets ORDER BY id")
        .query(String.class)
        .list();
  }

  @Test
  void syncTwiceIsANoOp() {
    sync.sync(List.of(A, B));
    var first = rows();
    sync.sync(List.of(A, B));
    assertThat(rows()).isEqualTo(first).hasSize(2).allMatch(r -> r.endsWith(":true"));
  }

  @Test
  void changingClipSettingsFails() {
    sync.sync(List.of(A));
    var changed = new DatasetSpec("a", "1", 10, 5, 30, 50);
    assertThatIllegalStateException()
        .isThrownBy(() -> sync.sync(List.of(changed)))
        .withMessageContaining("dataset 'a' cannot change");
  }

  @Test
  void changingFolderFails() {
    sync.sync(List.of(A));
    assertThatIllegalStateException()
        .isThrownBy(() -> sync.sync(List.of(new DatasetSpec("a", "9", 12, 5, 30, 50))));
  }

  @Test
  void removedGoesInactiveAndReturns() {
    sync.sync(List.of(A, B));
    sync.sync(List.of(A));
    assertThat(rows())
        .satisfiesExactly(
            a -> assertThat(a).endsWith(":a:true"), b -> assertThat(b).endsWith(":b:false"));
    sync.sync(List.of(A, B));
    assertThat(rows()).allMatch(r -> r.endsWith(":true"));
  }
}
