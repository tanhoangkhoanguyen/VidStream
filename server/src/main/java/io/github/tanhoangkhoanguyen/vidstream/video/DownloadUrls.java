package io.github.tanhoangkhoanguyen.vidstream.video;

import io.github.tanhoangkhoanguyen.vidstream.auth.MemberTokens;
import io.github.tanhoangkhoanguyen.vidstream.box.BoxClient;
import java.net.URI;

/**
 * A fresh download URL for a dataset's Box file: it expires in 10–15 minutes, so one per attempt.
 */
@FunctionalInterface
interface DownloadUrls {

  URI of(long datasetId, String boxFileId);

  /** With any member's token. */
  static DownloadUrls box(MemberTokens members, BoxClient box) {
    return (dataset, file) -> members.call(dataset, token -> box.downloadUrl(token, file));
  }
}
