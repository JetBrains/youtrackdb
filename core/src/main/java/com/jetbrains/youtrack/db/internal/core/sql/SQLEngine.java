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
package com.jetbrains.youtrack.db.internal.core.sql;

import static com.jetbrains.youtrack.db.internal.common.util.ClassLoaderHelper.lookupProviderWithYouTrackDBClassLoader;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.Collate;
import com.jetbrains.youtrack.db.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.common.util.Collections;
import com.jetbrains.youtrack.db.internal.core.collate.CollateFactory;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandExecutor;
import com.jetbrains.youtrack.db.internal.core.command.CommandExecutorAbstract;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilter;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLTarget;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunction;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionFactory;
import com.jetbrains.youtrack.db.internal.core.sql.method.SQLMethod;
import com.jetbrains.youtrack.db.internal.core.sql.method.SQLMethodFactory;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperator;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorFactory;
import com.jetbrains.youtrack.db.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLSecurityResourceSegment;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLServerStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.StatementCache;
import com.jetbrains.youtrack.db.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

public class SQLEngine {

  protected static final SQLEngine INSTANCE = new SQLEngine();
  private static volatile List<SQLFunctionFactory> FUNCTION_FACTORIES = null;
  private static List<SQLMethodFactory> METHOD_FACTORIES = null;
  private static List<CommandExecutorSQLFactory> EXECUTOR_FACTORIES = null;
  private static List<QueryOperatorFactory> OPERATOR_FACTORIES = null;
  private static List<CollateFactory> COLLATE_FACTORIES = null;
  private static QueryOperator[] SORTED_OPERATORS = null;
  private static final ClassLoader youTrackDbClassLoader = SQLEngine.class.getClassLoader();

  public static SQLStatement parse(String query, DatabaseSessionInternal db) {
    return StatementCache.get(query, db);
  }

  public static SQLServerStatement parseServerStatement(String query, YouTrackDBInternal db) {
    return StatementCache.getServerStatement(query, db);
  }

  public static List<SQLStatement> parseScript(String script, DatabaseSessionInternal db) {
    final InputStream is = new ByteArrayInputStream(script.getBytes());
    return parseScript(is, db);
  }

  public static List<SQLStatement> parseScript(InputStream script,
      DatabaseSessionInternal session) {
    try {
      final var osql = new YouTrackDBSql(script);
      var result = osql.parseScript();
      return result;
    } catch (ParseException e) {
      throw new CommandSQLParsingException(session.getDatabaseName(), e, "");
    }
  }

  public static SQLOrBlock parsePredicate(String predicate) throws CommandSQLParsingException {
    final InputStream is = new ByteArrayInputStream(predicate.getBytes());
    try {
      final var osql = new YouTrackDBSql(is);
      var result = osql.OrBlock();
      return result;
    } catch (ParseException e) {
      throw new CommandSQLParsingException(null, e, "");
    }
  }

  public static SQLSecurityResourceSegment parseSecurityResource(String exp) {
    final InputStream is = new ByteArrayInputStream(exp.getBytes());
    try {
      final var osql = new YouTrackDBSql(is);
      var result = osql.SecurityResourceSegment();
      return result;
    } catch (ParseException e) {
      throw new CommandSQLParsingException(null, e, "");
    }
  }

  /**
   * internal use only, to sort operators.
   */
  private static final class Pair {

    private final QueryOperator before;
    private final QueryOperator after;

    public Pair(final QueryOperator before, final QueryOperator after) {
      this.before = before;
      this.after = after;
    }

