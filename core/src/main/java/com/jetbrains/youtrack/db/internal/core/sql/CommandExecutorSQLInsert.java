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
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.command.CommandResultListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.QueryParsingException;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLAsynchQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQL INSERT command.
 */
public class CommandExecutorSQLInsert extends CommandExecutorSQLSetAware
    implements CommandDistributedReplicateRequest, CommandResultListener {

  public static final String KEYWORD_INSERT = "INSERT";
  protected static final String KEYWORD_RETURN = "RETURN";
  private static final String KEYWORD_VALUES = "VALUES";
  private String className = null;
  private SchemaClass clazz = null;
  private String clusterName = null;
  private String indexName = null;
  private List<Map<String, Object>> newRecords;
  private SQLAsynchQuery<Identifiable> subQuery = null;
  private final AtomicLong saved = new AtomicLong(0);
  private Object returnExpression = null;
  private List<EntityImpl> queryResult = null;
  private boolean unsafe = false;

  @SuppressWarnings("unchecked")
  public CommandExecutorSQLInsert parse(final CommandRequest iRequest) {
    final CommandRequestText textRequest = (CommandRequestText) iRequest;

    String queryText = textRequest.getText();
    String originalQuery = queryText;
    try {
      queryText = preParse(queryText, iRequest);
      textRequest.setText(queryText);

      final var database = getDatabase();

      init((CommandRequestText) iRequest);

      className = null;
      newRecords = null;
      content = null;

      if (parserTextUpperCase.endsWith(KEYWORD_UNSAFE)) {
        unsafe = true;
        parserText = parserText.substring(0, parserText.length() - KEYWORD_UNSAFE.length() - 1);
        parserTextUpperCase =
            parserTextUpperCase.substring(
                0, parserTextUpperCase.length() - KEYWORD_UNSAFE.length() - 1);
      }

      parserRequiredKeyword("INSERT");
      parserRequiredKeyword("INTO");

      String subjectName =
          parserRequiredWord(false, "Invalid subject name. Expected cluster, class or index");
      String subjectNameUpper = subjectName.toUpperCase(Locale.ENGLISH);
      if (subjectNameUpper.startsWith(CommandExecutorSQLAbstract.CLUSTER_PREFIX))
      // CLUSTER
      {
        clusterName = subjectName.substring(CommandExecutorSQLAbstract.CLUSTER_PREFIX.length());
      } else if (subjectNameUpper.startsWith(CommandExecutorSQLAbstract.INDEX_PREFIX))
      // INDEX
      {
        indexName = subjectName.substring(CommandExecutorSQLAbstract.INDEX_PREFIX.length());
      } else {
        // CLASS
        if (subjectNameUpper.startsWith(CommandExecutorSQLAbstract.CLASS_PREFIX)) {
          subjectName = subjectName.substring(CommandExecutorSQLAbstract.CLASS_PREFIX.length());
        }

        final SchemaClass cls =
            database.getMetadata().getImmutableSchemaSnapshot().getClass(subjectName);
        if (cls == null) {
          throwParsingException("Class " + subjectName + " not found in database");
        }

        if (!unsafe && cls.isSubClassOf(database, "E"))
        // FOUND EDGE
        {
          throw new CommandExecutionException(
              "'INSERT' command cannot create Edges. Use 'CREATE EDGE' command instead, or apply"
                  + " the 'UNSAFE' keyword to force it");
        }

        className = cls.getName();
        clazz = database.getMetadata().getSchema().getClass(className);
        if (clazz == null) {
          throw new QueryParsingException("Class '" + className + "' was not found");
        }
      }

      if (clusterName != null && className == null) {
        DatabaseSessionInternal db = getDatabase();
        final int clusterId = db.getClusterIdByName(clusterName);
        if (clusterId >= 0) {
          clazz = db.getMetadata().getSchema().getClassByClusterId(clusterId);
          if (clazz != null) {
            className = clazz.getName();
          }
        }
      }

      parserSkipWhiteSpaces();
      if (parserIsEnded()) {
        throwSyntaxErrorException(
            "Set of fields is missed. Example: (name, surname) or SET name = 'Bill'");
      }

      final String temp = parseOptionalWord(true);
      if (parserGetLastWord().equalsIgnoreCase("cluster")) {
        clusterName = parserRequiredWord(false);

        parserSkipWhiteSpaces();
        if (parserIsEnded()) {
          throwSyntaxErrorException(
              "Set of fields is missed. Example: (name, surname) or SET name = 'Bill'");
        }
      } else {
        parserGoBack();
      }

      newRecords = new ArrayList<Map<String, Object>>();
      Boolean sourceClauseProcessed = false;
      if (parserGetCurrentChar() == '(') {
        parseValues();
        parserNextWord(true, " \r\n");
        sourceClauseProcessed = true;
      } else {
        parserNextWord(true, " ,\r\n");

        if (parserGetLastWord().equals(KEYWORD_CONTENT)) {
          newRecords = null;
          parseContent();
          sourceClauseProcessed = true;
        } else if (parserGetLastWord().equals(KEYWORD_SET)) {
          final List<Pair<String, Object>> fields = new ArrayList<Pair<String, Object>>();
          parseSetFields(clazz, fields, database);

          newRecords.add(Pair.convertToMap(fields));

          sourceClauseProcessed = true;
        }
      }
      if (sourceClauseProcessed) {
        parserNextWord(true, " \r\n");
      }
      // it has to be processed before KEYWORD_FROM in order to not be taken as part of SELECT
      if (parserGetLastWord().equals(KEYWORD_RETURN)) {
        parseReturn(!sourceClauseProcessed);
        parserNextWord(true, " \r\n");
      }

      if (!sourceClauseProcessed) {
        if (parserGetLastWord().equals(KEYWORD_FROM)) {
          newRecords = null;
          subQuery =
              new SQLAsynchQuery<Identifiable>(
                  parserText.substring(parserGetCurrentPosition()), this);
        }
      }

    } finally {
      textRequest.setText(originalQuery);
    }

    return this;
  }

  /**
   * Execute the INSERT and return the EntityImpl object created.
   */
  public Object execute(final Map<Object, Object> iArgs, DatabaseSessionInternal querySession) {
    final DatabaseSessionInternal database = getDatabase();
    if (newRecords == null && content == null && subQuery == null) {
      throw new CommandExecutionException(
          "Cannot execute the command because it has not been parsed yet");
    }

    final CommandParameters commandParameters = new CommandParameters(iArgs);
    if (indexName != null) {
      if (newRecords == null) {
        throw new CommandExecutionException("No key/value found");
      }

      IndexAbstract.manualIndexesWarning();

      final Index index =
          database.getMetadata().getIndexManagerInternal().getIndex(database, indexName);
      if (index == null) {
        throw new CommandExecutionException("Target index '" + indexName + "' not found");
      }

      // BIND VALUES
      Map<String, Object> result = new HashMap<String, Object>();

      for (Map<String, Object> candidate : newRecords) {
        Object indexKey = getIndexKeyValue(database, commandParameters, candidate);
        Identifiable indexValue = getIndexValue(database, commandParameters, candidate);
        index.put(database, indexKey, indexValue);

        result.put(KEYWORD_KEY, indexKey);
        result.put(KEYWORD_RID, indexValue);
      }

      // RETURN LAST ENTRY
      return prepareReturnItem(new EntityImpl(result));
    } else {
      // CREATE NEW DOCUMENTS
      final List<EntityImpl> docs = new ArrayList<EntityImpl>();
      if (newRecords != null) {
        for (Map<String, Object> candidate : newRecords) {
          final EntityImpl entity =
              className != null ? new EntityImpl(className) : new EntityImpl();
          SQLHelper.bindParameters(entity, candidate, commandParameters, context);

          saveRecord(entity);
          docs.add(entity);
        }

        if (docs.size() == 1) {
          return prepareReturnItem(docs.get(0));
        } else {
          return prepareReturnResult(docs);
        }
      } else if (content != null) {
        final EntityImpl entity =
            className != null ? new EntityImpl(className) : new EntityImpl();
        entity.merge(content, true, false);
        saveRecord(entity);
        return prepareReturnItem(entity);
      } else if (subQuery != null) {
        subQuery.execute(querySession);
        if (queryResult != null) {
          return prepareReturnResult(queryResult);
        }

        return saved.longValue();
      }
    }
    return null;
  }

  @Override
  public CommandDistributedReplicateRequest.DISTRIBUTED_EXECUTION_MODE
  getDistributedExecutionMode() {
    return indexName != null
        ? DISTRIBUTED_EXECUTION_MODE.REPLICATE
        : DISTRIBUTED_EXECUTION_MODE.LOCAL;
  }

  @Override
  public Set<String> getInvolvedClusters() {
    if (className != null) {
      var clazz =
          getDatabase().getMetadata().getImmutableSchemaSnapshot().getClassInternal(className);
      return Collections.singleton(
          getDatabase().getClusterNameById(clazz.getClusterSelection().getCluster(clazz, null)));
    } else if (clusterName != null) {
      return getInvolvedClustersOfClusters(Collections.singleton(clusterName));
    }

    return Collections.EMPTY_SET;
  }

  @Override
  public String getSyntax() {
    return "INSERT INTO [class:]<class>|cluster:<cluster>|index:<index> [(<field>[,]*) VALUES"
        + " (<expression>[,]*)[,]*]|[SET <field> = <expression>|<sub-command>[,]*]|CONTENT"
        + " {<JSON>} [RETURN <expression>] [FROM select-query]";
  }

  @Override
  public boolean result(DatabaseSessionInternal querySession, final Object iRecord) {
    SchemaClass oldClass = null;
    RecordAbstract oldRecord = ((Identifiable) iRecord).getRecord();

    if (oldRecord instanceof EntityImpl) {
      oldClass = EntityInternalUtils.getImmutableSchemaClass(((EntityImpl) oldRecord));
    }
    final RecordAbstract rec = oldRecord.copy();

    // RESET THE IDENTITY TO AVOID UPDATE
    rec.getIdentity().reset();

    if (rec instanceof EntityImpl entity) {

      if (className != null) {
        entity.setClassName(className);
        entity.setTrackingChanges(true);
      }
    }

    if (rec instanceof Entity) {
      EntityInternal entity = (EntityInternal) rec;

      if (oldClass != null && oldClass.isSubClassOf(querySession, "V")) {
        LogManager.instance()
            .warn(
                this,
                "WARNING: copying vertex record "
                    + entity
                    + " with INSERT/SELECT, the edge pointers won't be copied");
        String[] fields = ((EntityImpl) rec).fieldNames();
        for (String field : fields) {
          if (field.startsWith("out_") || field.startsWith("in_")) {
            Object edges = entity.getPropertyInternal(field);
            if (edges instanceof Identifiable) {
              EntityImpl edgeRec = ((Identifiable) edges).getRecord();
              SchemaClass clazz = EntityInternalUtils.getImmutableSchemaClass(edgeRec);
              if (clazz != null && clazz.isSubClassOf(querySession, "E")) {
                entity.removeProperty(field);
              }
            } else if (edges instanceof Iterable) {
              for (Object edge : (Iterable) edges) {
                if (edge instanceof Identifiable) {
                  Entity edgeRec = ((Identifiable) edge).getRecord();
                  if (edgeRec.getSchemaType().isPresent()
                      && edgeRec.getSchemaType().get().isSubClassOf(querySession, "E")) {
                    entity.removeProperty(field);
                    break;
                  }
                }
              }
            }
          }
        }
      }
    }
    rec.setDirty();
    synchronized (this) {
      saveRecord(rec);
      if (queryResult != null) {
        queryResult.add(((EntityImpl) rec));
      }
    }

    return true;
  }

  @Override
  public void end() {
  }

  protected Object prepareReturnResult(List<EntityImpl> res) {
    if (returnExpression == null) {
      return res; // No transformation
    }
    final ArrayList<Object> ret = new ArrayList<Object>();
    for (EntityImpl resItem : res) {
      ret.add(prepareReturnItem(resItem));
    }
    return ret;
  }

  protected Object prepareReturnItem(EntityImpl item) {
    if (returnExpression == null) {
      return item; // No transformation
    }

    this.getContext().setVariable("current", item);
    final Object res = SQLHelper.getValue(returnExpression, item, this.getContext());
    if (res instanceof Identifiable) {
      return res;
    } else { // wrapping entity
      final EntityImpl wrappingDoc = new EntityImpl("result", res);
      wrappingDoc.field(
          "rid", item.getIdentity()); // passing record id.In many cases usable on client side
      wrappingDoc.field("version", item.getVersion()); // passing record version
      return wrappingDoc;
    }
  }

  protected void saveRecord(final RecordAbstract rec) {
    if (clusterName != null) {
      rec.save(clusterName);
    } else {
      rec.save();
    }
    saved.incrementAndGet();
  }

  protected void parseValues() {
    final int beginFields = parserGetCurrentPosition();

    final int endFields = parserText.indexOf(')', beginFields + 1);
    if (endFields == -1) {
      throwSyntaxErrorException("Missed closed brace");
    }

    final ArrayList<String> fieldNamesQuoted = new ArrayList<String>();
    parserSetCurrentPosition(
        StringSerializerHelper.getParameters(
            parserText, beginFields, endFields, fieldNamesQuoted));
    final ArrayList<String> fieldNames = new ArrayList<String>();
    for (String fieldName : fieldNamesQuoted) {
      fieldNames.add(decodeClassName(fieldName));
    }

    if (fieldNames.size() == 0) {
      throwSyntaxErrorException("Set of fields is empty. Example: (name, surname)");
    }

    // REMOVE QUOTATION MARKS IF ANY
    for (int i = 0; i < fieldNames.size(); ++i) {
      fieldNames.set(i, StringSerializerHelper.removeQuotationMarks(fieldNames.get(i)));
    }

    parserRequiredKeyword(KEYWORD_VALUES);
    parserSkipWhiteSpaces();
    if (parserIsEnded() || parserText.charAt(parserGetCurrentPosition()) != '(') {
      throwParsingException("Set of values is missed. Example: ('Bill', 'Stuart', 300)");
    }

    int blockStart = parserGetCurrentPosition();
    int blockEnd = parserGetCurrentPosition();

    final List<String> records =
        StringSerializerHelper.smartSplit(
            parserText, new char[]{','}, blockStart, -1, true, true, false, false);
    for (String record : records) {

      final List<String> values = new ArrayList<String>();
      blockEnd += StringSerializerHelper.getParameters(record, 0, -1, values);

      if (blockEnd == -1) {
        throw new CommandSQLParsingException(
            "Missed closed brace. Use " + getSyntax(), parserText, blockStart);
      }

      if (values.isEmpty()) {
        throw new CommandSQLParsingException(
            "Set of values is empty. Example: ('Bill', 'Stuart', 300). Use " + getSyntax(),
            parserText,
            blockStart);
      }

      if (values.size() != fieldNames.size()) {
        throw new CommandSQLParsingException(
            "Fields not match with values", parserText, blockStart);
      }

      // TRANSFORM FIELD VALUES
      final Map<String, Object> fields = new LinkedHashMap<String, Object>();
      for (int i = 0; i < values.size(); ++i) {
        fields.put(
            fieldNames.get(i),
            SQLHelper.parseValue(
                this, StringSerializerHelper.decode(values.get(i).trim()), context));
      }

      newRecords.add(fields);
      blockStart = blockEnd;
    }
  }

  /**
   * Parses the returning keyword if found.
   */
  protected void parseReturn(Boolean subQueryExpected) throws CommandSQLParsingException {
    parserNextWord(false, " ");
    String returning = parserGetLastWord().trim();
    if (returning.startsWith("$") || returning.startsWith("@")) {
      if (subQueryExpected) {
        queryResult = new ArrayList<EntityImpl>();
      }
      returnExpression =
          (returning.length() > 0)
              ? SQLHelper.parseValue(this, returning, this.getContext())
              : null;
    } else {
      throwSyntaxErrorException(
          "record attribute (@attributes) or functions with $current variable expected");
    }
  }

  private Object getIndexKeyValue(
      DatabaseSession session, CommandParameters commandParameters,
      Map<String, Object> candidate) {
    final Object parsedKey = candidate.get(KEYWORD_KEY);
    if (parsedKey instanceof SQLFilterItemField f) {
      if (f.getRoot(session).equals("?"))
      // POSITIONAL PARAMETER
      {
        return commandParameters.getNext();
      } else if (f.getRoot(session).startsWith(":"))
      // NAMED PARAMETER
      {
        return commandParameters.getByName(f.getRoot(session).substring(1));
      }
    }
    return parsedKey;
  }

  private Identifiable getIndexValue(
      DatabaseSession session, CommandParameters commandParameters,
      Map<String, Object> candidate) {
    final Object parsedRid = candidate.get(KEYWORD_RID);
    if (parsedRid instanceof SQLFilterItemField f) {
      if (f.getRoot(session).equals("?"))
      // POSITIONAL PARAMETER
      {
        return (Identifiable) commandParameters.getNext();
      } else if (f.getRoot(session).startsWith(":"))
      // NAMED PARAMETER
      {
        return (Identifiable) commandParameters.getByName(f.getRoot(session).substring(1));
      }
    }
    return (Identifiable) parsedRid;
  }

  @Override
  public QUORUM_TYPE getQuorumType() {
    return QUORUM_TYPE.WRITE;
  }

  @Override
  public Object getResult() {
    return null;
  }
}
