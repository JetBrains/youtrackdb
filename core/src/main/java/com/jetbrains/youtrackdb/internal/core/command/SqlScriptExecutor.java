package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.internal.common.util.CommonConst;
import com.jetbrains.youtrackdb.internal.core.command.script.CommandExecutorUtility;
import com.jetbrains.youtrackdb.internal.core.command.traverse.AbstractScriptExecutor;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandScriptException;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RetryExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RetryStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ScriptExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBeginStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLCommitStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLetStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRollbackStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptException;

/**
 * Script executor that parses and executes SQL script blocks containing multiple statements.
 */
public class SqlScriptExecutor extends AbstractScriptExecutor {

  public SqlScriptExecutor() {
    super("SQL");
  }

  @Override
  public ResultSet execute(DatabaseSessionEmbedded database, String script, Object... args)
      throws CommandSQLParsingException, CommandExecutionException {

    if (!(!script.trim().isEmpty() && script.trim().charAt(script.trim().length() - 1) == ';')) {
      script += ";";
    }
    var statements = SQLEngine.parseScript(script, database);

    CommandContext scriptContext = new BasicCommandContext();
    scriptContext.setDatabaseSession(database);
    Map<Object, Object> params = new HashMap<>();
    if (args != null) {
      for (var i = 0; i < args.length; i++) {
        params.put(i, args[i]);
      }
    }
    scriptContext.setInputParameters(params);

    return executeInternal(statements, scriptContext);
  }

  @Override
  public ResultSet execute(DatabaseSessionEmbedded database, String script, Map params) {
    if (!(!script.trim().isEmpty() && script.trim().charAt(script.trim().length() - 1) == ';')) {
      script += ";";
    }
    var statements = SQLEngine.parseScript(script, database);

    CommandContext scriptContext = new BasicCommandContext();
    scriptContext.setDatabaseSession(database);

    //noinspection unchecked
    scriptContext.setInputParameters((Map<Object, Object>) params);

    return executeInternal(statements, scriptContext);
  }

  private static ResultSet executeInternal(List<SQLStatement> statements,
      CommandContext scriptContext) {
    var plan = new ScriptExecutionPlan(scriptContext);

    plan.setStatement(
        statements.stream().map(SQLStatement::toString).collect(Collectors.joining(";")));

    List<SQLStatement> lastRetryBlock = new ArrayList<>();
    var nestedTxLevel = 0;

    for (var stm : statements) {
      if (stm.getOriginalStatement() == null) {
        stm.setOriginalStatement(stm.toString());
      }
      if (stm instanceof SQLBeginStatement) {
        nestedTxLevel++;
      }

      if (nestedTxLevel <= 0) {
        var sub = stm.createExecutionPlan(scriptContext);
        plan.chain(sub, false);
      } else {
        lastRetryBlock.add(stm);
      }

      if (stm instanceof SQLCommitStatement commitStatement && nestedTxLevel > 0) {
        nestedTxLevel--;
        if (nestedTxLevel == 0) {
          if (commitStatement.getRetry() != null) {
            var nRetries = commitStatement.getRetry().getValue().intValue();
            if (nRetries <= 0) {
              throw new CommandExecutionException(
                  scriptContext.getDatabaseSession().getDatabaseName(),
                  "Invalid retry number: " + nRetries);
            }

            var step =
                new RetryStep(
                    lastRetryBlock,
                    nRetries,
                    ((SQLCommitStatement) stm).getElseStatements(),
                    ((SQLCommitStatement) stm).getElseFail(),
                    scriptContext,
                    false);
            var retryPlan = new RetryExecutionPlan(scriptContext);
            retryPlan.chain(step);
            plan.chain(retryPlan, false);
          } else {
            for (var statement : lastRetryBlock) {
              var sub = statement.createExecutionPlan(scriptContext);
              plan.chain(sub, false);
            }
          }

          lastRetryBlock = new ArrayList<>();
        }
      } else if (stm instanceof SQLRollbackStatement && nestedTxLevel > 0) {
        nestedTxLevel = 0;

        for (var statement : lastRetryBlock) {
          var sub = statement.createExecutionPlan(scriptContext);
          plan.chain(sub, false);
        }

        lastRetryBlock = new ArrayList<>();
      }

      if (stm instanceof SQLLetStatement letStatement) {
        scriptContext.declareScriptVariable(letStatement.getName().getStringValue());
      }
    }
    return new LocalResultSet(scriptContext.getDatabaseSession(), plan);
  }

  @Override
  public Object executeFunction(
      CommandContext context, final String functionName, final Map<Object, Object> iArgs) {
    var session = context.getDatabaseSession();
    final var f = session.getMetadata().getFunctionLibrary().getFunction(session, functionName);

    session.checkSecurity(Rule.ResourceGeneric.FUNCTION, Role.PERMISSION_READ, f.getName());
    final var scriptManager = session.getSharedContext().getYouTrackDB().getScriptManager();

    final var scriptEngine =
        scriptManager.acquireDatabaseEngine(session, f.getLanguage());
    try {
      final var binding =
          scriptManager.bindContextVariables(
              scriptEngine,
              scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE),
              session,
              context,
              iArgs);
      try {
        final Object result;

        if (scriptEngine instanceof Invocable invocableEngine) {
          // INVOKE AS FUNCTION. PARAMS ARE PASSED BY POSITION
          Object[] args = null;
          if (iArgs != null) {
            args = new Object[iArgs.size()];
            var i = 0;
            for (var arg : iArgs.entrySet()) {
              args[i++] = arg.getValue();
            }
          } else {
            args = CommonConst.EMPTY_OBJECT_ARRAY;
          }
          result = invocableEngine.invokeFunction(functionName, args);

        } else {
          // INVOKE THE CODE SNIPPET
          final var args = iArgs == null ? null : iArgs.values().toArray();
          result = scriptEngine.eval(scriptManager.getFunctionInvoke(session, f, args), binding);
        }
        return CommandExecutorUtility.transformResult(
            scriptManager.handleResult(f.getLanguage(), result, scriptEngine, binding, session));

      } catch (ScriptException e) {
        throw BaseException.wrapException(
            new CommandScriptException(session.getDatabaseName(),
                "Error on execution of the function", functionName, e.getColumnNumber()),
            e, session);
      } catch (NoSuchMethodException e) {
        throw BaseException.wrapException(
            new CommandScriptException(session.getDatabaseName(),
                "Error on execution of the function",
                functionName, 0),
            e, session);
      } finally {
        scriptManager.unbind(scriptEngine, binding, context, iArgs);
      }
    } finally {
      scriptManager.releaseDatabaseEngine(f.getLanguage(), session.getDatabaseName(), scriptEngine);
    }
  }
}
