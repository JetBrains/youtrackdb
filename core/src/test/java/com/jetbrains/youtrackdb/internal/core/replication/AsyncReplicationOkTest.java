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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Coverage for the {@link AsyncReplicationOk} {@code @FunctionalInterface}. It is held as a
 * field on {@code CommandRequestAbstract} ({@code protected AsyncReplicationOk
 * onAsyncReplicationOk}) and on {@code ExecutionThreadLocal.ExecutionThreadData} ({@code public
 * volatile AsyncReplicationOk onAsyncReplicationOk}); the value is supplied by callers as a
 * lambda that runs after a successful asynchronous replication operation. Cluster classification
 * marks the interface 22a-keep with {@code mainNew=0} (never instantiated by name); coverage
 * therefore comes from exercising it as a lambda target type and pinning that
 * {@code onAsyncReplicationOk()} dispatches to the body — finding A7 (covered indirectly via
 * lambda call sites).
 *
 * <p>Pure unit-level — no DB session required.
 */
public class AsyncReplicationOkTest {

  /**
   * A lambda assigned to a {@code AsyncReplicationOk}-typed reference dispatches its body when
   * {@code onAsyncReplicationOk()} is invoked. The {@code AtomicInteger} witness pins exactly one
   * dispatch per call so a future regression that no-ops the lambda contract — or that swaps the
   * single abstract method for a default / static — fails loudly.
   */
  @Test
  public void lambdaTargetDispatchesBodyExactlyOncePerCall() {
    var calls = new AtomicInteger();
    AsyncReplicationOk callback = () -> calls.incrementAndGet();

    callback.onAsyncReplicationOk();
    assertEquals(1, calls.get());

    callback.onAsyncReplicationOk();
    assertEquals(2, calls.get());
  }

  /**
   * The interface has a single abstract method shape suitable for lambdas. Reflection-based
   * pin: a non-null SAM exists and is named {@code onAsyncReplicationOk}; if that contract
   * changes (rename, additional non-default abstract method) the {@code @FunctionalInterface}
   * compile-check would already break, but we additionally pin the SAM name here so a refactor
   * inside the same single-method shape that nonetheless renames the method also breaks loudly.
   */
  @Test
  public void singleAbstractMethodIsNamedOnAsyncReplicationOk() throws NoSuchMethodException {
    var sam = AsyncReplicationOk.class.getMethod("onAsyncReplicationOk");
    assertNotNull(sam);
    assertEquals("onAsyncReplicationOk", sam.getName());
    assertEquals(0, sam.getParameterCount());
    assertEquals(void.class, sam.getReturnType());
  }
}
