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
import com.jetbrains.youtrack.db.api.query.Result;
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
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

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
  private final AtomicLong saved = new AtomicLong(0);
  private Object returnExpression = null;
  private List<EntityImpl> queryResult = null;
  private boolean unsafe = false;

  @SuppressWarnings("unchecked")
  public CommandExecutorSQLInsert parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;

    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      init(session, (CommandRequestText) iRequest);

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

      parserRequiredKeyword(session.getDatabaseName(), "INSERT");
      parserRequiredKeyword(session.getDatabaseName(), "INTO");

      var subjectName =
          parserRequiredWord(false, "Invalid subject name. Expected cluster, class or index",
              session.getDatabaseName());
      var subjectNameUpper = subjectName.toUpperCase(Locale.ENGLISH);
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

        final var cls =
            session.getMetadata().getImmutableSchemaSnapshot().getClass(subjectName);
        if (cls == null) {
          throwParsingException(session.getDatabaseName(),
              "Class " + subjectName + " not found in database");
        }

        if (!unsafe && cls.isSubClassOf("E"))
        // FOUND EDGE
        {
          throw new CommandExecutionException(session.getDatabaseName(),
              "'INSERT' command cannot create Edges. Use 'CREATE EDGE' command instead, or apply"
                  + " the 'UNSAFE' keyword to force it");
        }

        className = cls.getName();
        clazz = session.getMetadata().getSchema().getClass(className);
        if (clazz == null) {
          throw new QueryParsingException(session.getDatabaseName(),
              "Class '" + className + "' was not found");
        }
      }

      if (clusterName != null && className == null) {
        final var clusterId = session.getClusterIdByName(clusterName);
        if (clusterId >= 0) {
          clazz = session.getMetadata().getSchema().getClassByClusterId(clusterId);
          if (clazz != null) {
            className = clazz.getName();
          }
        }
      }

      parserSkipWhiteSpaces();
      if (parserIsEnded()) {
        throwSyntaxErrorException(session.getDatabaseName(),
            "Set of fields is missed. Example: (name, surname) or SET name = 'Bill'");
      }

      final var temp = parseOptionalWord(session.getDatabaseName(), true);
      if (parserGetLastWord().equalsIgnoreCase("cluster")) {
        clusterName = parserRequiredWord(session.getDatabaseName(), false);

        parserSkipWhiteSpaces();
        if (parserIsEnded()) {
          throwSyntaxErrorException(session.getDatabaseName(),
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
          parseContent(session);
          sourceClauseProcessed = true;
        } else if (parserGetLastWord().equals(KEYWORD_SET)) {
          final List<Pair<String, Object>> fields = new ArrayList<Pair<String, Object>>();
          parseSetFields(session, clazz, fields);

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
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (newRecords == null && content == null) {
      throw new CommandExecutionException(session,
          "Cannot execute the command because it has not been parsed yet");
    }

    final var commandParameters = new CommandParameters(iArgs);
    if (indexName != null) {
      if (newRecords == null) {
        throw new CommandExecutionException(session, "No key/value found");
      }

      throw new UnsupportedOperationException("Manual indexes are not supported");
    } else {
      // CREATE NEW DOCUMENTS
      final List<EntityImpl> docs = new ArrayList<EntityImpl>();
      if (newRecords != null) {
        for (var candidate : newRecords) {
          final var entity =
              className != null ? new EntityImpl(session, className) : new EntityImpl(session);
          SQLHelper.bindParameters(entity, candidate, commandParameters, context);

          saveRecord(entity);
          docs.add(entity);
        }

        if (docs.size() == 1) {
          return prepareReturnItem(session, docs.get(0));
        } else {
          return prepareReturnResult(session, docs);
        }
      } else if (content != null) {
        final var entity =
            className != null ? new EntityImpl(session, className) : new EntityImpl(session);
        throw new UnsupportedOperationException();
      }
    }
    return null;
  }

  @Override
  public Set<String> getInvolvedClusters(DatabaseSessionInternal session) {
    if (className != null) {
      var clazz =
          session.getMetadata().getImmutableSchemaSnapshot().getClassInternal(className);
      return Collections.singleton(
          session.getClusterNameById(
              clazz.getClusterSelection().getCluster(session, clazz, null)));
    } else if (clusterName != null) {
      return getInvolvedClustersOfClusters(session, Collections.singleton(clusterName));
    }

    return Collections.emptySet();
  }

  @Override
  public String getSyntax() {
    return "INSERT INTO [class:]<class>|cluster:<cluster>|index:<index> [(<field>[,]*) VALUES"
        + " (<expression>[,]*)[,]*]|[SET <field> = <expression>|<sub-command>[,]*]|CONTENT"
        + " {<JSON>} [RETURN <expression>] [FROM select-query]";
  }

  @Override
  public boolean result(@Nonnull DatabaseSessionInternal session, final Object iRecord) {
    SchemaClass oldClass = null;
    var transaction1 = session.getActiveTransaction();
    RecordAbstract rec = transaction1.load(((Identifiable) iRecord));

    if (rec instanceof EntityImpl) {
      if (className != null) {
        throw new UnsupportedOperationException("Cannot insert a record with a specific class");
      }
    }

    if (rec instanceof Entity) {
      var entity = (EntityImpl) rec;

      if (oldClass != null && oldClass.isSubClassOf("V")) {
        LogManager.instance()
            .warn(
                this,
                "WARNING: copying vertex record "
                    + entity
                    + " with INSERT/SELECT, the edge pointers won't be copied");
        var fields = ((EntityImpl) rec).propertyNames();
        for (var field : fields) {
          if (field.startsWith("out_") || field.startsWith("in_")) {
            var edges = entity.getPropertyInternal(field);
            if (edges instanceof Identifiable) {
              var transaction = session.getActiveTransaction();
              EntityImpl edgeRec = transaction.load(((Identifiable) edges));
              SchemaImmutableClass result = null;
              if (edgeRec != null) {
                result = edgeRec.getImmutableSchemaClass(session);
              }
              SchemaClass clazz = result;
              if (clazz != null && clazz.isSubClassOf("E")) {
                entity.removeProperty(field);
              }
            } else if (edges instanceof Iterable) {
              for (var edge : (Iterable) edges) {
                if (edge instanceof Identifiable identifiable) {
                  var transaction = session.getActiveTransaction();
                  var edgeRec = (EntityImpl) transaction.loadEntity(identifiable);
                  var schemaClass = edgeRec.getImmutableSchemaClass(session);
                  if (schemaClass != null
                      && schemaClass.isSubClassOf("E")) {
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
        queryResult.add((EntityImpl) rec);
      }
    }

    return true;
  }

  @Override
  public void end(@Nonnull DatabaseSessionInternal session) {
  }

  protected Object prepareReturnResult(DatabaseSessionInternal db, List<EntityImpl> res) {
    if (returnExpression == null) {
      return res; // No transformation
    }
    final var ret = new ArrayList<Object>();
    for (var resItem : res) {
      ret.add(prepareReturnItem(db, resItem));
    }
    return ret;
  }

  protected Object prepareReturnItem(DatabaseSessionInternal db, Result item) {
    if (returnExpression == null) {
      return item; // No transformation
    }

    this.getContext().setVariable("current", item);
    final var res = SQLHelper.getValue(returnExpression, item, this.getContext());
    if (res instanceof Identifiable) {
      return res;
    } else { // wrapping entity
      final var wrappingResult = new ResultInternal(db);

      wrappingResult.setProperty("value", res);
      wrappingResult.setProperty(
          "rid", item.getIdentity());
      if (item.isRecord()) {
        wrappingResult.setProperty("version", item.asRecord().getVersion());
      }

      return wrappingResult;
    }
  }

  protected void saveRecord(final RecordAbstract rec) {
    saved.incrementAndGet();
  }

  protected void parseValues() {
    final var beginFields = parserGetCurrentPosition();

    final var endFields = parserText.indexOf(')', beginFields + 1);
    if (endFields == -1) {
      throwSyntaxErrorException(null, "Missed closed brace");
    }

    final var fieldNamesQuoted = new ArrayList<String>();
    parserSetCurrentPosition(
        StringSerializerHelper.getParameters(
            parserText, beginFields, endFields, fieldNamesQuoted));
    final var fieldNames = new ArrayList<String>();
    for (var fieldName : fieldNamesQuoted) {
      fieldNames.add(decodeClassName(fieldName));
    }

    if (fieldNames.size() == 0) {
      throwSyntaxErrorException(null, "Set of fields is empty. Example: (name, surname)");
    }

    // REMOVE QUOTATION MARKS IF ANY
    for (var i = 0; i < fieldNames.size(); ++i) {
      fieldNames.set(i, StringSerializerHelper.removeQuotationMarks(fieldNames.get(i)));
    }

    parserRequiredKeyword(null, KEYWORD_VALUES);
    parserSkipWhiteSpaces();
    if (parserIsEnded() || parserText.charAt(parserGetCurrentPosition()) != '(') {
      throwParsingException(null, "Set of values is missed. Example: ('Bill', 'Stuart', 300)");
    }

    var blockStart = parserGetCurrentPosition();
    var blockEnd = parserGetCurrentPosition();

    final var records =
        StringSerializerHelper.smartSplit(
            parserText, new char[]{','}, blockStart, -1, true, true, false, false);
    for (var record : records) {

      final List<String> values = new ArrayList<String>();
      blockEnd += StringSerializerHelper.getParameters(record, 0, -1, values);

      if (blockEnd == -1) {
        throw new CommandSQLParsingException(
            "Missed closed brace. Use " + getSyntax(), parserText, blockStart);
      }

      if (values.isEmpty()) {
        throw new CommandSQLParsingException(
            "Set of values is empty. Example: ('Bill', 'Stuart', 300). Use " + getSyntax(),
            parserText, blockStart);
      }

      if (values.size() != fieldNames.size()) {
        throw new CommandSQLParsingException(
            "Fields not match with values", parserText, blockStart);
      }

      // TRANSFORM FIELD VALUES
      final Map<String, Object> fields = new LinkedHashMap<String, Object>();
      for (var i = 0; i < values.size(); ++i) {
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
    var returning = parserGetLastWord().trim();
    if (returning.startsWith("$") || returning.startsWith("@")) {
      if (subQueryExpected) {
        queryResult = new ArrayList<EntityImpl>();
      }
      returnExpression =
          (returning.length() > 0)
              ? SQLHelper.parseValue(this, returning, this.getContext())
              : null;
    } else {
      throwSyntaxErrorException(null,
          "record attribute (@attributes) or functions with $current variable expected");
    }
  }

  private Object getIndexKeyValue(
      DatabaseSession session, CommandParameters commandParameters,
      Map<String, Object> candidate) {
    final var parsedKey = candidate.get(KEYWORD_KEY);
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
    final var parsedRid = candidate.get(KEYWORD_RID);
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
  public Object getResult() {
    return null;
  }
}
