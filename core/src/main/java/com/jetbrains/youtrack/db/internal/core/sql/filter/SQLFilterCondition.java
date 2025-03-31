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
package com.jetbrains.youtrack.db.internal.core.sql.filter;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.Collate;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.QueryParsingException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.query.QueryRuntimeValueMulti;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.BinaryField;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.BytesContainer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrack.db.internal.core.sql.SQLHelper;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperator;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorMatches;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Run-time query condition evaluator.
 */
public class SQLFilterCondition {

  private static final String NULL_VALUE = "null";
  protected Object left;
  protected QueryOperator operator;
  protected Object right;
  protected boolean inBraces = false;

  public SQLFilterCondition(final Object iLeft, final QueryOperator iOperator) {
    this.left = iLeft;
    this.operator = iOperator;
  }

  public SQLFilterCondition(
      final Object iLeft, final QueryOperator iOperator, final Object iRight) {
    this.left = iLeft;
    this.operator = iOperator;
    this.right = iRight;
  }

  public Object evaluate(
      final Result iCurrentRecord,
      final EntityImpl iCurrentResult,
      final CommandContext iContext) {
    var session = iContext.getDatabaseSession();
    var binaryEvaluation =
        operator != null && operator.isSupportingBinaryEvaluate()
            && session.getSerializer().getSupportBinaryEvaluate()
            && iCurrentRecord != null && iCurrentRecord.isEntity()
            && iCurrentRecord.getIdentity().isPersistent();

    var l = evaluate(iCurrentRecord, iCurrentResult, left, iContext, binaryEvaluation);

    if (operator == null || operator.canShortCircuit(l)) {
      return l;
    }

    var r = evaluate(iCurrentRecord, iCurrentResult, right, iContext, binaryEvaluation);
    var schema = session.getMetadata().getImmutableSchemaSnapshot();

    if (binaryEvaluation && l instanceof BinaryField) {
      if (r != null && !(r instanceof BinaryField)) {
        final var type = PropertyTypeInternal.getTypeByValue(r);

        if (RecordSerializerBinary.INSTANCE
            .getCurrentSerializer()
            .getComparator()
            .isBinaryComparable(type)) {
          final var bytes = new BytesContainer();
          RecordSerializerBinary.INSTANCE
              .getCurrentSerializer()
              .serializeValue(session, bytes, r, type, null, schema, null);
          bytes.offset = 0;
          final var collate =
              r instanceof SQLFilterItemField
                  ? ((SQLFilterItemField) r).getCollate(session, iCurrentRecord)
                  : null;
          r = new BinaryField(null, type, bytes, collate);
          if (!(right instanceof SQLFilterItem || right instanceof SQLFilterCondition))
          // FIXED VALUE, REPLACE IT
          {
            right = r;
          }
        }
      } else if (r instanceof BinaryField)
      // GET THE COPY OR MT REASONS
      {
        r = ((BinaryField) r).copy();
      }
    }

    if (binaryEvaluation && r instanceof BinaryField) {
      if (l != null && !(l instanceof BinaryField)) {
        final var type = PropertyTypeInternal.getTypeByValue(l);
        if (RecordSerializerBinary.INSTANCE
            .getCurrentSerializer()
            .getComparator()
            .isBinaryComparable(type)) {
          final var bytes = new BytesContainer();
          RecordSerializerBinary.INSTANCE
              .getCurrentSerializer()
              .serializeValue(session, bytes, l, type, null, schema, null);
          bytes.offset = 0;
          final var collate =
              l instanceof SQLFilterItemField
                  ? ((SQLFilterItemField) l).getCollate(session, iCurrentRecord)
                  : null;
          l = new BinaryField(null, type, bytes, collate);
          if (!(left instanceof SQLFilterItem || left instanceof SQLFilterCondition))
          // FIXED VALUE, REPLACE IT
          {
            left = l;
          }
        }
      } else if (l instanceof BinaryField)
      // GET THE COPY OR MT REASONS
      {
        l = ((BinaryField) l).copy();
      }
    }

    if (binaryEvaluation) {
      binaryEvaluation = l instanceof BinaryField && r instanceof BinaryField;
    }

    if (!binaryEvaluation) {
      // no collate for regular expressions, otherwise quotes will result in no match
      Collate collate = null;
      if (iCurrentRecord.isEntity()) {
        var entity = iCurrentRecord.asEntity();
        collate = operator instanceof QueryOperatorMatches ? null : getCollate(session, entity);
      }

      final var convertedValues = checkForConversion(session, l, r, collate);
      if (convertedValues != null) {
        l = convertedValues[0];
        r = convertedValues[1];
      }
    }

    Object result;
    try {
      result =
          operator.evaluateRecord(
              iCurrentRecord,
              iCurrentResult,
              this,
              l,
              r,
              iContext,
              RecordSerializerBinary.INSTANCE.getCurrentSerializer());
    } catch (CommandExecutionException e) {
      throw e;
    } catch (Exception e) {
      if (LogManager.instance().isDebugEnabled()) {
        LogManager.instance().debug(this, "Error on evaluating expression (%s)", e, toString());
      }
      result = Boolean.FALSE;
    }

    return result;
  }