    @Override
    public boolean equals(final Object obj) {
      if (obj instanceof Pair that) {
        return before == that.before && after == that.after;
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
   * @return Iterator of all function factories
   */
  public static Iterator<SQLFunctionFactory> getFunctionFactories(
      DatabaseSessionInternal session) {
    if (FUNCTION_FACTORIES == null) {
      synchronized (INSTANCE) {
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
      }
    }
    return FUNCTION_FACTORIES.iterator();
  }

  public static Iterator<SQLMethodFactory> getMethodFactories() {
    if (METHOD_FACTORIES == null) {
      synchronized (INSTANCE) {
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
      }
    }
    return METHOD_FACTORIES.iterator();
  }

  /**
   * @return Iterator of all function factories
   */
  public static Iterator<CollateFactory> getCollateFactories() {
    if (COLLATE_FACTORIES == null) {
      synchronized (INSTANCE) {
        if (COLLATE_FACTORIES == null) {

          final var ite =
              lookupProviderWithYouTrackDBClassLoader(CollateFactory.class, youTrackDbClassLoader);

          final List<CollateFactory> factories = new ArrayList<>();
          while (ite.hasNext()) {
            factories.add(ite.next());
          }
          COLLATE_FACTORIES = java.util.Collections.unmodifiableList(factories);
        }
      }
    }
    return COLLATE_FACTORIES.iterator();
  }

  /**
   * @return Iterator of all operator factories
   */
  public static Iterator<QueryOperatorFactory> getOperatorFactories() {
    if (OPERATOR_FACTORIES == null) {
      synchronized (INSTANCE) {
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
      }
    }
    return OPERATOR_FACTORIES.iterator();
  }

  /**
   * @return Iterator of all command factories
   */
  public static Iterator<CommandExecutorSQLFactory> getCommandFactories() {
    if (EXECUTOR_FACTORIES == null) {
      synchronized (INSTANCE) {
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
      }
    }
    return EXECUTOR_FACTORIES.iterator();
  }

  /**
   * Iterates on all factories and append all function names.
   *
   * @return Set of all function names.
   */
  public static Set<String> getFunctionNames(DatabaseSessionInternal session) {
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

  public static Object foreachRecord(
      final CallableFunction<Object, Identifiable> iCallable,
      Object iCurrent,
      final CommandContext iContext) {
    if (iCurrent == null) {
      return null;
    }

    if (!CommandExecutorAbstract.checkInterruption(iContext)) {
      return null;
    }

    if (iCurrent instanceof Iterable && !(iCurrent instanceof Identifiable)) {
      iCurrent = ((Iterable) iCurrent).iterator();
    }
    if (MultiValue.isMultiValue(iCurrent) || iCurrent instanceof Iterator) {
      final var result = new MultiCollectionIterator<>();
      for (var o : MultiValue.getMultiValueIterable(iCurrent)) {
        if (iContext != null && !iContext.checkTimeout()) {
          return null;
        }

        if (MultiValue.isMultiValue(o) || o instanceof Iterator) {
          for (var inner : MultiValue.getMultiValueIterable(o)) {
            result.add(iCallable.call((Identifiable) inner));
          }
        } else {
          result.add(iCallable.call((Identifiable) o));
        }
      }
      return result;
    } else if (iCurrent instanceof Identifiable) {
      return iCallable.call((Identifiable) iCurrent);
    } else if (iCurrent instanceof Result result) {
      assert result.isEntity();
      return iCallable.call(result.asEntityOrNull());
    }

    return null;
  }

  public static SQLEngine getInstance() {
    return INSTANCE;
  }

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

  public QueryOperator[] getRecordOperators() {
    if (SORTED_OPERATORS == null) {
      synchronized (INSTANCE) {
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
                  case BEFORE:
                    pairs.add(new Pair(ca, cb));
                    break;
                  case AFTER:
                    pairs.add(new Pair(cb, ca));
                    break;
                }
                switch (cb.compare(ca)) {
                  case BEFORE:
                    pairs.add(new Pair(cb, ca));
                    break;
                  case AFTER:
                    pairs.add(new Pair(ca, cb));
                    break;
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
              for (final var itp = pairs.iterator(); itp.hasNext(); ) {
                if (itp.next().before == candidate) {
                  itp.remove();
                }
              }
              added = true;
            }
          } while (added);
          if (!operators.isEmpty()) {
            throw new DatabaseException("Invalid sorting. " + Collections.toString(pairs));
          }
          SORTED_OPERATORS = sorted.toArray(new QueryOperator[sorted.size()]);
        }
      }
    }
    return SORTED_OPERATORS;
  }

