package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerBackup;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Direct owner tests use the real facade without creating a production backup request. */
public class BackupRequestOwnerTest {
  private static final UUID TRACKER = new UUID(0x1020304050607080L, 0x1122334455667788L);
  private static final UUID PREVIOUS = new UUID(0x2132435465768798L, 0x31425364758697a8L);
  private static final UUID DATABASE = new UUID(9, 10);
  private static final ChangedPageTrackerBackup.CompletionFacts DURABLE =
      new ChangedPageTrackerBackup.CompletionFacts(true, true);
  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  /** Every pair-table row is frozen at begin, including trusted and untrusted capable bootstrap. */
  @Test
  public void pairsUseOnlyTheirImmutableContext() throws Exception {
    for (boolean trusted : new boolean[] {false, true}) {
      for (boolean capable : new boolean[] {false, true}) {
        var tracker = trusted ? trusted() : new ChangedPageTracker();
        var owner = new DiskStorage.BackupRequestOwner(tracker);
        var context = owner.begin(null, capable);
        var pair = DiskStorage.pairFor(context);
        if (capable || trusted) {
          assertEquals(context.trackerIdentifier(), pair.trackerId());
          assertEquals(capable ? context.consumedIdentifier() : PREVIOUS, pair.generationId());
        } else {
          assertNull(pair);
        }
        owner.abort(null);
        var later = new DiskStorage.BackupRequestOwner(tracker);
        later.begin(null, true);
        assertEquals(ChangedPageTrackerBackup.Status.SAVED_CONTINUITY,
            later.finish(path(), DURABLE, source(), message -> {
            }));
        assertEquals(pair, DiskStorage.pairFor(context));
      }
    }
    assertThrows(NullPointerException.class, () -> DiskStorage.pairFor(null));
  }

  /** Pending requests have no authority, no third generation and no continuity for either output. */
  @Test
  public void pendingRequestsWarnAndLeaveTheEarlierSealUntouched() throws Exception {
    for (boolean capable : new boolean[] {false, true}) {
      var tracker = trusted();
      var earlier = ChangedPageTrackerBackup.begin(tracker, null, true);
      var seal = field(tracker, "sealed");
      var active = field(tracker, "active");
      var owner = new DiskStorage.BackupRequestOwner(tracker);
      var context = owner.begin(null, capable);
      assertEquals(DiskStorage.BackupRequestOwner.State.BEGUN_PENDING, owner.state());
      assertFalse(context.selectionEligible());
      assertFalse(context.trustedAtBoundary());
      assertNull(context.consumedIdentifier());
      assertNull(context.lastCompletedIdentifier());
      assertNull(DiskStorage.pairFor(context));
      assertArrayEquals(new byte[36], java.util.Arrays.copyOfRange(tail(null), 12, 48));
      var warnings = new ArrayList<String>();
      assertEquals(ChangedPageTrackerBackup.Status.NO_BOUNDARY,
          owner.finish(path(), DURABLE, source(), warnings::add));
      assertEquals(1, warnings.size());
      owner.abort(null);
      assertSame(seal, field(tracker, "sealed"));
      assertSame(active, field(tracker, "active"));
      ChangedPageTrackerBackup.abandon(tracker, earlier);
    }
  }

