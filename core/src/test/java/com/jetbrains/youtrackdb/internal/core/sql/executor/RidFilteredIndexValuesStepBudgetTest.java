package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for the live scan budget on {@link RidFilteredIndexValuesStep}.
 *
 * <p>The pre-emission bail-out in {@code IndexOrderedEdgeStep} needs the bound armed while it
 * fills its buffer. Once that buffer is full, the continuation must keep yielding — a
 * post-emission bail-out is not possible, and an armed bound would silently truncate when a
 * downstream filter asks for more rows. {@link RidFilteredIndexValuesStep#liftScanBudget()} is
 * the gate clear that makes that continuation safe.
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

  /**
   * After a successful prefill-sized pull, {@link RidFilteredIndexValuesStep#liftScanBudget()}
   * clears the live gate so the same stream can keep yielding past the original budget — the
   * continuation path {@code IndexOrderedEdgeStep} needs once rows are already buffered.
   */
  @Test
  public void liftScanBudgetLetsTheContinuationPassTheOriginalBound() {
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
      var firstBatch = new ArrayList<Integer>();
      while (firstBatch.size() < 5 && stream.hasNext(ctx)) {
        firstBatch.add(((Number) stream.next(ctx).getProperty("key")).intValue());
      }
      assertThat(firstBatch)
          .as("prefill-sized pull must succeed under the tight budget")
          .hasSize(5);

      step.liftScanBudget();

      var rest = new ArrayList<Integer>();
      while (stream.hasNext(ctx)) {
        rest.add(((Number) stream.next(ctx).getProperty("key")).intValue());
      }
      stream.close(ctx);

      assertThat(firstBatch.size() + rest.size())
          .as("after the lift the continuation must reach every RidSet member")
          .isEqualTo(TOTAL);
      assertThat(step.consumedEntryCount())
          .as("consumed entries must grow past the original budget once the gate is lifted")
          .isGreaterThan(TIGHT_BUDGET);
    } finally {
      if (session.getTransactionInternal().isActive()) {
        session.rollback();
      }
    }
  }
}
