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
package com.jetbrains.youtrackdb.internal.core.sql.functions;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.parser.BaseParser;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.SQLHelper;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemAbstract;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemVariable;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLPredicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Wraps function managing the binding of parameters.
 */
public class SQLFunctionRuntime extends SQLFilterItemAbstract {

  public SQLFunction function;
  public Object[] configuredParameters;
  public Object[] runtimeParameters;

  public SQLFunctionRuntime(DatabaseSessionEmbedded session, final BaseParser iQueryToParse,
      final String iText) {
    super(session, iQueryToParse, iText);
  }

  public SQLFunctionRuntime(final SQLFunction iFunction) {
    function = iFunction;
  }

  public boolean aggregateResults() {
    return function.aggregateResults();
  }

  public boolean filterResult() {
    return function.filterResult();
  }

  /**
   * Execute a function.
   *
   * @param iCurrentRecord Current record
   * @param iCurrentResult TODO
   * @param iContext
   * @return
   */
  public Object execute(
      final Object iThis,
      final Result iCurrentRecord,
      final Object iCurrentResult,
      @Nonnull final CommandContext iContext) {
    var session = iContext.getDatabaseSession();
    // RESOLVE VALUES USING THE CURRENT RECORD
    for (var i = 0; i < configuredParameters.length; ++i) {
      runtimeParameters[i] = configuredParameters[i];

      if (configuredParameters[i] instanceof SQLFilterItemField) {
        runtimeParameters[i] =
            ((SQLFilterItemField) configuredParameters[i])
                .getValue(iCurrentRecord, iCurrentResult, iContext);
      } else if (configuredParameters[i] instanceof SQLFunctionRuntime) {
        runtimeParameters[i] =
            ((SQLFunctionRuntime) configuredParameters[i])
                .execute(iThis, iCurrentRecord, iCurrentResult, iContext);
      } else if (configuredParameters[i] instanceof SQLFilterItemVariable) {
        runtimeParameters[i] =
            ((SQLFilterItemVariable) configuredParameters[i])
                .getValue(iCurrentRecord, iCurrentResult, iContext);
      } else if (configuredParameters[i] instanceof SQLPredicate) {
        runtimeParameters[i] =
            ((SQLPredicate) configuredParameters[i])
                .evaluate(
                    iCurrentRecord,
                    (iCurrentRecord instanceof EntityImpl ? (EntityImpl) iCurrentResult : null),
                    iContext);
      } else if (configuredParameters[i] instanceof String) {
        if (configuredParameters[i].toString().startsWith("\"")
            || configuredParameters[i].toString().startsWith("'")) {
          runtimeParameters[i] = IOUtils.getStringContent(configuredParameters[i]);
        }
      }
    }

    if (function.getMaxParams(session) == -1 || function.getMaxParams(session) > 0) {
      if (runtimeParameters.length < function.getMinParams()
          || (function.getMaxParams(session) > -1
          && runtimeParameters.length > function.getMaxParams(
          session))) {
        String params;
        if (function.getMinParams() == function.getMaxParams(session)) {
          params = "" + function.getMinParams();
        } else {
          params = function.getMinParams() + "-" + function.getMaxParams(session);
        }
        throw new CommandExecutionException(session,
            "Syntax error: function '"
                + function.getName(session)
                + "' needs "
                + params
                + " argument(s) while has been received "
                + runtimeParameters.length);
      }
    }

    final var functionResult =
        function.execute(iThis, iCurrentRecord, iCurrentResult, runtimeParameters, iContext);

    return transformValue(iCurrentRecord, iContext, functionResult);
  }

  public Object getResult(DatabaseSessionEmbedded session) {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    return transformValue(null, context, function.getResult());
  }

  public void setResult(final Object iValue) {
    function.setResult(iValue);
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
    return function.getName(session);
  }

  public SQLFunctionRuntime setParameters(@Nonnull CommandContext context,
      final Object[] iParameters, final boolean iEvaluate) {
    this.configuredParameters = new Object[iParameters.length];

    for (var i = 0; i < iParameters.length; ++i) {
      this.configuredParameters[i] = iParameters[i];

      if (iEvaluate) {
        if (iParameters[i] != null) {
          if (iParameters[i] instanceof String) {
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
    }

    function.config(configuredParameters);

    // COPY STATIC VALUES
    this.runtimeParameters = new Object[configuredParameters.length];
    for (var i = 0; i < configuredParameters.length; ++i) {
      if (!(configuredParameters[i] instanceof SQLFilterItemField)
          && !(configuredParameters[i] instanceof SQLFunctionRuntime)) {
        runtimeParameters[i] = configuredParameters[i];
      }
    }

    return this;
  }

  public SQLFunction getFunction() {
    return function;
  }

  public Object[] getConfiguredParameters() {
    return configuredParameters;
  }

  public Object[] getRuntimeParameters() {
    return runtimeParameters;
  }

  @Override
  protected void setRoot(DatabaseSessionEmbedded session, final BaseParser iQueryToParse,
      final String iText) {
    final var beginParenthesis = iText.indexOf('(');

    // SEARCH FOR THE FUNCTION
    final var funcName = iText.substring(0, beginParenthesis);

    final var funcParamsText = StringSerializerHelper.getParameters(iText);

    function = SQLEngine.getFunction(session, funcName);
    if (function == null) {
      throw new CommandSQLParsingException(session.getDatabaseName(),
          "Unknown function " + funcName + "()");
    }

    // PARSE PARAMETERS
    this.configuredParameters = new Object[funcParamsText.size()];
    for (var i = 0; i < funcParamsText.size(); ++i) {
      this.configuredParameters[i] = funcParamsText.get(i);
    }

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    setParameters(context, configuredParameters, true);
  }
}
