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

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.RetryQueryException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.type.IdentityWrapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Stored function. It contains language and code to execute as a function. The execute() takes
 * parameters. The function is state-less, so can be used by different threads.
 */
public class Function extends IdentityWrapper {

  public static final String CLASS_NAME = "OFunction";
  public static final String NAME_PROPERTY = "name";
  public static final String CODE_PROPERTY = "code";
  public static final String LANGUAGE_PROPERTY = "language";
  public static final String PARAMETERS_PROPERTY = "parameters";
  public static final String IDEMPOTENT_PROPERTY = "idempotent";
  private CallableFunction<Object, Map<Object, Object>> callback;

  private volatile String name;
  private volatile String code;
  private volatile String language;
  private volatile List<String> parameters;
  private volatile boolean idempotent;

  /**
   * Creates a new function.
   */
  public Function(DatabaseSessionEmbedded session) {
    super(session, CLASS_NAME);
    this.language = "SQL";
  }

  /**
   * Creates a new function wrapping the saved document.
   *
   * @param entity Document to assign
   */
  public Function(final EntityImpl entity) {
    super(entity);
    fromEntity(entity);
  }

  /**
   * Loads a function.
   *
   * @param iRid RID of the function to load
   */
  public Function(DatabaseSessionEmbedded session, final RecordId iRid) {
    super((EntityImpl) session.getActiveTransaction().loadEntity(iRid));
    var transaction = session.getActiveTransaction();
    var entity = transaction.loadEntity(iRid);
    fromEntity(entity);
  }

  private void fromEntity(Entity entity) {
    name = entity.getProperty(NAME_PROPERTY);
    code = entity.getProperty(CODE_PROPERTY);
    language = entity.getProperty(LANGUAGE_PROPERTY);
    parameters = entity.getEmbeddedList(PARAMETERS_PROPERTY);

    var storedIdempotent = entity.<Boolean>getProperty(IDEMPOTENT_PROPERTY);
    idempotent = storedIdempotent != null ? storedIdempotent : false;
  }

  @Override
  protected void toEntity(@Nonnull DatabaseSessionInternal session, @Nonnull EntityImpl entity) {
    entity.setProperty(NAME_PROPERTY, name);
    entity.setProperty(CODE_PROPERTY, code);
    entity.setProperty(LANGUAGE_PROPERTY, language);

    var params = entity.<String>newEmbeddedList(PARAMETERS_PROPERTY);
    if (parameters != null) {
      params.addAll(parameters);
    }

    entity.setProperty(IDEMPOTENT_PROPERTY, idempotent);
  }

  public String getName() {
    return name;
  }

  public Function setName(final String name) {
    this.name = name;
    return this;
  }

  public String getCode() {
    return code;
  }

  public void setCode(final String code) {
    this.code = code;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(final String language) {
    this.language = language;
  }

  public List<String> getParameters() {
    return parameters;
  }

  public Function setParameters(final List<String> parameters) {
    this.parameters = parameters;
    return this;
  }

  public boolean isIdempotent() {
    return idempotent;
  }

  public Function setIdempotent(final boolean idempotent) {
    this.idempotent = idempotent;
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
    var database = iContext.getDatabaseSession();
    final var params = parameters;

    // CONVERT PARAMETERS IN A MAP
    Map<Object, Object> args = null;

    if (iArgs.length > 0) {
      args = new LinkedHashMap<>();
      for (var i = 0; i < iArgs.length; ++i) {
        // final Object argValue =
        // RecordSerializerStringAbstract.getTypeValue(iArgs[i].toString());
        final var argValue = iArgs[i];

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

    var executor =
        database
            .getSharedContext()
            .getYouTrackDB()
            .getScriptManager()
            .getCommandManager()
            .getScriptExecutor(language);

    return executor.executeFunction(iContext, name, args);
  }

  public Object executeInContext(@Nonnull CommandContext iContext,
      @Nonnull final Map<String, Object> iArgs) {
    var database = iContext.getDatabaseSession();
    // CONVERT PARAMETERS IN A MAP
    final Map<Object, Object> args = new LinkedHashMap<>();

    if (!iArgs.isEmpty()) {
      // PRESERVE THE ORDER FOR PARAMETERS (ARE USED AS POSITIONAL)
      final var params = parameters;
      for (var p : params) {
        args.put(p, iArgs.get(p));
      }
    }

    if (callback != null) {
      // EXECUTE CALLBACK
      return callback.call(args);
    }

    var executor =
        database
            .getSharedContext()
            .getYouTrackDB()
            .getScriptManager()
            .getCommandManager()
            .getScriptExecutor(language);

    return executor.executeFunction(iContext, name, args);
  }

  @Deprecated
  public Object execute(DatabaseSessionEmbedded session, final Map<Object, Object> iArgs) {
    Object result;

    while (true) {
      try {
        if (callback != null) {
          return callback.call(iArgs);
        }

        var executor =
            session
                .getSharedContext()
                .getYouTrackDB()
                .getScriptManager()
                .getCommandManager()
                .getScriptExecutor(language);

        result = session.computeInTx(transaction -> executor.execute(session, code, iArgs));

        break;

      } catch (NeedRetryException | RetryQueryException ignore) {
      }
    }

    return result;
  }
}
