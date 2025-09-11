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
package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.io.YTDBIOUtils;
import com.jetbrains.youtrackdb.internal.common.util.Pair;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseExportException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.RecordSerializerStringAbstract;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.SQLHelper;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLPredicate;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.lang.reflect.Array;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Helper class to manage documents.
 */
public class EntityHelper {

  public static final String ATTRIBUTE_THIS = "@this";
  public static final String ATTRIBUTE_RID = "@rid";
  public static final String ATTRIBUTE_RID_ID = "@rid_id";
  public static final String ATTRIBUTE_RID_POS = "@rid_pos";
  public static final String ATTRIBUTE_VERSION = "@version";
  public static final String ATTRIBUTE_CLASS = "@class";
  public static final String ATTRIBUTE_INTERNAL_ENTITY = "@internal";
  public static final String ATTRIBUTE_INDEX_MANAGER_ENTITY = "@indexManager";
  public static final String ATTRIBUTE_SCHEMA_MANAGER_ENTITY = "@schemaManager";
  public static final String ATTRIBUTE_TYPE = "@type";
  public static final String ATTRIBUTE_EMBEDDED = "@embedded";
  public static final String ATTRIBUTE_SIZE = "@size";
  public static final String ATTRIBUTE_FIELDS = "@fields";
  public static final String ATTRIBUTE_FIELD_TYPES = "@fieldtypes";
  public static final String ATTRIBUTE_RAW = "@raw";

  public interface RIDMapper {

    RID map(RID rid);
  }

  public static Set<String> getReservedAttributes() {
    Set<String> retSet = new HashSet<>();
    retSet.add(ATTRIBUTE_THIS);
    retSet.add(ATTRIBUTE_RID);
    retSet.add(ATTRIBUTE_RID_ID);
    retSet.add(ATTRIBUTE_RID_POS);
    retSet.add(ATTRIBUTE_VERSION);
    retSet.add(ATTRIBUTE_CLASS);
    retSet.add(ATTRIBUTE_TYPE);
    retSet.add(ATTRIBUTE_SIZE);
    retSet.add(ATTRIBUTE_FIELDS);
    retSet.add(ATTRIBUTE_RAW);
    retSet.add(ATTRIBUTE_FIELD_TYPES);
    return retSet;
  }

  public static void sort(
      List<? extends Identifiable> ioResultSet,
      List<Pair<String, String>> iOrderCriteria,
      CommandContext context) {
    if (ioResultSet != null) {
      ioResultSet.sort(new EntityComparator(iOrderCriteria, context));
    }
  }


  public static <RET> RET getFieldValue(DatabaseSessionEmbedded db, Object value,
      final String iFieldName) {
    var context = new BasicCommandContext();
    context.setDatabaseSession(db);

    return getFieldValue(db, value, iFieldName, context);
  }

