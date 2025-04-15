package com.jetbrains.youtrack.db.internal.common.profiler.metrics;

import java.util.ArrayList;
import java.util.List;
import org.junit.After;

abstract public class MetricsBaseTest {

  protected List<AutoCloseable> closeables;

  @After
  public void freeResources() {
    if (closeables != null) {
      for (AutoCloseable closeable : closeables) {
        try {
          closeable.close();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
      closeables = null;
    }
  }

  protected <T extends AutoCloseable> T closeable(T closeable) {
    if (closeables == null) {
      closeables = new ArrayList<>();
    }
    closeables.add(closeable);
    return closeable;
  }
}