  @Deprecated
  public Collate getCollate() {
    if (left instanceof SQLFilterItemField) {
      return ((SQLFilterItemField) left).getCollate();
    } else if (right instanceof SQLFilterItemField) {
      return ((SQLFilterItemField) right).getCollate();
    }
    return null;
  }

  public Collate getCollate(DatabaseSessionInternal db, Identifiable identifiable) {
    if (left instanceof SQLFilterItemField) {
      return ((SQLFilterItemField) left).getCollate(db, identifiable);
    } else if (right instanceof SQLFilterItemField) {
      return ((SQLFilterItemField) right).getCollate(db, identifiable);
    }
    return null;
  }

  public RID getBeginRidRange(DatabaseSession session) {
    if (operator == null) {
      if (left instanceof SQLFilterCondition) {
        return ((SQLFilterCondition) left).getBeginRidRange(session);
      } else {
        return null;
      }
    }

    return operator.getBeginRidRange(session, left, right);
  }

  public RID getEndRidRange(DatabaseSession session) {
    if (operator == null) {
      if (left instanceof SQLFilterCondition) {
        return ((SQLFilterCondition) left).getEndRidRange(session);
      } else {
        return null;
      }
    }

    return operator.getEndRidRange(session, left, right);
  }

  public List<String> getInvolvedFields(final List<String> list) {
    extractInvolvedFields(left, list);
    extractInvolvedFields(right, list);

    return list;
  }

  private void extractInvolvedFields(Object left, List<String> list) {
    if (left != null) {
      if (left instanceof SQLFilterItemField) {
        if (((SQLFilterItemField) left).isFieldChain()) {
          list.add(
              ((SQLFilterItemField) left)
                  .getFieldChain()
                  .getItemName(((SQLFilterItemField) left).getFieldChain().getItemCount() - 1));
        }
      } else if (left instanceof SQLFilterCondition) {
        ((SQLFilterCondition) left).getInvolvedFields(list);
      }
    }
  }

  @Override
  public String toString() {
    var buffer = new StringBuilder(128);

    buffer.append('(');
    buffer.append(left);
    if (operator != null) {
      buffer.append(' ');
      buffer.append(operator);
      buffer.append(' ');
      if (right instanceof String) {
        buffer.append('\'');
      }
      buffer.append(right);
      if (right instanceof String) {
        buffer.append('\'');
      }
      buffer.append(')');
    }

    return buffer.toString();
  }

  public String asString(DatabaseSessionInternal session) {
    var buffer = new StringBuilder(128);

    buffer.append('(');
    if (left instanceof SQLFilterItemAbstract filterItemAbstract) {
      buffer.append(filterItemAbstract.asString(session));
    } else {
      buffer.append(left);
    }
    if (operator != null) {
      buffer.append(' ');
      buffer.append(operator);
      buffer.append(' ');
      if (right instanceof SQLFilterItemAbstract filterItemAbstract) {
        buffer.append(filterItemAbstract.asString(session));
      } else {
        if (right instanceof String) {
          buffer.append('\'');
        }
        buffer.append(right);
        if (right instanceof String) {
          buffer.append('\'');
        }
      }
      buffer.append(')');
    }

    return buffer.toString();
  }

