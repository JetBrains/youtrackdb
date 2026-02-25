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

package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.IndexKeyUpdater;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;

/**
 * Engine interface for index implementations that support key-value storage operations.
 *
 * @since 6/29/13
 */
public interface IndexEngine extends BaseIndexEngine {

  int VERSION = 0;

  Object get(DatabaseSessionEmbedded db, Object key);

  void put(DatabaseSessionEmbedded db, AtomicOperation atomicOperation, Object key,
      Object value) throws IOException;

  void update(DatabaseSessionEmbedded db, AtomicOperation atomicOperation, Object key,
      IndexKeyUpdater<Object> updater)
      throws IOException;

  boolean remove(Storage storage, AtomicOperation atomicOperation, Object key) throws IOException;

  /**
   * Puts the given value under the given key into this index engine. Validates the operation using
   * the provided validator.
   *
   * @param atomicOperation the atomic operation context for this put
   * @param key             the key to put the value under.
   * @param value           the value to put.
   * @param validator       the operation validator.
   * @return {@code true} if the validator allowed the put, {@code false} otherwise.
   * @see IndexEngineValidator#validate(Object, Object, Object)
   */
  boolean validatedPut(
      AtomicOperation atomicOperation,
      Object key,
      RID value,
      IndexEngineValidator<Object, RID> validator)
      throws IOException;

  @Override
  default int getEngineAPIVersion() {
    return VERSION;
  }
}
