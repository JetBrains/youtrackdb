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

package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBListenerAbstract;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.annotation.Nullable;

/**
 * @since 11/26/13
 */
public class RecordSerializationContext {

  private static volatile ThreadLocal<Deque<RecordSerializationContext>>
      SERIALIZATION_CONTEXT_STACK = new SerializationContextThreadLocal();

  static {
    YouTrackDBEnginesManager.instance()
        .registerListener(
            new YouTrackDBListenerAbstract() {
              @Override
              public void onStartup() {
                if (SERIALIZATION_CONTEXT_STACK == null) {
                  SERIALIZATION_CONTEXT_STACK = new SerializationContextThreadLocal();
                }
              }

              @Override
              public void onShutdown() {
                SERIALIZATION_CONTEXT_STACK = null;
              }
            });
  }

  private final Deque<RecordSerializationOperation> operations = new ArrayDeque<>();

  public static int getDepth() {
    return RecordSerializationContext.SERIALIZATION_CONTEXT_STACK.get().size();
  }

  public static RecordSerializationContext pushContext() {
    final var stack = SERIALIZATION_CONTEXT_STACK.get();

    final var context = new RecordSerializationContext();
    stack.push(context);
    return context;
  }

  @Nullable
  public static RecordSerializationContext getContext() {
    final var stack = SERIALIZATION_CONTEXT_STACK.get();
    if (stack.isEmpty()) {
      return null;
    }

    return stack.peek();
  }

  public static RecordSerializationContext pullContext() {
    final var stack = SERIALIZATION_CONTEXT_STACK.get();
    if (stack.isEmpty()) {
      throw new IllegalStateException("Cannot find current serialization context");
    }

    return stack.poll();
  }

  public void push(RecordSerializationOperation operation) {
    operations.push(operation);
  }

  public void executeOperations(
      AtomicOperation atomicOperation, AbstractPaginatedStorage storage) {
    for (var operation : operations) {
      operation.execute(atomicOperation, storage);
    }
  }

  private static class SerializationContextThreadLocal
      extends ThreadLocal<Deque<RecordSerializationContext>> {

    @Override
    protected Deque<RecordSerializationContext> initialValue() {
      return new ArrayDeque<>();
    }
  }
}
