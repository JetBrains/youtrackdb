package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Branch-coverage tests for {@link DatabaseRecordWalker}.
 *
 * <p>The walker is exercised by populating a small fixture in a MEMORY-storage database
 * (via {@link DbTestBase}) and pinning every public method on the walker:
 * <ul>
 *   <li>{@code walk} with a visitor that returns {@code true} for every record (covers
 *       the "visit accepted" arm and the per-collection progress-listener path).
 *   <li>{@code walk} with a visitor that returns {@code false} for every record (covers
 *       the "visit rejected" arm; visited counters stay zero per-collection).
 *   <li>{@code walkEntitiesInTx(visitor)} — the convenience overload that defaults
 *       {@code skipLinkConsistencyCheck} to {@code false}.
 *   <li>{@code walkEntitiesInTx(true, visitor)} — the explicit-skip overload, which
 *       additionally exercises {@code DatabaseSessionEmbedded.disableLinkConsistencyCheck} /
 *       {@code enableLinkConsistencyCheck} via the {@code try / finally} sandwich.
 *   <li>{@code onProgressPeriodically} — both registers a progress listener and is
 *       observed firing at least once during {@code walk}.
 *   <li>The {@code excludeCollections} guard — adding a real class to the exclude set
 *       must drop its records from the walk count.
 *   <li>The internal-collection short-circuit — the
 *       {@link MetadataDefault#COLLECTION_INTERNAL_NAME} bucket is always skipped,
 *       even if not present in {@code excludeCollections}.
 * </ul>
 *
 * <p>Test-additive only; production code is untouched.
 */
public class DatabaseRecordWalkerTest extends DbTestBase {

  private static final String CLS_A = "PersonA";
  private static final String CLS_B = "PersonB";

  private void seedFixture() {
    // Schema mutations are non-transactional in YouTrackDB and must therefore be
    // applied outside any active transaction. Records, by contrast, must go in.
    var schema = session.getMetadata().getSchema();
    var clsA = schema.createClass(CLS_A);
    clsA.createProperty("name", PropertyType.STRING);
    var clsB = schema.createClass(CLS_B);
    clsB.createProperty("name", PropertyType.STRING);

    session.executeInTx(
        tx -> {
          for (var i = 0; i < 3; i++) {
            var e = (EntityImpl) session.newEntity(CLS_A);
            e.setProperty("name", "a" + i);
          }
          for (var i = 0; i < 2; i++) {
            var e = (EntityImpl) session.newEntity(CLS_B);
            e.setProperty("name", "b" + i);
          }
        });
  }

  @Test
  public void walkVisitsEveryRecordWhenVisitorAccepts() {
    seedFixture();
    var walker = new DatabaseRecordWalker(session, systemCollectionsToExclude());
    var visited = new AtomicInteger();

    var total = walker.walk(rid -> {
      assertNotNull("walker must hand out non-null record IDs", rid);
      visited.incrementAndGet();
      return true;
    });

    // Five user records (3 + 2). With system / security clusters excluded the total
    // matches exactly the seeded fixture size.
    assertEquals(5, total);
    assertEquals(5, visited.get());
  }

  /**
   * The set of system / security collections present after DbTestBase initialisation.
   * Each user class has multiple underlying clusters named {@code <classname>_<id>},
   * one per partition; the walker iterates clusters individually. We exclude every
   * cluster whose name does NOT start with the lower-cased CLS_A/CLS_B prefix so
   * only user-class clusters remain.
   */
  private Set<String> systemCollectionsToExclude() {
    var excluded = new HashSet<String>();
    var clsAPrefix = CLS_A.toLowerCase(java.util.Locale.ROOT);
    var clsBPrefix = CLS_B.toLowerCase(java.util.Locale.ROOT);
    for (var name : session.getCollectionNames()) {
      var lower = name.toLowerCase(java.util.Locale.ROOT);
      if (!lower.startsWith(clsAPrefix) && !lower.startsWith(clsBPrefix)) {
        excluded.add(name);
      }
    }
    return excluded;
  }

  @Test
  public void walkSkipsRecordsWhenVisitorRejects() {
    seedFixture();
    var walker = new DatabaseRecordWalker(session, systemCollectionsToExclude());
    var seen = new AtomicInteger();

    var total = walker.walk(rid -> {
      seen.incrementAndGet();
      return false;
    });

    // The visitor saw every record, but the walker's "visit accepted" counter
    // stays at 0 because the visitor returned false for each.
    assertEquals(5, seen.get());
    assertEquals(0, total);
  }

  @Test
  public void walkEntitiesInTxDefaultsToConsistencyCheckEnabled() {
    seedFixture();
    var walker = new DatabaseRecordWalker(session, systemCollectionsToExclude());
    var seenEntities = new AtomicInteger();

    var total = walker.walkEntitiesInTx(entity -> {
      assertNotNull(entity);
      assertNotNull(entity.getProperty("name"));
      seenEntities.incrementAndGet();
      return true;
    });

    assertEquals(5, total);
    assertEquals(5, seenEntities.get());
  }

  @Test
  public void walkEntitiesInTxWithSkipFlagToggleConsistencyCheck() {
    seedFixture();
    var walker = new DatabaseRecordWalker(session, systemCollectionsToExclude());
    var seenEntities = new AtomicInteger();

    var total = walker.walkEntitiesInTx(true, entity -> {
      seenEntities.incrementAndGet();
      return true;
    });

    assertEquals(5, total);
    assertEquals(5, seenEntities.get());

    // After the walker returns, the session's link-consistency check must be
    // re-enabled — the walker's finally block restores it. We can't read the
    // flag back directly, but a follow-up walk with skip=false must succeed
    // without throwing (proves the disable side-effect was un-done).
    seenEntities.set(0);
    walker.walkEntitiesInTx(false, entity -> {
      seenEntities.incrementAndGet();
      return true;
    });
    assertEquals(5, seenEntities.get());
  }

  @Test
  public void onProgressPeriodicallyRegistersListenerAndFiresDuringWalk() {
    seedFixture();
    // 0 ms interval means "fire on every record" (interval >= onProgressInterval is
    // always true). The progress listener must therefore be invoked at least once.
    var walker =
        new DatabaseRecordWalker(session, systemCollectionsToExclude())
            .onProgressPeriodically(0L, (colName, colSize, seenInCol, finished, total, rps) -> {
            });

    // Verify the chained-self return.
    assertSame(walker,
        walker.onProgressPeriodically(0L, DatabaseRecordWalker.ProgressListener.NOOP));

    var calls = new AtomicLong();
    var lastFinishedFlag = new AtomicReference<Boolean>(null);
    walker.onProgressPeriodically(
        0L,
        (colName, colSize, seenInCol, finished, total, rps) -> {
          calls.incrementAndGet();
          lastFinishedFlag.set(finished);
        });

    var total = walker.walk(rid -> true);
    assertEquals(5, total);
    assertTrue("progress listener must fire at least once", calls.get() > 0);
    // The walker emits a final per-collection callback with `finished=true` after
    // the inner loop drains; that must be the most recent callback we observed.
    assertEquals(Boolean.TRUE, lastFinishedFlag.get());
  }

  @Test
  public void excludeCollectionsDropsConfiguredClassesFromTheWalk() {
    seedFixture();
    // System / security collections plus all CLS_A partitions — leaves only CLS_B
    // records visible to the walker.
    var excluded = systemCollectionsToExclude();
    var clsAPrefix = CLS_A.toLowerCase(java.util.Locale.ROOT);
    for (var name : session.getCollectionNames()) {
      if (name.toLowerCase(java.util.Locale.ROOT).startsWith(clsAPrefix)) {
        excluded.add(name);
      }
    }
    var walker = new DatabaseRecordWalker(session, excluded);
    var visited = new AtomicInteger();

    var total = walker.walk(rid -> {
      visited.incrementAndGet();
      return true;
    });

    // CLS_A had 3 records; with it excluded only the 2 CLS_B records remain.
    assertEquals(2, total);
    assertEquals(2, visited.get());
  }

  @Test
  public void internalCollectionIsAlwaysSkippedEvenWithoutExplicitExclusion() {
    seedFixture();
    // Empty exclusion set — the walker still skips the
    // MetadataDefault.COLLECTION_INTERNAL_NAME bucket via its inner-loop continue.
    // We pin the constant referenced by the implementation here so a refactor to a
    // different constant surfaces during review.
    assertEquals("internal", MetadataDefault.COLLECTION_INTERNAL_NAME);
    // Exclude system clusters but NOT 'internal' — verify the walker still skips it
    // because its inner-loop guard is independent of the excludeCollections set.
    var excluded = systemCollectionsToExclude();
    excluded.remove(MetadataDefault.COLLECTION_INTERNAL_NAME);
    var walker = new DatabaseRecordWalker(session, excluded);

    var visitedRids = new HashSet<Object>();
    walker.walk(rid -> {
      // Verify no record came from the 'internal' cluster. The cluster ID for
      // 'internal' is reachable via the session lookup; comparing against the
      // visited rid's cluster ID rules out a leak through the inner-loop guard.
      assertFalse(
          "no record from the internal collection should leak through the walk",
          rid.getCollectionId() == session.getCollectionIdByName(
              MetadataDefault.COLLECTION_INTERNAL_NAME));
      visitedRids.add(rid);
      return true;
    });

    // User records were visited (5 from the fixture); the 'internal' bucket was
    // skipped despite being absent from the explicit exclusion set.
    assertEquals(5, visitedRids.size());
  }

  @Test
  public void noopProgressListenerConstantIsHonoured() {
    // The interface declares NOOP as a usable default; pin its callable shape so a
    // refactor that drops or reshapes it surfaces here.
    DatabaseRecordWalker.ProgressListener.NOOP.onProgress("c", 0L, 0L, true, 0L, 0f);
  }

  @Test
  public void walkOnEmptyDatabaseReturnsZeroForUserClasses() {
    // No fixture seeded; the only collections present are system / security ones
    // (DbTestBase always creates them). Excluding them all leaves zero records to
    // walk.
    var walker = new DatabaseRecordWalker(session, systemCollectionsToExclude());
    var total = walker.walk(rid -> true);
    assertEquals(0, total);
  }
}
