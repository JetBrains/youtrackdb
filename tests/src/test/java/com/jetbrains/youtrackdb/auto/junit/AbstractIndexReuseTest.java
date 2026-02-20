/*
 * JUnit 4 version of AbstractIndexReuseTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/AbstractIndexReuseTest.java
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.auto.ProfilerStub;
import org.junit.Before;

/**
 * Abstract base class for index reuse tests that verify query optimizer behavior.
 *
 * <p><b>Suite Dependency:</b> Subclasses of this test are part of {@link DatabaseTestSuite}.
 * This base class provides profiler integration for measuring index usage during query
 * execution.</p>
 *
 * <p><b>Implementing Subclasses:</b> Must add a {@code @BeforeClass} method that calls
 * {@code beforeClass()} and any required data generation methods.</p>
 *
 * <p>Original: {@code tests/src/test/java/com/jetbrains/youtrackdb/auto/AbstractIndexReuseTest.java}</p>
 *
 * @see com.jetbrains.youtrackdb.auto.junit.databasesuite.SQLSelectIndexReuseTest
 * @see com.jetbrains.youtrackdb.auto.junit.databasesuite.SQLSelectByLinkedSchemaPropertyIndexReuseTest
 * @see com.jetbrains.youtrackdb.auto.junit.databasesuite.OrderByIndexReuseTest
 */
public abstract class AbstractIndexReuseTest extends BaseDBTest {

  protected ProfilerStub profiler;

  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();
    profiler = getProfilerInstance();
  }

  public void afterClass() throws Exception {
    super.afterClass();
  }

  @Override
  @Before
  public void beforeMethod() throws Exception {
    super.beforeMethod();
  }

  private static ProfilerStub getProfilerInstance() throws Exception {
    return ProfilerStub.INSTANCE;
  }
}
