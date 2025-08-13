package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

public interface SyncSource {

  boolean getIncremental();

  InputStream getInputStream();

  CountDownLatch getFinished();

  void invalidate();

  boolean isValid();
}
