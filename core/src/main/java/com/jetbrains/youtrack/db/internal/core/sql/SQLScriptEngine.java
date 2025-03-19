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

package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.command.script.CommandScript;
import com.jetbrains.youtrack.db.internal.core.command.script.ScriptDatabaseWrapper;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.sql.query.BasicLegacyResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.query.LegacyResultSet;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

/**
 * Dynamic script engine for YouTrackDB SQL commands. This implementation is multi-threads.
 */
public class SQLScriptEngine implements ScriptEngine {

  public static final String NAME = "sql";
  private final ScriptEngineFactory factory;

  public SQLScriptEngine(ScriptEngineFactory factory) {
    this.factory = factory;
  }

  @Override
  public Object eval(String script, ScriptContext context) throws ScriptException {
    return eval(script, (Bindings) null);
  }

  @Override
  public Object eval(Reader reader, ScriptContext context) throws ScriptException {
    return eval(reader, (Bindings) null);
  }

  @Override
  public Object eval(String script) throws ScriptException {
    return eval(script, (Bindings) null);
  }

  @Override
  public Object eval(Reader reader) throws ScriptException {
    return eval(reader, (Bindings) null);
  }

  @Override
  public Object eval(String script, Bindings n) throws ScriptException {
    ScriptDatabaseWrapper session = null;
    if (n != null) {
      session = (ScriptDatabaseWrapper) n.get("db");
    }

    if (session == null) {
      throw new CommandExecutionException("No database available in bindings");
    }
    var params = convertToParameters(n);
    ResultSet queryResult;
    if (params.keySet().stream().anyMatch(x -> !(x instanceof String))) {
      queryResult = session.runScript("sql", script, params);
    } else {
      queryResult = session.runScript("sql", script, (Map) params);
    }
    try (var res = queryResult) {
      LegacyResultSet<Result> finalResult = new BasicLegacyResultSet<>();
      res.stream().forEach(finalResult::add);
      return finalResult;
    }
  }

  @SuppressWarnings("unchecked")
  protected Map<Object, Object> convertToParameters(Object... iArgs) {
    final Map<Object, Object> params;

    if (iArgs.length == 1 && iArgs[0] instanceof Map) {
      params = (Map<Object, Object>) iArgs[0];
    } else {
      if (iArgs.length == 1
          && iArgs[0] != null
          && iArgs[0].getClass().isArray()
          && iArgs[0] instanceof Object[]) {
        iArgs = (Object[]) iArgs[0];
      }

      params = new HashMap<Object, Object>(iArgs.length);
      for (var i = 0; i < iArgs.length; ++i) {
        var par = iArgs[i];

        if (par instanceof Identifiable
            && ((RecordId) ((Identifiable) par).getIdentity()).isValid())
        // USE THE RID ONLY
        {
          par = ((Identifiable) par).getIdentity();
        }

        params.put(i, par);
      }
    }
    return params;
  }

  @Override
  public Object eval(Reader reader, Bindings n) throws ScriptException {
    DatabaseSessionInternal session = null;
    if (n != null) {
      session = (DatabaseSessionInternal) n.get("db");
    }
    if (session == null) {
      throw new CommandExecutionException("No database available in bindings");
    }

    final var buffer = new StringBuilder();
    try {
      while (reader.ready()) {
        buffer.append((char) reader.read());
      }
    } catch (IOException e) {
      throw new ScriptException(e);
    }

    return new CommandScript(buffer.toString()).execute(session, n);
  }

  @Override
  public void put(String key, Object value) {
  }

  @Override
  public Object get(String key) {
    return null;
  }

  @Override
  public Bindings getBindings(int scope) {
    return new SimpleBindings();
  }

  @Override
  public void setBindings(Bindings bindings, int scope) {
  }

  @Override
  public Bindings createBindings() {
    return new SimpleBindings();
  }

  @Override
  public ScriptContext getContext() {
    return null;
  }

  @Override
  public void setContext(ScriptContext context) {
  }

  @Override
  public ScriptEngineFactory getFactory() {
    return factory;
  }
}
