package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.Test;

/**
 * Direct-step tests for the live scan budget on {@link RidFilteredIndexValuesStep}.
 *
 * <p>The pre-emission bail-out keeps the initial scan bounded. A successful prefill removes the
 * bound because later graph-pattern filters can reject the prefetched rows.
 */
public class RidFilteredIndexValuesStepBudgetTest extends DbTestBase {

  private static final int TOTAL = 40;
  private static final long TIGHT_BUDGET = 10;

  private void seedIndexedPeople() {
    var person = session.createVertexClass("Person");
    person.createProperty("age", PropertyType.INTEGER);
    person.getProperty("age").createIndex(INDEX_TYPE.NOTUNIQUE);
    session.begin();
    for (var i = 0; i < TOTAL; i++) {
      session.execute("CREATE VERTEX Person SET age = " + i + ", name = 'p" + i + "'").close();
    }
    session.commit();
  }

  private RidSet allPersonRids() {
    var ridSet = new RidSet();
    try (var rs = session.query("SELECT @rid AS rid FROM Person")) {
      while (rs.hasNext()) {
        ridSet.add((RID) rs.next().getProperty("rid"));
      }
    }
    return ridSet;
  }

  private static List<Integer> drainAges(
      RidFilteredIndexValuesStep step, BasicCommandContext ctx) {
    var ages = new ArrayList<Integer>();
    var stream = step.internalStart(ctx);
    while (stream.hasNext(ctx)) {
      var row = stream.next(ctx);
      ages.add(((Number) row.getProperty("key")).intValue());
    }
    stream.close(ctx);
    return ages;
  }

  /**
   * Without a lift, a tight budget stops the filtered scan after roughly that many index
   * entries — far short of the full membership set.
   */
  @Test
  public void tightBudgetStopsTheFilteredScanShortOfAllMembers() {
    seedIndexedPeople();
    var index = session.getSharedContext().getIndexManager()
        .getIndex(session, "Person.age");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    var step = new RidFilteredIndexValuesStep(
        new IndexSearchDescriptor(index), true, ctx, false, allPersonRids(), TIGHT_BUDGET);

    session.begin();
    try {
      var ages = drainAges(step, ctx);
      assertThat(ages)
          .as("a budget of " + TIGHT_BUDGET + " must not deliver all " + TOTAL + " members")
          .hasSizeLessThan(TOTAL);
      assertThat(step.scanBudgetExhausted())
          .as("the stream must end because the budget fired, not because the index ran out")
          .isTrue();
      assertThat(step.consumedEntryCount())
          .as("consumed entries stop around the configured budget")
          .isGreaterThan(TIGHT_BUDGET)
          .isLessThanOrEqualTo(TIGHT_BUDGET + 8);
    } finally {
      if (session.getTransactionInternal().isActive()) {
        session.rollback();
      }
    }
  }

  /** Lifting an initially finite budget lets the active stream reach every indexed member. */
  @Test
  public void liftScanBudgetRemovesFiniteBound() {
    seedIndexedPeople();
    var index = session.getSharedContext().getIndexManager()
        .getIndex(session, "Person.age");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    var step = new RidFilteredIndexValuesStep(
        new IndexSearchDescriptor(index), true, ctx, false, allPersonRids(), TIGHT_BUDGET);

    session.begin();
    try {
      var stream = step.internalStart(ctx);
      var prefillAges = new ArrayList<Integer>();
      while (prefillAges.size() < TIGHT_BUDGET && stream.hasNext(ctx)) {
        prefillAges.add(((Number) stream.next(ctx).getProperty("key")).intValue());
      }

      step.liftScanBudget();

      var continuationAges = new ArrayList<Integer>();
      while (stream.hasNext(ctx)) {
        continuationAges.add(((Number) stream.next(ctx).getProperty("key")).intValue());
      }
      stream.close(ctx);

      assertThat(prefillAges)
          .as("the prefill returns the first ordered window")
          .containsExactlyElementsOf(agesFrom(0, (int) TIGHT_BUDGET));
      assertThat(continuationAges)
          .as("continuation resumes after the prefill without duplicates")
          .containsExactlyElementsOf(agesFrom((int) TIGHT_BUDGET, TOTAL));
      assertThat(step.consumedEntryCount()).isGreaterThan(TIGHT_BUDGET * 2);
    } finally {
      if (session.getTransactionInternal().isActive()) {
        session.rollback();
      }
    }
  }

  /** Lifting a zero budget removes the gate before the stream starts. */
  @Test
  public void liftScanBudgetRemovesZeroBound() {
    seedIndexedPeople();
    var index = session.getSharedContext().getIndexManager()
        .getIndex(session, "Person.age");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    var step = new RidFilteredIndexValuesStep(
        new IndexSearchDescriptor(index), true, ctx, false, allPersonRids(), 0);

    step.liftScanBudget();

    session.begin();
    try {
      assertThat(drainAges(step, ctx)).containsExactlyElementsOf(agesFrom(0, TOTAL));
    } finally {
      if (session.getTransactionInternal().isActive()) {
        session.rollback();
      }
    }
  }

  /** An initially negative budget remains unbounded without requiring a lift. */
  @Test
  public void initiallyNegativeScanBudgetRemainsUnbounded() {
    seedIndexedPeople();
    var index = session.getSharedContext().getIndexManager()
        .getIndex(session, "Person.age");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    var step = new RidFilteredIndexValuesStep(
        new IndexSearchDescriptor(index), true, ctx, false, allPersonRids(), -2);

    session.begin();
    try {
      assertThat(drainAges(step, ctx)).containsExactlyElementsOf(agesFrom(0, TOTAL));
    } finally {
      if (session.getTransactionInternal().isActive()) {
        session.rollback();
      }
    }
  }

  /** Repeated lifts of an ordinary finite budget remain idempotently unbounded. */
  @Test
  public void repeatedLiftScanBudgetCallsRemainUnbounded() {
    seedIndexedPeople();
    var index = session.getSharedContext().getIndexManager()
        .getIndex(session, "Person.age");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    var step = new RidFilteredIndexValuesStep(
        new IndexSearchDescriptor(index), true, ctx, false, allPersonRids(), TIGHT_BUDGET);

    step.liftScanBudget();
    step.liftScanBudget();
    step.liftScanBudget();

    session.begin();
    try {
      assertThat(drainAges(step, ctx)).containsExactlyElementsOf(agesFrom(0, TOTAL));
    } finally {
      if (session.getTransactionInternal().isActive()) {
        session.rollback();
      }
    }
  }

  private static List<Integer> agesFrom(int startInclusive, int endExclusive) {
    return IntStream.range(startInclusive, endExclusive).boxed().toList();
  }
}
