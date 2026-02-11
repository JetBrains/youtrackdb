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
package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.index.engine.UniqueIndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Index implementation that allows only one value for a key.
 */
public class IndexUnique extends IndexOneValue {

  private final IndexEngineValidator<Object, RID> uniqueValidator =
      new UniqueIndexEngineValidator(this);

  public IndexUnique(@Nullable RID identity, @Nonnull FrontendTransaction transaction,
      @Nonnull Storage storage) {
    super(identity, transaction, storage);
  }

  public IndexUnique(@Nonnull Storage storage) {
    super(storage);
  }

  @Override
  public void doPut(DatabaseSessionEmbedded session, AbstractStorage storage,
      Object key,
      RID rid)
      throws InvalidIndexEngineIdException {
    storage.validatedPutIndexValue(indexId, key, rid, uniqueValidator);
  }

  @Override
  public boolean canBeUsedInEqualityOperators() {
    return true;
  }

  @Override
  public boolean supportsOrderedIterations() {
    while (true) {
      try {
        return storage.hasIndexRangeQuerySupport(indexId);
      } catch (InvalidIndexEngineIdException ignore) {
        doReloadIndexEngine();
      }
    }
  }

  @Override
  public Iterable<TransactionIndexEntry> interpretTxKeyChanges(
      FrontendTransactionIndexChangesPerKey changes) {
    return changes.interpret(FrontendTransactionIndexChangesPerKey.Interpretation.Unique);
  }
}
