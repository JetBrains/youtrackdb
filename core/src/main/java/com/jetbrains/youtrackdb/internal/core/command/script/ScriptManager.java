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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.parser.StringParser;
import com.jetbrains.youtrackdb.internal.common.util.ClassLoaderHelper;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandManager;
import com.jetbrains.youtrackdb.internal.core.command.ScriptExecutor;
import com.jetbrains.youtrackdb.internal.core.command.ScriptExecutorRegister;
import com.jetbrains.youtrackdb.internal.core.command.script.formatter.GroovyScriptFormatter;
import com.jetbrains.youtrackdb.internal.core.command.script.formatter.JSScriptFormatter;
import com.jetbrains.youtrackdb.internal.core.command.script.formatter.RubyScriptFormatter;
import com.jetbrains.youtrackdb.internal.core.command.script.formatter.SQLScriptFormatter;
import com.jetbrains.youtrackdb.internal.core.command.script.formatter.ScriptFormatter;
import com.jetbrains.youtrackdb.internal.core.command.script.js.JSScriptEngineFactory;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.ScriptTransformerImpl;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandScriptException;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionUtilWrapper;
import com.jetbrains.youtrackdb.internal.core.sql.SQLScriptEngine;
import com.jetbrains.youtrackdb.internal.core.sql.SQLScriptEngineFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 * Executes Script Commands.
 *
 * @see CommandScript
 */
public class ScriptManager {

  protected static final Object[] EMPTY_PARAMS = new Object[]{};
  protected static final int LINES_AROUND_ERROR = 5;
  protected static final String DEF_LANGUAGE = "javascript";
  protected String defaultLanguage = DEF_LANGUAGE;
  protected ScriptEngineManager scriptEngineManager;
  protected Map<String, ScriptEngineFactory> engines = new HashMap<String, ScriptEngineFactory>();
  protected Map<String, ScriptFormatter> formatters = new HashMap<String, ScriptFormatter>();
  protected List<ScriptInjection> injections = new ArrayList<ScriptInjection>();
  protected ConcurrentHashMap<String, DatabaseScriptManager> dbManagers =
      new ConcurrentHashMap<String, DatabaseScriptManager>();
  protected Map<String, ScriptResultHandler> handlers =
      new HashMap<String, ScriptResultHandler>();
  protected Map<String, java.util.function.Function<String, ScriptExecutor>> executorsFactories = new HashMap<>();
  protected CommandManager commandManager = new CommandManager();

  public ScriptManager() {
    scriptEngineManager = new ScriptEngineManager();

    final var useGraal = GlobalConfiguration.SCRIPT_POLYGLOT_USE_GRAAL.getValueAsBoolean();
    executorsFactories.put(
        "javascript",
        (lang) ->
            useGraal
                ? new PolyglotScriptExecutor(lang, new ScriptTransformerImpl())
                : new Jsr223ScriptExecutor(lang, new ScriptTransformerImpl()));
    executorsFactories.put(
        "ecmascript",
        (lang) ->
            useGraal
                ? new PolyglotScriptExecutor(lang, new ScriptTransformerImpl())
                : new Jsr223ScriptExecutor(lang, new ScriptTransformerImpl()));

    for (var f : scriptEngineManager.getEngineFactories()) {
      registerEngine(f.getLanguageName().toLowerCase(Locale.ENGLISH), f);

      if (defaultLanguage == null) {
        defaultLanguage = f.getLanguageName();
      }
    }

    if (!existsEngine(DEF_LANGUAGE)) {
      // if graal is disabled, try to load nashorn manually
      var defEngine =
          scriptEngineManager.getEngineByName(useGraal ? DEF_LANGUAGE : "nashorn");
      if (defEngine == null) {
        // no nashorn engine, use the default
        defEngine = scriptEngineManager.getEngineByName(DEF_LANGUAGE);
      }
      if (defEngine == null) {
        LogManager.instance()
            .warn(this, "Cannot find default script language for %s", DEF_LANGUAGE);
      } else {
        // GET DIRECTLY THE LANGUAGE BY NAME (DON'T KNOW WHY SOMETIMES DOESN'T RETURN IT WITH
        // getEngineFactories() ABOVE!
        registerEngine(DEF_LANGUAGE, defEngine.getFactory());
        defaultLanguage = DEF_LANGUAGE;
      }
    }

    registerFormatter(SQLScriptEngine.NAME, new SQLScriptFormatter());
    registerFormatter(DEF_LANGUAGE, new JSScriptFormatter());
    registerFormatter("ruby", new RubyScriptFormatter());
    registerFormatter("groovy", new GroovyScriptFormatter());
    for (var lang : engines.keySet()) {
      var factory = executorsFactories.get(lang);
      ScriptExecutor executor = null;
      if (factory != null) {
        executor = factory.apply(lang);
      } else {
        executor = new Jsr223ScriptExecutor(lang, new ScriptTransformerImpl());
      }
      commandManager.registerScriptExecutor(lang, executor);
    }

    // Registring sql script engine after for not fight with the basic engine
    registerEngine(SQLScriptEngine.NAME, new SQLScriptEngineFactory());

    var customExecutors =
        ClassLoaderHelper.lookupProviderWithYouTrackDBClassLoader(ScriptExecutorRegister.class);

    customExecutors.forEachRemaining(e -> e.registerExecutor(this, commandManager));
  }

