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
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Per-session, transaction-scoped schema state for a schema-changing transaction. While a
 * transaction holds one of these, a schema mutation lands in {@link #txLocalSchema} (a private copy
 * of the committed {@link SchemaShared}) instead of the shared committed instance, so the change is
 * visible only to the owning transaction and rolls back for free.
 *
 * <p>The tx-local copy is a full working {@link SchemaShared} re-parsed from the committed schema
 * (see {@link SchemaShared#copyForTx}), not a field-level clone of the committed class objects.
 * Re-parsing is required because each class binds back to its {@link SchemaShared} through a final
 * owner field and links to its relatives by direct object reference; a clone would leave those
 * references pointing at the shared instances. The copy reuses the existing mutation machinery, so a
 * schema write recomputes the cross-class derived state (inheritance, polymorphic collection ids,
 * subclass sets, the global-property table) inside the copy with no new code.
 *
 * <p>{@link #changedClasses} records the names of classes the transaction touched, so the commit can
 * write only the changed per-class records (the per-class-record format) rather than the whole
 * schema. The names are stable across a class rename within the transaction only insofar as the same
 * mutation that renames also records the new name here.
 *
 * <p>The tx-local index-definition overlay that this state will also carry is introduced with the
 * index work and is not part of this class yet; this version holds the schema copy and the
 * changed-class set. Routing reads and writes to the copy, seeding it on the first schema write, and
 * promoting it at commit are the responsibility of the proxy-routing and commit-reconciliation work,
 * not of this holder.
 */
public final class TxSchemaState {

  private final SchemaShared txLocalSchema;
  private final Set<String> changedClasses = new HashSet<>();

  /**
   * @param txLocalSchema the tx-local {@link SchemaShared} copy, seeded by
   *     {@link SchemaShared#copyForTx}. Must be a fresh copy private to the owning transaction, never
   *     the committed shared instance.
   */
  public TxSchemaState(@Nonnull SchemaShared txLocalSchema) {
    this.txLocalSchema = txLocalSchema;
  }

  /**
   * The tx-local {@link SchemaShared} copy that schema reads and writes route to during the
   * transaction.
   */
  @Nonnull
  public SchemaShared getTxLocalSchema() {
    return txLocalSchema;
  }

  /**
   * Records that the named class was created, altered, or dropped in this transaction, so the commit
   * writes its per-class record. Idempotent: recording the same class twice is a no-op.
   */
  public void markClassChanged(@Nonnull String className) {
    changedClasses.add(className);
  }

  /**
   * The names of classes the transaction has changed so far. The returned set is the live backing
   * set; callers must not mutate it outside {@link #markClassChanged}.
   */
  @Nonnull
  public Set<String> getChangedClasses() {
    return changedClasses;
  }
}
