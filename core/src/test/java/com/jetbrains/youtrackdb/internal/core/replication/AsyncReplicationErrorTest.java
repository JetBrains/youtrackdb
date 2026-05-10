/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.replication;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.internal.core.replication.AsyncReplicationError.ACTION;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Coverage for the {@link AsyncReplicationError} {@code @FunctionalInterface} and the nested
 * {@link AsyncReplicationError.ACTION} enum. Like {@link AsyncReplicationOk} the interface is
 * stored as a field on {@code CommandRequestAbstract} / {@code
 * ExecutionThreadLocal.ExecutionThreadData} and supplied by callers as a lambda; cluster
 * classification marks it 22a-keep with {@code mainNew=0} (never instantiated by name) per
 * finding A7.
 *
 * <p>Pure unit-level — no DB session required. The lambda dispatch test exercises both ACTION
 * branches (RETRY and IGNORE) by toggling the result based on the caller's retry counter so the
 * test pins both enum values as legitimate return shapes; the standalone enum test pins
 * {@code values()} / {@code valueOf} on each constant.
 */
public class AsyncReplicationErrorTest {

  /**
   * A lambda assigned to a {@code AsyncReplicationError}-typed reference receives the caught
   * exception and the retry counter, and returns either {@code RETRY} or {@code IGNORE}. Pin
   * both branches in a single call sequence: the first call returns RETRY (retry=0), the second
   * returns IGNORE (retry &ge; threshold). The witness atomics record the exact arguments the
   * lambda saw so a future regression that drops the {@code Throwable} or {@code int} parameter
   * — or silently swaps their order in the SAM signature — fails loudly.
   */
  @Test
  public void lambdaTargetDispatchesBodyAndCanReturnBothActionBranches() {
    var lastException = new AtomicReference<Throwable>();
    var lastRetry = new AtomicInteger(-1);

    AsyncReplicationError handler = (iException, iRetry) -> {
      lastException.set(iException);
      lastRetry.set(iRetry);
      return iRetry < 3 ? ACTION.RETRY : ACTION.IGNORE;
    };

    var cause1 = new RuntimeException("first");
    var firstResult = handler.onAsyncReplicationError(cause1, 0);
    assertSame(cause1, lastException.get());
    assertEquals(0, lastRetry.get());
    assertEquals(ACTION.RETRY, firstResult);

    var cause2 = new IllegalStateException("retry exhausted");
    var secondResult = handler.onAsyncReplicationError(cause2, 5);
    assertSame(cause2, lastException.get());
    assertEquals(5, lastRetry.get());
    assertEquals(ACTION.IGNORE, secondResult);
  }

  /**
   * The interface's single abstract method is named {@code onAsyncReplicationError} and has the
   * expected {@code (Throwable, int) -> ACTION} signature shape. Pinning the reflective shape
   * catches signature drift even within a single-abstract-method refactor.
   */
  @Test
  public void singleAbstractMethodSignaturePinned() throws NoSuchMethodException {
    var sam =
        AsyncReplicationError.class.getMethod(
            "onAsyncReplicationError", Throwable.class, int.class);
    assertEquals("onAsyncReplicationError", sam.getName());
    assertEquals(ACTION.class, sam.getReturnType());
  }

  /**
   * Pin the {@code ACTION} enum's exhaustive value set and {@code values()} ordering. Any
   * addition of a new constant or reorder breaks consumers that switch on the enum, so a
   * regression here surfaces immediately.
   */
  @Test
  public void actionEnumExhaustsKnownConstants() {
    assertArrayEquals(
        new ACTION[] {ACTION.IGNORE, ACTION.RETRY},
        ACTION.values());
    assertSame(ACTION.IGNORE, ACTION.valueOf("IGNORE"));
    assertSame(ACTION.RETRY, ACTION.valueOf("RETRY"));
  }
}
