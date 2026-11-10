package com.jetbrains.youtrackdb.benchmarks.pagelock;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/** Existing 1 KiB records. One instance, session and collection belong to each writer. */
public final class RecordWorkload implements AutoCloseable {

  public static final int FULL_RECORD_COUNT = 262144;
  private static final String DATABASE = "records";
  final DatabaseSessionEmbedded session;
  final int collectionId;
  final List<RID> records = new ArrayList<>();
  private final SplittableRandom random;
  private long value;

  public RecordWorkload(YouTrackDBImpl database, int writer, int expectedRecords) {
    session = database.open(DATABASE, "admin", "admin");
    random = new SplittableRandom(writer);
    try {
      int[] ids = session.getMetadata().getSchema().getClass(className(writer)).getCollectionIds();
      if (ids.length != 1) {
        throw new IllegalStateException("Writer must own exactly one collection");
      }
      collectionId = ids[0];
      session.begin();
      try (var iterator = session.browseClass(className(writer))) {
        while (iterator.hasNext()) {
          records.add(iterator.next().getIdentity());
        }
      }
      session.commit();
      if (records.size() != expectedRecords
          || records.stream().anyMatch(rid -> rid.getCollectionId() != collectionId)) {
        throw new IllegalStateException("Unexpected record count or RID ownership");
      }
    } catch (RuntimeException failure) {
      session.close();
      throw failure;
    }
  }

  private static String className(int writer) {
    return "Writer" + writer;
  }

  /** Successful return means exactly one commit succeeded. No retry or exception swallowing. */
  public RID commit() {
    return commit(null);
  }

  // Observation is for smoke tests only. No counter snapshot runs in the measured operation.
  RID commit(Consumer<Object> observer) {
    session.activateOnCurrentThread();
    RID rid = records.get(random.nextInt(records.size()));
    session.begin();
    session.loadEntity(rid).setProperty("value", ++value);
    if (observer != null) {
      observer.accept(session.getActiveTransaction().getAtomicOperation());
    }
    session.commit();
    return rid;
  }

  /** Enforce partition identity outside measured operations, not just distinct Java objects. */
  public static void checkOwnership(RecordWorkload[] writers) {
    var ids = new HashSet<Integer>();
    var sessions = new HashSet<DatabaseSessionEmbedded>();
    for (var writer : writers) {
      if (!ids.add(writer.collectionId) || !sessions.add(writer.session)
          || writer.records.stream()
              .anyMatch(rid -> rid.getCollectionId() != writer.collectionId)) {
        throw new IllegalStateException("Writers share a collection, session or RID partition");
      }
    }
  }

  /**
   * Preparation only. Full small-cache seeds contain 256 MiB of payload across all writers.
   * Each shape has its own closed seed. All run copies must use SeedDatabase.freshCopy.
   */
  public static SeedDatabase prepare(Path root, int writers, int totalRecords) throws Exception {
    WriterSettings.validateRun(writers, 1);
    if (totalRecords < writers || totalRecords % writers != 0) {
      throw new IllegalArgumentException("Record count must divide equally across writers");
    }
    return SeedDatabase.prepare(root.resolve("records-v1-" + writers + "-" + totalRecords),
        path -> {
          try (var db = (YouTrackDBImpl) YourTracks.instance(path.toString())) {
            db.create(DATABASE, DatabaseType.DISK, "admin", "admin", "admin");
            try (var session = db.open(DATABASE, "admin", "admin")) {
              for (int w = 0; w < writers; w++) {
                session.getMetadata().getSchema().createClass(className(w), 1);
                // Bounded batches avoid retaining the full data set in one transaction.
                for (int start = 0; start < totalRecords / writers; start += 128) {
                  session.begin();
                  for (int i = start; i < Math.min(start + 128, totalRecords / writers); i++) {
                    var entity = session.newEntity(className(w));
                    entity.setProperty("payload", new byte[1024]);
                    entity.setProperty("value", 0L);
                  }
                  session.commit();
                }
              }
            }
          }
        });
  }

  /** Full seed preparation entry point, never part of a timed run. */
  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("Arguments: seed-root writers commit-limit");
    }
    int writers = Integer.parseInt(args[1]);
    var settings = new WriterSettings(80, 20, 8, Integer.parseInt(args[2]), 1);
    settings.apply(writers, WriterSettings.RECORD_PAGE_BOUND);
    var root = Path.of(args[0]);
    prepare(root, writers, FULL_RECORD_COUNT);
    System.out.println("record seed physicalBytes=" + physicalBytes(
        root.resolve("records-v1-" + writers + "-" + FULL_RECORD_COUNT)));
  }

  /** Measure a closed seed, not a live database with transient checkpoint files. */
  public static long physicalBytes(Path root) throws Exception {
    long bytes = 0;
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        bytes += Files.size(file);
      }
    }
    return bytes;
  }

  @Override
  public void close() {
    session.activateOnCurrentThread();
    session.close();
  }
}
