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

import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaIndex;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import org.jspecify.annotations.NonNull;

/**
 * Index implementation that allows multiple values for the same key.
 */
public class IndexNotUnique extends IndexMultiValues {

  public IndexNotUnique(@NonNull SchemaIndex schemaIndex, @NonNull AbstractStorage storage) {
    super(schemaIndex, storage);
  }

  @Override
  public boolean canBeUsedInEqualityOperators() {
    return true;
  }

  @Override
  public Iterable<TransactionIndexEntry> interpretTxKeyChanges(
      FrontendTransactionIndexChangesPerKey changes) {
    return changes.interpret(FrontendTransactionIndexChangesPerKey.Interpretation.NonUnique);
  }

  @Override
  public boolean isUnique() {
    return false;
  }

  @Override
  public boolean supportsOrderedIterations() {
    return true;
  }
}