  public String getFunctionDefinition(DatabaseSessionEmbedded session, final Function iFunction) {
    final var formatter =
        formatters.get(iFunction.getLanguage().toLowerCase(Locale.ENGLISH));
    if (formatter == null) {
      throw new IllegalArgumentException(
          "Cannot find script formatter for the language '" + iFunction.getLanguage() + "'");
    }

    return formatter.getFunctionDefinition(session, iFunction);
  }

  public String getFunctionInvoke(DatabaseSessionEmbedded session, final Function iFunction,
      final Object[] iArgs) {
    final var formatter =
        formatters.get(iFunction.getLanguage().toLowerCase(Locale.ENGLISH));
    if (formatter == null) {
      throw new IllegalArgumentException(
          "Cannot find script formatter for the language '" + iFunction.getLanguage() + "'");
    }

    return formatter.getFunctionInvoke(session, iFunction, iArgs);
  }

  /**
   * Formats the library of functions for a language.
   *
   * @param session   Current database instance
   * @param iLanguage Language as filter
   * @return String containing all the functions
   */
  @Nullable
  public String getLibrary(final DatabaseSessionEmbedded session, final String iLanguage) {
    if (session == null)
    // NO DB = NO LIBRARY
    {
      return null;
    }

    final var code = new StringBuilder();

    final var functions = session.getMetadata().getFunctionLibrary().getFunctionNames();
    for (var fName : functions) {
      final var f = session.getMetadata().getFunctionLibrary().getFunction(session, fName);

      if (f.getLanguage() == null) {
        throw new ConfigurationException(session.getDatabaseName(),
            "Database function '" + fName + "' has no language");
      }

      if (f.getLanguage().equalsIgnoreCase(iLanguage)) {
        final var def = getFunctionDefinition(session, f);
        if (def != null) {
          code.append(def);
          code.append("\n");
        }
      }
    }

    return code.length() == 0 ? null : code.toString();
  }

  public boolean existsEngine(String iLanguage) {
    if (iLanguage == null) {
      return false;
    }

    iLanguage = iLanguage.toLowerCase(Locale.ENGLISH);
    return engines.containsKey(iLanguage);
  }

  public ScriptEngine getEngine(String dbName, final String iLanguage) {
    if (iLanguage == null) {
      throw new CommandScriptException(dbName, "No language was specified");
    }

    final var lang = iLanguage.toLowerCase(Locale.ENGLISH);

    final var scriptEngineFactory = engines.get(lang);
    if (scriptEngineFactory == null) {
      throw new CommandScriptException(dbName,
          "Unsupported language: "
              + iLanguage
              + ". Supported languages are: "
              + getSupportedLanguages());
    }

    return scriptEngineFactory.getScriptEngine();
  }

  /**
   * Acquires a database engine from the pool. Once finished using it, the instance MUST be returned
   * in the pool by calling the method #releaseDatabaseEngine(String, ScriptEngine).
   *
   * @param db       Database instance
   * @param language Script language
   * @return ScriptEngine instance with the function library already parsed
   * @see #releaseDatabaseEngine(String, String, ScriptEngine)
   */
  public ScriptEngine acquireDatabaseEngine(final DatabaseSessionEmbedded db,
      final String language) {
    var dbManager = dbManagers.get(db.getDatabaseName());
    if (dbManager == null) {
      // CREATE A NEW DATABASE SCRIPT MANAGER
      dbManager = new DatabaseScriptManager(this, db.getDatabaseName());
      final var prev = dbManagers.putIfAbsent(db.getDatabaseName(), dbManager);
      if (prev != null) {
        dbManager.close();
        // GET PREVIOUS ONE
        dbManager = prev;
      }
    }

    return dbManager.acquireEngine(db, language);
  }

