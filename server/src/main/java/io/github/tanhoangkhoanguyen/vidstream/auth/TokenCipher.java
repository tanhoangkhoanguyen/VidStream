package io.github.tanhoangkhoanguyen.vidstream.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.tanhoangkhoanguyen.vidstream.config.VidStreamProperties;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** AES-256-GCM for Box tokens at rest. Stored as random 12-byte nonce, then ciphertext and tag. */
@Component
public class TokenCipher {

  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  @Autowired
  public TokenCipher(VidStreamProperties props) {
    this(
        Base64.getDecoder()
            .decode(Objects.requireNonNull(props.tokenKey(), "vidstream.token-key is not set")));
  }

  TokenCipher(byte[] key) {
    if (key.length != 32)
      throw new IllegalArgumentException("vidstream.token-key must be 32 bytes");
    this.key = new SecretKeySpec(key, "AES");
  }

  public byte[] encrypt(String token) {
    var nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      var sealed = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(token.getBytes(UTF_8));
      return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("token encryption failed", e);
    }
  }

  /** Fails if the value was changed or encrypted with another key. */
  public String decrypt(byte[] stored) {
    try {
      var nonce = Arrays.copyOf(stored, NONCE_BYTES);
      var plain =
          cipher(Cipher.DECRYPT_MODE, nonce)
              .doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
      return new String(plain, UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("token decryption failed", e);
    }
  }

  private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
    return cipher;
  }
}
