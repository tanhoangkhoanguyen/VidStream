package io.github.tanhoangkhoanguyen.vidstream.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

  final TokenCipher cipher = new TokenCipher(new byte[32]);

  @Test
  void roundTrip() {
    assertThat(cipher.decrypt(cipher.encrypt("refresh-token"))).isEqualTo("refresh-token");
  }

  @Test
  void sameTokenEncryptsDifferently() {
    assertThat(cipher.encrypt("t")).isNotEqualTo(cipher.encrypt("t"));
  }

  @Test
  void rejectsTamperedValue() {
    var stored = cipher.encrypt("refresh-token");
    stored[stored.length - 1] ^= 1;
    assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(stored));
  }

  @Test
  void rejectsOtherKey() {
    var otherKey = new byte[32];
    Arrays.fill(otherKey, (byte) 1);
    var stored = new TokenCipher(otherKey).encrypt("refresh-token");
    assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(stored));
  }

  @Test
  void rejectsShortKey() {
    assertThatIllegalArgumentException().isThrownBy(() -> new TokenCipher(new byte[16]));
  }
}
