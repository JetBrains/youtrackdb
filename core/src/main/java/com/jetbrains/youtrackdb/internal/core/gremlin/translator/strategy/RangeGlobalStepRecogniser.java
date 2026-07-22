package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStepContract;

/**
 * Recogniser for {@code RangeGlobalStep} / {@code RangeGlobalStepPlaceholder}: {@code limit(n)},
 * {@code skip(n)}, and {@code range(low, high)} map to {@link SQLSkip} / {@link SQLLimit}. Unbounded
 * high ({@code -1} or {@link Long#MAX_VALUE}) is skip-only.
 */
final class RangeGlobalStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final RangeGlobalStepRecogniser INSTANCE = new RangeGlobalStepRecogniser();

  private RangeGlobalStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof RangeGlobalStepContract<?> range)) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    // A second range/limit/skip has no clear MATCH composition rule in Phase 1.
    if (ctxHasSkipOrLimit(ctx)) {
      return Outcome.DECLINE;
    }

    var lowObj = range.getLowRange();
    var highObj = range.getHighRange();
    if (lowObj == null || highObj == null) {
      return Outcome.DECLINE;
    }
    long low = lowObj;
    long high = highObj;
    if (low < 0) {
      return Outcome.DECLINE;
    }

    var unboundedHigh = high < 0 || high == Long.MAX_VALUE;
    if (unboundedHigh) {
      if (low == 0) {
        // range(0, -1) / skip(0) is a no-op — accept without clauses.
        return Outcome.ACCEPTED;
      }
      ctx.setSkip(parseSkip(low));
      return Outcome.ACCEPTED;
    }
    if (high < low) {
      // Native emits no traversers; LIMIT 0 matches that empty result.
      high = low;
    }
    long limit = high - low;
    if (low > 0) {
      ctx.setSkip(parseSkip(low));
    }
    ctx.setLimit(parseLimit(limit));
    return Outcome.ACCEPTED;
  }

  private static boolean ctxHasSkipOrLimit(RecognitionContext ctx) {
    if (ctx instanceof WalkerContext walker) {
      return walker.skip != null || walker.limit != null;
    }
    return false;
  }

  private static SQLSkip parseSkip(long value) {
    try {
      var sql = "SELECT FROM V SKIP " + value;
      var parser =
          new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
      var stmt = (SQLSelectStatement) parser.parse();
      var skip = stmt.getSkip();
      if (skip == null) {
        throw new IllegalArgumentException("failed to parse SKIP " + value);
      }
      return skip;
    } catch (ParseException e) {
      throw new IllegalArgumentException("failed to parse SKIP " + value, e);
    }
  }

  private static SQLLimit parseLimit(long value) {
    try {
      var sql = "SELECT FROM V LIMIT " + value;
      var parser =
          new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
      var stmt = (SQLSelectStatement) parser.parse();
      var limit = stmt.getLimit();
      if (limit == null) {
        throw new IllegalArgumentException("failed to parse LIMIT " + value);
      }
      return limit;
    } catch (ParseException e) {
      throw new IllegalArgumentException("failed to parse LIMIT " + value, e);
    }
  }
}
