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
package com.jetbrains.youtrackdb.internal.core.command.script;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;

/**
 * YouTrackDB wrapper class to use from scripts.
 */
@Deprecated
public class ScriptYouTrackDbWrapper {

  protected final DatabaseSessionEmbedded db;

  public ScriptYouTrackDbWrapper() {
    this.db = null;
  }

  public ScriptYouTrackDbWrapper(final DatabaseSessionEmbedded db) {
    this.db = db;
  }

  public ScriptDocumentDatabaseWrapper getDatabase() {
    if (db == null) {
      throw new ConfigurationException("No database instance found in context");
    }

    if (db instanceof DatabaseSessionEmbedded) {
      return new ScriptDocumentDatabaseWrapper((DatabaseSessionEmbedded) db);
    }

    throw new ConfigurationException(db.getDatabaseName(),
        "No valid database instance found in context: " + db + ", class: " + db.getClass());
  }
}
