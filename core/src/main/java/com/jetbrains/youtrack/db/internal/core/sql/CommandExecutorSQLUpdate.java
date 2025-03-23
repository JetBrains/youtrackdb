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

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.common.util.Triple;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.command.CommandResultListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMap;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Security;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilter;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItem;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLUpdateStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * SQL UPDATE command.
 */
public class CommandExecutorSQLUpdate extends CommandExecutorSQLRetryAbstract
    implements CommandDistributedReplicateRequest, CommandResultListener {

  public static final String KEYWORD_UPDATE = "UPDATE";
  private static final String KEYWORD_ADD = "ADD";
  private static final String KEYWORD_PUT = "PUT";
  private static final String KEYWORD_REMOVE = "REMOVE";
  private static final String KEYWORD_INCREMENT = "INCREMENT";
  private static final String KEYWORD_MERGE = "MERGE";
  private static final String KEYWORD_UPSERT = "UPSERT";
  private static final String KEYWORD_EDGE = "EDGE";
  private static final Object EMPTY_VALUE = new Object();
  private final List<Pair<String, Object>> setEntries = new ArrayList<Pair<String, Object>>();
  private final List<Pair<String, Object>> addEntries = new ArrayList<Pair<String, Object>>();
  private final List<Triple<String, String, Object>> putEntries =
      new ArrayList<Triple<String, String, Object>>();
  private final List<Pair<String, Object>> removeEntries = new ArrayList<Pair<String, Object>>();
  private final List<Pair<String, Object>> incrementEntries =
      new ArrayList<Pair<String, Object>>();
  private EntityImpl merge = null;
  private ReturnHandler returnHandler = new RecordCountHandler();
  private SQLFilter compiledFilter;
  private String subjectName;
  private CommandParameters parameters;
  private boolean upsertMode = false;
  private boolean isUpsertAllowed = false;
  private boolean updated = false;
  private SchemaClass clazz = null;

  private boolean updateEdge = false;

  @SuppressWarnings("unchecked")
  public CommandExecutorSQLUpdate parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;

    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iRequest);
      if (updateEdge) {
        queryText =
            queryText.replaceFirst(
                "EDGE ", ""); // work-around to use UPDATE syntax without having to
      }
      textRequest.setText(queryText);

      init(session, (CommandRequestText) iRequest);

      setEntries.clear();
      addEntries.clear();
      putEntries.clear();
      removeEntries.clear();
      incrementEntries.clear();
      content = null;
      merge = null;

      parserRequiredKeyword(session.getDatabaseName(), KEYWORD_UPDATE);

      subjectName = parserRequiredWord(false, "Invalid target", " =><,\r\n",
          session.getDatabaseName());
      if (subjectName == null) {
        throwSyntaxErrorException(session.getDatabaseName(),
            "Invalid subject name. Expected cluster, class, index or sub-query");
      }
      if (subjectName.equalsIgnoreCase("EDGE")) {
        updateEdge = true;
        subjectName = parserRequiredWord(false, "Invalid target", " =><,\r\n",
            session.getDatabaseName());
      }

      clazz = extractClassFromTarget(session, subjectName);

      var word = parserNextWord(true);

      if (parserIsEnded()
          || (!word.equals(KEYWORD_SET)
          && !word.equals(KEYWORD_ADD)
          && !word.equals(KEYWORD_PUT)
          && !word.equals(KEYWORD_REMOVE)
          && !word.equals(KEYWORD_INCREMENT)
          && !word.equals(KEYWORD_CONTENT)
          && !word.equals(KEYWORD_MERGE)
          && !word.equals(KEYWORD_RETURN)
          && !word.equals(KEYWORD_UPSERT)
          && !word.equals(KEYWORD_EDGE))) {
        throwSyntaxErrorException(session.getDatabaseName(),
            "Expected keyword "
                + KEYWORD_SET
                + ","
                + KEYWORD_ADD
                + ","
                + KEYWORD_CONTENT
                + ","
                + KEYWORD_MERGE
                + ","
                + KEYWORD_PUT
                + ","
                + KEYWORD_REMOVE
                + ","
                + KEYWORD_INCREMENT
                + " or "
                + KEYWORD_RETURN
                + " or "
                + KEYWORD_UPSERT
                + " or "
                + KEYWORD_EDGE);
      }

      while ((!parserIsEnded()
          && !parserGetLastWord().equals(CommandExecutorSQLAbstract.KEYWORD_WHERE))
          || parserGetLastWord().equals(KEYWORD_UPSERT)) {
        word = parserGetLastWord();

        if (word.equals(KEYWORD_CONTENT)) {
          parseContent(session);
        } else if (word.equals(KEYWORD_MERGE)) {
          parseMerge(session);
        } else if (word.equals(KEYWORD_SET)) {
          parseSetFields(session, clazz, setEntries);
        } else if (word.equals(KEYWORD_ADD)) {
          parseAddFields(session);
        } else if (word.equals(KEYWORD_PUT)) {
          parsePutFields();
        } else if (word.equals(KEYWORD_REMOVE)) {
          parseRemoveFields();
        } else if (word.equals(KEYWORD_INCREMENT)) {
          parseIncrementFields();
        } else if (word.equals(KEYWORD_UPSERT)) {
          upsertMode = true;
        } else if (word.equals(KEYWORD_RETURN)) {
          parseReturn();
        } else if (word.equals(KEYWORD_RETRY)) {
          LogManager.instance().warn(this, "RETRY keyword will be ignored in " + originalQuery);
          parseRetry();
        } else {
          break;
        }

        parserNextWord(true);
      }

      final var additionalStatement = parserGetLastWord();

      if (subjectName.startsWith("(")) {
        subjectName = subjectName.trim();
        throw new UnsupportedOperationException();

      } else if (additionalStatement.equals(CommandExecutorSQLAbstract.KEYWORD_WHERE)
          || additionalStatement.equals(CommandExecutorSQLAbstract.KEYWORD_LIMIT)
          || additionalStatement.equals(CommandExecutorSQLAbstract.KEYWORD_LET)) {
        if (this.preParsedStatement != null) {
          var params = ((CommandRequestText) iRequest).getParameters();
          var updateStm = (SQLUpdateStatement) preParsedStatement;
          var selectString = new StringBuilder();
          selectString.append("select from ");
          updateStm.target.toString(params, selectString);
          if (updateStm.whereClause != null) {
            selectString.append(" WHERE ");
            updateStm.whereClause.toString(params, selectString);
          }
          if (updateStm.limit != null) {
            selectString.append(" ");
            updateStm.limit.toString(params, selectString);
          }
          if (updateStm.timeout != null) {
            selectString.append(" ");
            updateStm.timeout.toString(params, selectString);
          }

        } else {
        }

        isUpsertAllowed =
            (session.getMetadata().getImmutableSchemaSnapshot().getClass(subjectName)
                != null);
      } else if (!additionalStatement.isEmpty()) {
        throwSyntaxErrorException(session.getDatabaseName(),
            "Invalid keyword " + additionalStatement);
      } else {
      }

      if (upsertMode && !isUpsertAllowed) {
        throwSyntaxErrorException(session.getDatabaseName(), "Upsert only works with class names ");
      }

      if (upsertMode && !additionalStatement.equals(CommandExecutorSQLAbstract.KEYWORD_WHERE)) {
        throwSyntaxErrorException(session.getDatabaseName(),
            "Upsert only works with WHERE keyword");
      }
      if (upsertMode && updateEdge) {
        throwSyntaxErrorException(session.getDatabaseName(),
            "Upsert is not supported with UPDATE EDGE");
      }
    } finally {
      textRequest.setText(originalQuery);
    }

    return this;
  }

  private boolean isUpdateEdge() {
    return updateEdge;
  }

  private String getSelectTarget() {
    if (preParsedStatement == null) {
      return subjectName;
    }
    return ((SQLUpdateStatement) preParsedStatement).target.toString();
  }

  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (subjectName == null) {
      throw new CommandExecutionException(session,
          "Cannot execute the command because it has not been parsed yet");
    }

    parameters = new CommandParameters(iArgs);
    Map<Object, Object> queryArgs;
    if (parameters.size() > 0 && parameters.getByName(0) != null) {
      queryArgs = new HashMap<Object, Object>();
      for (var i = parameterCounter; i < parameters.size(); i++) {
        if (parameters.getByName(i) != null) {
          queryArgs.put(i - parameterCounter, parameters.getByName(i));
        }
      }
    } else {
      queryArgs = iArgs;
    }


    returnHandler.reset();

    throw new UnsupportedOperationException();
  }

  /**
   * Update current record.
   */
  @SuppressWarnings("unchecked")
  public boolean result(@Nonnull DatabaseSessionInternal session, final Object iRecord) {
    var transaction = session.getActiveTransaction();
    final EntityImpl record = transaction.load(((Identifiable) iRecord));

    if (updateEdge && !isRecordInstanceOf(session, iRecord, "E")) {
      throw new CommandExecutionException(session,
          "Using UPDATE EDGE on a record that is not an instance of E");
    }
    if (compiledFilter != null) {
      // ADDITIONAL FILTERING
      if (!(Boolean) compiledFilter.evaluate(record, null, context)) {
        return false;
      }
    }

    parameters.reset();

    returnHandler.beforeUpdate(record);

    var updated = handleContent(session, record);
    updated |= handleMerge(record);
    updated |= handleSetEntries(record);
    updated |= handleIncrementEntries(record);
    updated |= handleAddEntries(session, record);
    updated |= handlePutEntries(session, record);
    updated |= handleRemoveEntries(session, record);

    if (updated) {
      handleUpdateEdge(session, record);
      record.setDirty();

      returnHandler.afterUpdate(record);
      this.updated = true;
    }

    return true;
  }

  /**
   * checks if an object is an Identifiable and an instance of a particular (schema) class
   *
   * @param session
   * @param iRecord         The record object
   * @param youTrackDbClass The schema class
   * @return
   */
  private static boolean isRecordInstanceOf(DatabaseSessionInternal session, Object iRecord,
      String youTrackDbClass) {
    if (iRecord == null) {
      return false;
    }
    if (!(iRecord instanceof Identifiable)) {
      return false;
    }
    var transaction = session.getActiveTransaction();
    EntityImpl record = transaction.load(((Identifiable) iRecord));
    SchemaImmutableClass result;
    result = record.getImmutableSchemaClass(session);
    return (result.isSubClassOf(youTrackDbClass));
  }

  /**
   * handles vertex consistency after an UPDATE EDGE
   *
   * @param db
   * @param record the edge record
   */
  private void handleUpdateEdge(DatabaseSessionInternal db, EntityImpl record) {
    if (!updateEdge) {
      return;
    }
    var currentOut = record.getProperty("out");
    var currentIn = record.getProperty("in");

    var prevOut = record.getOriginalValue("out");
    var prevIn = record.getOriginalValue("in");

    validateOutInForEdge(db, record, currentOut, currentIn);

    changeVertexEdgePointer(db, record, (Identifiable) prevIn, (Identifiable) currentIn, "in");
    changeVertexEdgePointer(db, record, (Identifiable) prevOut, (Identifiable) currentOut,
        "out");
  }

  /**
   * updates old and new vertices connected to an edge after out/in update on the edge itself
   *
   * @param db
   * @param edge          the edge
   * @param prevVertex    the previously connected vertex
   * @param currentVertex the currently connected vertex
   * @param direction     the direction ("out" or "in")
   */
  private static void changeVertexEdgePointer(
      DatabaseSessionInternal db, EntityImpl edge, Identifiable prevVertex,
      Identifiable currentVertex, String direction) {
    if (prevVertex != null && !prevVertex.equals(currentVertex)) {
      var edgeClassName = edge.getSchemaClassName();
      if (edgeClassName.equalsIgnoreCase("E")) {
        edgeClassName = "";
      }
      var vertexFieldName = direction + "_" + edgeClassName;
      var transaction1 = db.getActiveTransaction();
      EntityImpl prevOutDoc = transaction1.load(prevVertex);
      RidBag prevBag = prevOutDoc.getProperty(vertexFieldName);

      if (prevBag != null) {
        prevBag.remove(edge.getIdentity());

      }

      var transaction = db.getActiveTransaction();
      EntityImpl currentVertexDoc = transaction.load(currentVertex);
      RidBag currentBag = currentVertexDoc.getProperty(vertexFieldName);
      if (currentBag == null) {
        currentBag = new RidBag(db);
        currentVertexDoc.setProperty(vertexFieldName, currentBag);
      }

      currentBag.add(edge.getIdentity());
    }
  }

  private static void validateOutInForEdge(DatabaseSessionInternal session, EntityImpl record,
      Object currentOut, Object currentIn) {
    if (!isRecordInstanceOf(session, currentOut, "V")) {
      throw new CommandExecutionException(session,
          "Error updating edge: 'out' is not a vertex - " + currentOut);
    }
    if (!isRecordInstanceOf(session, currentIn, "V")) {
      throw new CommandExecutionException(session,
          "Error updating edge: 'in' is not a vertex - " + currentIn);
    }
  }

  @Override
  public String getSyntax() {
    return "UPDATE <class>|cluster:<cluster>> [SET|ADD|PUT|REMOVE|INCREMENT|CONTENT {<JSON>}|MERGE"
        + " {<JSON>}] [[,] <field-name> = <expression>|<sub-command>]* [LOCK <NONE|RECORD>]"
        + " [UPSERT] [RETURN <COUNT|BEFORE|AFTER>] [WHERE <conditions>]";
  }

  @Override
  public void end(@Nonnull DatabaseSessionInternal session) {
  }

  @Override
  public int getSecurityOperationType() {
    return Role.PERMISSION_UPDATE;
  }

  protected void parseMerge(DatabaseSessionInternal session) {
    if (!parserIsEnded() && !parserGetLastWord().equals(KEYWORD_WHERE)) {
      final var contentAsString = parserRequiredWord(false, "entity to merge expected",
          session.getDatabaseName()).trim();
      merge = new EntityImpl(session);
      merge.updateFromJSON(contentAsString);
      parserSkipWhiteSpaces();
    }

    if (merge == null) {
      throwSyntaxErrorException(session.getDatabaseName(),
          "Document to merge not provided. Example: MERGE { \"name\": \"Jay\" }");
    }
  }

  protected String getBlock(String fieldValue) {
    final var startPos = parserGetCurrentPosition();

    if (fieldValue.startsWith("{") || fieldValue.startsWith("[")) {
      if (startPos > 0) {
        parserSetCurrentPosition(startPos - fieldValue.length());
      } else {
        parserSetCurrentPosition(parserText.length() - fieldValue.length());
      }

      parserSkipWhiteSpaces();
      final var buffer = new StringBuilder();
      parserSetCurrentPosition(
          StringSerializerHelper.parse(
              parserText,
              buffer,
              parserGetCurrentPosition(),
              -1,
              StringSerializerHelper.DEFAULT_FIELD_SEPARATOR,
              true,
              true,
              false,
              -1,
              false,
              StringSerializerHelper.DEFAULT_IGNORE_CHARS));
      fieldValue = buffer.toString();
    }
    return fieldValue;
  }

  /**
   * Parses the returning keyword if found.
   */
  protected void parseReturn() throws CommandSQLParsingException {
    parserNextWord(false, " ");
    var mode = parserGetLastWord().trim();

    if (mode.equalsIgnoreCase("COUNT")) {
      returnHandler = new RecordCountHandler();
    } else if (mode.equalsIgnoreCase("BEFORE") || mode.equalsIgnoreCase("AFTER")) {

      parserNextWord(false, " ");
      var returning = parserGetLastWord().trim();
      Object returnExpression = null;
      if (returning.equalsIgnoreCase(KEYWORD_WHERE)
          || returning.equalsIgnoreCase(KEYWORD_TIMEOUT)
          || returning.equalsIgnoreCase(KEYWORD_LIMIT)
          || returning.equalsIgnoreCase(KEYWORD_UPSERT)
          || returning.length() == 0) {
        parserGoBack();
      } else {
        if (returning.startsWith("$") || returning.startsWith("@")) {
          returnExpression =
              (returning.length() > 0)
                  ? SQLHelper.parseValue(this, returning, this.getContext())
                  : null;
        } else {
          throwSyntaxErrorException(null,
              "record attribute (@attributes) or functions with $current variable expected");
        }
      }

      if (mode.equalsIgnoreCase("BEFORE")) {
        returnHandler = new OriginalRecordsReturnHandler(returnExpression, getContext());
      } else {
        returnHandler = new UpdatedRecordsReturnHandler(returnExpression, getContext());
      }

    } else {
      throwSyntaxErrorException(null, " COUNT | BEFORE | AFTER keywords expected");
    }
  }

  private boolean handleContent(DatabaseSessionInternal session, EntityImpl record) {
    var updated = false;
    if (content != null) {
      // REPLACE ALL THE CONTENT
      final var fieldsToPreserve = new EntityImpl(session);

      final var restricted =
          session.getMetadata()
              .getImmutableSchemaSnapshot()
              .getClass(Security.RESTRICTED_CLASSNAME);

      if (restricted != null) {
        SchemaImmutableClass result = null;
        if (record != null) {
          result = record.getImmutableSchemaClass(session);
        }
        if (restricted.isSuperClassOf(
            result)) {
          for (var prop : restricted.getProperties()) {
            final String iFieldName = prop.getName();
            fieldsToPreserve.setProperty(iFieldName, record.getProperty(prop.getName()));
          }
        }
      }

      SchemaImmutableClass result = null;
      if (record != null) {
        result = record.getImmutableSchemaClass(session);
      }
      SchemaClass recordClass = result;
      if (recordClass != null && recordClass.isSubClassOf("V")) {
        for (var fieldName : record.propertyNames()) {
          if (fieldName.startsWith("in_") || fieldName.startsWith("out_")) {
            fieldsToPreserve.setProperty(fieldName, record.getProperty(fieldName));
          }
        }
      } else if (recordClass != null && recordClass.isSubClassOf("E")) {
        for (var fieldName : record.propertyNames()) {
          if (fieldName.equals("in") || fieldName.equals("out")) {
            fieldsToPreserve.setProperty(fieldName, record.getProperty(fieldName));
          }
        }
      }
      throw new UnsupportedOperationException();
    }
    return updated;
  }

  private boolean handleMerge(EntityImpl record) {
    var updated = false;
    if (merge != null) {
      // MERGE THE CONTENT
      throw new UnsupportedOperationException();
    }
    return updated;
  }

  private boolean handleSetEntries(final EntityImpl record) {
    var updated = false;
    // BIND VALUES TO UPDATE
    if (!setEntries.isEmpty()) {
      SQLHelper.bindParameters(record, setEntries, parameters, context);
      updated = true;
    }
    return updated;
  }

  private boolean handleIncrementEntries(final EntityImpl record) {
    var updated = false;
    // BIND VALUES TO INCREMENT
    if (!incrementEntries.isEmpty()) {
      for (var entry : incrementEntries) {
        final Number prevValue = record.getProperty(entry.getKey());

        Number current;
        if (entry.getValue() instanceof SQLFilterItem) {
          current = (Number) ((SQLFilterItem) entry.getValue()).getValue(record, null, context);
        } else if (entry.getValue() instanceof Number) {
          current = (Number) entry.getValue();
        } else {
          throw new CommandExecutionException((String) null,
              "Increment value is not a number (" + entry.getValue() + ")");
        }

        if (prevValue == null)
        // NO PREVIOUS VALUE: CONSIDER AS 0
        {
          record.setProperty(entry.getKey(), current);
        } else
        // COMPUTING INCREMENT
        {
          Object iPropertyValue = PropertyTypeInternal.increment(prevValue, current);
          record.setProperty(entry.getKey(), iPropertyValue);
        }
      }
      updated = true;
    }
    return updated;
  }

  private boolean handleAddEntries(DatabaseSessionInternal session, EntityImpl record) {
    var updated = false;
    // BIND VALUES TO ADD
    Object fieldValue;
    for (var entry : addEntries) {
      Collection coll = null;
      RidBag bag = null;
      if (!record.hasProperty(entry.getKey())) {
        // GET THE TYPE IF ANY
        var cls = record.getImmutableSchemaClass(session);
        if (cls != null) {
          var prop = cls.getProperty(entry.getKey());
          if (prop != null && prop.getType() == PropertyType.LINKSET)
          // SET TYPE
          {
            coll = session.newLinkSet();
          }
          if (prop != null && prop.getType() == PropertyType.LINKBAG) {
            // there is no ridbag value already but property type is defined as LINKBAG
            bag = new RidBag(session);
            bag.setOwner(record);
            record.setProperty(entry.getKey(), bag);
          }
        }
        if (coll == null && bag == null)
        // IN ALL OTHER CASES USE A LIST
        {
          coll = new ArrayList<Object>();
        }
        if (coll != null) {
          // containField's condition above does NOT check subdocument's fields so
          Collection<Object> currColl = record.getProperty(entry.getKey());
          if (currColl == null) {
            record.setProperty(entry.getKey(), coll);
            coll = record.getProperty(entry.getKey());
          } else {
            coll = currColl;
          }
        }

      } else {
        fieldValue = record.getProperty(entry.getKey());

        if (fieldValue instanceof Collection<?>) {
          coll = (Collection<Object>) fieldValue;
        } else if (fieldValue instanceof RidBag) {
          bag = (RidBag) fieldValue;
        } else {
          continue;
        }
      }

      final var value = extractValue(session, record, entry);

      if (coll != null) {
        if (value instanceof Identifiable) {
          coll.add(value);
        } else {
          MultiValue.add(coll, value);
        }
      } else {
        if (!(value instanceof Identifiable)) {
          throw new CommandExecutionException(session,
              "Only links or records can be added to LINKBAG");
        }

        bag.add(((Identifiable) value).getIdentity());
      }
      updated = true;
    }
    return updated;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private boolean handlePutEntries(DatabaseSessionInternal session, EntityImpl record) {
    var updated = false;
    if (!putEntries.isEmpty()) {
      // BIND VALUES TO PUT (AS MAP)
      for (var entry : putEntries) {
        var fieldValue = record.getProperty(entry.getKey());

        if (fieldValue == null) {
          SchemaImmutableClass result1 = null;
          if (record != null) {
            result1 = record.getImmutableSchemaClass(session);
          }
          if (result1 != null) {
            SchemaImmutableClass result = null;
            if (record != null) {
              result = record.getImmutableSchemaClass(session);
            }
            final var property =
                result
                    .getProperty(entry.getKey());
            if (property != null
                && (property.getType() != null
                && (!property.getType().equals(PropertyTypeInternal.EMBEDDEDMAP)
                && !property.getType().equals(PropertyTypeInternal.LINKMAP)))) {
              throw new CommandExecutionException(session,
                  "field " + entry.getKey() + " is not defined as a map");
            }
          }
          fieldValue = new HashMap<String, Object>();
          final String iFieldName = entry.getKey();
          record.setProperty(iFieldName, fieldValue);
        }

        if (fieldValue instanceof Map<?, ?>) {
          var map = (Map<String, Object>) fieldValue;

          var pair = entry.getValue();

          var value = extractValue(session, record, pair);

          SchemaImmutableClass result1 = null;
          if (record != null) {
            result1 = record.getImmutableSchemaClass(session);
          }
          if (result1 != null) {
            SchemaImmutableClass result = null;
            if (record != null) {
              result = record.getImmutableSchemaClass(session);
            }
            final var property =
                result
                    .getProperty(entry.getKey());
            if (property != null
                && property.getType().equals(PropertyTypeInternal.LINKMAP)
                && !(value instanceof Identifiable)) {
              throw new CommandExecutionException(session,
                  "field " + entry.getKey() + " defined of type LINKMAP accept only link values");
            }
          }
          if (PropertyTypeInternal.LINKMAP.equals(PropertyTypeInternal.getTypeByValue(fieldValue))
              && !(value instanceof Identifiable)) {
            map = new TrackedMap(record, map, Object.class);
            String fieldName = entry.getKey();
            record.setProperty(fieldName, map, PropertyType.EMBEDDEDMAP);
          }
          map.put(pair.getKey(), value);
          updated = true;
        }
      }
    }
    return updated;
  }

  private boolean handleRemoveEntries(DatabaseSessionInternal querySession, EntityImpl record) {
    var updated = false;
    if (!removeEntries.isEmpty()) {
      // REMOVE FIELD IF ANY
      for (var entry : removeEntries) {
        var value = extractValue(querySession, record, entry);

        if (value == EMPTY_VALUE) {
          record.removeProperty(entry.getKey());
          updated = true;
        } else {
          final var fieldValue = record.getProperty(entry.getKey());

          if (fieldValue instanceof Collection<?>) {
            updated = removeFromCollection(updated, value, (Collection<?>) fieldValue);
          } else if (fieldValue instanceof Map<?, ?>) {
            updated = removeFromMap(updated, value, (Map<?, ?>) fieldValue);
          } else if (fieldValue instanceof RidBag) {
            updated = removeFromBag(record, updated, value, (RidBag) fieldValue);
          }
        }
      }
    }
    return updated;
  }

  private boolean removeFromCollection(boolean updated, Object value, Collection<?> collection) {
    if (value instanceof Collection<?>) {
      updated |= collection.removeAll(((Collection) value));
    } else {
      updated |= collection.remove(value);
    }
    return updated;
  }

  private boolean removeFromMap(boolean updated, Object value, Map<?, ?> map) {
    if (value instanceof Collection) {
      for (var o : ((Collection) value)) {
        updated |= map.remove(o) != null;
      }
    } else {
      updated |= map.remove(value) != null;
    }
    return updated;
  }

  private static boolean removeFromBag(EntityImpl record, boolean updated, Object value,
      RidBag bag) {
    if (value instanceof Collection) {
      for (var o : ((Collection) value)) {
        updated |= removeSingleValueFromBag(bag, o, record);
      }
    } else {
      updated |= removeSingleValueFromBag(bag, value, record);
    }
    return updated;
  }

  private static boolean removeSingleValueFromBag(RidBag bag, Object value, EntityImpl record) {
    if (!(value instanceof Identifiable)) {
      throw new CommandExecutionException((String) null,
          "Only links or records can be removed from LINKBAG");
    }

    bag.remove(((Identifiable) value).getIdentity());
    return record.isDirty();
  }

  private Object extractValue(DatabaseSessionInternal requestSession, EntityImpl record,
      Pair<String, Object> entry) {
    var value = entry.getValue();

    if (value instanceof SQLFilterItem) {
      value = ((SQLFilterItem) value).getValue(record, null, context);
    } else if (value instanceof CommandRequest) {
      value = ((CommandRequest) value).execute(requestSession, record, null, context);
    }

    if (value instanceof Identifiable && ((Identifiable) value).getIdentity().isPersistent())
    // USE ONLY THE RID TO AVOID CONCURRENCY PROBLEM WITH OLD VERSIONS
    {
      value = ((Identifiable) value).getIdentity();
    }
    return value;
  }

  private void parseAddFields(DatabaseSessionInternal session) {
    String fieldName;
    String fieldValue;

    var firstLap = true;
    while (!parserIsEnded()
        && (firstLap || parserGetLastSeparator() == ',' || parserGetCurrentChar() == ',')
        && !parserGetLastWord().equals(KEYWORD_WHERE)) {

      fieldName = parserRequiredWord(false, "Field name expected", session.getDatabaseName());
      parserRequiredKeyword(session.getDatabaseName(), "=");
      fieldValue = parserRequiredWord(false, "Value expected", " =><,\r\n",
          session.getDatabaseName());

      final var v = convertValue(session, clazz, fieldName,
          getFieldValueCountingParameters(fieldValue));

      // INSERT TRANSFORMED FIELD VALUE
      addEntries.add(new Pair<>(fieldName, v));
      parserSkipWhiteSpaces();

      firstLap = false;
    }

    if (addEntries.isEmpty()) {
      throwSyntaxErrorException(session.getDatabaseName(),
          "Entries to add <field> = <value> are missed. Example: name = 'Bill', salary = 300.2.");
    }
  }

  private void parsePutFields() {
    String fieldName;
    String fieldKey;
    String fieldValue;

    var firstLap = true;
    while (!parserIsEnded()
        && (firstLap || parserGetLastSeparator() == ',' || parserGetCurrentChar() == ',')
        && !parserGetLastWord().equals(KEYWORD_WHERE)) {

      fieldName = parserRequiredWord(false, "Field name expected", null);
      parserRequiredKeyword(null, "=");
      fieldKey = parserRequiredWord(false, "Key expected", null);
      fieldValue = getBlock(parserRequiredWord(false, "Value expected", " =><,\r\n", null));

      // INSERT TRANSFORMED FIELD VALUE
      putEntries.add(
          new Triple<>(
              fieldName,
              (String) getFieldValueCountingParameters(fieldKey),
              getFieldValueCountingParameters(fieldValue)));
      parserSkipWhiteSpaces();

      firstLap = false;
    }

    if (putEntries.isEmpty()) {
      throwSyntaxErrorException(null,
          "Entries to put <field> = <key>, <value> are missed. Example: name = 'Bill', 30");
    }
  }

  private void parseRemoveFields() {
    String fieldName;
    String fieldValue;
    Object value;

    var firstLap = true;
    while (!parserIsEnded()
        && (firstLap || parserGetLastSeparator() == ',' || parserGetCurrentChar() == ',')
        && !parserGetLastWord().equals(KEYWORD_WHERE)) {

      fieldName = parserRequiredWord(false, "Field name expected", null);
      final var found = parserOptionalKeyword(null, "=", "WHERE");
      if (found) {
        if (parserGetLastWord().equals("WHERE")) {
          parserGoBack();
          value = EMPTY_VALUE;
        } else {
          fieldValue = getBlock(parserRequiredWord(false,
              "Value expected", " =><,\r\n", null));
          value = getFieldValueCountingParameters(fieldValue);
        }
      } else {
        value = EMPTY_VALUE;
      }

      // INSERT FIELD NAME TO BE REMOVED
      removeEntries.add(new Pair<String, Object>(fieldName, value));
      parserSkipWhiteSpaces();

      firstLap = false;
    }

    if (removeEntries.size() == 0) {
      throwSyntaxErrorException(null, "Field(s) to remove are missed. Example: name, salary");
    }
  }

  private void parseIncrementFields() {
    String fieldName;
    String fieldValue;

    var firstLap = true;
    while (!parserIsEnded()
        && (firstLap || parserGetLastSeparator() == ',')
        && !parserGetLastWord().equals(KEYWORD_WHERE)) {

      fieldName = parserRequiredWord(false, "Field name expected", null);
      parserRequiredKeyword(null, "=");
      fieldValue = getBlock(parserRequiredWord(false, "Value expected", null));

      // INSERT TRANSFORMED FIELD VALUE
      incrementEntries.add(new Pair(fieldName, getFieldValueCountingParameters(fieldValue)));
      parserSkipWhiteSpaces();

      firstLap = false;
    }

    if (incrementEntries.size() == 0) {
      throwSyntaxErrorException(null,
          "Entries to increment <field> = <value> are missed. Example: salary = -100");
    }
  }

  @Override
  public Object getResult() {
    return null;
  }
}
