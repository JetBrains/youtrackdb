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

package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.util.ArrayDeque;
import java.util.Deque;
/**
 * Accumulates record serialization operations and executes them within an atomic operation.
 *
 * @since 11/26/13
 */
public class RecordSerializationContext {
  private final Deque<RecordSerializationOperation> operations = new ArrayDeque<>();

  public void push(RecordSerializationOperation operation) {
    operations.push(operation);
  }

  public void executeOperations(
      AtomicOperation atomicOperation, AbstractStorage storage) {
    for (var operation : operations) {
      operation.execute(atomicOperation, storage);
    }

    operations.clear();
  }

  public void clear() {
    operations.clear();
  }
}
