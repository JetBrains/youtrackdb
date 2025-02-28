package com.jetbrains.youtrack.db.internal.core.sql.filter;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.IndexSearchResult;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
import org.junit.Assert;
import org.junit.Test;

public class FilterOptimizerTest extends DbTestBase {

  private final FilterOptimizer optimizer = new FilterOptimizer();

  @Test
  public void testOptimizeFullOptimization() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    final var filter = SQLEngine.parseCondition("a = 3", context,
        "WHERE");

    final var condition = filter.getRootCondition();

    final var searchResult =
        new IndexSearchResult(
            condition.getOperator(),
            ((SQLFilterItemField) condition.getLeft()).getFieldChain(),
            3);

    optimizer.optimize(filter, searchResult);

    Assert.assertNull(filter.getRootCondition());
  }

  @Test
  public void testOptimizeFullOptimizationComplex() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    final var filter =
        SQLEngine.parseCondition("a = 3 and b = 4", context, "WHERE");

    final var condition = filter.getRootCondition();

    final IndexSearchResult searchResult;
    {
      final IndexSearchResult searchResult1;
      {
        final var cnd = (SQLFilterCondition) condition.getLeft();
        searchResult1 =
            new IndexSearchResult(
                cnd.getOperator(), ((SQLFilterItemField) cnd.getLeft()).getFieldChain(), 3);
      }
      final IndexSearchResult searchResult2;
      {
        final var cnd = (SQLFilterCondition) condition.getRight();
        searchResult2 =
            new IndexSearchResult(
                cnd.getOperator(), ((SQLFilterItemField) cnd.getLeft()).getFieldChain(), 4);
      }
      searchResult = searchResult1.merge(searchResult2);
    }

    optimizer.optimize(filter, searchResult);

    Assert.assertNull(filter.getRootCondition());
  }

  @Test
  public void testOptimizePartialOptimization() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    final var filter =
        SQLEngine.parseCondition("a = 3 and b > 5", context, "WHERE");

    final var condition = filter.getRootCondition();

    final var searchResult =
        new IndexSearchResult(
            ((SQLFilterCondition) condition.getLeft()).getOperator(),
            ((SQLFilterItemField) ((SQLFilterCondition) condition.getLeft()).getLeft())
                .getFieldChain(),
            3);

    optimizer.optimize(filter, searchResult);

    Assert.assertEquals(filter.getRootCondition().asString(session), "(b > 5)");
  }

  @Test
  public void testOptimizePartialOptimizationMethod() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    final var filter =
        SQLEngine.parseCondition("a = 3 and b.asFloat() > 3.14", context,
            "WHERE");

    final var condition = filter.getRootCondition();

    final var searchResult =
        new IndexSearchResult(
            ((SQLFilterCondition) condition.getLeft()).getOperator(),
            ((SQLFilterItemField) ((SQLFilterCondition) condition.getLeft()).getLeft())
                .getFieldChain(),
            3);

    optimizer.optimize(filter, searchResult);

    Assert.assertEquals(filter.getRootCondition().asString(session), "(b.asfloat > 3.14)");
  }
}