  @Nullable
  @SuppressWarnings("unchecked")
  public static <RET> RET getFieldValue(
      DatabaseSessionEmbedded session, Object value, final String iFieldName,
      @Nonnull final CommandContext iContext) {
    if (value == null) {
      return null;
    }

    final var fieldNameLength = iFieldName.length();
    if (fieldNameLength == 0) {
      return (RET) value;
    }

    var currentRecord = value instanceof Identifiable ? (Identifiable) value : null;

    var beginPos = iFieldName.charAt(0) == '.' ? 1 : 0;
    var nextSeparatorPos = iFieldName.charAt(0) == '.' ? 1 : 0;
    var firstInChain = true;
    do {
      var nextSeparator = ' ';
      for (; nextSeparatorPos < fieldNameLength; ++nextSeparatorPos) {
        nextSeparator = iFieldName.charAt(nextSeparatorPos);
        if (nextSeparator == '.' || nextSeparator == '[') {
          break;
        }
      }

      final String fieldName;
      if (nextSeparatorPos < fieldNameLength) {
        fieldName = iFieldName.substring(beginPos, nextSeparatorPos);
      } else {
        nextSeparator = ' ';
        if (beginPos > 0) {
          fieldName = iFieldName.substring(beginPos);
        } else {
          fieldName = iFieldName;
        }
      }

      if (nextSeparator == '[') {
        if (!fieldName.isEmpty()) {
          if (currentRecord != null) {
            value = getIdentifiableValue(session, currentRecord, fieldName);
          } else if (value instanceof Map<?, ?>) {
            value = getMapEntry(session, (Map<String, ?>) value, fieldName);
          } else if (MultiValue.isMultiValue(value)) {
            final HashSet<Object> temp = new LinkedHashSet<Object>();
            for (var o : MultiValue.getMultiValueIterable(value)) {
              if (o instanceof Identifiable) {
                var r = getFieldValue(session, o, iFieldName);
                if (r != null) {
                  MultiValue.add(temp, r);
                }
              }
            }
            value = temp;
          }
        }

        if (value == null) {
          return null;
        } else if (value instanceof Identifiable) {
          currentRecord = (Identifiable) value;
        }

        // final int end = iFieldName.indexOf(']', nextSeparatorPos);
        final var end = findClosingBracketPosition(iFieldName, nextSeparatorPos);
        if (end == -1) {
          throw new IllegalArgumentException("Missed closed ']'");
        }

        var indexPart = iFieldName.substring(nextSeparatorPos + 1, end);
        if (indexPart.isEmpty()) {
          return null;
        }

        nextSeparatorPos = end;

        if (value instanceof CommandContext) {
          value = ((CommandContext) value).getVariables();
        }

        if (value instanceof Identifiable) {
          final DBRecord record;
          if (currentRecord instanceof Identifiable) {
            var transaction = session.getActiveTransaction();
            record = transaction.load(currentRecord);
          } else {
            record = null;
          }

          final var index = getIndexPart(iContext, indexPart);
          final var indexAsString = index != null ? index.toString() : null;

          final var indexParts =
              StringSerializerHelper.smartSplit(
                  indexAsString, ',', StringSerializerHelper.DEFAULT_IGNORE_CHARS);
          final var indexRanges =
              StringSerializerHelper.smartSplit(indexAsString, '-', ' ');
          final var indexCondition =
              StringSerializerHelper.smartSplit(indexAsString, '=', ' ');

          if (indexParts.size() == 1 && indexCondition.size() == 1 && indexRanges.size() == 1)
          // SINGLE VALUE
          {
            value = ((EntityImpl) record).getProperty(indexAsString);
          } else if (indexParts.size() > 1) {
            // MULTI VALUE
            final var values = new Object[indexParts.size()];
            for (var i = 0; i < indexParts.size(); ++i) {
              final var iFieldName1 = YTDBIOUtils.getStringContent(indexParts.get(i));
              values[i] = ((EntityImpl) record).getProperty(iFieldName1);
            }
            value = values;
          } else if (indexRanges.size() > 1) {

            // MULTI VALUES RANGE
            var from = indexRanges.get(0);
            var to = indexRanges.get(1);

            final var entity = (EntityImpl) record;

            final var fieldNames = entity.propertyNames();
            final var rangeFrom = from != null && !from.isEmpty() ? Integer.parseInt(from) : 0;
            final var rangeTo =
                to != null && !to.isEmpty()
                    ? Math.min(Integer.parseInt(to), fieldNames.length - 1)
                    : fieldNames.length - 1;

            final var values = new Object[rangeTo - rangeFrom + 1];

            for (var i = rangeFrom; i <= rangeTo; ++i) {
              values[i - rangeFrom] = entity.getProperty(fieldNames[i]);
            }

            value = values;

          } else if (!indexCondition.isEmpty()) {
            // CONDITION
            final var conditionFieldName = indexCondition.get(0);
            var conditionFieldValue =
                RecordSerializerStringAbstract.getTypeValue(session, indexCondition.get(1));

            if (conditionFieldValue instanceof String) {
              conditionFieldValue = YTDBIOUtils.getStringContent(conditionFieldValue);
            }

            final var fieldValue = getFieldValue(session, currentRecord, conditionFieldName);

            if (conditionFieldValue != null && fieldValue != null) {
              var type = PropertyTypeInternal.getTypeByValue(fieldValue);
              conditionFieldValue = type.convert(conditionFieldValue, null,
                  null, session);
            }

            if (fieldValue == null && !conditionFieldValue.equals("null")
                || fieldValue != null && !fieldValue.equals(conditionFieldValue)) {
              value = null;
            }
          }
        } else if (value instanceof Map<?, ?> || value instanceof Result) {
          final var index = getIndexPart(iContext, indexPart);
          final var indexAsString = index != null ? index.toString() : null;

          final var indexParts =
              StringSerializerHelper.smartSplit(
                  indexAsString, ',', StringSerializerHelper.DEFAULT_IGNORE_CHARS);
          final var indexRanges =
              StringSerializerHelper.smartSplit(indexAsString, '-', ' ');
          final var indexCondition =
              StringSerializerHelper.smartSplit(indexAsString, '=', ' ');

          if (indexParts.size() == 1 && indexCondition.size() == 1 && indexRanges.size() == 1)
          // SINGLE VALUE
          {
            if (value instanceof Map<?, ?> map) {
              value = map.get(index.toString());
            } else {
              var result = (Result) value;
              value = result.getProperty(indexAsString);
            }
          } else if (indexParts.size() > 1) {
            // MULTI VALUE
            final var values = new Object[indexParts.size()];
            if (value instanceof Map<?, ?> map) {
              for (var i = 0; i < indexParts.size(); ++i) {
                values[i] = map.get(YTDBIOUtils.getStringContent(indexParts.get(i)));
              }
            } else {
              var result = (Result) value;
              for (var i = 0; i < indexParts.size(); ++i) {
                values[i] = result.getProperty(YTDBIOUtils.getStringContent(indexParts.get(i)));
              }
            }
            value = values;
          } else if (indexRanges.size() > 1) {

            // MULTI VALUES RANGE
            var from = indexRanges.get(0);
            var to = indexRanges.get(1);

            final ArrayList<String> fieldNames;
            if (value instanceof Map<?, ?> map) {
              fieldNames = new ArrayList<>(((Map<String, Object>) map).keySet());
            } else {
              var result = (Result) value;
              fieldNames = new ArrayList<>(result.getPropertyNames());
            }
            final var rangeFrom = from != null && !from.isEmpty() ? Integer.parseInt(from) : 0;
            final var rangeTo =
                to != null && !to.isEmpty()
                    ? Math.min(Integer.parseInt(to), fieldNames.size() - 1)
                    : fieldNames.size() - 1;

            final var values = new Object[rangeTo - rangeFrom + 1];

            if (value instanceof Map<?, ?> map) {
              for (var i = rangeFrom; i <= rangeTo; ++i) {
                values[i - rangeFrom] = map.get(fieldNames.get(i));
              }
            } else {
              var result = (Result) value;

              for (var i = rangeFrom; i <= rangeTo; ++i) {
                values[i - rangeFrom] = result.getProperty(fieldNames.get(i));
              }
            }

            value = values;

          } else if (!indexCondition.isEmpty()) {
            // CONDITION
            final var conditionFieldName = indexCondition.get(0);
            var conditionFieldValue =
                RecordSerializerStringAbstract.getTypeValue(session, indexCondition.get(1));

            if (conditionFieldValue instanceof String) {
              conditionFieldValue = YTDBIOUtils.getStringContent(conditionFieldValue);
            }

            Object fieldValue = null;

            if (value instanceof Map<?, ?> map) {
              value = map.get(conditionFieldName);
            } else {
              var result = (Result) value;
              fieldValue = result.getProperty(conditionFieldName);
            }

            if (conditionFieldValue != null && fieldValue != null) {
              var type = PropertyTypeInternal.getTypeByValue(fieldValue);
              conditionFieldValue = type.convert(conditionFieldValue, null,
                  null, session);
            }

            if (fieldValue == null && !conditionFieldValue.equals("null")
                || fieldValue != null && !fieldValue.equals(conditionFieldValue)) {
              value = null;
            }
          }

        } else if (MultiValue.isMultiValue(value)) {
          // MULTI VALUE
          final var index = getIndexPart(iContext, indexPart);
          final var indexAsString = index != null ? index.toString() : null;

          final var indexParts = StringSerializerHelper.smartSplit(indexAsString, ',');
          final var indexRanges = StringSerializerHelper.smartSplit(indexAsString, '-');
          if (isFieldName(indexAsString)) {
            // SINGLE VALUE
            if (Character.isDigit(indexAsString.charAt(0))) {
              value = MultiValue.getValue(value, Integer.parseInt(indexAsString));
            } else
            // FILTER BY FIELD
            {
              value = getFieldValue(session, value, indexAsString, iContext);
            }

          } else if (isListOfNumbers(indexParts)) {

            // MULTI VALUES
            final var values = new Object[indexParts.size()];
            for (var i = 0; i < indexParts.size(); ++i) {
              values[i] = MultiValue.getValue(value, Integer.parseInt(indexParts.get(i)));
            }
            if (indexParts.size() > 1) {
              value = values;
            } else {
              value = values[0];
            }

          } else if (isListOfNumbers(indexRanges)) {

            // MULTI VALUES RANGE
            var from = indexRanges.get(0);
            var to = indexRanges.get(1);

            final var rangeFrom = from != null && !from.isEmpty() ? Integer.parseInt(from) : 0;
            final int rangeTo;
            if (to != null && !to.isEmpty()) {
              rangeTo = Math.min(Integer.parseInt(to), (int) MultiValue.getSize(value) - 1);
            } else {
              rangeTo = (int) MultiValue.getSize(value) - 1;
            }

            var arraySize = rangeTo - rangeFrom + 1;
            if (arraySize < 0) {
              arraySize = 0;
            }
            final var values = new Object[arraySize];
            for (var i = rangeFrom; i <= rangeTo; ++i) {
              values[i - rangeFrom] = MultiValue.getValue(value, i);
            }
            value = values;

          } else {
            // CONDITION
            var pred = new SQLPredicate(iContext, indexAsString);
            final HashSet<Object> values = new LinkedHashSet<Object>();

            for (var v : MultiValue.getMultiValueIterable(value)) {
              if (v instanceof Identifiable identifiable) {
                var transaction = session.getActiveTransaction();
                var entity = transaction.loadEntity(identifiable);
                var result =
                    pred.evaluate(entity, (EntityImpl) entity, iContext);
                if (Boolean.TRUE.equals(result)) {
                  values.add(v);
                }
              } else if (v instanceof Map) {
                var entity = (EntityImpl) session.newEmbeddedEntity();
                entity.updateFromMap((Map<String, ?>) v);
                var result = pred.evaluate(entity, entity, iContext);
                if (Boolean.TRUE.equals(result)) {
                  values.add(v);
                }
              }
            }

            if (values.isEmpty())
            // RETURNS NULL
            {
              value = values;
            } else if (values.size() == 1)
            // RETURNS THE SINGLE ODOCUMENT
            {
              value = values.iterator().next();
            } else
            // RETURNS THE FILTERED COLLECTION
            {
              value = values;
            }
          }
        }
      } else {
        if (fieldName.isEmpty()) {
          // NO FIELD NAME: THIS IS THE CASE OF NOT USEFUL . AFTER A ] OR .
          beginPos = ++nextSeparatorPos;
          continue;
        }

        if (fieldName.charAt(0) == '$') {
          value = iContext.getVariable(fieldName);
        } else if (fieldName.contains("(")) {
          var executedMethod = false;
          if (!firstInChain && fieldName.endsWith("()")) {
            var method =
                SQLEngine.getMethod(fieldName.substring(0, fieldName.length() - 2));
            if (method != null) {
              var transaction = session.getActiveTransaction();
              value = method.execute(value,
                  currentRecord != null ? transaction.loadEntity(currentRecord) : null, iContext,
                  value,
                  new Object[]{});
              executedMethod = true;
            }
          }
          if (!executedMethod) {
            value = evaluateFunction(value, fieldName, iContext);
          }
        } else {
          final var indexCondition =
              StringSerializerHelper.smartSplit(fieldName, '=', ' ');

          if (indexCondition.size() == 2) {
            final var conditionFieldName = indexCondition.get(0);
            var conditionFieldValue =
                RecordSerializerStringAbstract.getTypeValue(session, indexCondition.get(1));

            if (conditionFieldValue instanceof String) {
              conditionFieldValue = YTDBIOUtils.getStringContent(conditionFieldValue);
            }

            value = filterItem(session, conditionFieldName, conditionFieldValue, value);

          } else if (currentRecord != null) {
            // GET THE LINKED OBJECT IF ANY
            value = getIdentifiableValue(session, currentRecord, fieldName);
          } else if (value instanceof Map<?, ?>) {
            value = getMapEntry(session, (Map<String, ?>) value, fieldName);
          } else if (value instanceof Result result) {
            value = getResultEntry(session, result, fieldName);
          } else if (MultiValue.isMultiValue(value)) {
            final Set<Object> values = new LinkedHashSet<Object>();
            for (var v : MultiValue.getMultiValueIterable(value)) {
              final Object item;

              if (v instanceof Identifiable) {
                item = getIdentifiableValue(session, (Identifiable) v, fieldName);
              } else if (v instanceof Map) {
                item = ((Map<?, ?>) v).get(fieldName);
              } else {
                item = null;
              }

              if (item != null) {
                if (item instanceof Collection<?>) {
                  values.addAll((Collection<?>) item);
                } else {
                  values.add(item);
                }
              }
            }

            if (values.isEmpty()) {
              value = null;
            } else {
              value = values;
            }
          } else {
            return null;
          }
        }
      }

      if (value instanceof Identifiable) {
        currentRecord = (Identifiable) value;
      } else {
        currentRecord = null;
      }

      beginPos = ++nextSeparatorPos;
      firstInChain = false;
    } while (nextSeparatorPos < fieldNameLength && value != null);

    return (RET) value;
  }

