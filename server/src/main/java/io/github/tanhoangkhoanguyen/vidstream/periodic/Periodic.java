package io.github.tanhoangkhoanguyen.vidstream.periodic;

import io.github.tanhoangkhoanguyen.vidstream.auth.TokenVault;
import io.github.tanhoangkhoanguyen.vidstream.video.OrphanClips;
import io.github.tanhoangkhoanguyen.vidstream.video.VideoScan;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The web's periodic work, each run on one replica: every replica's timer fires (a timer sleeps, no
 * thread waits), and only the one that claims the run does it.
 */
@Component
@Profile("web")
class Periodic {

  private static final Duration HOUR = Duration.ofHours(1);
  private static final Duration DAY = Duration.ofDays(1);
  private static final String ZONE = "America/New_York";

  private final Schedules schedules;
  private final VideoScan scan;
  private final TokenVault tokens;
  private final OrphanClips orphans;

  Periodic(Schedules schedules, VideoScan scan, TokenVault tokens, OrphanClips orphans) {
    this.schedules = schedules;
    this.scan = scan;
    this.tokens = tokens;
    this.orphans = orphans;
  }

  @Scheduled(cron = "0 0 3 * * *", zone = ZONE)
  void scanDatasets() {
    if (schedules.claim("scan-datasets", DAY)) scan.scanActive();
  }

  @Scheduled(cron = "0 0 4 * * *", zone = ZONE)
  void refreshIdleTokens() {
    if (schedules.claim("refresh-idle-tokens", DAY)) tokens.refreshIdle();
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
  void sweepOrphanClips() {
    if (schedules.claim("sweep-orphan-clips", HOUR)) orphans.sweep();
  }
}
