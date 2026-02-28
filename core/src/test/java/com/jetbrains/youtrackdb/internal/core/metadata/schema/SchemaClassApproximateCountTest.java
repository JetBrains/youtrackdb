package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import org.junit.Test;

/**
 * Tests for the O(1) approximate record count feature at the schema class and session levels.
 * Verifies that {@link SchemaClassInternal#approximateCount} and
 * {@link com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded#approximateCountClass}
 * return correct values for empty classes, after inserts, after deletes, with polymorphic
 * hierarchies, and with pending transaction adjustments.
 */
public class SchemaClassApproximateCountTest extends BaseMemoryInternalDatabase {

  /**
   * Approximate count of a newly created class with no records should be 0.
   */
  @Test
  public void testApproximateCountEmptyClass() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("EmptyClass");

    var cls = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("EmptyClass");

    assertEquals(0, cls.approximateCount(session));
    assertEquals(0, cls.approximateCount(session, false));
    assertEquals(0, cls.approximateCount(session, true));
  }

  /**
   * After inserting N records, approximate count should equal N.
   */
  @Test
  public void testApproximateCountAfterInserts() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("InsertClass");

    int n = 10;
    session.executeInTx(tx -> {
      for (int i = 0; i < n; i++) {
        session.newEntity("InsertClass");
      }
    });

    var cls = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("InsertClass");

    assertEquals(n, cls.approximateCount(session, false));
  }

  /**
   * After inserting N records and deleting M of them, approximate count should equal N - M.
   */
  @Test
  public void testApproximateCountAfterDeletes() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("DeleteClass");

    int n = 10;
    int m = 4;

    // Insert N records
    session.executeInTx(tx -> {
      for (int i = 0; i < n; i++) {
        session.newEntity("DeleteClass");
      }
    });

    // Delete M records
    session.executeInTx(tx -> {
      var result = session.query("SELECT FROM DeleteClass LIMIT " + m);
      while (result.hasNext()) {
        session.delete(result.next().asEntity());
      }
      result.close();
    });

    var cls = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("DeleteClass");

    assertEquals(n - m, cls.approximateCount(session, false));
  }

  /**
   * Polymorphic approximate count should include subclass records, while non-polymorphic
   * should only include direct records.
   */
  @Test
  public void testApproximateCountPolymorphic() {
    Schema schema = session.getMetadata().getSchema();
    var parent = schema.createClass("ParentClass");
    var child1 = schema.createClass("ChildClass1", parent);
    var child2 = schema.createClass("ChildClass2", parent);

    // Insert 3 in parent, 5 in child1, 7 in child2
    session.executeInTx(tx -> {
      for (int i = 0; i < 3; i++) {
        session.newEntity("ParentClass");
      }
      for (int i = 0; i < 5; i++) {
        session.newEntity("ChildClass1");
      }
      for (int i = 0; i < 7; i++) {
        session.newEntity("ChildClass2");
      }
    });

    var parentCls = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("ParentClass");
    var child1Cls = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("ChildClass1");
    var child2Cls = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("ChildClass2");

    // Non-polymorphic counts
    assertEquals(3, parentCls.approximateCount(session, false));
    assertEquals(5, child1Cls.approximateCount(session, false));
    assertEquals(7, child2Cls.approximateCount(session, false));

    // Polymorphic count on parent should include all: 3 + 5 + 7 = 15
    assertEquals(15, parentCls.approximateCount(session, true));

    // Polymorphic count on child classes (no subclasses) should equal non-polymorphic
    assertEquals(5, child1Cls.approximateCount(session, true));
    assertEquals(7, child2Cls.approximateCount(session, true));
  }

  /**
   * Approximate count should reflect pending transaction adjustments: uncommitted creates
   * increase the count, uncommitted deletes decrease it.
   */
  @Test
  public void testApproximateCountWithPendingTransaction() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxClass");

    // Insert 5 committed records
    session.executeInTx(tx -> {
      for (int i = 0; i < 5; i++) {
        session.newEntity("TxClass");
      }
    });

    // Begin a transaction, add 3 more, and check count before commit
    session.begin();
    for (int i = 0; i < 3; i++) {
      session.newEntity("TxClass");
    }

    var cls = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("TxClass");

    // Should reflect pending creates: 5 + 3 = 8
    assertEquals(8, cls.approximateCount(session, false));

    session.commit();

    // After commit, re-read immutable snapshot
    cls = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("TxClass");
    assertEquals(8, cls.approximateCount(session, false));
  }

  /**
   * Approximate count should reflect pending transaction deletes: uncommitted deletes decrease
   * the count visible within the transaction.
   */
  @Test
  public void testApproximateCountWithPendingTransactionDeletes() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("TxDeleteClass");

    // Insert 5 committed records
    session.executeInTx(tx -> {
      for (int i = 0; i < 5; i++) {
        session.newEntity("TxDeleteClass");
      }
    });

    // Begin a transaction, delete 2 records, and check count before commit
    session.begin();
    var result = session.query("SELECT FROM TxDeleteClass LIMIT 2");
    while (result.hasNext()) {
      session.delete(result.next().asEntity());
    }
    result.close();

    var cls = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("TxDeleteClass");

    // Should reflect pending deletes: 5 - 2 = 3
    assertEquals(3, cls.approximateCount(session, false));

    session.commit();

    // After commit, verify the count is persisted
    cls = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass("TxDeleteClass");
    assertEquals(3, cls.approximateCount(session, false));
  }

  /**
   * In a single-threaded scenario with committed data, approximate count should be consistent
   * with exact count.
   */
  @Test
  public void testApproximateCountConsistentWithExactCount() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("ConsistencyClass");

    session.executeInTx(tx -> {
      for (int i = 0; i < 20; i++) {
        session.newEntity("ConsistencyClass");
      }
    });

    session.executeInTx(tx -> {
      var cls = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
          .getClass("ConsistencyClass");

      long exactCount = cls.count(session, false);
      long approxCount = cls.approximateCount(session, false);

      assertEquals(exactCount, approxCount);
    });
  }

  /**
   * Verify that {@code session.approximateCountClass()} returns correct values directly.
   */
  @Test
  public void testApproximateCountAtSessionLevel() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("SessionLevelClass");

    session.executeInTx(tx -> {
      for (int i = 0; i < 12; i++) {
        session.newEntity("SessionLevelClass");
      }
    });

    // Non-polymorphic
    assertEquals(12, session.approximateCountClass("SessionLevelClass", false));
    // Polymorphic (no subclasses, so same result)
    assertEquals(12, session.approximateCountClass("SessionLevelClass", true));
    // Default (polymorphic)
    assertEquals(12, session.approximateCountClass("SessionLevelClass"));
  }

  /**
   * Verify that approximateCount works correctly through SchemaClassProxy (mutable schema path),
   * which delegates to SchemaClassImpl. This exercises the SchemaClassProxy.approximateCount()
   * and SchemaClassImpl.approximateCount() code paths.
   */
  @Test
  public void testApproximateCountThroughSchemaClassProxy() {
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("ProxyTestClass");

    session.executeInTx(tx -> {
      for (int i = 0; i < 7; i++) {
        session.newEntity("ProxyTestClass");
      }
    });

    // Access via mutable schema (returns SchemaClassProxy wrapping SchemaClassImpl)
    var cls = (SchemaClassInternal) session.getMetadata().getSchema()
        .getClass("ProxyTestClass");

    // Test the single-arg overload (default polymorphic)
    assertEquals(7, cls.approximateCount(session));
    // Test the two-arg overload (non-polymorphic)
    assertEquals(7, cls.approximateCount(session, false));
    // Test the two-arg overload (polymorphic)
    assertEquals(7, cls.approximateCount(session, true));
  }

  /**
   * Calling approximateCountClass with a non-existent class name should throw
   * IllegalArgumentException with a message containing the class name.
   * Tests both the two-arg and single-arg overloads.
   */
  @Test
  public void testApproximateCountClassNotFound() {
    var ex = assertThrows(
        IllegalArgumentException.class,
        () -> session.approximateCountClass("NonExistentClass", false));
    assertTrue(ex.getMessage().contains("NonExistentClass"));

    // Also verify the single-arg (default polymorphic) overload
    var ex2 = assertThrows(
        IllegalArgumentException.class,
        () -> session.approximateCountClass("NonExistentClass"));
    assertTrue(ex2.getMessage().contains("NonExistentClass"));
  }
}
