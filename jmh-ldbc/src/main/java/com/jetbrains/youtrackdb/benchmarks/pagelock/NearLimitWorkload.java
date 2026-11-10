package com.jetbrains.youtrackdb.benchmarks.pagelock;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.stream.IntStream;

/** Durable existing pages, with one independently locked component per writer. */
public final class NearLimitWorkload {

  static final String DATABASE = "pages";
  private final PageWorkloadComponent component;
  private final int[] pages;
  private long value;

  public NearLimitWorkload(AbstractStorage storage, int writer, WriterSettings settings)
      throws IOException {
    settings.verifyCache(storage.getReadCache(), Boolean.getBoolean("pagelock.expectUnavailable"));
    pages = IntStream.range(0, settings.commitLimit() - 1).toArray();
    component = new PageWorkloadComponent(storage, name(writer), pages.length, true);
    component.open();
    validate();
  }

  private static String name(int writer) {
    return "near-limit-writer-" + writer;
  }

  /** One successful durable commit per return. No retry and no setup probes on this path. */
  public PageWorkloadComponent.Sample[] commit() throws IOException {
    return component.commit(pages, ++value);
  }

  /**
   * Setup only. Both cores expose page tracking and local-change checks. Tracking alone counts
   * touches, not writes, so also check local changes and the committed coordinates and values.
   */
  void validate() throws IOException {
    long expected = ++value;
    var written = component.commit(pages, expected,
        op -> ((AtomicOperation) op).enablePageTracking(),
        op -> checkChangedPages((AtomicOperation) op));
    var read = component.read(pages).pages();
    for (int i = 0; i < pages.length; i++) {
      if (!written[i].equals(read[i]) || read[i].pageIndex() != pages[i]
          || read[i].value() != expected) {
        throw new IllegalStateException("Near-limit committed page differs from requested page");
      }
    }
  }

  private void checkChangedPages(AtomicOperation op) {
    if (op.touchedPagesCount() != pages.length) {
      throw new IllegalStateException("Near-limit commit must touch exactly " + pages.length);
    }
    long file = op.fileIdByName(component.getFullName());
    for (int page : pages) {
      if (!op.hasChangesForPage(file, page)) {
        throw new IllegalStateException("Near-limit page has no changes: " + page);
      }
    }
  }

  /** Distinct component objects are insufficient. Check the actual storage file identities. */
  public static void checkOwnership(NearLimitWorkload[] writers) throws IOException {
    var files = new HashSet<Long>();
    for (var writer : writers) {
      long file = writer.component.read(new int[] {0}).pages()[0].fileId();
      if (!files.add(file)) {
        throw new IllegalStateException("Near-limit writers share a storage component");
      }
    }
  }

  public static Path seedPath(Path root, int writers, WriterSettings settings) {
    return root.resolve("near-limit-v1-" + writers + "-" + settings.pageKiB()
        + "-" + (settings.commitLimit() - 1));
  }

  /** Preparation only. Capacity is checked before SeedDatabase can create any data. */
  public static SeedDatabase prepare(Path root, int writers, WriterSettings settings)
      throws Exception {
    int count = settings.commitLimit() - 1;
    settings.checkCapacity(writers, count);
    return SeedDatabase.prepare(seedPath(root, writers, settings), path -> {
      try (var db = (YouTrackDBImpl) YourTracks.instance(path.toString())) {
        db.create(DATABASE, DatabaseType.DISK, "admin", "admin", "admin");
        try (var session = db.open(DATABASE, "admin", "admin")) {
          for (int writer = 0; writer < writers; writer++) {
            new PageWorkloadComponent(session.getStorage(), name(writer), count, true).create();
          }
        }
      }
    });
  }

  /** Explicit seed preparation in a fresh JVM, never inside a timed run. */
  public static void main(String[] args) throws Exception {
    if (args.length != 7) {
      throw new IllegalArgumentException(
          "Arguments: seed-root writers buffer-MiB write-percent page-KiB commit-limit pin-percent");
    }
    int writers = Integer.parseInt(args[1]);
    var settings = new WriterSettings(Integer.parseInt(args[2]), Integer.parseInt(args[3]),
        Integer.parseInt(args[4]), Integer.parseInt(args[5]), Double.parseDouble(args[6]));
    settings.apply(writers, settings.commitLimit() - 1);
    var root = Path.of(args[0]);
    prepare(root, writers, settings);
    System.out.println("near-limit seed fileSizeBytes="
        + RecordWorkload.physicalBytes(seedPath(root, writers, settings)));
  }
}
