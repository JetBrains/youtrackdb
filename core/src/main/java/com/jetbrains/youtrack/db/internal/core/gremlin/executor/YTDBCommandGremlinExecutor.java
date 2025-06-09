/*
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
 */
package com.jetbrains.youtrack.db.internal.core.gremlin.executor;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandScriptException;
import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.script.ScriptInjection;
import com.jetbrains.youtrack.db.internal.core.command.script.ScriptManager;
import com.jetbrains.youtrack.db.internal.core.command.script.ScriptResultHandler;
import com.jetbrains.youtrack.db.internal.core.command.script.formatter.GroovyScriptFormatter;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.ScriptTransformer;
import com.jetbrains.youtrack.db.internal.core.command.traverse.AbstractScriptExecutor;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBAbstractElement;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBVertexPropertyImpl;
import com.jetbrains.youtrack.db.internal.core.gremlin.executor.transformer.YTDBEntityTransformer;
import com.jetbrains.youtrack.db.internal.core.gremlin.executor.transformer.YTDBGremlinTransformer;
import com.jetbrains.youtrack.db.internal.core.gremlin.executor.transformer.YTDBPropertyTransformer;
import com.jetbrains.youtrack.db.internal.core.gremlin.executor.transformer.YTDBTraversalMetricTransformer;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import groovy.lang.MissingPropertyException;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngineFactory;
import org.apache.tinkerpop.gremlin.groovy.jsr223.GroovyCompilerGremlinPlugin;
import org.apache.tinkerpop.gremlin.jsr223.CachedGremlinScriptEngineManager;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalMetrics;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalExplanation;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;

/**
 * Executes a GREMLIN command.
 */
