package com.jetbrains.youtrack.db.internal.server.network;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.client.remote.ServerAdmin;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
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

    var server = new ServerAdmin("remote:localhost");
    server.connect("root", "root");
    server.createDatabase(LiveQueryShutdownTest.class.getSimpleName(), "graph", "memory");
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
      youTrackDd.createIfNotExists(LiveQueryShutdownTest.class.getSimpleName(),
          DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var db = youTrackDd.open(
          LiveQueryShutdownTest.class.getSimpleName(), "admin", "admin")) {
        db.getSchema().createClass("Test");
        youTrackDd.live(LiveQueryShutdownTest.class.getSimpleName(), "admin", "admin",
            "live select from Test",
            new LiveQueryResultListener() {

              @Override
              public void onCreate(@Nonnull DatabaseSessionInternal session, @Nonnull Result data) {

              }

              @Override
              public void onUpdate(@Nonnull DatabaseSessionInternal session, @Nonnull Result before,
                  @Nonnull Result after) {

              }

              @Override
              public void onDelete(@Nonnull DatabaseSessionInternal session, @Nonnull Result data) {

              }

              @Override
              public void onError(@Nonnull DatabaseSession session,
                  @Nonnull BaseException exception) {

              }

              @Override
              public void onEnd(@Nonnull DatabaseSession session) {
                end.countDown();
              }
            });
      }
    }
    shutdownServer();

    assertTrue("onEnd method never called on shutdown", end.await(2, TimeUnit.SECONDS));
  }
}