  /** Every returned facade status is terminal before warnings, even if the sink reenters and throws. */
  @Test
  public void returnedStatusesNeverTriggerAnotherCleanup() throws Exception {
    for (var expected : ChangedPageTrackerBackup.Status.values()) {
      var tracker = new ChangedPageTracker();
      boolean pending = expected == ChangedPageTrackerBackup.Status.NO_BOUNDARY;
      if (pending) {
        ChangedPageTrackerBackup.begin(tracker, null, true);
      }
      var owner = new DiskStorage.BackupRequestOwner(tracker);
      owner.begin(null, expected != ChangedPageTrackerBackup.Status.MERGED_NOT_CONSUMED);
      var target = folder.newFolder().toPath().resolve("history");
      boolean failedSave = expected == ChangedPageTrackerBackup.Status.SAVE_FAILED_INVALIDATED
          || expected == ChangedPageTrackerBackup.Status.SAVE_FAILED_INVALIDATION_FAILED;
      boolean failedInvalidation = expected
          == ChangedPageTrackerBackup.Status.SAVE_FAILED_INVALIDATION_FAILED
          || expected == ChangedPageTrackerBackup.Status.COVERAGE_UNAVAILABLE_INVALIDATION_FAILED;
      if (failedSave) {
        Files.createDirectories(target.resolveSibling("history.tmp"));
        Files.writeString(target.resolveSibling("history.tmp").resolve("child"), "block delete");
      }
      if (failedInvalidation) {
        Files.createDirectories(target);
        Files.writeString(target.resolve("child"), "block delete");
      }
      var facts = new ChangedPageTrackerBackup.CompletionFacts(
          expected != ChangedPageTrackerBackup.Status.SAVED_UNTRUSTED, true);
      var coverage = expected == ChangedPageTrackerBackup.Status.COVERAGE_UNAVAILABLE
          || expected == ChangedPageTrackerBackup.Status.COVERAGE_UNAVAILABLE_INVALIDATION_FAILED
              ? unavailable() : source();
      var warnings = new AtomicLong();
      var warningStates = new ArrayList<DiskStorage.BackupRequestOwner.State>();
      Consumer<String> sink = message -> {
        warningStates.add(owner.state());
        owner.abort(new AssertionError("outer cleanup"));
        warnings.incrementAndGet();
        throw new AssertionError("diagnostic failure");
      };
      assertEquals(expected, owner.finish(target, facts, coverage, sink));
      long before = failures(tracker);
      owner.abort(null);
      assertEquals(before, failures(tracker));
      assertEquals(expected == ChangedPageTrackerBackup.Status.SAVED_CONTINUITY
          || expected == ChangedPageTrackerBackup.Status.MERGED_NOT_CONSUMED ? 0 : 1,
          warnings.get());
      if (!warningStates.isEmpty()) {
        assertEquals(pending ? DiskStorage.BackupRequestOwner.State.ABANDONED
            : DiskStorage.BackupRequestOwner.State.COMPLETED, warningStates.getFirst());
      }
      assertThrows(IllegalStateException.class,
          () -> owner.finish(target, DURABLE, source(), sink));
      assertThrows(IllegalStateException.class, () -> owner.begin(null, true));
    }
  }

  /** Local guards reject premature finish and duplicate begin, while abort is harmless before begin. */
  @Test
  public void guardsAndExplicitAbortsPreserveCompletion() throws Exception {
    for (boolean readPair : new boolean[] {false, true}) {
      var tracker = trusted();
      var owner = new DiskStorage.BackupRequestOwner(tracker);
      owner.abort(null);
      assertEquals(DiskStorage.BackupRequestOwner.State.CREATED, owner.state());
      assertThrows(IllegalStateException.class,
          () -> owner.finish(path(), DURABLE, source(), message -> {
          }));
      var context = owner.begin(null, false);
      assertThrows(IllegalStateException.class, () -> owner.begin(null, true));
      if (readPair) {
        assertNotNull(DiskStorage.pairFor(context));
      }
      owner.abort(null);
      owner.abort(null);
      assertEquals(DiskStorage.BackupRequestOwner.State.ABANDONED, owner.state());
      assertEquals(PREVIOUS, field(tracker, "lastCompleted"));
      assertNull(field(tracker, "sealed"));
    }
    var tracker = trusted();
    var owner = new DiskStorage.BackupRequestOwner(tracker);
    owner.begin(null, true);
    assertEquals(ChangedPageTrackerBackup.Status.MERGED_NOT_CONSUMED,
        owner.finish(path(), new ChangedPageTrackerBackup.CompletionFacts(true, false),
            source(), message -> {
            }));
    assertEquals(PREVIOUS, field(tracker, "lastCompleted"));
  }

