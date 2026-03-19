package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests execution of OPTIMIZE DATABASE SQL statements.
 */
public class OptimizeDatabaseExecutionTest extends DbTestBase {

  /**
   * Verifies that OPTIMIZE DATABASE -LWEDGES throws UnsupportedOperationException
   * because lightweight edge conversion was removed after edge unification.
   */
  @Test
  public void testOptimizeLwedgesThrowsUnsupported() {
    Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> session.execute("optimize database -LWEDGES").close());
  }
}
