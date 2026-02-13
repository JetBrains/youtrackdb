package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for new transaction DSL methods: autoExecuteInTx(), autoCalculateInTx(), and DSL steps.
 *
 * NOTE: Tests with multiple transactions (commit followed by rollback) are not included
 * because YouTrackDB uses ThreadLocal transactions. All operations in the same thread share
 * the same transaction, so rollback affects all operations, not just the current traversal.
 */
public class YTDBTransactionDSLTest extends GraphBaseTest {

  private YTDBGraphTraversalSource g;

  @Override
  public void setupGraphDB() {
    super.setupGraphDB();
    g = graph.traversal();
  }

  @Test
  public void testAutoExecuteInTxCommitsChanges() {
    // Execute mutations in transaction with begin/commit
    g.autoExecuteInTx(source ->
        source.addV("Person").property("name", "John")
            .begin()
            .addV("Person").property("name", "Jane")
            .commit()
    );

    // Verify changes were committed
    var count = g.V().hasLabel("Person").count().next();
    Assert.assertEquals("Should have 2 vertices after commit", Long.valueOf(2L), count);
  }

  @Test
  public void testAutoCalculateInTxReturnsValue() {
    // Add vertices with commit
    g.addV("Person").property("name", "Alice")
        .begin()
        .addV("Person").property("name", "Bob")
        .addV("Company").property("name", "Acme")
        .commit()
        .iterate();

    // Calculate in transaction
    var count = g.autoCalculateInTx(source ->
        source.V().hasLabel("Person")
            .begin()
            .count()
            .commit()
            .next()
    );

    Assert.assertEquals("Should return 2 Person vertices", Long.valueOf(2L), count);
  }

  @Test
  public void testBeginCommitDSLSteps() {
    // Use DSL steps directly
    g.addV("Person")
        .property("name", "John")
        .begin()
        .property("age", 30)
        .commit()
        .iterate();

    // Verify changes were committed
    var vertex = g.V().hasLabel("Person").next();
    Assert.assertEquals("John", vertex.value("name"));
    Assert.assertEquals(Integer.valueOf(30), vertex.value("age"));
  }

  @Test
  public void testMultipleSequentialTransactions() {
    // First transaction
    g.autoExecuteInTx(source ->
        source.addV("Person").property("name", "First")
            .begin()
            .commit()
    );

    // Second transaction
    g.autoExecuteInTx(source ->
        source.addV("Person").property("name", "Second")
            .begin()
            .commit()
    );

    // Third transaction
    g.autoExecuteInTx(source ->
        source.addV("Person").property("name", "Third")
            .begin()
            .commit()
    );

    // Verify all vertices were added
    var count = g.V().hasLabel("Person").count().next();
    Assert.assertEquals("Should have 3 vertices from sequential transactions", Long.valueOf(3L), count);
  }

  @Test
  public void testComplexTraversalWithTransaction() {
    // Add initial data with commit
    g.addV("Person").property("name", "Alice").property("age", 30)
        .begin()
        .addV("Person").property("name", "Bob").property("age", 25)
        .commit()
        .iterate();

    // Complex transaction: update existing vertex and add new one
    g.autoExecuteInTx(source ->
        source.V().hasLabel("Person").has("name", "Alice")
            .begin()
            .property("age", 31)
            .addV("Person").property("name", "Charlie").property("age", 28)
            .commit()
    );

    // Verify changes
    var aliceAge = g.V().hasLabel("Person").has("name", "Alice").values("age").next();
    Assert.assertEquals("Alice age should be updated", Integer.valueOf(31), aliceAge);

    var personCount = g.V().hasLabel("Person").count().next();
    Assert.assertEquals("Should have 3 persons", Long.valueOf(3L), personCount);
  }
}