  /**
   * Acquires a database engine from the pool. Once finished using it, the instance MUST be returned
   * in the pool by calling the method
   *
   * @param iLanguage     Script language
   * @param iDatabaseName Database name
   * @param poolEntry     Pool entry to free
   * @see #acquireDatabaseEngine(DatabaseSessionEmbedded, String)
   */
  public void releaseDatabaseEngine(
      final String iLanguage, final String iDatabaseName, final ScriptEngine poolEntry) {
    final var dbManager = dbManagers.get(iDatabaseName);
    // We check if there is still a valid pool because it could be removed by the function reload
    if (dbManager != null) {
      dbManager.releaseEngine(iLanguage, poolEntry);
    }
  }

  public Iterable<String> getSupportedLanguages() {
    final var result = new HashSet<String>();
    result.addAll(engines.keySet());
    return result;
  }

  public Bindings bindContextVariables(
      ScriptEngine engine,
      final Bindings binding,
      final DatabaseSessionEmbedded db,
      final CommandContext iContext,
      final Map<Object, Object> iArgs) {

    bindDatabase(binding, db);

    bindInjectors(engine, binding, db);

    bindContext(binding, iContext);

    bindParameters(binding, iArgs);

    return binding;
  }

  @Deprecated
  public Bindings bind(
      ScriptEngine scriptEngine,
      final Bindings binding,
      final DatabaseSessionEmbedded db,
      final CommandContext iContext,
      final Map<Object, Object> iArgs) {

    bindLegacyDatabaseAndUtil(binding, db);

    bindDatabase(binding, db);

    bindInjectors(scriptEngine, binding, db);

    bindContext(binding, iContext);

    bindParameters(binding, iArgs);

    return binding;
  }

  private void bindInjectors(ScriptEngine engine, Bindings binding,
      DatabaseSessionEmbedded database) {
    for (var i : injections) {
      i.bind(engine, binding, database);
    }
  }

  private void bindContext(Bindings binding, CommandContext iContext) {
    // BIND CONTEXT VARIABLE INTO THE SCRIPT
    if (iContext != null) {
      binding.put("ctx", iContext);
      for (var a : iContext.getVariables().entrySet()) {
        binding.put(a.getKey(), a.getValue());
      }
    }
  }

  private void bindLegacyDatabaseAndUtil(Bindings binding, DatabaseSessionEmbedded db) {
    if (db != null) {
      // BIND FIXED VARIABLES
      //      binding.put("db", new ScriptDocumentDatabaseWrapper(db));
      binding.put("youtrackdb", new ScriptYouTrackDbWrapper(db));
    }
    binding.put("util", new FunctionUtilWrapper());
  }

  private void bindDatabase(Bindings binding, DatabaseSessionEmbedded db) {
    if (db != null) {
      binding.put("db", new ScriptDatabaseWrapper(db));
    }
  }

  private void bindParameters(Bindings binding, Map<Object, Object> iArgs) {
    // BIND PARAMETERS INTO THE SCRIPT
    if (iArgs != null) {
      for (var a : iArgs.entrySet()) {
        binding.put(a.getKey().toString(), a.getValue());
      }

      binding.put("params", iArgs.values().toArray());
    } else {
      binding.put("params", EMPTY_PARAMS);
    }
  }

  public String throwErrorMessage(String dbName, final ScriptException e, final String lib) {
    var errorLineNumber = e.getLineNumber();

    if (errorLineNumber <= 0) {
      // FIX TO RHINO: SOMETIMES HAS THE LINE NUMBER INSIDE THE TEXT :-(
      final var excMessage = e.toString();
      final var pos = excMessage.indexOf("<Unknown Source>#");
      if (pos > -1) {
        final var end = excMessage.indexOf(')', pos + "<Unknown Source>#".length());
        var lineNumberAsString = excMessage.substring(pos + "<Unknown Source>#".length(), end);
        errorLineNumber = Integer.parseInt(lineNumberAsString);
      }
    }

    if (errorLineNumber <= 0) {
      throw new CommandScriptException(dbName,
          "Error on evaluation of the script library. Error: "
              + e.getMessage()
              + "\nScript library was:\n"
              + lib);
    } else {
      final var code = new StringBuilder();
      final var scanner = new Scanner(lib);
      try {
        scanner.useDelimiter("\n");
        String currentLine = null;
        var lastFunctionName = "unknown";

        for (var currentLineNumber = 1; scanner.hasNext(); currentLineNumber++) {
          currentLine = scanner.next();
          var pos = currentLine.indexOf("function");
          if (pos > -1) {
            final var words =
                StringParser.getWords(
                    currentLine.substring(
                        Math.min(pos + "function".length() + 1, currentLine.length())),
                    " \r\n\t");
            if (words.length > 0 && words[0] != "(") {
              lastFunctionName = words[0];
            }
          }

          if (currentLineNumber == errorLineNumber)
          // APPEND X LINES BEFORE
          {
            code.append(String.format("%4d: >>> %s\n", currentLineNumber, currentLine));
          } else if (Math.abs(currentLineNumber - errorLineNumber) <= LINES_AROUND_ERROR)
          // AROUND: APPEND IT
          {
            code.append(String.format("%4d: %s\n", currentLineNumber, currentLine));
          }
        }

        code.insert(
            0,
            String.format(
                "ScriptManager: error %s.\nFunction %s:\n\n", e.getMessage(), lastFunctionName));

      } finally {
        scanner.close();
      }

      throw new CommandScriptException(dbName, code.toString());
    }
  }

