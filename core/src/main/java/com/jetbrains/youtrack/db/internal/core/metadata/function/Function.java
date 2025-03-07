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
package com.jetbrains.youtrack.db.internal.core.metadata.function;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.ScriptExecutor;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.RetryQueryException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.type.EntityWrapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Stored function. It contains language and code to execute as a function. The execute() takes
 * parameters. The function is state-less, so can be used by different threads.
 */
public class Function extends EntityWrapper {

  public static final String CLASS_NAME = "OFunction";
  private CallableFunction<Object, Map<Object, Object>> callback;

  /**
   * Creates a new function.
   */
  public Function(DatabaseSessionInternal session) {
    super(CLASS_NAME);
    setLanguage(session, "SQL");
  }

  /**
   * Creates a new function wrapping the saved document.
   *
   * @param entity Document to assign
   */
  public Function(final EntityImpl entity) {
    super(entity);
  }

  /**
   * Loads a function.
   *
   * @param iRid RID of the function to load
   */
  public Function(final RecordId iRid) {
    super(iRid.getRecord());
  }

  public String getName(DatabaseSession session) {
    return getDocument(session).field("name");
  }

  public Function setName(DatabaseSession session, final String iName) {
    getDocument(session).field("name", iName);
    return this;
  }

  public String getCode(DatabaseSession session) {
    return getDocument(session).field("code");
  }

  public void setCode(DatabaseSession session, final String iCode) {
    getDocument(session).field("code", iCode);
  }

  public String getLanguage(DatabaseSession session) {
    return getDocument(session).field("language");
  }

  public void setLanguage(DatabaseSession session, final String iLanguage) {
    getDocument(session).field("language", iLanguage);
  }

  public List<String> getParameters(DatabaseSession session) {
    return getDocument(session).field("parameters");
  }

  public Function setParameters(DatabaseSession session, final List<String> iParameters) {
    getDocument(session).field("parameters", iParameters);
    return this;
  }

  public boolean isIdempotent(DatabaseSession session) {
    final Boolean idempotent = getDocument(session).field("idempotent");
    return idempotent != null && idempotent;
  }

  public Function setIdempotent(DatabaseSession session, final boolean iIdempotent) {
    getDocument(session).field("idempotent", iIdempotent);
    return this;
  }

  public CallableFunction<Object, Map<Object, Object>> getCallback() {
    return callback;
  }

  public Function setCallback(final CallableFunction<Object, Map<Object, Object>> callback) {
    this.callback = callback;
    return this;
  }

  @Deprecated
  public Object execute(final Object... iArgs) {
    return executeInContext(null, iArgs);
  }

  @Deprecated
  public Object executeInContext(CommandContext iContext, final Object... iArgs) {
    if (iContext == null) {
      iContext = new BasicCommandContext();
    }
    var database = iContext.getDatabase();
    final List<String> params = getParameters(database);

    // CONVERT PARAMETERS IN A MAP
    Map<Object, Object> args = null;

    if (iArgs.length > 0) {
      args = new LinkedHashMap<Object, Object>();
      for (int i = 0; i < iArgs.length; ++i) {
        // final Object argValue =
        // RecordSerializerStringAbstract.getTypeValue(iArgs[i].toString());
        final Object argValue = iArgs[i];

        if (params != null && i < params.size()) {
          args.put(params.get(i), argValue);
        } else {
          args.put("param" + i, argValue);
        }
      }
    }

    if (callback != null) {
      // EXECUTE CALLBACK
      return callback.call(args);
    }

    ScriptExecutor executor =
        database
            .getSharedContext()
            .getYouTrackDB()
            .getScriptManager()
            .getCommandManager()
            .getScriptExecutor(getLanguage(database));

    return executor.executeFunction(iContext, getName(database), args);
  }

  public Object executeInContext(@Nonnull CommandContext iContext,
      @Nonnull final Map<String, Object> iArgs) {
    DatabaseSessionInternal database = iContext.getDatabase();
    // CONVERT PARAMETERS IN A MAP
    final Map<Object, Object> args = new LinkedHashMap<Object, Object>();

    if (!iArgs.isEmpty()) {
      // PRESERVE THE ORDER FOR PARAMETERS (ARE USED AS POSITIONAL)
      final List<String> params = getParameters(database);
      for (String p : params) {
        args.put(p, iArgs.get(p));
      }
    }

    if (callback != null) {
      // EXECUTE CALLBACK
      return callback.call(args);
    }

    ScriptExecutor executor =
        database
            .getSharedContext()
            .getYouTrackDB()
            .getScriptManager()
            .getCommandManager()
            .getScriptExecutor(getLanguage(database));

    return executor.executeFunction(iContext, getName(database), args);
  }

  @Deprecated
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    Object result;

    while (true) {
      try {
        if (callback != null) {
          return callback.call(iArgs);
        }

        ScriptExecutor executor =
            session
                .getSharedContext()
                .getYouTrackDB()
                .getScriptManager()
                .getCommandManager()
                .getScriptExecutor(getLanguage(session));

        result = session.computeInTx(() -> executor.execute(session, getCode(session), iArgs));

        break;

      } catch (NeedRetryException | RetryQueryException ignore) {
      }
    }

    return result;
  }

  public RID getId(DatabaseSession session) {
    return getDocument(session).getIdentity();
  }

  @Override
  public String toString() {
    var database = DatabaseRecordThreadLocal.instance().getIfDefined();
    if (database != null) {
      return getName(database);
    }

    return super.toString();
  }
}
