package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;

/**
 * Base fixture for executor-step tests. Extends {@link DbTestBase} with helpers for creating
 * uniquely-named schema classes plus a transaction-leak guard that runs before
 * {@link DbTestBase#afterTest()}.
 *
 * <p>Why the leak guard:
 *
 * <ul>
 *   <li>{@link DbTestBase#beforeTest()} creates a fresh database (and session) per {@code @Test}
 *       method — so leaks do not cascade across methods within a class — but a test that throws
 *       mid-transaction still leaves {@code session.isTxActive() == true} when
 *       {@link DbTestBase#afterTest()} runs.
 *   <li>{@link DbTestBase#afterTest()} calls {@code session.close()} on an active transaction,
 *       which logs a warning and may mask the original failure (the close-path exception can
 *       propagate in place of the test's real AssertionError).
 *   <li>The rollback guard runs before {@link DbTestBase#afterTest()} (JUnit 4 runs subclass
 *       {@code @After} methods first), cleans up silently, and preserves the test's original
 *       failure for the test runner.
 * </ul>
 *
 * <p>Track 7 established this {@code @After rollbackIfLeftOpen} idiom for {@code sql/method}
 * tests. Track 8 adopts it as the default for executor tests via this fixture, so per-class
 * boilerplate is no longer required.
 */
public class TestUtilsFixture extends DbTestBase {

  protected SchemaClassInternal createClassInstance() {
    return (SchemaClassInternal) getDBSchema().createClass(generateClassName());
  }

  protected SchemaClass createChildClassInstance(SchemaClass superclass) {
    return getDBSchema().createClass(generateClassName(), superclass);
  }

  private Schema getDBSchema() {
    return session.getMetadata().getSchema();
  }

  private static String generateClassName() {
    return "Class" + RandomStringUtils.randomNumeric(10);
  }

  /**
   * Roll back any transaction left open by a failing test method before
   * {@link DbTestBase#afterTest()} drops the database. JUnit 4 runs subclass {@code @After}
   * methods before superclass ones, so this safety net runs ahead of the database teardown.
   *
   * <p>This is a no-op for tests that close their transactions cleanly. It only catches the
   * "test threw mid-transaction" path — exactly the scenario that otherwise cascade-poisons
   * subsequent tests in the same class.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }
}
