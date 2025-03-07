package com.jetbrains.youtrack.db.auto;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;

public abstract class AbstractIndexReuseTest extends BaseDBTest {

  protected ProfilerStub profiler;

  public AbstractIndexReuseTest(boolean remote) {
    super(remote);
  }

  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    profiler = getProfilerInstance();
  }

  @AfterClass
  @Override
  public void afterClass() throws Exception {
    super.afterClass();
  }

  @BeforeMethod
  public void beforeMethod() throws Exception {
    super.beforeMethod();
  }

  private static ProfilerStub getProfilerInstance() throws Exception {
    return ProfilerStub.INSTANCE;
  }
}