  /** A begin allocation failure leaves CREATED without any published context or cleanup authority. */
  @Test
  public void failedBeginHasNoCleanupAuthority() throws Exception {
    var primary = new OutOfMemoryError("begin");
    var tracker = observed((method, args) -> {
      if (method.equals("beforeBoundaryAllocation")) {
        throw primary;
      }
    });
    var owner = new DiskStorage.BackupRequestOwner(tracker);
    assertSame(primary, assertThrows(OutOfMemoryError.class, () -> owner.begin(null, true)));
    long before = failures(tracker);
    owner.abort(primary);
    assertEquals(before, failures(tracker));
    assertNull(field(tracker, "sealed"));
    assertEquals(DiskStorage.BackupRequestOwner.State.CREATED, owner.state());
  }

  /** A pre-stage Error merges once and preserves primary identity without converting it to success. */
  @Test
  public void exceptionalCompletionBeforeStagingMergesExactlyOnce() throws Exception {
    var tracker = trusted();
    var owner = new DiskStorage.BackupRequestOwner(tracker);
    owner.begin(null, true);
    var primary = new AssertionError("sample");
    var coverage = mock(ChangedPageTrackerBackup.CoverageSource.class);
    doAnswer(call -> {
      throw primary;
    }).when(coverage).sampleBoundary();
    assertSame(primary, assertThrows(AssertionError.class,
        () -> owner.finish(path(), DURABLE, coverage, message -> {
        })));
    assertEquals(DiskStorage.BackupRequestOwner.State.ABANDONED, owner.state());
    assertNull(field(tracker, "sealed"));
    long before = failures(tracker);
    owner.abort(primary);
    assertEquals(before, failures(tracker));
    assertEquals(0, primary.getSuppressed().length);
  }

  /** Post-detachment and post-merge throws preserve primary identity and suppress stale cleanup once. */
  @Test
  public void exceptionalCompletionAfterDetachmentHasOnlySecondaryStaleRejection()
      throws Exception {
    for (boolean merged : new boolean[] {false, true}) {
      var tracker = trusted();
      var owner = new DiskStorage.BackupRequestOwner(tracker);
      owner.begin(null, true);
      var primary = new IllegalStateException("path provider");
      var target = mock(Path.class);
      doAnswer(call -> {
        throw primary;
      }).when(target).toAbsolutePath();
      assertSame(primary, assertThrows(IllegalStateException.class,
          () -> owner.finish(target, DURABLE, merged ? unavailable() : source(), message -> {
          })));
      assertNull(field(tracker, "sealed"));
      assertEquals(DiskStorage.BackupRequestOwner.State.CLEANUP_FAILED, owner.state());
      assertEquals(1, primary.getSuppressed().length);
      assertTrue(primary.getSuppressed()[0] instanceof IllegalStateException);
      long before = failures(tracker);
      owner.abort(primary);
      assertEquals(before, failures(tracker));
    }
  }

