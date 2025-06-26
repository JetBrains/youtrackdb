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
package com.jetbrains.youtrack.db.internal.core.sql.method;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.parser.BaseParser;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
import com.jetbrains.youtrack.db.internal.core.sql.SQLHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemAbstract;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemVariable;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionRuntime;
import javax.annotation.Nullable;

/**
 * Wraps function managing the binding of parameters.
 */
public class SQLMethodRuntime extends SQLFilterItemAbstract
    implements Comparable<SQLMethodRuntime> {

  public SQLMethod method;
  public Object[] configuredParameters;
  public Object[] runtimeParameters;

  public SQLMethodRuntime(DatabaseSessionEmbedded session, final BaseParser iQueryToParse,
      final String iText) {
    super(session, iQueryToParse, iText);
  }

  public SQLMethodRuntime(final SQLMethod iFunction) {
    method = iFunction;
  }

  /**
   * Execute a method.
   *
   * @param iCurrentRecord Current record
   * @param iCurrentResult TODO
   * @param iContext
   * @return
   */
  @Nullable
  public Object execute(
      final Object iThis,
      final Result iCurrentRecord,
      final Object iCurrentResult,
      final CommandContext iContext) {
    if (iThis == null) {
      return null;
    }

    if (configuredParameters != null) {
      // RESOLVE VALUES USING THE CURRENT RECORD
      for (var i = 0; i < configuredParameters.length; ++i) {
        runtimeParameters[i] = configuredParameters[i];

        if (method.evaluateParameters()) {
          if (configuredParameters[i] instanceof SQLFilterItemField) {
            runtimeParameters[i] =
                ((SQLFilterItemField) configuredParameters[i])
                    .getValue(iCurrentRecord, iCurrentResult, iContext);
            if (runtimeParameters[i] == null && iCurrentResult instanceof Identifiable)
            // LOOK INTO THE CURRENT RESULT
            {
              runtimeParameters[i] =
                  ((SQLFilterItemField) configuredParameters[i])
                      .getValue((Result) iCurrentResult, iCurrentResult, iContext);
            }
          } else if (configuredParameters[i] instanceof SQLMethodRuntime) {
            runtimeParameters[i] =
                ((SQLMethodRuntime) configuredParameters[i])
                    .execute(iThis, iCurrentRecord, iCurrentResult, iContext);
          } else if (configuredParameters[i] instanceof SQLFunctionRuntime) {
            runtimeParameters[i] =
                ((SQLFunctionRuntime) configuredParameters[i])
                    .execute(iCurrentRecord, iCurrentRecord, iCurrentResult, iContext);
          } else if (configuredParameters[i] instanceof SQLFilterItemVariable) {
            runtimeParameters[i] =
                ((SQLFilterItemVariable) configuredParameters[i])
                    .getValue(iCurrentRecord, iCurrentResult, iContext);
            if (runtimeParameters[i] == null && iCurrentResult instanceof Identifiable)
            // LOOK INTO THE CURRENT RESULT
            {
              runtimeParameters[i] =
                  ((SQLFilterItemVariable) configuredParameters[i])
                      .getValue((Result) iCurrentResult, iCurrentResult, iContext);
            }
          } else if (configuredParameters[i] instanceof String) {
            if (configuredParameters[i].toString().startsWith("\"")
                || configuredParameters[i].toString().startsWith("'")) {
              runtimeParameters[i] = IOUtils.getStringContent(configuredParameters[i]);
            }
          }
        }
      }

      var session = iContext.getDatabaseSession();
      if (method.getMaxParams(session) == -1 || method.getMaxParams(session) > 0) {
        if (runtimeParameters.length < method.getMinParams()
            || (method.getMaxParams(session) > -1 && runtimeParameters.length > method.getMaxParams(
            session))) {
          String params;
          if (method.getMinParams() == method.getMaxParams(session)) {
            params = "" + method.getMinParams();
          } else {
            params = method.getMinParams() + "-" + method.getMaxParams(session);
          }
          throw new CommandExecutionException(session,
              "Syntax error: function '"
                  + method.getName()
                  + "' needs "
                  + (params)
                  + " argument(s) while has been received "
                  + runtimeParameters.length);
        }
      }
    }

    final var functionResult =
        method.execute(iThis, iCurrentRecord, iContext, iCurrentResult, runtimeParameters);

    return transformValue(iCurrentRecord, iContext, functionResult);
  }

  @Nullable
  @Override
  public Object getValue(
      final Result iRecord, Object iCurrentResult, CommandContext iContext) {
    try {
      return execute(iRecord, iRecord, null, iContext);
    } catch (RecordNotFoundException rnf) {
      return null;
    }
  }

  @Override
  public String getRoot(DatabaseSession session) {
    return method.getName();
  }

  @Override
  protected void setRoot(DatabaseSessionEmbedded session, final BaseParser iQueryToParse,
      final String iText) {
    final var beginParenthesis = iText.indexOf('(');

    // SEARCH FOR THE FUNCTION
    final var funcName = iText.substring(0, beginParenthesis);

    final var funcParamsText = StringSerializerHelper.getParameters(iText);

    method = SQLEngine.getMethod(funcName);
    if (method == null) {
      throw new CommandSQLParsingException(session.getDatabaseName(),
          "Unknown method " + funcName + "()");
    }

    // PARSE PARAMETERS
    this.configuredParameters = new Object[funcParamsText.size()];
    for (var i = 0; i < funcParamsText.size(); ++i) {
      this.configuredParameters[i] = funcParamsText.get(i);
    }

    setParameters(session, configuredParameters, true);
  }

  public SQLMethodRuntime setParameters(DatabaseSessionEmbedded session,
      final Object[] iParameters, final boolean iEvaluate) {
    if (iParameters != null) {
      var context = new BasicCommandContext();
      context.setDatabaseSession(session);

      this.configuredParameters = new Object[iParameters.length];
      for (var i = 0; i < iParameters.length; ++i) {
        this.configuredParameters[i] = iParameters[i];

        if (iParameters[i] != null) {
          if (iParameters[i] instanceof String && !iParameters[i].toString().startsWith("[")) {
            final var v = SQLHelper.parseValue(null, null, iParameters[i].toString(), context);
            if (v == SQLHelper.VALUE_NOT_PARSED
                || (MultiValue.isMultiValue(v)
                && MultiValue.getFirstValue(v) == SQLHelper.VALUE_NOT_PARSED)) {
              continue;
            }

            configuredParameters[i] = v;
          }
        } else {
          this.configuredParameters[i] = null;
        }
      }

      // COPY STATIC VALUES
      this.runtimeParameters = new Object[configuredParameters.length];
      for (var i = 0; i < configuredParameters.length; ++i) {
        if (!(configuredParameters[i] instanceof SQLFilterItemField)
            && !(configuredParameters[i] instanceof SQLMethodRuntime)) {
          runtimeParameters[i] = configuredParameters[i];
        }
      }
    }

    return this;
  }

  public SQLMethod getMethod() {
    return method;
  }

  public Object[] getConfiguredParameters() {
    return configuredParameters;
  }

  public Object[] getRuntimeParameters() {
    return runtimeParameters;
  }

  @Override
  public int compareTo(final SQLMethodRuntime o) {
    return method.compareTo(o.method);
  }
}
