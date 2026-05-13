package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.Test;

/**
 * Self-test for {@link TestUtilsFixture#rollbackIfLeftOpen()} — the {@code @After} safety net
 * that every executor-step test inherits. The method is exercised transitively by every test
 * class that extends {@link TestUtilsFixture}, but neither branch is asserted directly in
 * those suites. A regression that silently changed the rollback to a commit (or dropped the
 * {@code session.isTxActive()} guard and called rollback on an inactive tx) would be invisible
 * in ordinary test runs because the @After runs last and its effects are not asserted.
 *
 * <p>Pinning both branches here catches meta-bugs in the fixture itself:
 *
 * <ul>
 *   <li>{@code activeTx → rollback} — the load-bearing behavior.
 *   <li>{@code inactiveTx → no-op} — ensures the guard does not spuriously invoke rollback on
 *       a closed or already-committed session.
 * </ul>
 */
public class TestUtilsFixtureSelfTest extends TestUtilsFixture {

  /**
   * When the fixture runs with an active transaction, {@code rollbackIfLeftOpen} must invoke
   * {@code session.rollback()} so the tx state becomes inactive. Pins the load-bearing branch.
   */
  @Test
  public void rollbackIfLeftOpenWithActiveTxRollsBack() {
    // Create the class outside a tx — schema changes are not transactional and throw
    // SchemaException when a tx is active.
    var c = createClassInstance();

    // Open the tx we want the guard to clean up. The guard runs both in @After AND via
    // direct invocation below; we invoke it directly to observe the state transition.
    session.begin();
    session.newEntity(c.getName()).setProperty("v", 1);
    assertThat(session.isTxActive())
        .as("precondition: session must have an active tx before the guard runs")
        .isTrue();

    rollbackIfLeftOpen();

    assertThat(session.isTxActive())
        .as("rollbackIfLeftOpen must close an active tx via session.rollback()")
        .isFalse();
  }

  /**
   * When the fixture runs with no active transaction, {@code rollbackIfLeftOpen} must be a
   * no-op — in particular it must not throw and must not produce a spurious rollback-empty log
   * message. Pins the guard that avoids calling {@code session.rollback()} on an inactive tx.
   */
  @Test
  public void rollbackIfLeftOpenWithoutActiveTxIsNoOp() {
    assertThat(session.isTxActive())
        .as("precondition: session must have no active tx")
        .isFalse();

    assertThatCode(this::rollbackIfLeftOpen)
        .as("rollbackIfLeftOpen on an inactive tx must not throw")
        .doesNotThrowAnyException();

    assertThat(session.isTxActive())
        .as("session must remain inactive after a no-op guard")
        .isFalse();
  }
}
