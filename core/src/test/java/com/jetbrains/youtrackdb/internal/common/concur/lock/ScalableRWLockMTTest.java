package com.jetbrains.youtrackdb.internal.common.concur.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vmlens.api.AllInterleavingsBuilder;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Explores nested reader and writer exclusion with a bounded VMLens schedule set. */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
public class ScalableRWLockMTTest {

  /** A writer cannot overlap either counted read hold or change its protected value. */
  @Test
  public void nestedReadersExcludeWriter() throws Exception {
    try (var schedules = new AllInterleavingsBuilder()
        .withMaximumIterations(30).build("scalable-nested-read")) {
      while (schedules.hasNext()) {
        var lock = new ScalableRWLock();
        // Atomic probes check overlap without relying on VMLens to model optimistic read fences.
        var value = new AtomicInteger();
        var occupants = new AtomicInteger();
        var failures = new Throwable[2];
        var reader = new Thread(() -> {
          try {
            lock.sharedLock();
            try {
              assertEquals(1, occupants.incrementAndGet());
              var before = value.get();
              lock.sharedLock();
              try {
                assertEquals(before, value.get());
              } finally {
                lock.sharedUnlock();
              }
              assertTrue(lock.isReadLockedByCurrentThread());
              assertEquals(before, value.get());
              assertEquals(0, occupants.decrementAndGet());
            } finally {
              lock.sharedUnlock();
            }
          } catch (Throwable failure) {
            failures[0] = failure;
          }
        });
        var writer = new Thread(() -> {
          try {
            lock.exclusiveLock();
            try {
              assertEquals(1, occupants.incrementAndGet());
              value.incrementAndGet();
              assertEquals(0, occupants.decrementAndGet());
            } finally {
              lock.exclusiveUnlock();
            }
          } catch (Throwable failure) {
            failures[1] = failure;
          }
        });
        reader.start();
        writer.start();
        reader.join();
        writer.join();
        assertNull(failures[0]);
        assertNull(failures[1]);
        assertEquals(1, value.get());
      }
    }
  }
}