  private static int findClosingBracketPosition(String iFieldName, int nextSeparatorPos) {
    Character currentQuote = null;
    var escaping = false;
    var innerBrackets = 0;
    var chars = iFieldName.toCharArray();
    for (var i = nextSeparatorPos + 1; i < chars.length; i++) {
      var next = chars[i];
      if (escaping) {
        escaping = false;
      } else if (next == '\\') {
        escaping = true;
      } else if (next == '`' || next == '\'' || next == '"') {
        if (currentQuote == null) {
          currentQuote = next;
        } else if (currentQuote == next) {
          currentQuote = null;
        }

      } else if (next == '[') {
        innerBrackets++;
      } else if (next == ']') {
        if (innerBrackets == 0) {
          return i;
        }
        innerBrackets--;
      }
    }
    return -1;
  }

  private static boolean isFieldName(String indexAsString) {
    indexAsString = indexAsString.trim();
    if (!indexAsString.isEmpty() && indexAsString.charAt(0) == '`'
        && indexAsString.charAt(indexAsString.length() - 1) == '`') {
      // quoted identifier
      return !indexAsString.substring(1, indexAsString.length() - 1).contains("`");
    }
    var firstChar = true;
    for (var c : indexAsString.toCharArray()) {
      if (isLetter(c) || (isNumber(c) && !firstChar)) {
        firstChar = false;
        continue;
      }
      return false;
    }
    return true;
  }

