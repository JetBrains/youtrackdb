package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

/**
 * Listener for exceptions which are thrown during background flush of files in write cache.
 */
public interface BackgroundExceptionListener {

  void onException(Throwable e);
}
