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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorClass;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorClassDescendentOrder;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorClusters;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilter;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLPredicate;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLTarget;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionRuntime;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorEquals;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorNotEquals;
import com.jetbrains.youtrack.db.internal.core.sql.query.LegacyResultSet;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLAsynchQuery;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes a TRAVERSE crossing records. Returns a List<Identifiable> containing all the traversed
 * records that match the WHERE condition.
 *
 * <p>SYNTAX: <code>TRAVERSE <field>* FROM <target> WHERE <condition></code>
 *
 * <p>In the command context you've access to the variable $depth containing the depth level from
 * the root node. This is useful to limit the traverse up to a level. For example to consider from
 * the first depth level (0 is root node) to the third use: <code> TRAVERSE children FROM #5:23
 * WHERE $depth BETWEEN 1 AND 3</code>. To filter traversed records use it combined with a SELECT
 * statement:
 *
 * <p><code>
 * SELECT FROM (TRAVERSE children FROM #5:23 WHERE $depth BETWEEN 1 AND 3) WHERE city.name = 'Rome'
 * </code>
 */
@SuppressWarnings("unchecked")
public abstract class CommandExecutorSQLResultsetAbstract extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest,
    IterableRecordSource {

  protected static final String KEYWORD_FROM_2FIND = " " + KEYWORD_FROM + " ";
  protected static final String KEYWORD_LET_2FIND = " " + KEYWORD_LET + " ";

  protected SQLAsynchQuery<EntityImpl> request;
  protected SQLTarget parsedTarget;
  protected SQLFilter compiledFilter;
  protected Map<String, Object> let = null;
  protected Iterator<? extends Identifiable> target;
  protected Iterable<Identifiable> tempResult;
  protected int resultCount;
  protected AtomicInteger serialTempRID = new AtomicInteger(0);
  protected int skip = 0;
  protected boolean lazyIteration = true;

  private static final class IndexValuesIterator implements Iterator<Identifiable> {

    private final Iterator<RID> indexValuesIterator;

    private IndexValuesIterator(DatabaseSessionInternal db, String indexName, boolean ascOrder) {
      if (ascOrder) {
        indexValuesIterator =
            db
                .getMetadata()
                .getIndexManagerInternal()
                .getIndex(db, indexName)
                .getInternal()
                .stream(db)
                .map((pair) -> pair.second)
                .iterator();
      } else {
        indexValuesIterator =
            db
                .getMetadata()
                .getIndexManagerInternal()
                .getIndex(db, indexName)
                .getInternal()
                .descStream(db)
                .map((pair) -> pair.second)
                .iterator();
      }
    }

    @Override
    public boolean hasNext() {
      return indexValuesIterator.hasNext();
    }

    @Override
    public Identifiable next() {
      return indexValuesIterator.next();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Compile the filter conditions only the first time.
   */
  public CommandExecutorSQLResultsetAbstract parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;

    init(session, textRequest);

    if (iRequest instanceof SQLSynchQuery) {
      request = (SQLSynchQuery<EntityImpl>) iRequest;
    } else {
      if (iRequest instanceof SQLAsynchQuery) {
        request = (SQLAsynchQuery<EntityImpl>) iRequest;
      } else {
        // BUILD A QUERY OBJECT FROM THE COMMAND REQUEST
        request = new SQLSynchQuery<EntityImpl>(textRequest.getText());
        if (textRequest.getResultListener() != null) {
          request.setResultListener(textRequest.getResultListener());
        }
      }
    }
    return this;
  }

  @Override
  public boolean isIdempotent() {
    return true;
  }

  /**
   * Assign the right TARGET if found.
   *
   * @param session
   * @param iArgs Parameters to bind
   * @return true if the target has been recognized, otherwise false
   */
  protected boolean assignTarget(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    parameters = iArgs;
    if (parsedTarget == null) {
      return true;
    }

    if (iArgs != null && !iArgs.isEmpty() && compiledFilter != null) {
      compiledFilter.bindParameters(iArgs);
    }

    if (target == null) {
      if (parsedTarget.getTargetClasses() != null) {
        searchInClasses(session);
      } else {
        if (parsedTarget.getTargetIndexValues() != null) {
          target =
              new IndexValuesIterator(session,
                  parsedTarget.getTargetIndexValues(), parsedTarget.isTargetIndexValuesAsc());
        } else {
          if (parsedTarget.getTargetClusters() != null) {
            searchInClusters(session);
          } else {
            if (parsedTarget.getTargetRecords() != null) {
              if (!lazyIteration && parsedTarget.getTargetQuery() != null) {
                // EXECUTE THE QUERY TO ALLOW DISTRIB EXECUTION
                target =
                    ((Iterable<? extends Identifiable>)
                        session
                            .command(new CommandSQL(parsedTarget.getTargetQuery()))
                            .execute(session, iArgs))
                        .iterator();
              } else {
                if (parsedTarget.getTargetRecords() instanceof IterableRecordSource) {
                  target =
                      ((IterableRecordSource) parsedTarget.getTargetRecords()).iterator(
                          session, iArgs);
                } else {
                  target = parsedTarget.getTargetRecords().iterator();
                }
              }
            } else {
              if (parsedTarget.getTargetVariable() != null) {
                final var var = getContext().getVariable(parsedTarget.getTargetVariable());
                if (var == null) {
                  target = Collections.EMPTY_LIST.iterator();
                  return true;
                } else {
                  if (var instanceof Identifiable) {
                    final var list = new ArrayList<Identifiable>();
                    list.add((Identifiable) var);
                    target = list.iterator();
                  } else {
                    if (var instanceof Iterable<?>) {
                      target = ((Iterable<? extends Identifiable>) var).iterator();
                    }
                  }
                }
              } else {
                return false;
              }
            }
          }
        }
      }
    }

    return true;
  }

  protected Object getResultInstance() {
    if (request instanceof SQLSynchQuery) {
      return ((SQLSynchQuery<EntityImpl>) request).getResult();
    }

    return request.getResultListener().getResult();
  }

  protected Object getResult(DatabaseSessionInternal session) {
    try {
      if (tempResult != null) {
        var fetched = 0;

        for (Object d : tempResult) {
          if (d != null) {
            if (!(d instanceof Identifiable))
            // NON-DOCUMENT AS RESULT, COMES FROM EXPAND? CREATE A DOCUMENT AT THE FLY
            {
              d = new EntityImpl(session).field("value", d);
            } else {
              d = ((Identifiable) d).getRecord(session);
            }

            if (limit > -1 && fetched >= limit) {
              break;
            }

            if (!pushResult(session, d)) {
              break;
            }

            ++fetched;
          }
        }
      }

      return getResultInstance();
    } finally {
      request.getResultListener().end(session);
    }
  }

  protected boolean pushResult(DatabaseSessionInternal session, Object rec) {
    if (rec instanceof RecordAbstract record) {
      if (session != null) {
        var cached = session.getLocalCache().findRecord(record.getIdentity());
        if (cached != record) {
          if (cached != null) {
            cached.fromStream(record.toStream());
            cached.setVersion(record.getVersion());
            cached.setIdentity(record.getIdentity());
            rec = cached;
          } else {
            session.getLocalCache().updateRecord(record, session);
          }
        }
      }
    }

    return request.getResultListener().result(session, rec);
  }

  protected boolean handleResult(final Identifiable iRecord, final CommandContext iContext) {
    if (iRecord != null) {
      resultCount++;

      var identifiable =
          iRecord instanceof DBRecord ? ((DBRecord) iRecord) : iRecord.getIdentity();

      // CALL THE LISTENER NOW
      if (identifiable != null && request.getResultListener() != null) {
        final var result = pushResult(iContext.getDatabaseSession(), identifiable);
        if (!result) {
          return false;
        }
      }

      // BREAK THE EXECUTION
      return limit <= -1 || resultCount < limit;
    }
    return true;
  }

  protected void parseLet(DatabaseSessionInternal session) {
    let = new LinkedHashMap<String, Object>();

    var stop = false;
    while (!stop) {
      // PARSE THE KEY
      final var letName = parserNextWord(false);

      parserOptionalKeyword(session.getDatabaseName(), "=");

      parserNextWord(false, " =><,\r\n", true);

      // PARSE THE VALUE
      var letValueAsString = parserGetLastWord();
      final Object letValue;

      // TRY TO PARSE AS FUNCTION
      final Object func = SQLHelper.getFunction(session, parsedTarget, letValueAsString);
      if (func != null) {
        letValue = func;
      } else {
        if (letValueAsString.startsWith("(")) {
          letValue =
              new SQLSynchQuery<Object>(
                  letValueAsString.substring(1, letValueAsString.length() - 1));
        } else {
          letValue = letValueAsString;
        }
      }

      let.put(letName, letValue);
      stop = parserGetLastSeparator() == ' ';
    }
  }

  /**
   * Parses the limit keyword if found.
   *
   * @param w
   * @return the limit found as integer, or -1 if no limit is found. -1 means no limits.
   * @throws CommandSQLParsingException if no valid limit has been found
   */
  protected int parseLimit(final String w) throws CommandSQLParsingException {
    if (!w.equals(KEYWORD_LIMIT)) {
      return -1;
    }

    final var word = parserNextWord(true);

    try {
      limit = Integer.parseInt(word);
    } catch (NumberFormatException ignore) {
      throwParsingException(null,
          "Invalid LIMIT value setted to '"
              + word
              + "' but it should be a valid integer. Example: LIMIT 10");
    }

    if (limit == 0) {
      throwParsingException(null,
          "Invalid LIMIT value setted to ZERO. Use -1 to ignore the limit or use a positive number."
              + " Example: LIMIT 10");
    }

    return limit;
  }

  /**
   * Parses the skip keyword if found.
   *
   * @param w
   * @return the skip found as integer, or -1 if no skip is found. -1 means no skip.
   * @throws CommandSQLParsingException if no valid skip has been found
   */
  protected int parseSkip(final String w) throws CommandSQLParsingException {
    if (!w.equals(KEYWORD_SKIP) && !w.equals(KEYWORD_OFFSET)) {
      return -1;
    }

    final var word = parserNextWord(true);

    try {
      skip = Integer.parseInt(word);

    } catch (NumberFormatException ignore) {
      throwParsingException(null,
          "Invalid SKIP value setted to '"
              + word
              + "' but it should be a valid positive integer. Example: SKIP 10");
    }

    if (skip < 0) {
      throwParsingException(null,
          "Invalid SKIP value setted to the negative number '"
              + word
              + "'. Only positive numbers are valid. Example: SKIP 10");
    }

    return skip;
  }

  protected boolean filter(final DBRecord iRecord, final CommandContext iContext) {
    if (iRecord instanceof EntityImpl recordSchemaAware) {
      // CHECK THE TARGET CLASS
      var targetClasses = parsedTarget.getTargetClasses();
      // check only classes that specified in query will go to result set
      var session = iContext.getDatabaseSession();
      if ((targetClasses != null) && (!targetClasses.isEmpty())) {
        for (var targetClass : targetClasses.keySet()) {
          SchemaImmutableClass result = null;
          result = recordSchemaAware.getImmutableSchemaClass(session);
          if (!session
              .getMetadata()
              .getImmutableSchemaSnapshot()
              .getClass(targetClass)
              .isSuperClassOf(session,
                  result)) {
            return false;
          }
        }
        iContext.updateMetric("documentAnalyzedCompatibleClass", +1);
      }
    }

    return evaluateRecord(iRecord, iContext);
  }

  protected boolean evaluateRecord(final DBRecord iRecord, final CommandContext iContext) {
    iContext.setVariable("current", iRecord);
    iContext.updateMetric("evaluated", +1);

    assignLetClauses(iContext.getDatabaseSession(), iRecord);
    if (compiledFilter == null) {
      return true;
    }

    Boolean evaluate = null;
    if (iRecord instanceof Entity entity) {
      evaluate = (Boolean) compiledFilter.evaluate(entity, null, iContext);
    }

    return evaluate != null && evaluate;
  }

  protected void assignLetClauses(DatabaseSessionInternal session, final DBRecord iRecord) {
    if (let != null && !let.isEmpty()) {
      // BIND CONTEXT VARIABLES
      for (var entry : let.entrySet()) {
        var varName = entry.getKey();
        if (!varName.isEmpty() && varName.charAt(0) == '$') {
          varName = varName.substring(1);
        }

        final var letValue = entry.getValue();

        Object varValue;
        if (letValue instanceof SQLSynchQuery<?>) {
          final var subQuery = (SQLSynchQuery<Object>) letValue;
          subQuery.reset();
          subQuery.resetPagination();
          subQuery.getContext().setParent(context);
          subQuery.getContext().setVariable("parentQuery", this);
          subQuery.getContext().setVariable("current", iRecord);
          varValue = session.query(subQuery);
          if (varValue instanceof LegacyResultSet) {
            varValue = ((LegacyResultSet) varValue).copy();
          }

        } else {
          if (letValue instanceof SQLFunctionRuntime f) {
            if (iRecord instanceof Entity entity) {
              if (f.getFunction().aggregateResults()) {
                f.execute(iRecord, entity, null, context);
                varValue = f.getFunction().getResult();

              } else {
                varValue = f.execute(iRecord, entity, null, context);
              }
            } else {
              throw new DatabaseException("Cannot evaluate function on non-entity record");
            }
          } else {
            if (letValue instanceof String) {
              var pred = new SQLPredicate(getContext(), ((String) letValue).trim());
              if (iRecord instanceof Entity entity) {
                varValue = pred.evaluate(entity, (EntityImpl) iRecord, context);
              } else {
                throw new DatabaseException("Cannot evaluate predicate on non-entity record");
              }
            } else {
              varValue = letValue;
            }
          }
        }

        context.setVariable(varName, varValue);
      }
    }
  }

  protected void searchInClasses(DatabaseSessionInternal db) {
    searchInClasses(true, db);
  }

  protected void searchInClasses(final boolean iAscendentOrder, DatabaseSessionInternal db) {
    final var cls = parsedTarget.getTargetClasses().keySet().iterator().next();
    target =
        searchInClasses(db,
            db.getMetadata().getImmutableSchemaSnapshot().getClass(cls),
            true, iAscendentOrder);
  }

  protected Iterator<? extends Identifiable> searchInClasses(
      DatabaseSessionInternal db, final SchemaClass iCls, final boolean iPolymorphic,
      final boolean iAscendentOrder) {
    db.checkSecurity(
        Rule.ResourceGeneric.CLASS,
        Role.PERMISSION_READ,
        iCls.getName(db).toLowerCase(Locale.ENGLISH));

    final var range = getRange(db);
    if (iAscendentOrder) {
      return new RecordIteratorClass<>(db, iCls.getName(db), iPolymorphic, false)
          .setRange(range[0], range[1]);
    } else {
      return new RecordIteratorClassDescendentOrder<>(
          db, db, iCls.getName(db), iPolymorphic)
          .setRange(range[0], range[1]);
    }
  }

  protected boolean isUseCache() {
    return request.isUseCache();
  }

  protected void searchInClusters(DatabaseSessionInternal session) {
    final var clusterIds = new IntOpenHashSet();
    for (var clusterName : parsedTarget.getTargetClusters().keySet()) {
      if (clusterName == null || clusterName.isEmpty()) {
        throw new CommandExecutionException(session,
            "No cluster or schema class selected in query");
      }

      session.checkSecurity(
          Rule.ResourceGeneric.CLUSTER,
          Role.PERMISSION_READ,
          clusterName.toLowerCase(Locale.ENGLISH));

      if (Character.isDigit(clusterName.charAt(0))) {
        // GET THE CLUSTER NUMBER
        for (var clusterId : StringSerializerHelper.splitIntArray(clusterName)) {
          if (clusterId == -1) {
            throw new CommandExecutionException(session, "Cluster '" + clusterName + "' not found");
          }

          clusterIds.add(clusterId);
        }
      } else {
        // GET THE CLUSTER NUMBER BY THE CLASS NAME
        final var clusterId = session.getClusterIdByName(clusterName.toLowerCase(Locale.ENGLISH));
        if (clusterId == -1) {
          throw new CommandExecutionException(session, "Cluster '" + clusterName + "' not found");
        }

        clusterIds.add(clusterId);
      }
    }

    final var range = getRange(session);
    target =
        new RecordIteratorClusters<>(session, clusterIds.toIntArray())
            .setRange(range[0], range[1]);
  }

  protected void applyLimitAndSkip() {
    if (tempResult != null && (limit > 0 || skip > 0)) {
      final List<Identifiable> newList = new ArrayList<Identifiable>();

      // APPLY LIMIT
      if (tempResult instanceof List<?>) {
        final var t = (List<Identifiable>) tempResult;
        final var start = Math.min(skip, t.size());

        var tot = t.size();
        if (limit > -1) {
          tot = Math.min(limit + start, tot);
        }
        for (var i = start; i < tot; ++i) {
          newList.add(t.get(i));
        }

        t.clear();
        tempResult = newList;
      }
    }
  }

  /**
   * Optimizes the condition tree.
   */
  protected void optimize(DatabaseSession session) {
    if (compiledFilter != null) {
      optimizeBranch(session, null, compiledFilter.getRootCondition());
    }
  }

  /**
   * Check function arguments and pre calculate it if possible
   *
   * @param function
   * @return optimized function, same function if no change
   */
  protected Object optimizeFunction(SQLFunctionRuntime function) {
    // boolean precalculate = true;
    // for (int i = 0; i < function.configuredParameters.length; ++i) {
    // if (function.configuredParameters[i] instanceof SQLFilterItemField) {
    // precalculate = false;
    // } else if (function.configuredParameters[i] instanceof SQLFunctionRuntime) {
    // final Object res = optimizeFunction((SQLFunctionRuntime) function.configuredParameters[i]);
    // function.configuredParameters[i] = res;
    // if (res instanceof SQLFunctionRuntime || res instanceof SQLFilterItemField) {
    // // function might have been optimized but result is still not static
    // precalculate = false;
    // }
    // }
    // }
    //
    // if (precalculate) {
    // // all fields are static, we can calculate it only once.
    // return function.execute(null, null, null); // we can pass nulls here, they wont be used
    // } else {
    return function;
    // }
  }

  protected void optimizeBranch(
      DatabaseSession session, final SQLFilterCondition iParentCondition,
      SQLFilterCondition iCondition) {
    if (iCondition == null) {
      return;
    }

    var left = iCondition.getLeft();

    if (left instanceof SQLFilterCondition) {
      // ANALYSE LEFT RECURSIVELY
      optimizeBranch(session, iCondition, (SQLFilterCondition) left);
    } else {
      if (left instanceof SQLFunctionRuntime) {
        left = optimizeFunction((SQLFunctionRuntime) left);
        iCondition.setLeft(left);
      }
    }

    var right = iCondition.getRight();

    if (right instanceof SQLFilterCondition) {
      // ANALYSE RIGHT RECURSIVELY
      optimizeBranch(session, iCondition, (SQLFilterCondition) right);
    } else {
      if (right instanceof SQLFunctionRuntime) {
        right = optimizeFunction((SQLFunctionRuntime) right);
        iCondition.setRight(right);
      }
    }

    final var oper = iCondition.getOperator();

    Object result = null;

    if (left instanceof SQLFilterItemField && right instanceof SQLFilterItemField) {
      if (((SQLFilterItemField) left).getRoot(session)
          .equals(((SQLFilterItemField) right).getRoot(session))) {
        if (oper instanceof QueryOperatorEquals) {
          result = Boolean.TRUE;
        } else {
          if (oper instanceof QueryOperatorNotEquals) {
            result = Boolean.FALSE;
          }
        }
      }
    }

    if (result != null) {
      if (iParentCondition != null) {
        if (iCondition == iParentCondition.getLeft())
        // REPLACE LEFT
        {
          iCondition.setLeft(result);
        } else
        // REPLACE RIGHT
        {
          iCondition.setRight(result);
        }
      } else {
        // REPLACE ROOT CONDITION
        if (result instanceof Boolean && ((Boolean) result)) {
          compiledFilter.setRootCondition(null);
        }
      }
    }
  }

  protected RID[] getRange(DatabaseSession session) {
    final RID beginRange;
    final RID endRange;

    final var rootCondition =
        compiledFilter == null ? null : compiledFilter.getRootCondition();
    if (compiledFilter == null || rootCondition == null) {
      if (request instanceof SQLSynchQuery) {
        beginRange = ((SQLSynchQuery<EntityImpl>) request).getNextPageRID();
      } else {
        beginRange = null;
      }
      endRange = null;
    } else {
      final var conditionBeginRange = rootCondition.getBeginRidRange(session);
      final var conditionEndRange = rootCondition.getEndRidRange(session);
      final RID nextPageRid;

      if (request instanceof SQLSynchQuery) {
        nextPageRid = ((SQLSynchQuery<EntityImpl>) request).getNextPageRID();
      } else {
        nextPageRid = null;
      }

      if (conditionBeginRange != null && nextPageRid != null) {
        beginRange =
            conditionBeginRange.compareTo(nextPageRid) > 0 ? conditionBeginRange : nextPageRid;
      } else {
        if (conditionBeginRange != null) {
          beginRange = conditionBeginRange;
        } else {
          beginRange = nextPageRid;
        }
      }

      endRange = conditionEndRange;
    }

    return new RID[]{beginRange, endRange};
  }

  public Iterator<? extends Identifiable> getTarget() {
    return target;
  }

  public void setTarget(final Iterator<? extends Identifiable> target) {
    this.target = target;
  }

  public void setRequest(final SQLAsynchQuery<EntityImpl> request) {
    this.request = request;
  }

  public void setParsedTarget(final SQLTarget parsedTarget) {
    this.parsedTarget = parsedTarget;
  }

  public void setCompiledFilter(final SQLFilter compiledFilter) {
    this.compiledFilter = compiledFilter;
  }

  @Override
  public boolean isCacheable() {
    return true;
  }

}
