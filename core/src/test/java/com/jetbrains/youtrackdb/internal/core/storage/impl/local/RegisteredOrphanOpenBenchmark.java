package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Standalone physical-open probe, not a Surefire test. Compile this same source against each
 * build. The Python driver owns fixture reset, process isolation, alternation and statistics.
 */
public class RegisteredOrphanOpenBenchmark {
  public static void main(String[] args) throws Exception {
    final var action = args[0];
    final var root = Path.of(args[1]);
    final var count = Integer.parseInt(args[2]);
    final var scenario = args[3];
    final var lane = args.length > 4 ? args[4] : "sentinel";
    GlobalConfiguration.STORAGE_CALL_FSYNC.setValue(true);
    final var db = (YouTrackDBImpl) YourTracks.instance(root);
    if (action.equals("prepare")) {
      final var createStart = System.nanoTime();
      db.create("bench", DatabaseType.DISK, "admin", "adminpwd", "admin");
      final var createNs = System.nanoTime() - createStart;
      try (var session = (DatabaseSessionEmbedded) db.open("bench", "admin", "adminpwd")) {
        final var storage = (AbstractStorage) session.getStorage();
        var collection = 0;
        while (storage.getWriteCache().files().size() < count) {
          session.addCollection("fixture_" + collection++);
        }
        if (lane.equals("sentinel")) {
          storage.getWriteCache().addFile("benchmark_sentinel.pcl");
        }
        if (scenario.equals("populated")) {
          for (var i = 0; i < 100; i++) {
            final var directory = Files.createDirectories(root.resolve("bench/orphans")
                .resolve(String.format("20200101T000000000Z-%032x", i)));
            final var file = directory.resolve("retained.pcl");
            Files.write(file, new byte[8192]);
            try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
              channel.force(true);
            }
          }
        }
        System.out.println("BENCH create_ns=" + createNs
            + " files=" + storage.getWriteCache().files().size());
        if (scenario.equals("crash")) {
          // Flush pages without a clean storage close. A committed collection keeps dirty open real.
          storage.getWriteCache().flush();
          System.out.flush();
          Runtime.getRuntime().halt(0);
        }
      }
      db.close();
      return;
    }
    final var start = System.nanoTime();
    try (var session = (DatabaseSessionEmbedded) db.open("bench", "admin", "adminpwd")) {
      final var openNs = System.nanoTime() - start;
      final var storage = (AbstractStorage) session.getStorage();
      java.lang.reflect.Method pass = null;
      try {
        pass = AbstractStorage.class.getDeclaredMethod("quarantineRegisteredOrphans");
        pass.setAccessible(true);
      } catch (NoSuchMethodException baseline) {
        // The bootstrap intentionally has no owner pass.
      }
      final var sentinelRemains =
          storage.getWriteCache().files().containsKey("benchmark_sentinel.pcl");
      if (pass != null && lane.equals("sentinel") && sentinelRemains) {
        throw new AssertionError("Owner pass did not retire the registered sentinel");
      }
      boolean passVerified = false;
      if (pass != null) {
        final var counter = AbstractStorage.class.getDeclaredField("registeredOrphanPassCount");
        counter.setAccessible(true);
        passVerified = counter.getLong(storage) == 1;
        if (!passVerified) {
          throw new AssertionError("Open must run exactly one owner pass");
        }
        if (lane.equals("no-orphan") && (sentinelRemains
            || Files.exists(root.resolve("bench/orphans"))
                && !scenario.equals("populated"))) {
          throw new AssertionError("No-orphan lane must not move any candidate");
        }
      }
      long moveNs = -1;
      if (pass != null && lane.equals("bulk")) {
        for (var i = 0; i < count; i++) {
          storage.getWriteCache().addFile("move_" + i + ".pcl");
        }
        storage.stateLock.writeLock().lock();
        try {
          final var moveStart = System.nanoTime();
          pass.invoke(storage);
          moveNs = System.nanoTime() - moveStart;
        } finally {
          storage.stateLock.writeLock().unlock();
        }
        if (storage.getWriteCache().files().containsKey("move_0.pcl")) {
          throw new AssertionError("Move fixture was not retired");
        }
      }
      System.out.println("BENCH open_ns=" + openNs + " move_ns=" + moveNs
          + " pass_verified=" + passVerified);
    }
    db.close();
  }
}
