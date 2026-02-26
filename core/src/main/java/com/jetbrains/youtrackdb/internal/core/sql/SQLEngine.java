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
package com.jetbrains.youtrackdb.internal.core.sql;

import static com.jetbrains.youtrackdb.internal.common.util.ClassLoaderHelper.lookupProviderWithYouTrackDBClassLoader;

import com.jetbrains.youtrackdb.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.Collections;
import com.jetbrains.youtrackdb.internal.core.collate.CollateFactory;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandExecutor;
import com.jetbrains.youtrackdb.internal.core.command.CommandExecutorAbstract;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilter;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunction;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionFactory;
import com.jetbrains.youtrackdb.internal.core.sql.method.SQLMethod;
import com.jetbrains.youtrackdb.internal.core.sql.method.SQLMethodFactory;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperator;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorFactory;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSecurityResourceSegment;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLServerStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.StatementCache;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SQLEngine {

  protected static final ReentrantLock LOCK = new ReentrantLock();

  private static final Pattern REPLACE_PATTERN = Pattern.compile(" +");
  private static volatile List<SQLFunctionFactory> FUNCTION_FACTORIES = null;
  private static volatile List<SQLMethodFactory> METHOD_FACTORIES = null;
  private static volatile List<CommandExecutorSQLFactory> EXECUTOR_FACTORIES = null;
  private static volatile List<QueryOperatorFactory> OPERATOR_FACTORIES = null;
  private static volatile List<CollateFactory> COLLATE_FACTORIES = null;
  private static volatile QueryOperator[] SORTED_OPERATORS = null;
  private static final ClassLoader youTrackDbClassLoader = SQLEngine.class.getClassLoader();

  public static SQLStatement parse(String query, DatabaseSessionEmbedded db) {
    return StatementCache.get(query, db);
  }

  public static SQLServerStatement parseServerStatement(String query, YouTrackDBInternal db) {
    return StatementCache.getServerStatement(query, db);
  }


  public static List<SQLStatement> parseScript(String script, DatabaseSessionEmbedded db) {
    final InputStream is = new ByteArrayInputStream(script.getBytes());
    return parseScript(is, db);
  }

  public static List<SQLStatement> parseScript(InputStream script,
      DatabaseSessionEmbedded session) {
    try {
      final var osql = new YouTrackDBSql(script);
      return osql.parseScript();
    } catch (ParseException e) {
      throw new CommandSQLParsingException(session.getDatabaseName(), e, "");
    }
  }

  public static SQLOrBlock parsePredicate(String predicate) throws CommandSQLParsingException {
    final InputStream is = new ByteArrayInputStream(predicate.getBytes());
    try {
      final var osql = new YouTrackDBSql(is);
      return osql.OrBlock();
    } catch (ParseException e) {
      throw new CommandSQLParsingException(null, e, "");
    }
  }

  public static SQLSecurityResourceSegment parseSecurityResource(String exp) {
    final InputStream is = new ByteArrayInputStream(exp.getBytes());
    try {
      final var osql = new YouTrackDBSql(is);
      return osql.SecurityResourceSegment();
    } catch (ParseException e) {
      throw new CommandSQLParsingException(null, e, "");
    }
  }

  /**
   * internal use only, to sort operators.
   */
  private record Pair(QueryOperator before, QueryOperator after) {

    @Override
    public boolean equals(final Object obj) {
      if (obj instanceof Pair(QueryOperator before1, QueryOperator after1)) {
        return before == before1 && after == after1;
      }
      return false;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(before) + 31 * System.identityHashCode(after);
    }

    @Override
    public String toString() {
      return before + " > " + after;
    }
  }

  protected SQLEngine() {
  }

  public static void registerOperator(final QueryOperator iOperator) {
    DynamicSQLElementFactory.OPERATORS.add(iOperator);
    SORTED_OPERATORS = null; // clear cache
  }

  /**
   * Returns an iterator over all registered SQL function factories.
   *
   * @return Iterator of all function factories
   */
  public static Iterator<SQLFunctionFactory> getFunctionFactories(
      DatabaseSessionEmbedded session) {
    if (FUNCTION_FACTORIES == null) {
      LOCK.lock();

      try {
        if (FUNCTION_FACTORIES == null) {
          final var ite =
              lookupProviderWithYouTrackDBClassLoader(SQLFunctionFactory.class,
                  youTrackDbClassLoader);

          final List<SQLFunctionFactory> factories = new ArrayList<>();
          while (ite.hasNext()) {
            var factory = ite.next();
            try {
              factory.registerDefaultFunctions(session);
              factories.add(factory);
            } catch (Exception e) {
              LogManager.instance().warn(SQLEngine.class,
                  "Cannot register default functions for function factory " + factory, e);
            }
          }
          FUNCTION_FACTORIES = java.util.Collections.unmodifiableList(factories);
        }
      } finally {
        LOCK.unlock();
      }
    }
    return FUNCTION_FACTORIES.iterator();
  }

  public static Iterator<SQLMethodFactory> getMethodFactories() {
    if (METHOD_FACTORIES == null) {
      LOCK.lock();
      try {
        if (METHOD_FACTORIES == null) {
          final var ite =
              lookupProviderWithYouTrackDBClassLoader(SQLMethodFactory.class,
                  youTrackDbClassLoader);

          final List<SQLMethodFactory> factories = new ArrayList<>();
          while (ite.hasNext()) {
            factories.add(ite.next());
          }
          METHOD_FACTORIES = java.util.Collections.unmodifiableList(factories);
        }
      } finally {
        LOCK.unlock();
      }
    }
    return METHOD_FACTORIES.iterator();
  }

  /**
   * Returns an iterator over all registered collate factories.
   *
   * @return Iterator of all function factories
   */
  public static Iterator<CollateFactory> getCollateFactories() {
    if (COLLATE_FACTORIES == null) {
      LOCK.lock();
      try {
        if (COLLATE_FACTORIES == null) {

          final var ite =
              lookupProviderWithYouTrackDBClassLoader(CollateFactory.class, youTrackDbClassLoader);

          final List<CollateFactory> factories = new ArrayList<>();
          while (ite.hasNext()) {
            factories.add(ite.next());
          }
          COLLATE_FACTORIES = java.util.Collections.unmodifiableList(factories);
        }
      } finally {
        LOCK.unlock();
      }
    }
    return COLLATE_FACTORIES.iterator();
  }

  /**
   * Returns an iterator over all registered query operator factories.
   *
   * @return Iterator of all operator factories
   */
  public static Iterator<QueryOperatorFactory> getOperatorFactories() {
    if (OPERATOR_FACTORIES == null) {
      LOCK.lock();
      try {
        if (OPERATOR_FACTORIES == null) {

          final var ite =
              lookupProviderWithYouTrackDBClassLoader(QueryOperatorFactory.class,
                  youTrackDbClassLoader);

          final List<QueryOperatorFactory> factories = new ArrayList<>();
          while (ite.hasNext()) {
            factories.add(ite.next());
          }
          OPERATOR_FACTORIES = java.util.Collections.unmodifiableList(factories);
        }
      } finally {
        LOCK.unlock();
      }
    }
    return OPERATOR_FACTORIES.iterator();
  }

  /**
   * Returns an iterator over all registered command executor factories.
   *
   * @return Iterator of all command factories
   */
  public static Iterator<CommandExecutorSQLFactory> getCommandFactories() {
    if (EXECUTOR_FACTORIES == null) {
      LOCK.lock();
      try {
        if (EXECUTOR_FACTORIES == null) {

          final var ite =
              lookupProviderWithYouTrackDBClassLoader(
                  CommandExecutorSQLFactory.class, youTrackDbClassLoader);
          final List<CommandExecutorSQLFactory> factories =
              new ArrayList<>();
          while (ite.hasNext()) {
            try {
              factories.add(ite.next());
            } catch (Exception e) {
              LogManager.instance()
                  .warn(
                      SQLEngine.class,
                      "Cannot load CommandExecutorSQLFactory instance from service registry",
                      e);
            }
          }

          EXECUTOR_FACTORIES = java.util.Collections.unmodifiableList(factories);
        }
      } finally {
        LOCK.unlock();
      }
    }
    return EXECUTOR_FACTORIES.iterator();
  }

  /**
   * Iterates on all factories and append all function names.
   *
   * @return Set of all function names.
   */
  public static Set<String> getFunctionNames(DatabaseSessionEmbedded session) {
    final Set<String> types = new HashSet<>();
    final var ite = getFunctionFactories(session);
    while (ite.hasNext()) {
      types.addAll(ite.next().getFunctionNames(session));
    }
    return types;
  }

  public static Set<String> getMethodNames() {
    final Set<String> types = new HashSet<>();
    final var ite = getMethodFactories();
    while (ite.hasNext()) {
      types.addAll(ite.next().getMethodNames());
    }
    return types;
  }

  /**
   * Iterates on all factories and append all collate names.
   *
   * @return Set of all colate names.
   */
  public static Set<String> getCollateNames() {
    final Set<String> types = new HashSet<>();
    final var ite = getCollateFactories();
    while (ite.hasNext()) {
      types.addAll(ite.next().getNames());
    }
    return types;
  }

  /**
   * Iterates on all factories and append all command names.
   *
   * @return Set of all command names.
   */
  public static Set<String> getCommandNames() {
    final Set<String> types = new HashSet<>();
    final var ite = getCommandFactories();
    while (ite.hasNext()) {
      types.addAll(ite.next().getCommandNames());
    }
    return types;
  }

  /**
   * Scans for factory plug-ins on the application class path. This method is needed because the
   * application class path can theoretically change, or additional plug-ins may become available.
   * Rather than re-scanning the classpath on every invocation of the API, the class path is scanned
   * automatically only on the first invocation. Clients can call this method to prompt a re-scan.
   * Thus this method need only be invoked by sophisticated applications which dynamically make new
   * plug-ins available at runtime.
   */
  public static void scanForPlugins() {
    // clear cache, will cause a rescan on next getFunctionFactories call
    FUNCTION_FACTORIES = null;
  }

  @Nullable
  public static Object foreachRecord(
      final Function<Object, Object> function,
      Object current,
      final CommandContext context) {
    if (current == null) {
      return null;
    }

    if (!CommandExecutorAbstract.checkInterruption(context)) {
      return null;
    }

    if (current instanceof Iterable && !(current instanceof Identifiable)) {
      current = ((Iterable<?>) current).iterator();
    }
    if (MultiValue.isMultiValue(current) || current instanceof Iterator) {
      final var result = new MultiCollectionIterator<>();
      for (var o : MultiValue.getMultiValueIterable(current)) {
        if (context != null && !context.checkTimeout()) {
          return null;
        }

        if (MultiValue.isMultiValue(o) || o instanceof Iterator) {
          for (var inner : MultiValue.getMultiValueIterable(o)) {
            result.add(function.apply(inner));
          }
        } else {
          result.add(function.apply(o));
        }
      }
      return result;
    } else if (current instanceof Identifiable) {
      return function.apply(current);
    } else if (current instanceof Result result) {
      if (result.isRelation()) {
        return function.apply(result.asRelation());
      } else if (result.isEntity()) {
        return function.apply(result.asEntity());
      }
      return null;
    }

    return null;
  }

  @Nullable
  public static Collate getCollate(final String name) {
    for (var iter = getCollateFactories(); iter.hasNext(); ) {
      var f = iter.next();
      final var c = f.getCollate(name);
      if (c != null) {
        return c;
      }
    }
    return null;
  }

  @Nullable
  public static SQLMethod getMethod(String iMethodName) {
    iMethodName = iMethodName.toLowerCase(Locale.ENGLISH);

    final var ite = getMethodFactories();
    while (ite.hasNext()) {
      final var factory = ite.next();
      if (factory.hasMethod(iMethodName)) {
        return factory.createMethod(iMethodName);
      }
    }

    return null;
  }

  public static QueryOperator[] getRecordOperators() {
    if (SORTED_OPERATORS == null) {
      LOCK.lock();
      try {
        if (SORTED_OPERATORS == null) {
          // sort operators, will happen only very few times since we cache the
          // result
          final var ite = getOperatorFactories();
          final List<QueryOperator> operators = new ArrayList<>();
          while (ite.hasNext()) {
            final var factory = ite.next();
            operators.addAll(factory.getOperators());
          }

          final List<QueryOperator> sorted = new ArrayList<>();
          final Set<Pair> pairs = new LinkedHashSet<>();
          for (final var ca : operators) {
            for (final var cb : operators) {
              if (ca != cb) {
                switch (ca.compare(cb)) {
                  case BEFORE -> pairs.add(new Pair(ca, cb));
                  case AFTER -> pairs.add(new Pair(cb, ca));
                }
                switch (cb.compare(ca)) {
                  case BEFORE -> pairs.add(new Pair(cb, ca));
                  case AFTER -> pairs.add(new Pair(ca, cb));
                }
              }
            }
          }
          boolean added;
          do {
            added = false;
            scan:
            for (final var it = operators.iterator(); it.hasNext(); ) {
              final var candidate = it.next();
              for (final var pair : pairs) {
                if (pair.after == candidate) {
                  continue scan;
                }
              }
              sorted.add(candidate);
              it.remove();
              pairs.removeIf(pair -> pair.before == candidate);
              added = true;
            }
          } while (added);
          if (!operators.isEmpty()) {
            throw new DatabaseException("Invalid sorting. " + Collections.toString(pairs));
          }
          SORTED_OPERATORS = sorted.toArray(new QueryOperator[0]);
        }
      } finally {
        LOCK.unlock();
      }
    }
    return SORTED_OPERATORS;
  }

  public static void registerFunction(final String iName, final SQLFunction iFunction) {
    DynamicSQLElementFactory.FUNCTIONS.put(iName.toLowerCase(Locale.ENGLISH), iFunction);
  }

  @Nullable
  public static SQLFunction getFunction(DatabaseSessionEmbedded session, String functionName) {
    var functionInstance = getFunctionOrNull(session, functionName);

    if (functionInstance != null) {
      return functionInstance;
    }

    throw new CommandSQLParsingException(session.getDatabaseName(),
        "No function with name '"
            + functionName
            + "', available names are : "
            + Collections.toString(getFunctionNames(session)));
  }

  @Nullable
  public static SQLFunction getFunctionOrNull(DatabaseSessionEmbedded session,
      String iFunctionName) {
    var functionName = iFunctionName.toLowerCase(Locale.ENGLISH);

    if (functionName.equalsIgnoreCase("any") || functionName.equalsIgnoreCase("all"))
    // SPECIAL FUNCTIONS
    {
      return null;
    }

    final var ite = getFunctionFactories(session);
    while (ite.hasNext()) {
      final var factory = ite.next();
      if (factory.hasFunction(functionName, session)) {
        return factory.createFunction(functionName, session);
      }
    }

    return null;
  }

  public static void unregisterFunction(String iName) {
    iName = iName.toLowerCase(Locale.ENGLISH);
    DynamicSQLElementFactory.FUNCTIONS.remove(iName);
  }

  @Nullable
  public static CommandExecutor getCommand(String candidate) {
    candidate = candidate.trim();
    final var names = getCommandNames();
    var commandName = candidate;
    var found = names.contains(commandName);
    var pos = -1;
    while (!found) {
      pos =
          StringSerializerHelper.getLowerIndexOf(
              candidate, pos + 1, " ", "\n", "\r", "\t", "(", "[");
      if (pos > -1) {
        commandName = candidate.substring(0, pos);
        // remove double spaces
        commandName = REPLACE_PATTERN.matcher(commandName).replaceAll(" ");
        found = names.contains(commandName);
      } else {
        break;
      }
    }

    if (found) {
      final var ite = getCommandFactories();
      while (ite.hasNext()) {
        final var factory = ite.next();
        if (factory.getCommandNames().contains(commandName)) {
          return factory.createCommand(commandName);
        }
      }
    }

    return null;
  }

  public static SQLFilter parseCondition(
      final String iText, @Nonnull final CommandContext iContext) {
    return new SQLFilter(iText, iContext);
  }

}
