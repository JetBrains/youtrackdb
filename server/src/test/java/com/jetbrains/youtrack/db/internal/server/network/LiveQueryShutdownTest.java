package com.jetbrains.youtrack.db.internal.server.network;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrack.db.api.remote.query.RemoteLiveQueryResultListener;
import com.jetbrains.youtrack.db.api.remote.query.RemoteResult;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.junit.Test;

public class LiveQueryShutdownTest {

  private YouTrackDBServer server;

  public void bootServer() throws Exception {
    server = new YouTrackDBServer(false);
    server.startup(getClass().getResourceAsStream("youtrackdb-server-config.xml"));
    server.activate();

    try (var youTrackDb = YourTracks.remote("remote:localhost", "root", "root")) {
      youTrackDb.createIfNotExists(LiveQueryShutdownTest.class.getSimpleName(),
          DatabaseType.MEMORY, "admin", "admin", "admin");
    }
  }

  public void shutdownServer() {
    server.shutdown();
    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(server.getDatabaseDirectory()));
    YouTrackDBEnginesManager.instance().startup();
  }

  @Test
  public void testShutDown() throws Exception {
    bootServer();
    final var end = new CountDownLatch(1);
    try (var youTrackDd = YourTracks.remote("remote:localhost", "root", "root")) {
      try (var db = youTrackDd.open(
          LiveQueryShutdownTest.class.getSimpleName(), "admin", "admin")) {
        db.command("create class Test");
        youTrackDd.live(LiveQueryShutdownTest.class.getSimpleName(), "admin", "admin",
            "select from Test",
            new RemoteLiveQueryResultListener() {

              @Override
              public void onCreate(@Nonnull RemoteDatabaseSession session,
                  @Nonnull RemoteResult data) {

              }

              @Override
              public void onUpdate(@Nonnull RemoteDatabaseSession session,
                  @Nonnull RemoteResult before,
                  @Nonnull RemoteResult after) {

              }

              @Override
              public void onDelete(@Nonnull RemoteDatabaseSession session,
                  @Nonnull RemoteResult data) {

              }

              @Override
              public void onError(@Nonnull RemoteDatabaseSession session,
                  @Nonnull BaseException exception) {

              }

              @Override
              public void onEnd(@Nonnull RemoteDatabaseSession session) {
                end.countDown();
              }
            });
      }
    }
    shutdownServer();

    assertTrue("onEnd method never called on shutdown", end.await(2, TimeUnit.SECONDS));
  }
}