  public Object getLeft() {
    return left;
  }

  public void setLeft(final Object iValue) {
    left = iValue;
  }

  public Object getRight() {
    return right;
  }

  public void setRight(final Object iValue) {
    right = iValue;
  }

  public QueryOperator getOperator() {
    return operator;
  }

  protected Integer getInteger(Object iValue) {
    if (iValue == null) {
      return null;
    }

    final var stringValue = iValue.toString();

    if (NULL_VALUE.equals(stringValue)) {
      return null;
    }
    if (SQLHelper.DEFINED.equals(stringValue)) {
      return null;
    }

    if (StringSerializerHelper.contains(stringValue, '.')
        || StringSerializerHelper.contains(stringValue, ',')) {
      return (int) Float.parseFloat(stringValue);
    } else {
      return !stringValue.isEmpty() ? Integer.valueOf(stringValue) : Integer.valueOf(0);
    }
  }

  protected static Float getFloat(final Object iValue) {
    if (iValue == null) {
      return null;
    }

    final var stringValue = iValue.toString();

    if (NULL_VALUE.equals(stringValue)) {
      return null;
    }

    return !stringValue.isEmpty() ? Float.valueOf(stringValue) : Float.valueOf(0);
  }

  protected Date getDate(final Object value, DatabaseSessionInternal session) {
    if (value == null) {
      return null;
    }

    final var config = session.getStorageInfo().getConfiguration();

    if (value instanceof Long) {
      var calendar = Calendar.getInstance(config.getTimeZone());
      calendar.setTimeInMillis(((Long) value));
      return calendar.getTime();
    }

    var stringValue = value.toString();

    if (NULL_VALUE.equals(stringValue)) {
      return null;
    }

    if (stringValue.length() <= 0) {
      return null;
    }

    if (Pattern.matches("^\\d+$", stringValue)) {
      return new Date(Long.valueOf(stringValue).longValue());
    }

    var formatter = config.getDateFormatInstance();

    if (stringValue.length() > config.getDateFormat().length())
    // ASSUMES YOU'RE USING THE DATE-TIME FORMATTE
    {
      formatter = config.getDateTimeFormatInstance();
    }

    try {
      return formatter.parse(stringValue);
    } catch (ParseException ignore) {
      try {
        return new Date(Double.valueOf(stringValue).longValue());
      } catch (Exception pe2) {
        throw BaseException.wrapException(
            new QueryParsingException(session.getDatabaseName(),
                "Error on conversion of date '"
                    + stringValue
                    + "' using the format: "
                    + formatter.toPattern()),
            pe2, session);
      }
    }
  }

  protected static Object evaluate(
      Result iCurrentRecord,
      final EntityImpl iCurrentResult,
      final Object iValue,
      final CommandContext iContext,
      final boolean binaryEvaluation) {
    if (iValue == null) {
      return null;
    }

    if (iValue instanceof BytesContainer) {
      return iValue;
    }

    var session = iContext.getDatabaseSession();
    if (iCurrentRecord != null && iCurrentRecord.isEntity()) {
      var entity = iCurrentRecord.asEntity();
      if (binaryEvaluation
          && iValue instanceof SQLFilterItemField
          && !entity.isDirty()
          && !((RecordId)entity.getIdentity()).isTemporary()) {
        final var bField = ((SQLFilterItemField) iValue).getBinaryField(session, entity);
        if (bField != null) {
          return bField;
        }
      }
    }

    if (iValue instanceof SQLFilterItem) {
      return ((SQLFilterItem) iValue).getValue(iCurrentRecord, iCurrentResult, iContext);
    }

    if (iValue instanceof SQLFilterCondition) {
      // NESTED CONDITION: EVALUATE IT RECURSIVELY
      return ((SQLFilterCondition) iValue).evaluate(iCurrentRecord, iCurrentResult, iContext);
    }

    if (MultiValue.isMultiValue(iValue) && !Map.class.isAssignableFrom(iValue.getClass())) {
      final Iterable<?> multiValue = MultiValue.getMultiValueIterable(iValue);

      // MULTI VALUE: RETURN A COPY
      final var result = new ArrayList<>(MultiValue.getSize(iValue));

      for (final var value : multiValue) {
        if (value instanceof SQLFilterItem) {
          result.add(((SQLFilterItem) value).getValue(iCurrentRecord, iCurrentResult, iContext));
        } else {
          result.add(value);
        }
      }
      return result;
    }

    // SIMPLE VALUE: JUST RETURN IT
    return iValue;
  }

