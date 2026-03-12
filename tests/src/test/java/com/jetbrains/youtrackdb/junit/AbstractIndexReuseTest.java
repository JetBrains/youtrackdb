package com.jetbrains.youtrackdb.junit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractIndexReuseTest extends BaseDBJUnit5Test {
  protected ProfilerStub profiler;

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

    profiler = getProfilerInstance();
  }

  @AfterAll
  @Override
  void afterAll() throws Exception {
    super.afterAll();
  }

  @BeforeEach
  @Override
  void beforeEach() throws Exception {
    super.beforeEach();
  }

  private static ProfilerStub getProfilerInstance() throws Exception {
    return ProfilerStub.INSTANCE;
  }
}
