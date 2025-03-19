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
package com.jetbrains.youtrack.db.api.session;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;

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

  default void onAfterTxCommit(final Transaction transaction) {
  }

  default void onClose(final DatabaseSession iDatabase) {
  }

  default void onCreateClass(DatabaseSession iDatabase, SchemaClass iClass) {
  }

  default void onDropClass(DatabaseSession iDatabase, SchemaClass iClass) {
  }

  default void onCommandStart(DatabaseSession database, ResultSet resultSet) {
  }

  default void onCommandEnd(DatabaseSession database, ResultSet resultSet) {
  }
}