  private Object[] checkForConversion(
      DatabaseSessionInternal session, Object l, Object r,
      final Collate collate) {
    Object[] result = null;

    final var oldL = l;
    final var oldR = r;
    if (collate != null) {

      l = collate.transform(l);
      r = collate.transform(r);

      if (l != oldL || r != oldR)
      // CHANGED
      {
        result = new Object[]{l, r};
      }
    }

    try {
      // DEFINED OPERATOR
      if ((oldR instanceof String && oldR.equals(SQLHelper.DEFINED))
          || (oldL instanceof String && oldL.equals(SQLHelper.DEFINED))) {
        result = new Object[]{((SQLFilterItemAbstract) this.left).getRoot(session), r};
      } else if ((oldR instanceof String && oldR.equals(SQLHelper.NOT_NULL))
          || (oldL instanceof String && oldL.equals(SQLHelper.NOT_NULL))) {
        // NOT_NULL OPERATOR
        result = null;
      } else if (l != null
          && r != null
          && !l.getClass().isAssignableFrom(r.getClass())
          && !r.getClass().isAssignableFrom(l.getClass()))
      // INTEGERS
      {
        if (r instanceof Integer && !(l instanceof Number || l instanceof Collection)) {
          if (l instanceof String && ((String) l).indexOf('.') > -1) {
            result = new Object[]{Float.valueOf((String) l).intValue(), r};
          } else if (l instanceof Date) {
            result = new Object[]{((Date) l).getTime(), r};
          } else if (!(l instanceof QueryRuntimeValueMulti)
              && !(l instanceof Collection<?>)
              && !l.getClass().isArray()
              && !(l instanceof Map)) {
            result = new Object[]{getInteger(l), r};
          }
        } else if (l instanceof Integer && !(r instanceof Number || r instanceof Collection)) {
          if (r instanceof String && ((String) r).indexOf('.') > -1) {
            result = new Object[]{l, Float.valueOf((String) r).intValue()};
          } else if (r instanceof Date) {
            result = new Object[]{l, ((Date) r).getTime()};
          } else if (!(r instanceof QueryRuntimeValueMulti)
              && !(r instanceof Collection<?>)
              && !r.getClass().isArray()
              && !(r instanceof Map)) {
            result = new Object[]{l, getInteger(r)};
          }
        } else if (r instanceof Date && !(l instanceof Collection || l instanceof Date)) {
          // DATES
          result = new Object[]{getDate(l, session), r};
        } else if (l instanceof Date && !(r instanceof Collection || r instanceof Date)) {
          // DATES
          result = new Object[]{l, getDate(r, session)};
        } else if (r instanceof Float && !(l instanceof Float || l instanceof Collection)) {
          // FLOATS
          result = new Object[]{getFloat(l), r};
        } else if (l instanceof Float && !(r instanceof Float || r instanceof Collection)) {
          // FLOATS
          result = new Object[]{l, getFloat(r)};
        } else if (r instanceof RID && l instanceof String && !oldL.equals(SQLHelper.NOT_NULL)) {
          // RIDS
          result = new Object[]{new RecordId((String) l), r};
        } else if (l instanceof RID && r instanceof String && !oldR.equals(SQLHelper.NOT_NULL)) {
          // RIDS
          result = new Object[]{l, new RecordId((String) r)};
        }
      }
    } catch (Exception ignore) {
      // JUST IGNORE CONVERSION ERRORS
    }

    return result;
  }
}
