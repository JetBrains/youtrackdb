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

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.parser.ContextVariableResolver;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandExecutorAbstract;
import com.jetbrains.youtrackdb.internal.core.command.CommandRequest;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandScriptException;
import com.jetbrains.youtrackdb.internal.core.exception.TransactionException;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.TemporaryRidGenerator;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLPredicate;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIfStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import com.jetbrains.youtrackdb.internal.core.sql.query.LegacyResultSet;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.script.Compilable;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes Script Commands.
 *
 * @see CommandScript
 */
public class CommandExecutorScript extends CommandExecutorAbstract
    implements TemporaryRidGenerator {

  private static final Logger logger = LoggerFactory.getLogger(CommandExecutorScript.class);

  private static final int MAX_DELAY = 100;
  protected CommandScript request;
  protected AtomicInteger serialTempRID = new AtomicInteger(0);

  public CommandExecutorScript() {
  }

  @Override
  @SuppressWarnings("unchecked")
  public CommandExecutorScript parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    request = (CommandScript) iRequest;
    return this;
  }

  @Override
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (context == null) {
      context = new BasicCommandContext();
    }
    return executeInContext(context, iArgs);
  }

  public Object executeInContext(final CommandContext iContext, final Map<Object, Object> iArgs) {
    final var language = request.getLanguage();
    parserText = request.getText();
    parameters = iArgs;

    if (language.equalsIgnoreCase("SQL")) {
      // SPECIAL CASE: EXECUTE THE COMMANDS IN SEQUENCE
      try {
        parserText = preParse(iContext.getDatabaseSession(), parserText, iArgs);
      } catch (ParseException e) {
        throw BaseException.wrapException(
            new CommandExecutionException(iContext.getDatabaseSession().getDatabaseName(),
                "Invalid script:" + e.getMessage()), e,
            iContext.getDatabaseSession().getDatabaseName());
      }
      return executeSQL(iContext.getDatabaseSession());
    } else {
      return executeJsr223Script(language, iContext, iArgs);
    }
  }

  private String preParse(DatabaseSessionInternal db, String parserText,
      final Map<Object, Object> iArgs)
      throws ParseException {
    final var strict = db.getStorageInfo().getConfiguration().isStrictSql();
    if (strict) {
      parserText = addSemicolons(parserText);

      byte[] bytes;
      try {
        bytes =
            parserText.getBytes(db.getStorageInfo().getConfiguration().getCharset());
      } catch (UnsupportedEncodingException e) {
        LogManager.instance()
            .warn(
                this,
                "Invalid charset for database "
                    + db
                    + " "
                    + db.getStorageInfo().getConfiguration().getCharset());

        bytes = parserText.getBytes();
      }

      InputStream is = new ByteArrayInputStream(bytes);

      YouTrackDBSql osql = null;
      try {

        if (db == null) {
          osql = new YouTrackDBSql(is);
        } else {
          osql = new YouTrackDBSql(is, db.getStorageInfo().getConfiguration().getCharset());
        }
      } catch (UnsupportedEncodingException e) {
        LogManager.instance()
            .warn(
                this,
                "Invalid charset for database "
                    + db
                    + " "
                    + db.getStorageInfo().getConfiguration().getCharset());
        osql = new YouTrackDBSql(is);
      }
      var statements = osql.parseScript();
      var result = new StringBuilder();
      for (var stm : statements) {
        stm.toString(iArgs, result);
        if (!(stm instanceof SQLIfStatement)) {
          result.append(";");
        }
        result.append("\n");
      }
      return result.toString();
    } else {
      return parserText;
    }
  }

  private String addSemicolons(String parserText) {
    var rows = parserText.split("\n");
    var builder = new StringBuilder();
    for (var row : rows) {
      row = row.trim();
      builder.append(row);
      if (!(row.endsWith(";") || row.endsWith("{"))) {
        builder.append(";");
      }
      builder.append("\n");
    }
    return builder.toString();
  }

  @Override
  public boolean isIdempotent() {
    return false;
  }

  protected Object executeJsr223Script(
      final String language, final CommandContext iContext, final Map<Object, Object> iArgs) {
    var db = iContext.getDatabaseSession();

    final var scriptManager = db.getSharedContext().getYouTrackDB().getScriptManager();
    var compiledScript = request.getCompiledScript();

    final var scriptEngine = scriptManager.acquireDatabaseEngine(db, language);
    try {

      if (compiledScript == null) {
        if (!(scriptEngine instanceof Compilable c)) {
          throw new CommandExecutionException(db.getDatabaseName(),
              "Language '" + language + "' does not support compilation");
        }

        try {
          compiledScript = c.compile(parserText);
        } catch (ScriptException e) {
          scriptManager.throwErrorMessage(iContext.getDatabaseSession().getDatabaseName(), e,
              parserText);
        }

        request.setCompiledScript(compiledScript);
      }

      final var binding =
          scriptManager.bind(
              scriptEngine,
              compiledScript.getEngine().getBindings(ScriptContext.ENGINE_SCOPE),
              db,
              iContext,
              iArgs);

      try {
        final var ob = compiledScript.eval(binding);

        return CommandExecutorUtility.transformResult(ob);
      } catch (ScriptException e) {
        throw BaseException.wrapException(
            new CommandScriptException(db.getDatabaseName(),
                "Error on execution of the script", request.getText(), e.getColumnNumber()),
            e, db.getDatabaseName());

      } finally {
        scriptManager.unbind(scriptEngine, binding, iContext, iArgs);
      }
    } finally {
      scriptManager.releaseDatabaseEngine(language, db.getDatabaseName(), scriptEngine);
    }
  }

  // TODO: CREATE A REGULAR JSR223 SCRIPT IMPL
  protected Object executeSQL(DatabaseSessionEmbedded db) {
    try {
      return executeSQLScript(parserText, db);

    } catch (IOException e) {
      throw BaseException.wrapException(
          new CommandExecutionException(db.getDatabaseName(),
              "Error on executing command: " + parserText),
          e, db.getDatabaseName());
    }
  }

  @Override
  protected void throwSyntaxErrorException(String dbName, String iText) {
    throw new CommandScriptException(dbName,
        "Error on execution of the script: " + iText, request.getText(), 0);
  }

  protected Object executeSQLScript(final String iText, final DatabaseSessionEmbedded db)
      throws IOException {
    Object lastResult = null;
    var maxRetry = 1;

    context.setVariable("transactionRetries", 0);
    context.setVariable("parentQuery", this);

    for (var retry = 1; retry <= maxRetry; retry++) {
      try {
        try {
          var txBegunAtLine = -1;
          var txBegunAtPart = -1;
          lastResult = null;
          var nestedLevel = 0;
          var skippingScriptsAtNestedLevel = -1;

          final var reader = new BufferedReader(new StringReader(iText));

          var line = 0;
          var linePart = 0;
          String lastLine;
          var txBegun = false;

          for (; line < txBegunAtLine; ++line)
          // SKIP PREVIOUS COMMAND AND JUMP TO THE BEGIN IF ANY
          {
            reader.readLine();
          }

          for (; (lastLine = reader.readLine()) != null; ++line) {
            lastLine = lastLine.trim();

            // this block is here (and not below, with the other conditions)
            // just because of the smartSprit() that does not parse correctly a single bracket

            // final List<String> lineParts = StringSerializerHelper.smartSplit(lastLine, ';',
            // true);
            final var lineParts = splitBySemicolon(lastLine);

            if (line == txBegunAtLine)
            // SKIP PREVIOUS COMMAND PART AND JUMP TO THE BEGIN IF ANY
            {
              linePart = txBegunAtPart;
            } else {
              linePart = 0;
            }

            var breakReturn = false;

            for (; linePart < lineParts.size(); ++linePart) {
              final var lastCommand = lineParts.get(linePart);

              if (isIfCondition(lastCommand)) {
                nestedLevel++;
                if (skippingScriptsAtNestedLevel >= 0) {
                  continue; // I'm in an (outer) IF that did not match the condition
                }
                var ifResult = evaluateIfCondition(db.getDatabaseName(), lastCommand);
                if (!ifResult) {
                  // if does not match the condition, skip all the inner statements
                  skippingScriptsAtNestedLevel = nestedLevel;
                }
                continue;
              } else if (lastCommand.equals("}")) {
                nestedLevel--;
                if (skippingScriptsAtNestedLevel > nestedLevel) {
                  skippingScriptsAtNestedLevel = -1;
                }
                continue;
              } else if (skippingScriptsAtNestedLevel >= 0) {
                continue; // I'm in an IF that did not match the condition
              } else if (StringSerializerHelper.startsWithIgnoreCase(lastCommand, "let ")) {
                lastResult = executeLet(lastCommand, db);

              } else if (StringSerializerHelper.startsWithIgnoreCase(lastCommand, "begin")) {

                if (txBegun) {
                  throw new CommandSQLParsingException(db.getDatabaseName(),
                      "Transaction already begun");
                }

                if (db.getTransactionInternal().isActive())
                // COMMIT ANY ACTIVE TX
                {
                  db.commit();
                }

                txBegun = true;
                txBegunAtLine = line;
                txBegunAtPart = linePart;

                db.begin();
              } else if ("rollback".equalsIgnoreCase(lastCommand)) {

                if (!txBegun) {
                  throw new CommandSQLParsingException(db.getDatabaseName(),
                      "Transaction not begun");
                }

                db.rollback();

                txBegun = false;
                txBegunAtLine = -1;
                txBegunAtPart = -1;

              } else if (StringSerializerHelper.startsWithIgnoreCase(lastCommand, "commit")) {
                if (txBegunAtLine < 0) {
                  throw new CommandSQLParsingException(db.getDatabaseName(),
                      "Transaction not begun");
                }

                if (retry == 1 && lastCommand.length() > "commit ".length()) {
                  // FIRST CYCLE: PARSE RETRY TIMES OVERWRITING DEFAULT = 1
                  var next = lastCommand.substring("commit ".length()).trim();
                  if (StringSerializerHelper.startsWithIgnoreCase(next, "retry ")) {
                    next = next.substring("retry ".length()).trim();
                    maxRetry = Integer.parseInt(next);
                  }
                }

                db.commit();

                txBegun = false;
                txBegunAtLine = -1;
                txBegunAtPart = -1;

              } else if (StringSerializerHelper.startsWithIgnoreCase(lastCommand, "sleep ")) {
                executeSleep(lastCommand);

              } else if (StringSerializerHelper.startsWithIgnoreCase(
                  lastCommand, "console.log ")) {
                executeConsoleLog(lastCommand, db);

              } else if (StringSerializerHelper.startsWithIgnoreCase(
                  lastCommand, "console.output ")) {
                executeConsoleOutput(lastCommand, db);

              } else if (StringSerializerHelper.startsWithIgnoreCase(
                  lastCommand, "console.error ")) {
                executeConsoleError(lastCommand, db);

              } else if (StringSerializerHelper.startsWithIgnoreCase(lastCommand, "return ")) {
                lastResult = getValue(lastCommand.substring("return ".length()), db);

                // END OF SCRIPT
                breakReturn = true;
                break;

              } else if (lastCommand != null && lastCommand.length() > 0) {
                lastResult = executeCommand(lastCommand, db);
              }
            }
            if (breakReturn) {
              break;
            }
          }
        } catch (RuntimeException ex) {
          if (db.getTransactionInternal().isActive()) {
            db.rollback();
          }
          throw ex;
        }

        // COMPLETED
        break;

      } catch (TransactionException e) {
        // THIS CASE IS ON UPSERT
        context.setVariable("retries", retry);
        if (retry >= maxRetry) {
          throw e;
        }

        waitForNextRetry();

      } catch (RecordDuplicatedException e) {
        // THIS CASE IS ON UPSERT
        context.setVariable("retries", retry);
        if (retry >= maxRetry) {
          throw e;
        }

        waitForNextRetry();

      } catch (RecordNotFoundException e) {
        // THIS CASE IS ON UPSERT
        context.setVariable("retries", retry);
        if (retry >= maxRetry) {
          throw e;
        }

      } catch (NeedRetryException e) {
        context.setVariable("retries", retry);
        if (retry >= maxRetry) {
          throw e;
        }

        waitForNextRetry();
      }
    }

    return lastResult;
  }

  private List<String> splitBySemicolon(String lastLine) {
    if (lastLine == null) {
      return Collections.EMPTY_LIST;
    }
    List<String> result = new ArrayList<String>();
    Character prev = null;
    Character lastQuote = null;
    var buffer = new StringBuilder();
    for (var c : lastLine.toCharArray()) {
      if (c == ';' && lastQuote == null) {
        if (buffer.toString().trim().length() > 0) {
          result.add(buffer.toString().trim());
        }
        buffer = new StringBuilder();
        prev = null;
        continue;
      }
      if ((c == '"' || c == '\'') && (prev == null || !prev.equals('\\'))) {
        if (lastQuote != null && lastQuote.equals(c)) {
          lastQuote = null;
        } else if (lastQuote == null) {
          lastQuote = c;
        }
      }
      buffer.append(c);
      prev = c;
    }
    if (buffer.toString().trim().length() > 0) {
      result.add(buffer.toString().trim());
    }
    return result;
  }

  private boolean evaluateIfCondition(String dbName, String lastCommand) {
    var cmd = lastCommand;
    cmd = cmd.trim().substring(2); // remove IF
    cmd = cmd.trim().substring(0, cmd.trim().length() - 1); // remove {
    var condition = SQLEngine.parseCondition(cmd, getContext());
    Object result = null;
    try {
      result = condition.evaluate(null, null, getContext());
    } catch (Exception e) {
      throw BaseException.wrapException(
          new CommandExecutionException(dbName,
              "Could not evaluate IF condition: " + cmd + " - " + e.getMessage()),
          e, dbName);
    }

    return Boolean.TRUE.equals(result);
  }

  private boolean isIfCondition(String iCommand) {
    if (iCommand == null) {
      return false;
    }
    var cmd = iCommand.trim();
    if (cmd.length() < 3) {
      return false;
    }
    if (!((StringSerializerHelper.startsWithIgnoreCase(cmd, "if "))
        || StringSerializerHelper.startsWithIgnoreCase(cmd, "if("))) {
      return false;
    }
    return cmd.endsWith("{");
  }

  /**
   * Wait before to retry
   */
  protected void waitForNextRetry() {
    try {
      Thread.sleep(new Random().nextInt(MAX_DELAY - 1) + 1);
    } catch (InterruptedException e) {
      LogManager.instance().error(this, "Wait was interrupted", e);
    }
  }

  private Object executeCommand(final String lastCommand, final DatabaseSession db) {
    var database = (DatabaseSessionInternal) db;
    var result =
        database
            .execute(lastCommand, toMap(parameters)).toList();
    return result;
  }

  private Object toMap(Object parameters) {
    if (parameters instanceof SimpleBindings) {
      HashMap<Object, Object> result = new LinkedHashMap<Object, Object>();
      result.putAll((SimpleBindings) parameters);
      return result;
    }
    return parameters;
  }

  private Object getValue(final String iValue, final DatabaseSessionEmbedded db) {
    Object lastResult = null;
    var recordResultSet = true;
    if (iValue.equalsIgnoreCase("NULL")) {
      lastResult = null;
    } else if (iValue.startsWith("[") && iValue.endsWith("]")) {
      // ARRAY - COLLECTION
      final List<String> items = new ArrayList<String>();

      StringSerializerHelper.getCollection(iValue, 0, items);
      final List<Object> result = new ArrayList<Object>(items.size());

      for (var i = 0; i < items.size(); ++i) {
        var item = items.get(i);

        result.add(getValue(item, db));
      }
      lastResult = result;
      checkIsRecordResultSet(lastResult);
    } else if (iValue.startsWith("{") && iValue.endsWith("}")) {
      // MAP
      final var map = StringSerializerHelper.getMap(db, iValue);
      final Map<Object, Object> result = new HashMap<Object, Object>(map.size());

      for (var entry : map.entrySet()) {
        // KEY
        var stringKey = entry.getKey();
        if (stringKey == null) {
          continue;
        }

        stringKey = stringKey.trim();

        Object key;
        if (stringKey.startsWith("$")) {
          key = getContext().getVariable(stringKey);
        } else {
          key = stringKey;
        }

        if (MultiValue.isMultiValue(key) && MultiValue.getSize(key) == 1) {
          key = MultiValue.getFirstValue(key);
        }

        // VALUE
        var stringValue = entry.getValue();
        if (stringValue == null) {
          continue;
        }

        stringValue = stringValue.trim();

        Object value;
        if (stringValue.startsWith("$")) {
          value = getContext().getVariable(stringValue);
        } else {
          value = stringValue;
        }

        result.put(key, value);
      }
      lastResult = result;
      checkIsRecordResultSet(lastResult);
    } else if (iValue.startsWith("\"") && iValue.endsWith("\"")
        || iValue.startsWith("'") && iValue.endsWith("'")) {
      lastResult = new ContextVariableResolver(context).parse(IOUtils.getStringContent(iValue));
      checkIsRecordResultSet(lastResult);
    } else if (iValue.startsWith("(") && iValue.endsWith(")")) {
      lastResult = executeCommand(iValue, db);
    } else {
      var context = getContext();
      lastResult = new SQLPredicate(getContext(), iValue).evaluate(context);
    }
    // END OF THE SCRIPT
    return lastResult;
  }

  private void checkIsRecordResultSet(Object result) {
    if (!(result instanceof Identifiable) && !(result instanceof LegacyResultSet)) {
      if (!MultiValue.isMultiValue(result)) {
        request.setRecordResultSet(false);
      } else {
        for (var val : MultiValue.getMultiValueIterable(result)) {
          if (!(val instanceof Identifiable)) {
            request.setRecordResultSet(false);
          }
        }
      }
    }
  }

  private void executeSleep(String lastCommand) {
    final var sleepTimeInMs = lastCommand.substring("sleep ".length()).trim();
    try {
      Thread.sleep(Integer.parseInt(sleepTimeInMs));
    } catch (InterruptedException e) {
      LogManager.instance().debug(this, "Sleep was interrupted in SQL batch", logger, e);
    }
  }

  private void executeConsoleLog(final String lastCommand, final DatabaseSessionEmbedded db) {
    final var value = lastCommand.substring("console.log ".length()).trim();
    LogManager.instance().info(this, "%s", getValue(IOUtils.wrapStringContent(value, '\''), db));
  }

  private void executeConsoleOutput(final String lastCommand, final DatabaseSessionEmbedded db) {
    final var value = lastCommand.substring("console.output ".length()).trim();
    System.out.println(getValue(IOUtils.wrapStringContent(value, '\''), db));
  }

  private void executeConsoleError(final String lastCommand, final DatabaseSessionEmbedded db) {
    final var value = lastCommand.substring("console.error ".length()).trim();
    System.err.println(getValue(IOUtils.wrapStringContent(value, '\''), db));
  }

  @Nullable
  private Object executeLet(final String lastCommand, final DatabaseSessionEmbedded db) {
    final var equalsPos = lastCommand.indexOf('=');
    final var variable = lastCommand.substring("let ".length(), equalsPos).trim();
    final var cmd = lastCommand.substring(equalsPos + 1).trim();
    if (cmd == null) {
      return null;
    }

    Object lastResult = null;

    if (cmd.equalsIgnoreCase("NULL")
        || !cmd.isEmpty() && cmd.charAt(0) == '$'
        || (!cmd.isEmpty() && cmd.charAt(0) == '['
        && cmd.charAt(cmd.length() - 1) == ']')
        || (!cmd.isEmpty() && cmd.charAt(0) == '{' && cmd.charAt(cmd.length() - 1) == '}')
        || (!cmd.isEmpty() && cmd.charAt(0) == '\"' && cmd.charAt(cmd.length() - 1) == '\"'
        || !cmd.isEmpty()
        && cmd.charAt(0) == '\'' && cmd.charAt(cmd.length() - 1) == '\'')
        || (!cmd.isEmpty() && cmd.charAt(0) == '(' && cmd.charAt(cmd.length() - 1) == ')')
        || !cmd.isEmpty() && cmd.charAt(0) == '#') {
      lastResult = getValue(cmd, db);
    } else {
      lastResult = executeCommand(cmd, db);
    }

    // PUT THE RESULT INTO THE CONTEXT
    getContext().setVariable(variable, lastResult);
    return lastResult;
  }

  @Override
  public int getTemporaryRIDCounter(CommandContext iContext) {
    return serialTempRID.incrementAndGet();
  }
}
