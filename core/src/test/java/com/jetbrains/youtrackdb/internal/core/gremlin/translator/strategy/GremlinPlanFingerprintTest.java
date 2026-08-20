package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import org.junit.Test;

/**
 * Low-level fingerprint regression tests for planner-visible distinctions that are easier to pin on
 * raw {@link MatchPlanInputs} than through a full Gremlin traversal.
 */
public class GremlinPlanFingerprintTest {

  /**
   * Nested projections are carried in {@link MatchPlanInputs#returnNestedProjections()} and affect
   * the planner's output shaping. Two MATCH inputs that differ only in the nested projection must
   * not share a Gremlin plan-cache fingerprint.
   */
  @Test
  public void nestedProjections_distinguishFingerprint() {
    var withName = parse("MATCH {class: V, as: a} RETURN a:{name}");
    var withSurname = parse("MATCH {class: V, as: a} RETURN a:{surname}");

    var fpName = fingerprintForProjection(withName);
    var fpSurname = fingerprintForProjection(withSurname);

    assertThat(fpName).isNotEqualTo(fpSurname);
  }

  /**
   * A plain return and a nested-projection return must not collide even when the return item itself
   * is the same identifier.
   */
  @Test
  public void plainReturn_and_nestedProjection_distinguishFingerprint() {
    var plain = parse("MATCH {class: V, as: a} RETURN a");
    var nested = parse("MATCH {class: V, as: a} RETURN a:{name}");

    var fpPlain = fingerprintForProjection(plain);
    var fpNested = fingerprintForProjection(nested);

    assertThat(fpPlain).isNotEqualTo(fpNested);
  }

  private static String fingerprintForProjection(SQLMatchStatement statement) {
    var inputs =
        MatchPlanInputs.builder(new Pattern())
            .returnItems(statement.getReturnItems())
            .returnAliases(statement.getReturnAliases())
            .returnNestedProjections(statement.getReturnNestedProjections())
            .build();
    return GremlinPlanFingerprint.fingerprint(inputs);
  }

  private static SQLMatchStatement parse(String query) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(query.getBytes()));
      return (SQLMatchStatement) parser.parse();
    } catch (ParseException e) {
      throw new RuntimeException("Failed to parse MATCH statement: " + query, e);
    }
  }
}
