/*
 * JUnit 4 version of AbstractIndexReuseTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/AbstractIndexReuseTest.java
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.auto.ProfilerStub;
import org.junit.Before;

/**
 * Base class for index reuse tests. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/AbstractIndexReuseTest.java
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
