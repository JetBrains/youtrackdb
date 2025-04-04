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
package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;

/**
 * Dictionary index similar to unique index but does not check for updates, just executes changes.
 * Last put always wins and override the previous value.
 */
public class IndexDictionary extends IndexOneValue {

  public IndexDictionary(IndexMetadata im, final Storage storage) {
    super(im, storage);
  }

  @Override
  public void doPut(DatabaseSessionInternal session, AbstractStorage storage,
      Object key,
      RID rid)
      throws InvalidIndexEngineIdException {
    if (apiVersion == 0) {
      putV0(storage, indexId, key, rid);
    } else if (apiVersion == 1) {
      putV1(storage, indexId, key, rid);
    } else {
      throw new IllegalStateException("Invalid API version, " + apiVersion);
    }
  }

  @Override
  public boolean isNativeTxSupported() {
    return true;
  }

  private static void putV0(
      final AbstractStorage storage, int indexId, Object key, Identifiable value)
      throws InvalidIndexEngineIdException {
    throw new UnsupportedOperationException();
  }

  private static void putV1(
      final AbstractStorage storage, int indexId, Object key, Identifiable value)
      throws InvalidIndexEngineIdException {
    storage.putRidIndexEntry(indexId, key, value.getIdentity());
  }

  public boolean canBeUsedInEqualityOperators() {
    return true;
  }

  public boolean supportsOrderedIterations() {
    return false;
  }

  @Override
  public Iterable<TransactionIndexEntry> interpretTxKeyChanges(
      FrontendTransactionIndexChangesPerKey changes) {
    return changes.interpret(FrontendTransactionIndexChangesPerKey.Interpretation.Dictionary);
  }
}
