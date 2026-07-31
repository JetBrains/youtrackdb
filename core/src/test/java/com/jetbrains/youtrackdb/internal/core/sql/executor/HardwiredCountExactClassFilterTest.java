package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Pins exact-{@code @class} recognition used by MATCH count short-circuit. */
public class HardwiredCountExactClassFilterTest {

  private final MatchWhereBuilder where = new MatchWhereBuilder();

  /** Gremlin {@code MatchWhereBuilder.classEquals} AST folds to leaf-exact count. */
  @Test
  public void gremlinBuiltClassEquals_isRecognized() {
    var clause = where.wrap(where.classEquals("Person"));
    assertThat(HardwiredCountOptimizations.isExactClassEqualsOnly(clause, "Person")).isTrue();
    assertThat(HardwiredCountOptimizations.isExactClassEqualsOnly(clause, "Company")).isFalse();
  }

  /**
   * SQL-parsed {@code WHERE @class = 'Person'} (Or → And → Not(negate=false) → Binary) also folds.
   */
  @Test
  public void sqlParsedClassEquals_isRecognized() throws Exception {
    var clause = parseWhere("@class = 'Person'");
    assertThat(HardwiredCountOptimizations.isExactClassEqualsOnly(clause, "Person")).isTrue();
    assertThat(HardwiredCountOptimizations.isExactClassEqualsOnly(clause, "Company")).isFalse();
  }

  /** {@code NOT @class = 'Person'} must not be treated as leaf-exact class equality. */
  @Test
  public void sqlParsedNegatedClassEquals_isRejected() throws Exception {
    var clause = parseWhere("NOT (@class = 'Person')");
    assertThat(HardwiredCountOptimizations.isExactClassEqualsOnly(clause, "Person")).isFalse();
  }

  private static com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause parseWhere(
      String body) throws Exception {
    var sql = "SELECT FROM V WHERE " + body;
    var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
    var stm = (SQLSelectStatement) parser.parse();
    return stm.getWhereClause();
  }
}