  public void registerFunction(final String iName, final SQLFunction iFunction) {
    DynamicSQLElementFactory.FUNCTIONS.put(iName.toLowerCase(Locale.ENGLISH), iFunction);
  }

  public void registerFunction(
      final String iName, final Class<? extends SQLFunction> iFunctionClass) {
    DynamicSQLElementFactory.FUNCTIONS.put(iName.toLowerCase(Locale.ENGLISH), iFunctionClass);
  }

  public SQLFunction getFunction(DatabaseSessionInternal session, String iFunctionName) {
    iFunctionName = iFunctionName.toLowerCase(Locale.ENGLISH);

    if (iFunctionName.equalsIgnoreCase("any") || iFunctionName.equalsIgnoreCase("all"))
    // SPECIAL FUNCTIONS
    {
      return null;
    }

    final var ite = getFunctionFactories(session);
    while (ite.hasNext()) {
      final var factory = ite.next();
      if (factory.hasFunction(iFunctionName, session)) {
        return factory.createFunction(iFunctionName, session);
      }
    }

    throw new CommandSQLParsingException(session.getDatabaseName(),
        "No function with name '"
            + iFunctionName
            + "', available names are : "
            + Collections.toString(getFunctionNames(session)));
  }

  public void unregisterFunction(String iName) {
    iName = iName.toLowerCase(Locale.ENGLISH);
    DynamicSQLElementFactory.FUNCTIONS.remove(iName);
  }

  public CommandExecutor getCommand(String candidate) {
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
        commandName = commandName.replaceAll(" +", " ");
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
      final String iText, @Nonnull final CommandContext iContext, final String iFilterKeyword) {
    assert iContext != null;
    return new SQLFilter(iText, iContext, iFilterKeyword);
  }

  public static SQLTarget parseTarget(final String iText, final CommandContext iContext) {
    return new SQLTarget(iText, iContext);
  }

  public Set<Identifiable> parseRIDTarget(
      final DatabaseSession database,
      String iTarget,
      final CommandContext iContext,
      Map<Object, Object> iArgs) {
    final Set<Identifiable> ids;
    if (!iTarget.isEmpty() && iTarget.charAt(0) == '(') {
      // SUB-QUERY

      final var result = ((DatabaseSessionInternal) database).query(
          iTarget.substring(1, iTarget.length() - 1),
          iArgs).toList();
      if (result == null || result.isEmpty()) {
        ids = java.util.Collections.emptySet();
      } else {
        ids = new HashSet<>((int) (result.size() * 1.3));
        for (var aResult : result) {
          ids.add(aResult.getIdentity());
        }
      }
    } else if (!iTarget.isEmpty() && iTarget.charAt(0) == '[') {
      // COLLECTION OF RIDS
      final var idsAsStrings = iTarget.substring(1, iTarget.length() - 1).split(",");
      ids = new HashSet<>((int) (idsAsStrings.length * 1.3));
      for (var idsAsString : idsAsStrings) {
        if (!idsAsString.isEmpty() && idsAsString.charAt(0) == '$') {
          var r = iContext.getVariable(idsAsString);
          if (r instanceof Identifiable) {
            ids.add((Identifiable) r);
          } else {
            MultiValue.add(ids, r);
          }
        } else {
          ids.add(new RecordId(idsAsString));
        }
      }
    } else {
      // SINGLE RID
      if (!iTarget.isEmpty() && iTarget.charAt(0) == '$') {
        var r = iContext.getVariable(iTarget);
        if (r instanceof Identifiable) {
          ids = java.util.Collections.singleton((Identifiable) r);
        } else {
          ids =
              (Set<Identifiable>)
                  MultiValue.add(new HashSet<Identifiable>(MultiValue.getSize(r)), r);
        }

      } else {
        ids = java.util.Collections.singleton(new RecordId(iTarget));
      }
    }
    return ids;
  }
}