  /**
   * Unbinds variables
   */
  public void unbind(
      ScriptEngine scriptEngine,
      final Bindings binding,
      final CommandContext iContext,
      final Map<Object, Object> iArgs) {
    for (var i : injections) {
      i.unbind(scriptEngine, binding);
    }

    binding.put("db", null);
    binding.put("youtrackdb", null);

    binding.put("util", null);

    binding.put("ctx", null);
    if (iContext != null) {
      for (var a : iContext.getVariables().entrySet()) {
        binding.put(a.getKey(), null);
      }
    }

    if (iArgs != null) {
      for (var a : iArgs.entrySet()) {
        binding.put(a.getKey().toString(), null);
      }
    }
    binding.put("params", null);
  }

  public void registerInjection(final ScriptInjection iInj) {
    if (!injections.contains(iInj)) {
      injections.add(iInj);
    }
  }

  public Set<String> getAllowedPackages() {
    final Set<String> result = new HashSet<>();
    this.engines
        .entrySet()
        .forEach(
            e -> {
              if (e.getValue() instanceof SecuredScriptFactory) {
                result.addAll(((SecuredScriptFactory) e.getValue()).getPackages());
              }
            });
    return result;
  }

  public void addAllowedPackages(Set<String> packages) {

    this.engines
        .entrySet()
        .forEach(
            e -> {
              if (e.getValue() instanceof SecuredScriptFactory) {
                ((SecuredScriptFactory) e.getValue()).addAllowedPackages(packages);
              }
            });
    closeAll();
  }

  public void removeAllowedPackages(Set<String> packages) {
    this.engines
        .entrySet()
        .forEach(
            e -> {
              if (e.getValue() instanceof SecuredScriptFactory) {
                ((SecuredScriptFactory) e.getValue()).removeAllowedPackages(packages);
              }
            });
    closeAll();
  }

  public void unregisterInjection(final ScriptInjection iInj) {
    injections.remove(iInj);
  }

  public List<ScriptInjection> getInjections() {
    return injections;
  }

  public ScriptManager registerEngine(final String iLanguage, final ScriptEngineFactory iEngine) {
    engines.put(iLanguage, JSScriptEngineFactory.maybeWrap(iEngine));
    return this;
  }

  public ScriptManager registerFormatter(
      final String iLanguage, final ScriptFormatter iFormatterImpl) {
    formatters.put(iLanguage.toLowerCase(Locale.ENGLISH), iFormatterImpl);
    return this;
  }

  public ScriptManager registerResultHandler(
      final String iLanguage, final ScriptResultHandler resultHandler) {
    handlers.put(iLanguage.toLowerCase(Locale.ENGLISH), resultHandler);
    return this;
  }

  public Object handleResult(
      String language,
      Object result,
      ScriptEngine engine,
      Bindings binding,
      DatabaseSessionEmbedded database) {
    var handler = handlers.get(language);
    if (handler != null) {
      return handler.handle(result, engine, binding, database);
    } else {
      return result;
    }
  }

  /**
   * Ask to the Script engine all the formatters
   *
   * @return Map containing all the formatters
   */
  public Map<String, ScriptFormatter> getFormatters() {
    return formatters;
  }

  /**
   * Closes the pool for a database. This is called at YouTrackDB shutdown and in case a function
   * has been updated.
   *
   * @param iDatabaseName
   */
  public void close(final String iDatabaseName) {
    final var dbPool = dbManagers.remove(iDatabaseName);
    if (dbPool != null) {
      dbPool.close();
    }
    commandManager.close(iDatabaseName);
  }

  public void closeAll() {
    dbManagers.entrySet().forEach(e -> e.getValue().close());
    commandManager.closeAll();
  }

  public CommandManager getCommandManager() {
    return commandManager;
  }
}