  /** A partial merge leaves the seal published, but cleanup never retries or self-suppresses. */
  @Test
  public void failedMergeEndsAuthorityForDistinctIdenticalAndAbsentPrimary() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var armed = new AtomicBoolean();
      var cleanup = new OutOfMemoryError("merge");
      var tracker = observed((method, args) -> {
        if (armed.get() && method.equals("beforeAllocation") && args[0].toString().equals("FILE")) {
          throw cleanup;
        }
      });
      var mark = ChangedPageTracker.class.getDeclaredMethod("mark", int.class, long.class);
      mark.setAccessible(true);
      mark.invoke(tracker, 1, 3L);
      var owner = new DiskStorage.BackupRequestOwner(tracker);
      owner.begin(null, true);
      var seal = field(tracker, "sealed");
      armed.set(true);
      Throwable primary = mode == 0 ? null : mode == 1 ? cleanup : new AssertionError("primary");
      if (primary == null) {
        assertSame(cleanup, assertThrows(OutOfMemoryError.class, () -> owner.abort(null)));
      } else {
        owner.abort(primary);
        assertEquals(mode == 1 ? 0 : 1, primary.getSuppressed().length);
      }
      assertEquals(DiskStorage.BackupRequestOwner.State.CLEANUP_FAILED, owner.state());
      assertSame(seal, field(tracker, "sealed"));
      long before = failures(tracker);
      owner.abort(primary);
      assertEquals(before, failures(tracker));
      assertEquals(ChangedPageTrackerBackup.BeginOutcome.PENDING_SEAL,
          ChangedPageTrackerBackup.begin(tracker, null, true).outcome());
    }
  }

  /** Stale rejection revokes trust through a higher failure version, not only an exception. */
  @Test
  public void realFacadeStaleRejectionIncreasesFailureVersion() throws Exception {
    var tracker = trusted();
    var context = ChangedPageTrackerBackup.begin(tracker, null, true);
    ChangedPageTrackerBackup.abandon(tracker, context);
    long before = failures(tracker);
    assertThrows(IllegalStateException.class,
        () -> ChangedPageTrackerBackup.abandon(tracker, context));
    assertTrue(failures(tracker) > before);
    assertFalse(tracker.isTrusted());
  }

  /** Independent byte oracles cover absent and asymmetric present pairs with exactly one write. */
  @Test
  public void tailBytesHaveCanonicalSlotsHashesAndDatabaseOrder() throws Exception {
    for (var pair : new DiskStorage.BackupContinuity[] {null,
        new DiskStorage.BackupContinuity(TRACKER, PREVIOUS)}) {
      var actual = tail(pair);
      var expected = ByteBuffer.allocate(122);
      expected.position(8);
      expected.putInt(0x42415252).putInt(pair == null ? 0 : 0x434f4e54);
      expected.putLong(pair == null ? 0 : 0x1020304050607080L);
      expected.putLong(pair == null ? 0 : 0x1122334455667788L);
      expected.putLong(pair == null ? 0 : 0x2132435465768798L);
      expected.putLong(pair == null ? 0 : 0x31425364758697a8L);
      expected.putShort((short) 6).putLong(10).putLong(9).putInt(0);
      expected.putLong(-1).putInt(-1).putLong(17).putInt(32).putLong(42);
      expected.putInt(BackupUnitFiles.supportedFeatureFormat());
      expected.putInt(BackupUnitFiles.supportedLayoutVersion()).putInt(0x47454e43);
      expected.putLong(0,
          DiskStorage.XX_HASH_64.hash(expected.array(), 8, 106, 0x4d45544144415441L));
      expected.putLong(114,
          DiskStorage.XX_HASH_64.hash(expected.array(), 0, 114, DiskStorage.XX_HASH_SEED));
      assertArrayEquals(expected.array(), actual);
    }
  }

  /** Real tail decode and shared head conversion match saved and Q-f history, not capable aborts. */
  @Test
  public void contextRoundTripPreservesEligibilityRules() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var tracker = trusted();
      var owner = new DiskStorage.BackupRequestOwner(tracker);
      var context = owner.begin(null, mode != 1);
      var pair = DiskStorage.pairFor(context);
      var bytes = tail(pair);
      var name = BackupUnitFiles.unitFileName(DATABASE, "source", 0);
      var metadata = DiskStorage.inspectBackupUnit(name, "source", DATABASE,
          new ByteArrayInputStream(bytes), null).metadata();
      assertNotNull(metadata);
      assertEquals(pair, metadata.continuity());
      assertEquals(new ChangedPageTrackerBackup.Continuity(pair.trackerId(), pair.generationId()),
          DiskStorage.toFacadeContinuity(metadata.continuity()));
      if (mode == 0) {
        assertEquals(ChangedPageTrackerBackup.Status.SAVED_CONTINUITY,
            owner.finish(path(), DURABLE, source(), message -> {
            }));
      } else {
        owner.abort(null);
      }
      var next = new DiskStorage.BackupRequestOwner(tracker);
      assertEquals(mode != 2, next.begin(metadata.continuity(), true).selectionEligible());
      next.abort(null);
    }
  }

  /**
   * Finish, not abort, owns failed Q-f, non-durable and coverage-unavailable merges. A partial
   * union remains pending, is captured with both generations and merges without loss on reload.
   */
  @Test
  public void failedFinishMergePersistsBothGenerationsAndNeverRetriesCleanup() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var armed = new AtomicBoolean();
      var allocations = new AtomicLong();
      var primary = new OutOfMemoryError("partial finish merge");
      var tracker = observed((method, args) -> {
        if (armed.get() && method.equals("beforeAllocation") && args[0].toString().equals("FILE")
            && allocations.incrementAndGet() >= 2) {
          throw primary;
        }
      });
      var mark = ChangedPageTracker.class.getDeclaredMethod("mark", int.class, long.class);
      mark.setAccessible(true);
      mark.invoke(tracker, 1, 11L);
      mark.invoke(tracker, 2, 22L);
      var owner = new DiskStorage.BackupRequestOwner(tracker);
      var context = owner.begin(null, mode != 0);
      var seal = field(tracker, "sealed");
      mark.invoke(tracker, 3, 33L);
      armed.set(true);
      int selected = mode;
      assertSame(primary, assertThrows(OutOfMemoryError.class,
          () -> owner.finish(path(), selected == 1
              ? new ChangedPageTrackerBackup.CompletionFacts(true, false) : DURABLE,
              selected == 2 ? unavailable() : source(), message -> {
                throw new AssertionError("no returned status");
              })));
      assertEquals(DiskStorage.BackupRequestOwner.State.CLEANUP_FAILED, owner.state());
      assertSame(seal, field(tracker, "sealed"));
      assertFalse(tracker.isTrusted());
      assertEquals(0, primary.getSuppressed().length);
      // Coverage complete attempts the merge, then the owner makes its one cleanup attempt.
      assertEquals(mode == 2 ? 3 : 2, allocations.get());
      long before = allocations.get();
      owner.abort(primary);
      assertEquals(before, allocations.get());
      assertEquals(1, candidates(tracker, "active", 1).size()
          + candidates(tracker, "active", 2).size());
      var pending = new DiskStorage.BackupRequestOwner(tracker);
      var pendingContext = pending.begin(null, true);
      assertEquals(ChangedPageTrackerBackup.BeginOutcome.PENDING_SEAL, pendingContext.outcome());
      assertNull(DiskStorage.pairFor(pendingContext));
      assertSame(seal, field(tracker, "sealed"));
      pending.abort(null);
      // Use the real codec's save path, which captures active plus the still-published seal.
      armed.set(false);
      var codec = Class.forName(
          "com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile");
      var save = codec.getDeclaredMethod("save", Path.class, ChangedPageTracker.class,
          LogSequenceNumber.class);
      save.setAccessible(true);
      var target = folder.newFolder().toPath().resolve("history");
      assertEquals("SAVED", save.invoke(null, target, tracker,
          new LogSequenceNumber(17, 32)).toString());
      var load = codec.getDeclaredMethod("load", Path.class);
      load.setAccessible(true);
      var loaded = load.invoke(null, target);
      var accessor = loaded.getClass().getDeclaredMethod("tracker");
      accessor.setAccessible(true);
      var restarted = (ChangedPageTracker) accessor.invoke(loaded);
      assertNotNull(field(restarted, "sealed"));
      var merge = ChangedPageTracker.class.getDeclaredMethod("mergeLoadedSealed");
      merge.setAccessible(true);
      merge.invoke(restarted);
      assertNull(field(restarted, "sealed"));
      assertEquals(java.util.List.of(11L), candidates(restarted, "active", 1));
      assertEquals(java.util.List.of(22L), candidates(restarted, "active", 2));
      assertEquals(java.util.List.of(33L), candidates(restarted, "active", 3));
      assertFalse(context.consumedIdentifier().equals(field(restarted, "lastCompleted")));
      var next = new DiskStorage.BackupRequestOwner(restarted);
      assertEquals(ChangedPageTrackerBackup.BeginOutcome.NEW_BOUNDARY,
          next.begin(null, true).outcome());
      next.abort(null);
    }
  }

  private static java.util.List<Long> candidates(ChangedPageTracker tracker, String generation,
      int file) throws Exception {
    var pages = new ArrayList<Long>();
    var current = field(tracker, generation);
    var method = current.getClass().getDeclaredMethod("forEachCandidate", int.class,
        java.util.function.LongConsumer.class);
    method.setAccessible(true);
    method.invoke(current, file, (java.util.function.LongConsumer) pages::add);
    return pages;
  }

  private Path path() {
    return folder.getRoot().toPath().resolve("history");
  }

  private static ChangedPageTracker trusted() throws Exception {
    var tracker = new ChangedPageTracker();
    set(tracker, "trackerId", TRACKER);
    set(tracker, "lastCompleted", PREVIOUS);
    set(tracker, "savedFailureVersion", 0L);
    set(tracker, "historyTrusted", true);
    return tracker;
  }

  private static Object field(Object object, String name) throws Exception {
    var field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private static void set(Object object, String name, Object value) throws Exception {
    var field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(object, value);
  }

  private static long failures(ChangedPageTracker tracker) throws Exception {
    return ((AtomicLong) field(tracker, "failures")).get();
  }

  private interface Observation {
    void visit(String method, Object[] args);
  }

  private static ChangedPageTracker observed(Observation observation) throws Exception {
    var type = Class.forName(ChangedPageTracker.class.getName() + "$AllocationObserver");
    var observer = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
        (proxy, method, args) -> {
          observation.visit(method.getName(), args);
          return null;
        });
    var constructor = ChangedPageTracker.class.getDeclaredConstructor(type);
    constructor.setAccessible(true);
    return constructor.newInstance(observer);
  }

  private static ChangedPageTrackerBackup.CoverageSource source() {
    return new ChangedPageTrackerBackup.CoverageSource() {
      public long sampleBoundary() {
        return 17;
      }

      public WriteAheadLog.CutPreflight preflightCut(long fixed) {
        return new WriteAheadLog.CutPreflight(false, 17, new LogSequenceNumber(17, 32));
      }
    };
  }

  private static ChangedPageTrackerBackup.CoverageSource unavailable() {
    return new ChangedPageTrackerBackup.CoverageSource() {
      public long sampleBoundary() {
        return -1;
      }

      public WriteAheadLog.CutPreflight preflightCut(long fixed) {
        return null;
      }
    };
  }

  private static byte[] tail(DiskStorage.BackupContinuity pair) throws Exception {
    var type = Class.forName(DiskStorage.class.getName() + "$XXHashOutputStream");
    var constructor = type.getDeclaredConstructor(OutputStream.class);
    constructor.setAccessible(true);
    var writes = new AtomicLong();
    var output = new ByteArrayOutputStream() {
      @Override
      public synchronized void write(byte[] bytes, int offset, int count) {
        writes.incrementAndGet();
        super.write(bytes, offset, count);
      }
    };
    var stream = constructor.newInstance(output);
    var method = DiskStorage.class.getDeclaredMethod("writeBackupMetadata", type, UUID.class,
        int.class, LogSequenceNumber.class, LogSequenceNumber.class, long.class,
        DiskStorage.BackupSemanticIdentity.class, boolean.class,
        DiskStorage.BackupContinuity.class);
    method.setAccessible(true);
    method.invoke(null, stream, DATABASE, 0, null, new LogSequenceNumber(17, 32), 42L,
        new DiskStorage.BackupSemanticIdentity(BackupUnitFiles.supportedFeatureFormat(),
            BackupUnitFiles.supportedLayoutVersion(), 0x47454e43),
        true, pair);
    assertEquals(1, writes.get());
    assertEquals(122, output.size());
    return output.toByteArray();
  }
}
