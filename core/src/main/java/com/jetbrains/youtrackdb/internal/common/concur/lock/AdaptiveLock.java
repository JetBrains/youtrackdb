/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.common.concur.lock;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptive class to handle shared resources. It's configurable specifying if it's running in a
 * concurrent environment and allow o specify a maximum timeout to avoid deadlocks.
 */
public class AdaptiveLock extends AbstractLock {

  private static final Logger logger = LoggerFactory.getLogger(AdaptiveLock.class);

  private final ReentrantLock lock = new ReentrantLock();
  private final boolean concurrent;
  private final int timeout;
  private final boolean ignoreThreadInterruption;

  public AdaptiveLock() {
    this.concurrent = true;
    this.timeout = 0;
    this.ignoreThreadInterruption = false;
  }

  public AdaptiveLock(final int iTimeout) {
    this.concurrent = true;
    this.timeout = iTimeout;
    this.ignoreThreadInterruption = false;
  }

  public AdaptiveLock(final boolean iConcurrent) {
    this.concurrent = iConcurrent;
    this.timeout = 0;
    this.ignoreThreadInterruption = false;
  }

  public AdaptiveLock(
      final boolean iConcurrent, final int iTimeout, boolean ignoreThreadInterruption) {
    this.concurrent = iConcurrent;
    this.timeout = iTimeout;
    this.ignoreThreadInterruption = ignoreThreadInterruption;
  }

  @Override
  public void lock() {
    if (concurrent) {
      if (timeout > 0) {
        try {
          if (lock.tryLock(timeout, TimeUnit.MILLISECONDS))
          // OK
          {
            return;
          }
        } catch (java.lang.InterruptedException e) {
          if (ignoreThreadInterruption) {
            // IGNORE THE THREAD IS INTERRUPTED: TRY TO RE-LOCK AGAIN
            try {
              if (lock.tryLock(timeout, TimeUnit.MILLISECONDS)) {
                // OK, RESET THE INTERRUPTED STATE
                Thread.currentThread().interrupt();
                return;
              }
            } catch (java.lang.InterruptedException ignore) {
              Thread.currentThread().interrupt();
            }
          }

          throw BaseException.wrapException(
              new LockException(
                  "Thread interrupted while waiting for resource of class '"
                      + getClass()
                      + "' with timeout="
                      + timeout),
              e, (String) null);
        }

        throwTimeoutException(lock);
      } else {
        lock.lock();
      }
    }
  }

  public boolean tryAcquireLock() {
    return tryAcquireLock(timeout, TimeUnit.MILLISECONDS);
  }

  public boolean tryAcquireLock(final long iTimeout, final TimeUnit iUnit) {
    if (concurrent) {
      if (timeout > 0) {
        try {
          return lock.tryLock(iTimeout, iUnit);
        } catch (java.lang.InterruptedException e) {
          throw BaseException.wrapException(
              new LockException(
                  "Thread interrupted while waiting for resource of class '"
                      + getClass()
                      + "' with timeout="
                      + timeout),
              e, (String) null);
        }
      } else {
        return lock.tryLock();
      }
    }

    return true;
  }

  @Override
  public void unlock() {
    if (concurrent) {
      lock.unlock();
    }
  }

  @Override
  public void close() {
    try {
      if (lock.isLocked()) {
        lock.unlock();
      }
    } catch (Exception e) {
      LogManager.instance().debug(this, "Cannot unlock a lock", logger, e);
    }
  }

  private void throwTimeoutException(Lock lock) {
    final var owner = extractLockOwnerStackTrace(lock);

    throw new TimeoutException(
        "Timeout on acquiring exclusive lock against resource of class: "
            + getClass()
            + " with timeout="
            + timeout
            + (owner != null ? "\n" + owner : ""));
  }

  @Nullable
  private String extractLockOwnerStackTrace(Lock lock) {
    try {
      var syncField = lock.getClass().getDeclaredField("sync");
      syncField.setAccessible(true);

      var sync = syncField.get(lock);
      var getOwner = sync.getClass().getSuperclass().getDeclaredMethod("getOwner");
      getOwner.setAccessible(true);

      final var owner = (Thread) getOwner.invoke(sync);
      if (owner == null) {
        return null;
      }

      var stringWriter = new StringWriter();
      var printWriter = new PrintWriter(stringWriter);

      printWriter.append("Owner thread : ").append(owner.toString()).append("\n");

      var stackTrace = owner.getStackTrace();
      for (var traceElement : stackTrace) {
        printWriter.println("\tat " + traceElement);
      }

      printWriter.flush();
      return stringWriter.toString();
    } catch (RuntimeException
             | NoSuchFieldException
             | IllegalAccessException
             | InvocationTargetException
             | NoSuchMethodException ignore) {
      return null;
    }
  }

  public boolean isConcurrent() {
    return concurrent;
  }

  public ReentrantLock getUnderlying() {
    return lock;
  }

  public boolean isHeldByCurrentThread() {
    return lock.isHeldByCurrentThread();
  }
}
