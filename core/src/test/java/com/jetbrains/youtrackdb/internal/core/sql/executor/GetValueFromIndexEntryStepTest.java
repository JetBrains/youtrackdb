package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Tests for {@link GetValueFromIndexEntryStep}, an intermediate execution step
 * that extracts a record RID from an index entry and loads the full record from
 * storage.
 *
 * <p>Covers:
 * <ul>
 *   <li>Null predecessor guard (throws IllegalStateException)</li>
 *   <li>No collection filtering: Identifiable rid loads a full record</li>
 *   <li>No collection filtering: Result-typed rid passes through unchanged</li>
 *   <li>No collection filtering: non-Identifiable, non-Result rid returns null (skipped)</li>
 *   <li>No collection filtering: null rid property is skipped</li>
 *   <li>Collection filtering: Identifiable with matching collection passes through</li>
 *   <li>Collection filtering: Identifiable with non-matching collection is filtered out</li>
 *   <li>Collection filtering: Identifiable with negative collection ID (new record) passes</li>
 *   <li>Collection filtering: non-Identifiable value is filtered out</li>
 *   <li>Collection filtering: multiple filter IDs, match on second ID</li>
 *   <li>Collection filtering: Result-typed rid is filtered out (not Identifiable)</li>
 *   <li>Mixed entries with filter: only matching entries pass through</li>
 *   <li>prettyPrint without profiling and without filter</li>
 *   <li>prettyPrint with profiling</li>
 *   <li>prettyPrint with collection filter renders filter list</li>
 *   <li>prettyPrint with depth applies indentation</li>
 *   <li>prettyPrint with both profiling and filter</li>
 *   <li>canBeCached always returns true</li>
 *   <li>copy produces an independent step with same settings</li>
 *   <li>Copied step loads records independently</li>
 *   <li>copy with null filter produces step without filtering</li>
 * </ul>
 */
public class GetValueFromIndexEntryStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: predecessor guard
  // =========================================================================

  /**
   * Starting GetValueFromIndexEntryStep without a predecessor throws
   * IllegalStateException because there is no upstream data source to extract
   * index entries from.
   */
  @Test
  public void startWithNoPredecessorThrowsIllegalStateException() {
    var ctx = newContext();
    var step = new GetValueFromIndexEntryStep(ctx, null, false);

    assertThatThrownBy(() -> step.start(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires a previous step");
  }

  // =========================================================================
  // filterMap: no collection filtering (filterCollectionIds == null)
  // =========================================================================

  /**
   * When no collection filter is applied and the index entry's "rid" property
   * is an Identifiable (a real record RID), the step loads the full record from
   * storage and returns it as a ResultInternal.
   */
  @Test
  public void identifiableRidWithoutFilterLoadsFullRecord() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    session.begin();
    var entity = session.newEntity(className);
    entity.setProperty("name", "Alice");
    session.commit();

    var rid = entity.getIdentity();

    // Bypass setProperty: it calls convertPropertyValue(), which converts
    // Identifiable to a bare RID, losing the Identifiable type that
    // filterMap() checks with instanceof.
    var indexEntry = new ResultInternal(session);
    indexEntry.content.put("rid", rid);

    var step = new GetValueFromIndexEntryStep(ctx, null, false);
    step.setPrevious(sourceStep(ctx, List.of(indexEntry)));

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      // The loaded record should have the original properties
      assertThat(results.getFirst().getIdentity()).isEqualTo(rid);
    } finally {
      session.rollback();
    }
  }

  /**
   * When no collection filter is applied and the index entry's "rid" property
   * is a Result (e.g. from a subquery index), the step passes it through
   * unchanged.
   */
  @Test
  public void resultRidWithoutFilterPassesThroughUnchanged() {
    var ctx = newContext();

    var innerResult = new ResultInternal(session);
    innerResult.setProperty("computed", 42);

    var indexEntry = new ResultInternal(session);
    indexEntry.setProperty("rid", innerResult);

    var step = new GetValueFromIndexEntryStep(ctx, null, false);
    step.setPrevious(sourceStep(ctx, List.of(indexEntry)));

    var results = drain(step.start(ctx), ctx);
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().<Integer>getProperty("computed")).isEqualTo(42);
  }

  /**
   * When no collection filter is applied and the index entry's "rid" property
   * is neither Identifiable nor Result (e.g. a plain String), the step returns
   * null (the entry is skipped).
   */
  @Test
  public void nonIdentifiableNonResultRidWithoutFilterIsSkipped() {
    var ctx = newContext();

    var indexEntry = new ResultInternal(session);
    indexEntry.setProperty("rid", "not-a-rid");

    var step = new GetValueFromIndexEntryStep(ctx, null, false);
    step.setPrevious(sourceStep(ctx, List.of(indexEntry)));

    var results = drain(step.start(ctx), ctx);
    assertThat(results).isEmpty();
  }

  /**
   * When no collection filter is applied and the index entry's "rid" property
   * is null, the step returns null (the entry is skipped) because null is
   * neither Identifiable nor Result.
   */
  @Test
  public void nullRidWithoutFilterIsSkipped() {
    var ctx = newContext();

    var indexEntry = new ResultInternal(session);
    indexEntry.setProperty("rid", null);

    var step = new GetValueFromIndexEntryStep(ctx, null, false);
    step.setPrevious(sourceStep(ctx, List.of(indexEntry)));

    var results = drain(step.start(ctx), ctx);
    assertThat(results).isEmpty();
  }

  // =========================================================================
  // filterMap: with collection filtering (filterCollectionIds != null)
  // =========================================================================

  /**
   * When a collection filter is active and the index entry's RID belongs to
   * one of the allowed collections, the record is loaded and returned.
   */
  @Test
  public void matchingCollectionIdPassesThroughFilter() {
    var clazz = createClassInstance();
    var ctx = newContext();

    session.begin();
    var entity = session.newEntity(clazz.getName());
    entity.setProperty("val", "ok");
    session.commit();

    var rid = entity.getIdentity();
    var indexEntry = new ResultInternal(session);
    indexEntry.content.put("rid", rid); // bypass setProperty (see first test)

    // Use the entity's actual collection ID to guarantee a match
    var filterIds = new IntArrayList(new int[]{rid.getCollectionId()});
    var step = new GetValueFromIndexEntryStep(ctx, filterIds, false);
    step.setPrevious(sourceStep(ctx, List.of(indexEntry)));

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat(results.getFirst().getIdentity()).isEqualTo(rid);
    } finally {
      session.rollback();
    }
  }

  /**
   * When a collection filter is active and the index entry's RID does not
   * belong to any of the allowed collections, the entry is filtered out.
   */
  @Test
  public void nonMatchingCollectionIdIsFilteredOut() {
    var clazz = createClassInstance();
    var ctx = newContext();

    session.begin();
    var entity = session.newEntity(clazz.getName());
    entity.setProperty("val", "filtered");
    session.commit();

    var rid = entity.getIdentity();
    var indexEntry = new ResultInternal(session);
    indexEntry.content.put("rid", rid); // bypass setProperty (see first test)

    // Use a collection ID that does not match the entity's collection
    var nonMatchingId = rid.getCollectionId() + 9999;
    var filterIds = new IntArrayList(new int[]{nonMatchingId});
    var step = new GetValueFromIndexEntryStep(ctx, filterIds, false);
    step.setPrevious(sourceStep(ctx, List.of(indexEntry)));

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).isEmpty();
    } finally {
      session.rollback();
    }
  }

  /**
   * When a collection filter is active and the RID has a negative collection ID
   * (indicating a not-yet-committed record), the entry passes through regardless
   * of the filter — negative collection IDs are always allowed.
   */
  @Test
  public void negativeCollectionIdPassesThroughFilter() {
    var ctx = newContext();

    // Create a RID with a negative collection ID (e.g. -1 = COLLECTION_ID_INVALID).
    // This simulates a record that has not been assigned to a collection yet.
    var negativeRid = new RecordId(-1, 0);
    assertThat(negativeRid.getCollectionId()).isLessThan(0);

    var indexEntry = new ResultInternal(session);
    indexEntry.content.put("rid", negativeRid); // bypass setProperty (see first test)

    // The filter collection ID 999 does NOT match -1, but the negative ID
    // bypasses the filter entirely.
    var filterIds = new IntArrayList(new int[]{999});
    var step = new GetValueFromIndexEntryStep(ctx, filterIds, false);
    step.setPrevious(sourceStep(ctx, List.of(indexEntry)));

    var results = drain(step.start(ctx), ctx);
    // The entry passes through because negative collection IDs are always allowed
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().getIdentity()).isEqualTo(negativeRid);
  }

  /**
   * When a collection filter is active and the index entry's "rid" property is
   * not an Identifiable (e.g. a plain string), the entry is filtered out because
   * the filter requires an Identifiable to check the collection ID.
   */
  @Test
  public void nonIdentifiableWithFilterIsFilteredOut() {
    var ctx = newContext();

    var indexEntry = new ResultInternal(session);
    indexEntry.setProperty("rid", "not-identifiable");

    var filterIds = new IntArrayList(new int[]{10});
    var step = new GetValueFromIndexEntryStep(ctx, filterIds, false);
    step.setPrevious(sourceStep(ctx, List.of(indexEntry)));

    var results = drain(step.start(ctx), ctx);
    assertThat(results).isEmpty();
  }

  /**
   * When a collection filter contains multiple collection IDs, a record whose
   * collection ID matches ANY of them should pass through (not just the first).
   */
  @Test
  public void multipleFilterIdsMatchesSecondId() {
    var clazz = createClassInstance();
    var ctx = newContext();

    session.begin();
    var entity = session.newEntity(clazz.getName());
    entity.setProperty("val", "multi");
    session.commit();

    var rid = entity.getIdentity();
    var collectionId = rid.getCollectionId();
    var indexEntry = new ResultInternal(session);
    indexEntry.content.put("rid", rid); // bypass setProperty (see first test)

    // The matching collection ID is the second in the list
    var filterIds = new IntArrayList(new int[]{collectionId + 9999, collectionId});
    var step = new GetValueFromIndexEntryStep(ctx, filterIds, false);
    step.setPrevious(sourceStep(ctx, List.of(indexEntry)));

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat(results.getFirst().getIdentity()).isEqualTo(rid);
    } finally {
      session.rollback();
    }
  }

  /**
   * With a collection filter active, a Result-typed "rid" (which is not
   * Identifiable) should be filtered out even though without a filter it
   * would pass through as-is.
   */
  @Test
  public void resultRidWithFilterIsFilteredOut() {
    var ctx = newContext();

    var innerResult = new ResultInternal(session);
    innerResult.setProperty("data", "test");

    var indexEntry = new ResultInternal(session);
    indexEntry.setProperty("rid", innerResult);

    var filterIds = new IntArrayList(new int[]{10});
    var step = new GetValueFromIndexEntryStep(ctx, filterIds, false);
    step.setPrevious(sourceStep(ctx, List.of(indexEntry)));

    var results = drain(step.start(ctx), ctx);
    assertThat(results).isEmpty();
  }

  // =========================================================================
  // Multiple results: mixed pass/filter
  // =========================================================================

  /**
   * When the upstream produces multiple index entries, some matching and some
   * not matching the collection filter, only the matching entries pass through.
   */
  @Test
  public void mixedEntriesWithFilterProducesOnlyMatching() {
    var clazz1 = createClassInstance();
    var clazz2 = createClassInstance();
    var ctx = newContext();

    session.begin();
    var e1 = session.newEntity(clazz1.getName());
    e1.setProperty("src", "class1");
    var e2 = session.newEntity(clazz2.getName());
    e2.setProperty("src", "class2");
    session.commit();

    var entry1 = new ResultInternal(session);
    entry1.content.put("rid", e1.getIdentity()); // bypass setProperty (see first test)
    var entry2 = new ResultInternal(session);
    entry2.content.put("rid", e2.getIdentity()); // bypass setProperty (see first test)

    // Only allow collection IDs from clazz1
    var filterIds = new IntArrayList(clazz1.getCollectionIds());
    var step = new GetValueFromIndexEntryStep(ctx, filterIds, false);
    step.setPrevious(sourceStep(ctx, List.of(entry1, entry2)));

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat(results.getFirst().getIdentity()).isEqualTo(e1.getIdentity());
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint without profiling and without collection filter renders only
   * the step label "EXTRACT VALUE FROM INDEX ENTRY".
   */
  @Test
  public void prettyPrintWithoutProfilingAndWithoutFilter() {
    var ctx = newContext();
    var step = new GetValueFromIndexEntryStep(ctx, null, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).isEqualTo("+ EXTRACT VALUE FROM INDEX ENTRY");
  }

  /**
   * prettyPrint with profiling enabled appends the cost in microseconds.
   */
  @Test
  public void prettyPrintWithProfilingAppendsCost() {
    var ctx = newContext();
    var step = new GetValueFromIndexEntryStep(ctx, null, true);

    var output = step.prettyPrint(0, 2);

    assertThat(output).startsWith("+ EXTRACT VALUE FROM INDEX ENTRY");
    assertThat(output).contains("\u03bcs");
  }

  /**
   * prettyPrint with a non-null collection filter appends the "filtering
   * collections [...]" line showing the collection IDs.
   */
  @Test
  public void prettyPrintWithCollectionFilterRendersFilterList() {
    var ctx = newContext();
    var filterIds = new IntArrayList(new int[]{5, 12});
    var step = new GetValueFromIndexEntryStep(ctx, filterIds, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("EXTRACT VALUE FROM INDEX ENTRY");
    assertThat(output).contains("filtering collections [");
    assertThat(output).contains("5");
    assertThat(output).contains("12");
  }

  /**
   * prettyPrint with non-zero depth applies the expected indentation
   * (depth * indent leading spaces).
   */
  @Test
  public void prettyPrintWithDepthAppliesIndentation() {
    var ctx = newContext();
    var step = new GetValueFromIndexEntryStep(ctx, null, false);

    // depth=1, indent=4 → 4 leading spaces
    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
    assertThat(output).contains("EXTRACT VALUE FROM INDEX ENTRY");
  }

  /**
   * prettyPrint with both profiling and filter renders cost and collection list.
   */
  @Test
  public void prettyPrintWithProfilingAndFilterRendersBoth() {
    var ctx = newContext();
    var filterIds = new IntArrayList(new int[]{7});
    var step = new GetValueFromIndexEntryStep(ctx, filterIds, true);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("EXTRACT VALUE FROM INDEX ENTRY");
    assertThat(output).contains("\u03bcs");
    assertThat(output).contains("filtering collections [");
    assertThat(output).contains("7");
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /**
   * GetValueFromIndexEntryStep is always cacheable because the collection ID
   * filter list is fixed at construction time.
   */
  @Test
  public void canBeCachedAlwaysReturnsTrue() {
    var ctx = newContext();
    var step = new GetValueFromIndexEntryStep(ctx, null, false);
    assertThat(step.canBeCached()).isTrue();

    var stepWithFilter = new GetValueFromIndexEntryStep(
        ctx, new IntArrayList(new int[]{1}), false);
    assertThat(stepWithFilter.canBeCached()).isTrue();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * copy() produces a new GetValueFromIndexEntryStep that is structurally
   * equivalent but not the same instance. The copy preserves the collection
   * filter and profiling setting.
   */
  @Test
  public void copyProducesIndependentStepWithSameSettings() {
    var ctx = newContext();
    var filterIds = new IntArrayList(new int[]{3, 7});
    var step = new GetValueFromIndexEntryStep(ctx, filterIds, true);

    var copied = step.copy(ctx);

    assertThat(copied).isNotSameAs(step);
    assertThat(copied).isInstanceOf(GetValueFromIndexEntryStep.class);
    var copiedStep = (GetValueFromIndexEntryStep) copied;
    assertThat(copiedStep.isProfilingEnabled()).isTrue();
    assertThat(copiedStep.canBeCached()).isTrue();

    // The copy should render the same collection filter in prettyPrint
    var output = copiedStep.prettyPrint(0, 2);
    assertThat(output).contains("filtering collections [");
    assertThat(output).contains("3");
    assertThat(output).contains("7");
  }

  /**
   * A copied step should be fully functional — loading records from index
   * entries independently of the original step.
   */
  @Test
  public void copiedStepLoadsRecordsIndependently() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    session.begin();
    var entity = session.newEntity(className);
    entity.setProperty("key", "copied");
    session.commit();

    var rid = entity.getIdentity();
    var indexEntry = new ResultInternal(session);
    indexEntry.content.put("rid", rid); // bypass setProperty (see first test)

    var original = new GetValueFromIndexEntryStep(ctx, null, false);
    var copied = (GetValueFromIndexEntryStep) original.copy(ctx);
    copied.setPrevious(sourceStep(ctx, List.of(indexEntry)));

    session.begin();
    try {
      var results = drain(copied.start(ctx), ctx);
      assertThat(results).hasSize(1);
      assertThat(results.getFirst().getIdentity()).isEqualTo(rid);
    } finally {
      session.rollback();
    }
  }

  /**
   * copy() with null filterCollectionIds produces a step without filtering.
   */
  @Test
  public void copyWithNullFilterProducesStepWithoutFiltering() {
    var ctx = newContext();
    var step = new GetValueFromIndexEntryStep(ctx, null, false);

    var copied = step.copy(ctx);

    assertThat(copied).isInstanceOf(GetValueFromIndexEntryStep.class);
    // prettyPrint should not contain "filtering collections"
    var output = ((GetValueFromIndexEntryStep) copied).prettyPrint(0, 2);
    assertThat(output).doesNotContain("filtering collections");
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /**
   * Creates a source step that produces the given results on each start().
   */
  private ExecutionStepInternal sourceStep(
      CommandContext ctx, List<? extends Result> rows) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.resultIterator(
            new ArrayList<>(rows).iterator());
      }
    };
  }

  /**
   * Drains all results from a stream into a list.
   */
  private List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var results = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      results.add(stream.next(ctx));
    }
    stream.close(ctx);
    return results;
  }
}
