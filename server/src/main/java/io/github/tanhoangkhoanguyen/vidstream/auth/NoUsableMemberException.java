package io.github.tanhoangkhoanguyen.vidstream.auth;

/** No member of the dataset has a usable Box token: its work waits until one signs in. */
public class NoUsableMemberException extends RuntimeException {

  public NoUsableMemberException(long datasetId) {
    super("dataset " + datasetId + ": a member must sign in to Box");
  }
}
