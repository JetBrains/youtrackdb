package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class MatchWhereBuilderMatchedAccessTest {

  @Test
  public void matchedAccess_parsesSingleLetterAlias() {
    var builder = new MatchWhereBuilder();
    var expr = builder.matchedAccess("a", "@rid");
    var sb = new StringBuilder();
    expr.toGenericStatement(sb);
    assertThat(sb.toString()).isEqualTo("$matched.a.@rid");
  }
}
