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
package com.jetbrains.youtrack.db.internal.core.command.script;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.common.concur.resource.ResourcePoolFactory;
import com.jetbrains.youtrack.db.internal.common.concur.resource.ResourcePoolListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

/**
 * Manages Script engines per database. Parsing of function library is done only the first time and
 * when changes.
 *
 * @see CommandScript
 */
public class DatabaseScriptManager {

  private final ScriptManager scriptManager;
  protected ResourcePoolFactory<String, ScriptEngine> pooledEngines;

  public DatabaseScriptManager(final ScriptManager iScriptManager, final String iDatabaseName) {
    scriptManager = iScriptManager;

    pooledEngines =
        new ResourcePoolFactory<>(
            language -> new ResourcePoolListener<>() {
              @Override
              public ScriptEngine createNewResource(String key, Object... args) {
                final var scriptEngine = scriptManager.getEngine(iDatabaseName, language);
                final var library =
                    scriptManager.getLibrary((DatabaseSessionInternal) args[0], language);

                if (library != null) {
                  try {
                    scriptEngine.eval(library);
                  } catch (ScriptException e) {
                    scriptManager.throwErrorMessage(iDatabaseName, e, library);
                  }
                }

                return scriptEngine;
              }

              @Override
              public boolean reuseResource(
                  String iKey, Object[] iAdditionalArgs, ScriptEngine iValue) {
                if (language.equals("sql")) {
                  return language.equals(iValue.getFactory().getLanguageName());
                } else {
                  return !(iValue.getFactory().getLanguageName()).equals("sql");
                }
              }
            });
    pooledEngines.setMaxPoolSize(GlobalConfiguration.SCRIPT_POOL.getValueAsInteger());
    pooledEngines.setMaxPartitions(1);
  }

  public ScriptEngine acquireEngine(DatabaseSessionInternal db, final String language) {
    return pooledEngines.get(language).getResource(language, 0, db);
  }

  public void releaseEngine(final String language, ScriptEngine entry) {
    pooledEngines.get(language).returnResource(entry);
  }

  public void close() {
    pooledEngines.close();
  }
}