public final class YTDBCommandGremlinExecutor extends AbstractScriptExecutor
    implements ScriptInjection, ScriptResultHandler {

  public static final String GREMLIN_GROOVY = "gremlin-groovy";
  private final ScriptManager scriptManager;

  private final ScriptTransformer transformer;

  public YTDBCommandGremlinExecutor(ScriptManager scriptManager, ScriptTransformer transformer) {
    super("gremlin");
    var factory = new GremlinGroovyScriptEngineFactory();
    var customizationManager = new CachedGremlinScriptEngineManager();
    Map<String, Object> compilerConfigs = new HashMap<>();
    Map<String, Object> optimizationConfigs = new HashMap<>();
    optimizationConfigs.put("asmResolving", false);
    compilerConfigs.put("OptimizationOptions", optimizationConfigs);
    customizationManager.addPlugin(
        GroovyCompilerGremlinPlugin.build().compilerConfigurationOptions(compilerConfigs).create());
    factory.setCustomizerManager(customizationManager);
    this.scriptManager = scriptManager;
    this.transformer = new YTDBGremlinTransformer(transformer);

    initCustomTransformer(this.transformer);

    scriptManager.registerInjection(this);

    scriptManager.registerFormatter(GREMLIN_GROOVY, new GroovyScriptFormatter());
    scriptManager.registerEngine(GREMLIN_GROOVY, factory);

    scriptManager.registerResultHandler(GREMLIN_GROOVY, this);
  }

  private static void initCustomTransformer(ScriptTransformer transformer) {
    transformer.registerResultTransformer(
        DefaultTraversalMetrics.class, new YTDBTraversalMetricTransformer());
    transformer.registerResultTransformer(YTDBAbstractElement.class, new YTDBEntityTransformer());
    transformer.registerResultTransformer(
        YTDBVertexPropertyImpl.class, new YTDBPropertyTransformer(transformer));
  }

  @Override
  public ResultSet execute(DatabaseSessionEmbedded database, String script, Object... params) {
    preExecute(database, script, params);
    Map<Object, Object> mapParams = new HashMap<>();
    if (params != null) {
      for (var i = 0; i < params.length; i++) {
        mapParams.put("par_" + i, params[i]);
      }
    }
    return execute(database, script, mapParams);
  }

  @Override
  public ResultSet execute(
      final DatabaseSessionEmbedded session, final String iText,
      @SuppressWarnings("rawtypes") final Map params) {
    preExecute(session, iText, params);
    ScriptEngine engine = null;
    try {
      engine = acquireGremlinEngine(acquireGraph(session));
      //noinspection unchecked
      bindParameters(engine, params);

      var eval = engine.eval(iText);

      return transformResult(session, eval);

    } catch (ScriptException e) {
      if (isGroovyException(e)) {
        throw new CommandExecutionException(session, e.getMessage());
      } else {
        throw BaseException.wrapException(
            new CommandExecutionException(session, "Error on execution of the GREMLIN script"), e,
            session);
      }
    } catch (Exception e) {
      throw BaseException.wrapException(
          new CommandExecutionException(session, "Error on execution of the GREMLIN script"), e,
          session);
    } finally {
      if (engine != null) {
        releaseGremlinEngine(session.getDatabaseName(), engine);
      }
    }
  }

  @Nonnull
  private ResultSet transformResult(DatabaseSessionEmbedded session, Object eval) {
    if (eval instanceof Traversal<?, ?> traversal) {
      return new YTDBGremlinScriptResultSet(session, traversal, this.transformer);
    } else if (eval instanceof TraversalExplanation) {
      var resultSet = new InternalResultSet(session);
      resultSet.setPlan(new YTDBGremlinExecutionPlan((TraversalExplanation) eval));
      var item = new ResultInternal(session);
      item.setProperty("executionPlan", ((TraversalExplanation) eval).prettyPrint());
      resultSet.add(item);
      return resultSet;
    } else {
      var resultSet = new InternalResultSet(session);
      var item = new ResultInternal(session);
      item.setProperty("value", this.transformer.toResult(session, eval));
      resultSet.add(item);
      return resultSet;
    }
  }

  @Override
  public Object executeFunction(
      CommandContext context, final String functionName, final Map<Object, Object> iArgs) {
    var session = context.getDatabaseSession();
    assert session != null;
    var f = session.getMetadata().getFunctionLibrary().getFunction(session, functionName);

    session.checkSecurity(Rule.ResourceGeneric.FUNCTION, Role.PERMISSION_READ, f.getName());
    var scriptManager = session.getSharedContext().getYouTrackDB().getScriptManager();

    final var scriptEngine = scriptManager.acquireDatabaseEngine(session, f.getLanguage());
    try {
      final var binding = scriptManager.bindContextVariables(
          scriptEngine,
          scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE),
          session,
          context,
          iArgs);

      try {
        final Object result;

        if (scriptEngine instanceof Invocable invocableEngine) {
          // INVOKE AS FUNCTION. PARAMS ARE PASSED BY POSITION
          Object[] args;
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
        return transformResult(session,
            scriptManager.handleResult(f.getLanguage(), result, scriptEngine, binding, session));

      } catch (ScriptException e) {
        throw BaseException.wrapException(
            new CommandScriptException(session.getDatabaseName(),
                "Error on execution of the script", functionName, e.getColumnNumber()),
            e, session);
      } catch (NoSuchMethodException e) {
        throw BaseException.wrapException(
            new CommandScriptException(session.getDatabaseName(),
                "Error on execution of the script", functionName, 0), e, session);
      } finally {
        scriptManager.unbind(scriptEngine, binding, context, iArgs);
      }
    } finally {
      scriptManager.releaseDatabaseEngine(f.getLanguage(), session.getDatabaseName(), scriptEngine);
    }
  }

  private static boolean isGroovyException(Throwable throwable) {
    return switch (throwable) {
      case null -> false;
      case MultipleCompilationErrorsException ignored -> true;
      case MissingPropertyException ignored -> true;
      default -> isGroovyException(throwable.getCause());
    };
  }

  private ScriptEngine acquireGremlinEngine(final YTDBGraph graph) {
    final var engine =
        scriptManager.acquireDatabaseEngine(
            (DatabaseSessionEmbedded) graph.getUnderlyingDatabaseSession(), GREMLIN_GROOVY);
    var bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
    bindGraph(graph, bindings);
    return engine;
  }

  private void releaseGremlinEngine(String dbName, ScriptEngine engine) {
    scriptManager.releaseDatabaseEngine(GREMLIN_GROOVY, dbName, engine);
  }

  private static void bindGraph(YTDBGraph graph, Bindings bindings) {
    bindings.put("graph", graph);
    bindings.put("g", graph.traversal());
  }

  private static void unbindGraph(Bindings bindings) {
    bindings.put("graph", null);
    bindings.put("g", null);
  }

  public static void bindParameters(final ScriptEngine iEngine,
      final Map<Object, Object> iParameters) {
    if (iParameters != null && !iParameters.isEmpty())
    // Every call to the function is a execution itself. Therefore, it requires a fresh set of
    // input parameters.
    // Therefore, clone the parameters map trying to recycle previous instances
    {
      for (var param : iParameters.entrySet()) {
        final var paramName = param.getKey().toString().trim();
        iEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(paramName, param.getValue());
      }
    }
  }

  public static YTDBGraph acquireGraph(final DatabaseSessionEmbedded session) {
    return session.asGraph();
  }

  @Override
  public void bind(ScriptEngine engine, Bindings binding, DatabaseSession database) {
    var graph = acquireGraph((DatabaseSessionEmbedded) database);

    bindGraph(graph, binding);
  }

  @Override
  public void unbind(ScriptEngine engine, Bindings binding) {
    unbindGraph(binding);
  }

  @Override
  public Object handle(
      Object result, ScriptEngine engine, Bindings binding, DatabaseSession session) {
    return result;
  }
}
