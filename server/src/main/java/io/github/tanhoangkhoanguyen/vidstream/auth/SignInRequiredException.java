package io.github.tanhoangkhoanguyen.vidstream.auth;

/** The user has no usable Box token until they sign in again. */
public class SignInRequiredException extends RuntimeException {

  public SignInRequiredException(long userId) {
    super("user " + userId + " must sign in to Box again");
  }
}
