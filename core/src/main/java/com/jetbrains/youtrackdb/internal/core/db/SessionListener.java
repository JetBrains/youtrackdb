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
package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Listener Interface for all the events of the session instances.
 */
public interface SessionListener {

  default void onBeforeTxBegin(final Transaction transaction) {
  }

  default void onBeforeTxRollback(final Transaction transaction) {
  }

  default void onAfterTxRollback(final Transaction transaction) {
  }

  default void onBeforeTxCommit(final Transaction transaction) {
  }

  default void onAfterTxCommit(final Transaction transaction, @Nullable Map<RID, RID> ridMapping) {
  }

  default void onClose(final DatabaseSessionEmbedded iDatabase) {
  }

  default void onCreateClass(DatabaseSessionEmbedded iDatabase, SchemaClass iClass) {
  }

  default void onDropClass(DatabaseSessionEmbedded iDatabase, SchemaClass iClass) {
  }

  default void onCommandStart(DatabaseSessionEmbedded database, ResultSet resultSet) {
  }

  default void onCommandEnd(DatabaseSessionEmbedded database, ResultSet resultSet) {
  }
}