  private static boolean isNumber(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isLetter(char c) {
    if (c == '$' || c == '_' || c == '@') {
      return true;
    }
    if (c >= 'a' && c <= 'z') {
      return true;
    }
    return c >= 'A' && c <= 'Z';
  }

  private static boolean isListOfNumbers(List<String> list) {
    for (var s : list) {
      try {
        Integer.parseInt(s);
      } catch (NumberFormatException ignore) {
        return false;
      }
    }
    return true;
  }

  @Nullable
  protected static Object getIndexPart(final CommandContext iContext, final String indexPart) {
    Object index = indexPart;
    if (indexPart.indexOf(',') == -1
        && (indexPart.charAt(0) == '"' || indexPart.charAt(0) == '\'')) {
      index = YTDBIOUtils.getStringContent(indexPart);
    } else if (indexPart.charAt(0) == '$') {
      final var ctxValue = iContext.getVariable(indexPart);
      if (ctxValue == null) {
        return null;
      }
      index = ctxValue;
    } else if (!Character.isDigit(indexPart.charAt(0)))
    // GET FROM CURRENT VALUE
    {
      index = indexPart;
    }
    return index;
  }

  @Nullable
  @SuppressWarnings("unchecked")
  protected static Object filterItem(
      DatabaseSessionEmbedded db, final String iConditionFieldName,
      final Object iConditionFieldValue, final Object iValue) {
    if (iValue instanceof Identifiable) {
      final DBRecord rec;
      try {
        var transaction = db.getActiveTransaction();
        rec = transaction.load(((Identifiable) iValue));
      } catch (RecordNotFoundException rnf) {
        return null;
      }

      if (rec instanceof EntityImpl entity) {

        var fieldValue = entity.getProperty(iConditionFieldName);

        if (iConditionFieldValue == null) {
          return fieldValue == null ? entity : null;
        }

        var dbType = PropertyTypeInternal.getTypeByValue(iConditionFieldValue);
        fieldValue = dbType.convert(fieldValue, null, null, db);

        if (fieldValue != null && fieldValue.equals(iConditionFieldValue)) {
          return entity;
        }
      }
    } else if (iValue instanceof Map<?, ?>) {
      final var map = (Map<String, ?>) iValue;
      var fieldValue = getMapEntry(db, map, iConditionFieldName);

      var dbType = PropertyTypeInternal.getTypeByValue(iConditionFieldValue);
      fieldValue = dbType.convert(fieldValue, null, null, db);
      if (fieldValue != null && fieldValue.equals(iConditionFieldValue)) {
        return map;
      }
    }
    return null;
  }

  /**
   * Retrieves the value crossing the map with the dotted notation
   *
   * @param iKey Field(s) to retrieve. If are multiple fields, then the dot must be used as
   *             separator
   */
  @Nullable
  @SuppressWarnings("unchecked")
  public static Object getMapEntry(DatabaseSessionEmbedded session, final Map<String, ?> iMap,
      final Object iKey) {
    if (iMap == null || iKey == null) {
      return null;
    }

    if (iKey instanceof String iName) {
      var pos = iName.indexOf('.');
      if (pos > -1) {
        iName = iName.substring(0, pos);
      }

      final var value = iMap.get(iName);
      if (value == null) {
        return null;
      }

      if (pos > -1) {
        final var restFieldName = iName.substring(pos + 1);
        if (value instanceof EntityImpl) {
          return getFieldValue(session, value, restFieldName);
        } else if (value instanceof Map<?, ?>) {
          return getMapEntry(session, (Map<String, ?>) value, restFieldName);
        }
      }

      return value;
    } else {
      return iMap.get(iKey.toString());
    }
  }

  @Nullable
  public static Object getResultEntry(DatabaseSessionEmbedded session, final Result result,
      final Object iKey) {
    if (result == null || iKey == null) {
      return null;
    }

    if (iKey instanceof String iName) {
      var pos = iName.indexOf('.');
      if (pos > -1) {
        iName = iName.substring(0, pos);
      }

      final var value = result.getProperty(iName);
      if (value == null) {
        return null;
      }

      if (pos > -1) {
        final var restFieldName = iName.substring(pos + 1);
        if (value instanceof EntityImpl) {
          return getFieldValue(session, value, restFieldName);
        } else if (value instanceof Map<?, ?>) {
          return getMapEntry(session, (Map<String, ?>) value, restFieldName);
        } else if (value instanceof Result res) {
          return getResultEntry(session, res, restFieldName);
        }
      }

      return value;
    } else {
      return result.getProperty(iKey.toString());
    }
  }

  @Nullable
  public static Object getIdentifiableValue(@Nonnull DatabaseSessionInternal session,
      final Identifiable current,
      final String iFieldName) {
    if (iFieldName == null) {
      return null;
    }
    if (current == null) {
      return null;
    }

    var result = getRecordAttribute(current, iFieldName);
    if (result != null) {
      return result;
    }

    try {
      var transaction = session.getActiveTransaction();
      final EntityImpl entity = transaction.load(current);
      return entity.accessProperty(iFieldName);
    } catch (RecordNotFoundException rnf) {
      return null;
    }
  }

  @Nullable
  public static Object getRecordAttribute(Identifiable current, String iFieldName) {
    if (!iFieldName.isEmpty()) {
      final var begin = iFieldName.charAt(0);
      if (begin == '@') {
        // RETURN AN ATTRIBUTE
        if (iFieldName.equalsIgnoreCase(ATTRIBUTE_THIS)) {
          return current;
        } else if (iFieldName.equalsIgnoreCase(ATTRIBUTE_RID)) {
          return current.getIdentity();
        } else if (iFieldName.equalsIgnoreCase(ATTRIBUTE_RID_ID)) {
          return current.getIdentity().getCollectionId();
        } else if (iFieldName.equalsIgnoreCase(ATTRIBUTE_RID_POS)) {
          return current.getIdentity().getCollectionPosition();
        } else if (iFieldName.equalsIgnoreCase(ATTRIBUTE_VERSION)) {
          if (current instanceof RecordAbstract recordAbstract) {
            return recordAbstract.getVersion();
          }

          return -1;
        } else if (iFieldName.equalsIgnoreCase(ATTRIBUTE_CLASS)) {
          return ((EntityImpl) current).getSchemaClassName();
        } else if (iFieldName.equalsIgnoreCase(ATTRIBUTE_TYPE)) {
          if (current instanceof RecordAbstract recordAbstract) {
            return YouTrackDBEnginesManager.instance()
                .getRecordFactoryManager()
                .getRecordTypeName(
                    recordAbstract.getRecordType());
          }
          return null;
        } else if (iFieldName.equalsIgnoreCase(ATTRIBUTE_SIZE)) {
          if (current instanceof RecordAbstract recordAbstract) {
            final var stream = recordAbstract.toStream();
            return stream != null ? stream.length : 0;
          }
          return null;
        } else if (iFieldName.equalsIgnoreCase(ATTRIBUTE_FIELDS)) {
          if (current instanceof Entity entity) {
            return entity.getPropertyNames();
          }
          return Collections.emptyList();
        } else if (iFieldName.equalsIgnoreCase(ATTRIBUTE_RAW)) {
          if (current instanceof RecordAbstract recordAbstract) {
            return new String(recordAbstract.toStream());
          }
          return null;
        }
      }
      return null;
    } else {
      return null;
    }
  }

  @Nullable
  public static Object evaluateFunction(
      final Object currentValue, final String iFunction, final CommandContext iContext) {
    if (currentValue == null) {
      return null;
    }

    Object result = null;

    final var function = iFunction.toUpperCase(Locale.ENGLISH);

    if (function.startsWith("SIZE(")) {
      result = currentValue instanceof DBRecord ? 1 : MultiValue.getSize(currentValue);
    } else if (function.startsWith("LENGTH(")) {
      result = currentValue.toString().length();
    } else if (function.startsWith("TOUPPERCASE(")) {
      result = currentValue.toString().toUpperCase(Locale.ENGLISH);
    } else if (function.startsWith("TOLOWERCASE(")) {
      result = currentValue.toString().toLowerCase(Locale.ENGLISH);
    } else if (function.startsWith("TRIM(")) {
      result = currentValue.toString().trim();
    } else if (function.startsWith("TOJSON(")) {
      result = currentValue instanceof EntityImpl ? ((EntityImpl) currentValue).toJSON() : null;
    } else if (function.startsWith("KEYS(")) {
      result = currentValue instanceof Map<?, ?> ? ((Map<?, ?>) currentValue).keySet() : null;
    } else if (function.startsWith("VALUES(")) {
      result = currentValue instanceof Map<?, ?> ? ((Map<?, ?>) currentValue).values() : null;
    } else if (function.startsWith("ASSTRING(")) {
      result = currentValue.toString();
    } else if (function.startsWith("ASINTEGER(")) {
      result = Integer.parseInt(currentValue.toString());
    } else if (function.startsWith("ASFLOAT(")) {
      result = Float.parseFloat(currentValue.toString());
    } else if (function.startsWith("ASBOOLEAN(")) {
      if (currentValue instanceof String) {
        result = Boolean.parseBoolean((String) currentValue);
      } else if (currentValue instanceof Number) {
        final var bValue = ((Number) currentValue).intValue();
        if (bValue == 0) {
          result = Boolean.FALSE;
        } else if (bValue == 1) {
          result = Boolean.TRUE;
        }
      }
    } else if (function.startsWith("ASDATE(")) {
      if (currentValue instanceof Date) {
        result = currentValue;
      } else if (currentValue instanceof Number) {
        result = new Date(((Number) currentValue).longValue());
      } else {
        try {
          result =
              DateHelper.getDateFormatInstance(iContext.getDatabaseSession())
                  .parse(currentValue.toString());
        } catch (ParseException ignore) {
        }
      }
    } else if (function.startsWith("ASDATETIME(")) {
      if (currentValue instanceof Date) {
        result = currentValue;
      } else if (currentValue instanceof Number) {
        result = new Date(((Number) currentValue).longValue());
      } else {
        try {
          result =
              DateHelper.getDateTimeFormatInstance(iContext.getDatabaseSession())
                  .parse(currentValue.toString());
        } catch (ParseException ignore) {
        }
      }
    } else {
      // EXTRACT ARGUMENTS
      final var args =
          StringSerializerHelper.getParameters(iFunction.substring(iFunction.indexOf('(')));

      final var currentRecord =
          iContext != null ? (Entity) iContext.getVariable("$current") : null;
      for (var i = 0; i < args.size(); ++i) {
        final var arg = args.get(i);
        final var o = SQLHelper.getValue(arg, currentRecord, iContext);
        if (o != null) {
          args.set(i, o.toString());
        }
      }

      if (function.startsWith("CHARAT(")) {
        result = currentValue.toString().charAt(Integer.parseInt(args.getFirst()));
      } else if (function.startsWith("INDEXOF(")) {
        if (args.size() == 1) {
          result = currentValue.toString().indexOf(YTDBIOUtils.getStringContent(args.getFirst()));
        } else {
          result =
              currentValue
                  .toString()
                  .indexOf(YTDBIOUtils.getStringContent(args.get(0)),
                      Integer.parseInt(args.get(1)));
        }
      } else if (function.startsWith("SUBSTRING(")) {
        if (args.size() == 1) {
          result = currentValue.toString().substring(Integer.parseInt(args.getFirst()));
        } else {
          result =
              currentValue
                  .toString()
                  .substring(Integer.parseInt(args.get(0)), Integer.parseInt(args.get(1)));
        }
      } else if (function.startsWith("APPEND(")) {
        result = currentValue + YTDBIOUtils.getStringContent(args.getFirst());
      } else if (function.startsWith("PREFIX(")) {
        result = YTDBIOUtils.getStringContent(args.getFirst()) + currentValue;
      } else if (function.startsWith("FORMAT(")) {
        if (currentValue instanceof Date) {
          var formatter = new SimpleDateFormat(
              YTDBIOUtils.getStringContent(args.getFirst()));
          formatter.setTimeZone(DateHelper.getDatabaseTimeZone(iContext.getDatabaseSession()));
          result = formatter.format(currentValue);
        } else {
          result = String.format(YTDBIOUtils.getStringContent(args.getFirst()), currentValue);
        }
      } else if (function.startsWith("LEFT(")) {
        final var len = Integer.parseInt(args.getFirst());
        final var stringValue = currentValue.toString();
        result = stringValue.substring(0, Math.min(len, stringValue.length()));
      } else if (function.startsWith("RIGHT(")) {
        final var offset = Integer.parseInt(args.getFirst());
        final var stringValue = currentValue.toString();
        result =
            stringValue.substring(
                offset < stringValue.length() ? stringValue.length() - offset : 0);
      } else {
        final var f = SQLHelper.getFunction(iContext.getDatabaseSession(), null,
            iFunction);
        if (f != null) {
          if (currentRecord instanceof Entity entity) {
            result = f.execute(currentRecord, entity, null, iContext);
          } else {
            throw new DatabaseExportException("Cannot execute function " + iFunction
                + " because the current record is not an entity");
          }
        }
      }
    }

    return result;
  }

  public static boolean hasSameContentItem(
      final Object iCurrent,
      DatabaseSessionInternal iMyDb,
      final Object iOther,
      final DatabaseSessionInternal iOtherDb,
      RIDMapper ridMapper) {
    if (iCurrent instanceof EntityImpl current) {
      if (iOther instanceof RID) {
        if (!current.isDirty()) {
          RID id;
          if (ridMapper != null) {
            var mappedId = ridMapper.map(current.getIdentity());
            if (mappedId != null) {
              id = mappedId;
            } else {
              id = current.getIdentity();
            }
          } else {
            id = current.getIdentity();
          }

          return id.equals(iOther);
        } else {
          final EntityImpl otherEntity = iOtherDb.load((RID) iOther);
          return EntityHelper.hasSameContentOf(current, iMyDb, otherEntity, iOtherDb, ridMapper);
        }
      } else {
        return EntityHelper.hasSameContentOf(
            current, iMyDb, (EntityImpl) iOther, iOtherDb, ridMapper);
      }
    } else {
      return compareScalarValues(iCurrent, iMyDb, iOther, iOtherDb, ridMapper);
    }
  }

  /**
   * Makes a deep comparison field by field to check if the passed EntityImpl instance is identical
   * as identity and content to the current one. Instead equals() just checks if the RID are the
   * same.
   *
   * @param iOther EntityImpl instance
   * @return true if the two document are identical, otherwise false
   * @see #equals(Object)
   */
  public static boolean hasSameContentOf(
      final EntityImpl iCurrent,
      final DatabaseSessionInternal iMyDb,
      final EntityImpl iOther,
      final DatabaseSessionInternal iOtherDb,
      RIDMapper ridMapper) {
    return hasSameContentOf(iCurrent, iMyDb, iOther, iOtherDb, ridMapper, true);
  }

  /**
   * Makes a deep comparison field by field to check if the passed EntityImpl instance is identical
   * in the content to the current one. Instead equals() just checks if the RID are the same.
   *
   * @param iOther EntityImpl instance
   * @return true if the two document are identical, otherwise false
   * @see #equals(Object)
   */
  @SuppressWarnings("unchecked")
  public static boolean hasSameContentOf(
      final EntityImpl iCurrent,
      final DatabaseSessionInternal iMyDb,
      final EntityImpl iOther,
      final DatabaseSessionInternal iOtherDb,
      RIDMapper ridMapper,
      final boolean iCheckAlsoIdentity) {
    if (iOther == null) {
      return false;
    }

    if (iCheckAlsoIdentity
        && iCurrent.getIdentity().isValidPosition()
        && !iCurrent.getIdentity().equals(iOther.getIdentity())) {
      return false;
    }

    iCurrent.checkForProperties();
    iOther.checkForProperties();

    if (iCurrent.getPropertiesCount() != iOther.getPropertiesCount()) {
      return false;
    }

    // CHECK FIELD-BY-FIELD
    Object myFieldValue;
    Object otherFieldValue;

    var propertyNames = iCurrent.getPropertyNames();
    for (var name : propertyNames) {
      myFieldValue = iCurrent.getProperty(name);
      otherFieldValue = iOther.getProperty(name);

      if (myFieldValue == otherFieldValue) {
        continue;
      }

      // CHECK FOR NULLS
      if (myFieldValue == null) {
        return false;
      } else if (otherFieldValue == null) {
        return false;
      }

      if (myFieldValue instanceof Set && otherFieldValue instanceof Set) {
        if (!compareSets(
            iMyDb, (Set<?>) myFieldValue, iOtherDb, (Set<?>) otherFieldValue, ridMapper)) {
          return false;
        }
      } else if (myFieldValue instanceof Collection && otherFieldValue instanceof Collection) {
        if (!compareCollections(
            iMyDb,
            (Collection<?>) myFieldValue,
            iOtherDb,
            (Collection<?>) otherFieldValue,
            ridMapper)) {
          return false;
        }
      } else if (myFieldValue instanceof LinkBag && otherFieldValue instanceof LinkBag) {
        if (!compareBags(
            (LinkBag) myFieldValue, (LinkBag) otherFieldValue, ridMapper)) {
          return false;
        }
      } else if (myFieldValue instanceof Map && otherFieldValue instanceof Map) {
        if (!compareMaps(
            iMyDb,
            (Map<Object, Object>) myFieldValue,
            iOtherDb,
            (Map<Object, Object>) otherFieldValue,
            ridMapper)) {
          return false;
        }
      } else if (myFieldValue instanceof EntityImpl && otherFieldValue instanceof EntityImpl) {
        if (!hasSameContentOf(
            (EntityImpl) myFieldValue, iMyDb, (EntityImpl) otherFieldValue, iOtherDb,
            ridMapper)) {
          return false;
        }
      } else {
        if (!compareScalarValues(myFieldValue, iMyDb, otherFieldValue, iOtherDb, ridMapper)) {
          return false;
        }
      }
    }

    return true;
  }

  public static boolean compareMaps(
      DatabaseSessionInternal iMyDb,
      Map<Object, Object> myFieldValue,
      DatabaseSessionInternal iOtherDb,
      Map<Object, Object> otherFieldValue,
      RIDMapper ridMapper) {
    // CHECK IF THE ORDER IS RESPECTED

    if (myFieldValue.size() != otherFieldValue.size()) {
      return false;
    }

    for (var myEntry : myFieldValue.entrySet()) {
      final var myKey = myEntry.getKey();
      if (!otherFieldValue.containsKey(myKey)) {
        return false;
      }

      if (myEntry.getValue() instanceof EntityImpl entity) {
        var identifiable = ((Identifiable) otherFieldValue.get(myEntry.getKey()));
        var transaction = iOtherDb.getActiveTransaction();
        if (!hasSameContentOf(entity, iMyDb,
            transaction.load(identifiable),
            iOtherDb,
            ridMapper)) {
          return false;
        }
      } else {
        final var myValue = myEntry.getValue();
        final var otherValue = otherFieldValue.get(myEntry.getKey());
        if (!compareScalarValues(myValue, iMyDb, otherValue, iOtherDb, ridMapper)) {
          return false;
        }
      }
    }
    return true;
  }

  public static boolean compareCollections(
      DatabaseSessionInternal iMyDb,
      Collection<?> myFieldValue,
      DatabaseSessionInternal iOtherDb,
      Collection<?> otherFieldValue,
      RIDMapper ridMapper) {
    if (myFieldValue.size() != otherFieldValue.size()) {
      return false;
    }

    final var myIterator = myFieldValue.iterator();
    final var otherIterator = otherFieldValue.iterator();
    while (myIterator.hasNext()) {
      final var myNextVal = myIterator.next();
      final var otherNextVal = otherIterator.next();
      if (!hasSameContentItem(myNextVal, iMyDb, otherNextVal, iOtherDb, ridMapper)) {
        return false;
      }
    }
    return true;
  }

  public static boolean compareSets(
      DatabaseSessionInternal iMyDb,
      Set<?> myFieldValue,
      DatabaseSessionInternal iOtherDb,
      Set<?> otherFieldValue,
      RIDMapper ridMapper) {
    final var mySize = myFieldValue.size();
    final var otherSize = otherFieldValue.size();

    if (mySize != otherSize) {
      return false;
    }

    for (var myNextVal : myFieldValue) {
      final var otherIterator = otherFieldValue.iterator();
      var found = false;
      while (!found && otherIterator.hasNext()) {
        final var otherNextVal = otherIterator.next();
        found = hasSameContentItem(myNextVal, iMyDb, otherNextVal, iOtherDb, ridMapper);
      }

      if (!found) {
        return false;
      }
    }
    return true;
  }

  public static boolean isEntity(byte recordType) {
    return (recordType == EntityImpl.RECORD_TYPE || recordType == VertexEntityImpl.RECORD_TYPE ||
        recordType == StatefullEdgeEntityImpl.RECORD_TYPE);
  }

  public static boolean compareBags(
      LinkBag myFieldValue,
      LinkBag otherFieldValue,
      RIDMapper ridMapper) {
    final var mySize = myFieldValue.size();
    final var otherSize = otherFieldValue.size();

    if (mySize != otherSize) {
      return false;
    }

    final var otherBagCopy = new ArrayList<RID>();
    for (Identifiable identifiable : otherFieldValue) {
      otherBagCopy.add(identifiable.getIdentity());
    }
    for (var myIdentifiable : myFieldValue) {
      final RID otherRid;
      if (ridMapper != null) {
        var convertedRid = ridMapper.map(myIdentifiable.getIdentity());
        if (convertedRid != null) {
          otherRid = convertedRid;
        } else {
          otherRid = myIdentifiable.getIdentity();
        }
      } else {
        otherRid = myIdentifiable.getIdentity();
      }

      otherBagCopy.remove(otherRid);
    }

    return otherBagCopy.isEmpty();
  }

  private static boolean compareScalarValues(
      Object myValue,
      DatabaseSessionInternal iMyDb,
      Object otherValue,
      DatabaseSessionInternal iOtherDb,
      RIDMapper ridMapper) {
    if (myValue == null && otherValue != null || myValue != null && otherValue == null) {
      return false;
    }

    if (myValue == null) {
      return true;
    }

    if (myValue.getClass().isArray() && !otherValue.getClass().isArray()
        || !myValue.getClass().isArray() && otherValue.getClass().isArray()) {
      return false;
    }

    if (myValue.getClass().isArray()) {
      final var myArraySize = Array.getLength(myValue);
      final var otherArraySize = Array.getLength(otherValue);

      if (myArraySize != otherArraySize) {
        return false;
      }

      for (var i = 0; i < myArraySize; i++) {
        final var first = Array.get(myValue, i);
        final var second = Array.get(otherValue, i);
        if (first == null && second != null) {
          return false;
        }
        if (first instanceof EntityImpl && second instanceof EntityImpl) {
          return hasSameContentOf(
              (EntityImpl) first, iMyDb, (EntityImpl) second, iOtherDb, ridMapper);
        }

        if (first != null && !first.equals(second)) {
          return false;
        }
      }

      return true;
    }

    if (myValue instanceof Number myNumberValue && otherValue instanceof Number otherNumberValue) {

      if (isInteger(myNumberValue) && isInteger(otherNumberValue)) {
        return myNumberValue.longValue() == otherNumberValue.longValue();
      } else if (isFloat(myNumberValue) && isFloat(otherNumberValue)) {
        return myNumberValue.doubleValue() == otherNumberValue.doubleValue();
      }
    }

    if (ridMapper != null
        && myValue instanceof Identifiable myIdentifiableValue
        && otherValue instanceof Identifiable otherIdentifiableValue) {
      myValue = myIdentifiableValue.getIdentity();
      otherValue = otherIdentifiableValue.getIdentity();
      if (((RID) myValue).isPersistent()) {
        var convertedValue = ridMapper.map((RID) myValue);
        if (convertedValue != null) {
          myValue = convertedValue;
        }
      }
    }

    if (myValue instanceof Date && otherValue instanceof Date) {
      return ((Date) myValue).getTime() / 1000 == ((Date) otherValue).getTime() / 1000;
    }

    return myValue.equals(otherValue);
  }

  private static boolean isInteger(Number value) {
    return value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long;
  }

  private static boolean isFloat(Number value) {
    return value instanceof Float || value instanceof Double;
  }
}
