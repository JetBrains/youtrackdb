package com.jetbrains.youtrack.db.internal.core.command.script;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandScriptException;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.common.concur.resource.ResourcePool;
import com.jetbrains.youtrack.db.internal.common.concur.resource.ResourcePoolListener;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.ScriptTransformer;
import com.jetbrains.youtrack.db.internal.core.command.traverse.AbstractScriptExecutor;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.script.ScriptException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;

/**
 *
 */
public class PolyglotScriptExecutor extends AbstractScriptExecutor
    implements ResourcePoolListener<DatabaseSessionInternal, Context> {

  private final ScriptTransformer transformer;
  protected ConcurrentHashMap<String, ResourcePool<DatabaseSessionInternal, Context>>
      contextPools =
      new ConcurrentHashMap<String, ResourcePool<DatabaseSessionInternal, Context>>();

  public PolyglotScriptExecutor(final String language, ScriptTransformer scriptTransformer) {
    super("javascript".equalsIgnoreCase(language) ? "js" : language);
    this.transformer = scriptTransformer;
  }

  private Context resolveContext(DatabaseSessionInternal database) {
    var pool =
        contextPools.computeIfAbsent(
            database.getDatabaseName(),
            (k) -> {
              return new ResourcePool<DatabaseSessionInternal, Context>(
                  database.getConfiguration().getValueAsInteger(GlobalConfiguration.SCRIPT_POOL),
                  PolyglotScriptExecutor.this);
            });
    return pool.getResource(database, 0);
  }

  private void returnContext(Context context, DatabaseSessionInternal database) {
    var pool = contextPools.get(database.getDatabaseName());
    if (pool != null) {
      pool.returnResource(context);
    }
  }

  @Override
  public Context createNewResource(DatabaseSessionInternal database, Object... iAdditionalArgs) {
    final var scriptManager =
        database.getSharedContext().getYouTrackDB().getScriptManager();

    final var allowedPackaged = scriptManager.getAllowedPackages();

    var ctx =
        Context.newBuilder()
            .allowHostAccess(HostAccess.ALL)
            .allowNativeAccess(false)
            .allowCreateProcess(false)
            .allowCreateThread(false)
            .allowIO(false)
            .allowHostClassLoading(false)
            .allowHostClassLookup(
                s -> {
                  if (allowedPackaged.contains(s)) {
                    return true;
                  }

                  final var pos = s.lastIndexOf('.');
                  if (pos > -1) {
                    return allowedPackaged.contains(s.substring(0, pos) + ".*");
                  }
                  return false;
                })
            .build();

    var bindings = new PolyglotScriptBinding(ctx.getBindings(language));

    scriptManager.bindContextVariables(null, bindings, database, null, null);
    final var library =
        scriptManager.getLibrary(
            database, "js".equalsIgnoreCase(language) ? "javascript" : language);
    if (library != null) {
      ctx.eval(language, library);
    }
    scriptManager.unbind(null, bindings, null, null);
    return ctx;
  }

  @Override
  public boolean reuseResource(
      DatabaseSessionInternal iKey, Object[] iAdditionalArgs, Context iValue) {
    return true;
  }

  @Override
  public ResultSet execute(DatabaseSessionInternal database, String script, Object... params) {
    preExecute(database, script, params);

    var par = new Int2ObjectOpenHashMap<Object>();

    for (var i = 0; i < params.length; i++) {
      par.put(i, params[i]);
    }
    return execute(database, script, par);
  }

  @Override
  public ResultSet execute(DatabaseSessionInternal database, String script, Map params) {

    preExecute(database, script, params);

    final var scriptManager =
        database.getSharedContext().getYouTrackDB().getScriptManager();

    var ctx = resolveContext(database);
    try {
      var bindings = new PolyglotScriptBinding(ctx.getBindings(language));

      scriptManager.bindContextVariables(null, bindings, database, null, params);

      var result = ctx.eval(language, script);
      var transformedResult = transformer.toResultSet(database, result);
      scriptManager.unbind(null, bindings, null, null);
      return transformedResult;

    } catch (PolyglotException e) {
      final var col = e.getSourceLocation() != null ? e.getSourceLocation().getStartColumn() : 0;
      throw BaseException.wrapException(
          new CommandScriptException(database.getDatabaseName(), "Error on execution of the script",
              script,
              col),
          new ScriptException(e), database.getDatabaseName());
    } finally {
      returnContext(ctx, database);
    }
  }

  @Override
  public Object executeFunction(
      CommandContext context, final String functionName, final Map<Object, Object> iArgs) {
    var sessionInternal = context.getDatabaseSession();
    final var f = sessionInternal.getMetadata().getFunctionLibrary()
        .getFunction(sessionInternal, functionName);

    sessionInternal.checkSecurity(Rule.ResourceGeneric.FUNCTION, Role.PERMISSION_READ,
        f.getName());

    final var scriptManager =
        sessionInternal.getSharedContext().getYouTrackDB().getScriptManager();

    var ctx = resolveContext(sessionInternal);
    try {

      var bindings = new PolyglotScriptBinding(ctx.getBindings(language));

      scriptManager.bindContextVariables(null, bindings, sessionInternal, null, iArgs);
      final var args = iArgs == null ? null : iArgs.keySet().toArray();

      var result = ctx.eval(language, scriptManager.getFunctionInvoke(sessionInternal, f, args));

      Object finalResult;
      if (result.isNull()) {
        finalResult = null;
      } else if (result.hasArrayElements()) {
        final List<Object> array = new ArrayList<>((int) result.getArraySize());
        for (var i = 0; i < result.getArraySize(); ++i) {
          array.add(new ResultInternal(result.getArrayElement(i).asHostObject()));
        }
        finalResult = array;
      } else if (result.isHostObject()) {
        finalResult = result.asHostObject();
      } else if (result.isString()) {
        finalResult = result.asString();
      } else if (result.isNumber()) {
        finalResult = result.asDouble();
      } else {
        finalResult = result;
      }
      scriptManager.unbind(null, bindings, null, null);
      return finalResult;
    } catch (PolyglotException e) {
      final var col = e.getSourceLocation() != null ? e.getSourceLocation().getStartColumn() : 0;
      throw BaseException.wrapException(
          new CommandScriptException(sessionInternal.getDatabaseName(),
              "Error on execution of the script",
              functionName, col),
          new ScriptException(e), sessionInternal.getDatabaseName());
    } finally {
      returnContext(ctx, sessionInternal);
    }
  }

  @Override
  public void close(String iDatabaseName) {
    var contextPool =
        contextPools.remove(iDatabaseName);
    if (contextPool != null) {
      for (var c : contextPool.getAllResources()) {
        c.close();
      }
      contextPool.close();
    }
  }

  @Override
  public void closeAll() {
    for (var d : contextPools.values()) {
      for (var c : d.getAllResources()) {
        c.close();
      }
      d.close();
    }
    contextPools.clear();
  }
}
