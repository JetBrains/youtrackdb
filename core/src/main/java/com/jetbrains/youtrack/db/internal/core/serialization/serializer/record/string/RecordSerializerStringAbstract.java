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
package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.string.StringSerializerAnyStreamable;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.string.StringSerializerEmbedded;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("serial")
public abstract class RecordSerializerStringAbstract implements RecordSerializer, Serializable {

  private static final char DECIMAL_SEPARATOR = '.';
  private static final String MAX_INTEGER_AS_STRING = String.valueOf(Integer.MAX_VALUE);
  private static final int MAX_INTEGER_DIGITS = MAX_INTEGER_AS_STRING.length();

  public static Object fieldTypeFromStream(
      DatabaseSessionInternal db, final EntityImpl entity, PropertyType iType,
      final Object iValue) {
    if (iValue == null) {
      return null;
    }

    if (iType == null) {
      iType = PropertyType.EMBEDDED;
    }

    switch (iType) {
      case STRING:
      case INTEGER:
      case BOOLEAN:
      case FLOAT:
      case DECIMAL:
      case LONG:
      case DOUBLE:
      case SHORT:
      case BYTE:
      case BINARY:
      case DATE:
      case DATETIME:
      case LINK:
        return simpleValueFromStream(db, iValue, iType);

      case EMBEDDED: {
        // EMBEDED RECORD
        final Object embeddedObject =
            StringSerializerEmbedded.INSTANCE.fromStream(db, (String) iValue);
        if (embeddedObject instanceof EntityImpl) {
          EntityInternalUtils.addOwner((EntityImpl) embeddedObject, entity);
        }

        // EMBEDDED OBJECT
        return embeddedObject;
      }

      case CUSTOM:
        // RECORD
        final Object result = StringSerializerAnyStreamable.INSTANCE.fromStream(db,
            (String) iValue);
        if (result instanceof EntityImpl) {
          EntityInternalUtils.addOwner((EntityImpl) result, entity);
        }
        return result;

      case EMBEDDEDSET:
      case EMBEDDEDLIST: {
        final String value = (String) iValue;
        return RecordSerializerSchemaAware2CSV.INSTANCE.embeddedCollectionFromStream(db,
            entity, iType, null, null, value);
      }

      case EMBEDDEDMAP: {
        final String value = (String) iValue;
        return RecordSerializerSchemaAware2CSV.INSTANCE.embeddedMapFromStream(db,
            entity, null, value, null);
      }
    }

    throw new IllegalArgumentException(
        "Type " + iType + " not supported to convert value: " + iValue);
  }

  public static Object convertValue(DatabaseSessionInternal db,
      final String iValue, final PropertyType iExpectedType) {
    final Object v = getTypeValue(db, iValue);
    return PropertyType.convert(db, v, iExpectedType.getDefaultJavaType());
  }

  public static void fieldTypeToString(
      final StringBuilder iBuffer, PropertyType iType, final Object iValue) {
    if (iValue == null) {
      return;
    }

    if (iType == null) {
      if (iValue instanceof RID) {
        iType = PropertyType.LINK;
      } else {
        iType = PropertyType.EMBEDDED;
      }
    }

    switch (iType) {
      case STRING, BOOLEAN, INTEGER, FLOAT, DECIMAL, LONG, DOUBLE, SHORT, BYTE, BINARY, DATE,
           DATETIME:
        simpleValueToStream(iBuffer, iType, iValue);
        break;

      case LINK:
        if (iValue instanceof RecordId) {
          ((RecordId) iValue).toString(iBuffer);
        } else {
          ((RecordId) ((Identifiable) iValue).getIdentity()).toString(iBuffer);
        }
        break;

      case EMBEDDEDSET:
        RecordSerializerSchemaAware2CSV.INSTANCE.embeddedCollectionToStream(
            DatabaseRecordThreadLocal.instance().getIfDefined(),
            iBuffer,
            null,
            null,
            iValue,
            true,
            true);
        break;

      case EMBEDDEDLIST:
        RecordSerializerSchemaAware2CSV.INSTANCE.embeddedCollectionToStream(
            DatabaseRecordThreadLocal.instance().getIfDefined(),
            iBuffer,
            null,
            null,
            iValue,
            true,
            false);
        break;

      case EMBEDDEDMAP:
        RecordSerializerSchemaAware2CSV.INSTANCE.embeddedMapToStream(
            DatabaseRecordThreadLocal.instance().getIfDefined(),
            iBuffer,
            null,
            null,
            iValue,
            true);
        break;

      case EMBEDDED:
        if (iValue instanceof EntityImpl) {
          RecordSerializerSchemaAware2CSV.INSTANCE.toString((EntityImpl) iValue, iBuffer, null);
        } else {
          StringSerializerEmbedded.INSTANCE.toStream(iBuffer, iValue);
        }
        break;

      case CUSTOM:
        StringSerializerAnyStreamable.INSTANCE.toStream(iBuffer, iValue);
        break;

      default:
        throw new IllegalArgumentException(
            "Type " + iType + " not supported to convert value: " + iValue);
    }
  }

  /**
   * Parses a string returning the closer type. Numbers by default are INTEGER if haven't decimal
   * separator, otherwise FLOAT. To treat all the number types numbers are postponed with a
   * character that tells the type: b=byte, s=short, l=long, f=float, d=double, t=date.
   *
   * @param iValue Value to parse
   * @return The closest type recognized
   */
  public static PropertyType getType(final String iValue) {
    if (iValue.length() == 0) {
      return null;
    }

    final char firstChar = iValue.charAt(0);

    if (firstChar == RID.PREFIX)
    // RID
    {
      return PropertyType.LINK;
    } else if (firstChar == '\'' || firstChar == '"') {
      return PropertyType.STRING;
    } else if (firstChar == StringSerializerHelper.BINARY_BEGINEND) {
      return PropertyType.BINARY;
    } else if (firstChar == StringSerializerHelper.EMBEDDED_BEGIN) {
      return PropertyType.EMBEDDED;
    } else if (firstChar == StringSerializerHelper.LIST_BEGIN) {
      return PropertyType.EMBEDDEDLIST;
    } else if (firstChar == StringSerializerHelper.SET_BEGIN) {
      return PropertyType.EMBEDDEDSET;
    } else if (firstChar == StringSerializerHelper.MAP_BEGIN) {
      return PropertyType.EMBEDDEDMAP;
    } else if (firstChar == StringSerializerHelper.CUSTOM_TYPE) {
      return PropertyType.CUSTOM;
    }

    // BOOLEAN?
    if (iValue.equalsIgnoreCase("true") || iValue.equalsIgnoreCase("false")) {
      return PropertyType.BOOLEAN;
    }

    // NUMBER OR STRING?
    boolean integer = true;
    for (int index = 0; index < iValue.length(); ++index) {
      final char c = iValue.charAt(index);
      if (c < '0' || c > '9') {
        if ((index == 0 && (c == '+' || c == '-'))) {
          continue;
        } else if (c == DECIMAL_SEPARATOR) {
          integer = false;
        } else {
          if (index > 0) {
            if (!integer && c == 'E') {
              // CHECK FOR SCIENTIFIC NOTATION
              if (index < iValue.length()) {
                if (iValue.charAt(index + 1) == '-')
                // JUMP THE DASH IF ANY (NOT MANDATORY)
                {
                  index++;
                }
                continue;
              }
            } else if (c == 'f') {
              return index != (iValue.length() - 1) ? PropertyType.STRING : PropertyType.FLOAT;
            } else if (c == 'c') {
              return index != (iValue.length() - 1) ? PropertyType.STRING : PropertyType.DECIMAL;
            } else if (c == 'l') {
              return index != (iValue.length() - 1) ? PropertyType.STRING : PropertyType.LONG;
            } else if (c == 'd') {
              return index != (iValue.length() - 1) ? PropertyType.STRING : PropertyType.DOUBLE;
            } else if (c == 'b') {
              return index != (iValue.length() - 1) ? PropertyType.STRING : PropertyType.BYTE;
            } else if (c == 'a') {
              return index != (iValue.length() - 1) ? PropertyType.STRING : PropertyType.DATE;
            } else if (c == 't') {
              return index != (iValue.length() - 1) ? PropertyType.STRING : PropertyType.DATETIME;
            } else if (c == 's') {
              return index != (iValue.length() - 1) ? PropertyType.STRING : PropertyType.SHORT;
            } else if (c == 'e') { // eg. 1e-06
              try {
                Double.parseDouble(iValue);
                return PropertyType.DOUBLE;
              } catch (Exception ignore) {
                return PropertyType.STRING;
              }
            }
          }

          return PropertyType.STRING;
        }
      }
    }

    if (integer) {
      // AUTO CONVERT TO LONG IF THE INTEGER IS TOO BIG
      final int numberLength = iValue.length();
      if (numberLength > MAX_INTEGER_DIGITS
          || (numberLength == MAX_INTEGER_DIGITS && iValue.compareTo(MAX_INTEGER_AS_STRING) > 0)) {
        return PropertyType.LONG;
      }

      return PropertyType.INTEGER;
    }

    // CHECK IF THE DECIMAL NUMBER IS A FLOAT OR DOUBLE
    final double dou = Double.parseDouble(iValue);
    if (dou <= Float.MAX_VALUE
        && dou >= Float.MIN_VALUE
        && Double.toString(dou).equals(Float.toString((float) dou))
        && (double) Double.valueOf(dou).floatValue() == dou) {
      return PropertyType.FLOAT;
    } else if (!Double.toString(dou).equals(iValue)) {
      return PropertyType.DECIMAL;
    }

    return PropertyType.DOUBLE;
  }

  /**
   * Parses a string returning the value with the closer type. Numbers by default are INTEGER if
   * haven't decimal separator, otherwise FLOAT. To treat all the number types numbers are postponed
   * with a character that tells the type: b=byte, s=short, l=long, f=float, d=double, t=date. If
   * starts with # it's a RecordID. Most of the code is equals to getType() but has been copied to
   * speed-up it.
   *
   * @param db
   * @param iValue Value to parse
   * @return The closest type recognized
   */
  public static Object getTypeValue(DatabaseSessionInternal db, final String iValue) {
    if (iValue == null || iValue.equalsIgnoreCase("NULL")) {
      return null;
    }

    if (iValue.length() == 0) {
      return "";
    }

    if (iValue.length() > 1) {
      if (iValue.charAt(0) == '"' && iValue.charAt(iValue.length() - 1) == '"')
      // STRING
      {
        return StringSerializerHelper.decode(iValue.substring(1, iValue.length() - 1));
      } else if (iValue.charAt(0) == StringSerializerHelper.BINARY_BEGINEND
          && iValue.charAt(iValue.length() - 1) == StringSerializerHelper.BINARY_BEGINEND)
      // STRING
      {
        return StringSerializerHelper.getBinaryContent(iValue);
      } else if (iValue.charAt(0) == StringSerializerHelper.LIST_BEGIN
          && iValue.charAt(iValue.length() - 1) == StringSerializerHelper.LIST_END) {
        // LIST
        final ArrayList<String> coll = new ArrayList<String>();
        StringSerializerHelper.getCollection(
            iValue,
            0,
            coll,
            StringSerializerHelper.LIST_BEGIN,
            StringSerializerHelper.LIST_END,
            StringSerializerHelper.COLLECTION_SEPARATOR);
        return coll;
      } else if (iValue.charAt(0) == StringSerializerHelper.SET_BEGIN
          && iValue.charAt(iValue.length() - 1) == StringSerializerHelper.SET_END) {
        // SET
        final Set<String> coll = new HashSet<String>();
        StringSerializerHelper.getCollection(
            iValue,
            0,
            coll,
            StringSerializerHelper.SET_BEGIN,
            StringSerializerHelper.SET_END,
            StringSerializerHelper.COLLECTION_SEPARATOR);
        return coll;
      } else if (iValue.charAt(0) == StringSerializerHelper.MAP_BEGIN
          && iValue.charAt(iValue.length() - 1) == StringSerializerHelper.MAP_END) {
        // MAP
        return StringSerializerHelper.getMap(db, iValue);
      }
    }

    if (iValue.charAt(0) == RID.PREFIX)
    // RID
    {
      return new RecordId(iValue);
    }

    boolean integer = true;
    char c;

    boolean stringStarBySign = false;

    for (int index = 0; index < iValue.length(); ++index) {
      c = iValue.charAt(index);
      if (c < '0' || c > '9') {
        if ((index == 0 && (c == '+' || c == '-'))) {
          stringStarBySign = true;
          continue;
        } else if (c == DECIMAL_SEPARATOR) {
          integer = false;
        } else {
          if (index > 0) {
            if (!integer && c == 'E') {
              // CHECK FOR SCIENTIFIC NOTATION
              if (index < iValue.length()) {
                index++;
              }
              if (iValue.charAt(index) == '-') {
                continue;
              }
            }

            final String v = iValue.substring(0, index);

            if (c == 'f') {
              return Float.valueOf(v);
            } else if (c == 'c') {
              return new BigDecimal(v);
            } else if (c == 'l') {
              return Long.valueOf(v);
            } else if (c == 'd') {
              return Double.valueOf(v);
            } else if (c == 'b') {
              return Byte.valueOf(v);
            } else if (c == 'a' || c == 't') {
              return new Date(Long.parseLong(v));
            } else if (c == 's') {
              return Short.valueOf(v);
            }
          }
          return iValue;
        }
      } else if (stringStarBySign) {
        stringStarBySign = false;
      }
    }
    if (stringStarBySign) {
      return iValue;
    }

    if (integer) {
      try {
        return Integer.valueOf(iValue);
      } catch (NumberFormatException ignore) {
        return Long.valueOf(iValue);
      }
    } else if ("NaN".equals(iValue) || "Infinity".equals(iValue))
    // NaN and Infinity CANNOT BE MANAGED BY BIG-DECIMAL TYPE
    {
      return Double.valueOf(iValue);
    } else {
      return new BigDecimal(iValue);
    }
  }

  public static Object simpleValueFromStream(DatabaseSessionInternal db, final Object iValue,
      final PropertyType iType) {
    switch (iType) {
      case STRING:
        if (iValue instanceof String) {
          final String s = IOUtils.getStringContent(iValue);
          return StringSerializerHelper.decode(s);
        }
        return iValue.toString();

      case INTEGER:
        if (iValue instanceof Integer) {
          return iValue;
        }
        return Integer.valueOf(iValue.toString());

      case BOOLEAN:
        if (iValue instanceof Boolean) {
          return iValue;
        }
        return Boolean.valueOf(iValue.toString());

      case FLOAT:
        if (iValue instanceof Float) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);

      case DECIMAL:
        if (iValue instanceof BigDecimal) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);

      case LONG:
        if (iValue instanceof Long) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);

      case DOUBLE:
        if (iValue instanceof Double) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);

      case SHORT:
        if (iValue instanceof Short) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);

      case BYTE:
        if (iValue instanceof Byte) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);

      case BINARY:
        return StringSerializerHelper.getBinaryContent(iValue);

      case DATE:
      case DATETIME:
        if (iValue instanceof Date) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);

      case LINK:
        if (iValue instanceof RID) {
          return iValue.toString();
        } else if (iValue instanceof String) {
          return new RecordId((String) iValue);
        } else {
          return ((DBRecord) iValue).getIdentity().toString();
        }
    }

    throw new IllegalArgumentException("Type " + iType + " is not simple type.");
  }

  public static void simpleValueToStream(
      final StringBuilder iBuffer, final PropertyType iType, final Object iValue) {
    if (iValue == null || iType == null) {
      return;
    }
    switch (iType) {
      case STRING:
        iBuffer.append('"');
        iBuffer.append(StringSerializerHelper.encode(iValue.toString()));
        iBuffer.append('"');
        break;

      case BOOLEAN:
        iBuffer.append(iValue);
        break;

      case INTEGER:
        iBuffer.append(iValue);
        break;

      case FLOAT:
        iBuffer.append(iValue);
        iBuffer.append('f');
        break;

      case DECIMAL:
        if (iValue instanceof BigDecimal) {
          iBuffer.append(((BigDecimal) iValue).toPlainString());
        } else {
          iBuffer.append(iValue);
        }
        iBuffer.append('c');
        break;

      case LONG:
        iBuffer.append(iValue);
        iBuffer.append('l');
        break;

      case DOUBLE:
        iBuffer.append(iValue);
        iBuffer.append('d');
        break;

      case SHORT:
        iBuffer.append(iValue);
        iBuffer.append('s');
        break;

      case BYTE:
        if (iValue instanceof Character) {
          iBuffer.append((int) ((Character) iValue).charValue());
        } else if (iValue instanceof String) {
          iBuffer.append((int) ((String) iValue).charAt(0));
        } else {
          iBuffer.append(iValue);
        }
        iBuffer.append('b');
        break;

      case BINARY:
        iBuffer.append(StringSerializerHelper.BINARY_BEGINEND);
        if (iValue instanceof Byte) {
          iBuffer.append(
              Base64.getEncoder().encodeToString(new byte[]{((Byte) iValue).byteValue()}));
        } else {
          iBuffer.append(Base64.getEncoder().encodeToString((byte[]) iValue));
        }
        iBuffer.append(StringSerializerHelper.BINARY_BEGINEND);
        break;

      case DATE:
        if (iValue instanceof Date) {
          // RESET HOURS, MINUTES, SECONDS AND MILLISECONDS
          final Calendar calendar = DateHelper.getDatabaseCalendar();
          calendar.setTime((Date) iValue);
          calendar.set(Calendar.HOUR_OF_DAY, 0);
          calendar.set(Calendar.MINUTE, 0);
          calendar.set(Calendar.SECOND, 0);
          calendar.set(Calendar.MILLISECOND, 0);

          iBuffer.append(calendar.getTimeInMillis());
        } else {
          iBuffer.append(iValue);
        }
        iBuffer.append('a');
        break;

      case DATETIME:
        if (iValue instanceof Date) {
          iBuffer.append(((Date) iValue).getTime());
        } else {
          iBuffer.append(iValue);
        }
        iBuffer.append('t');
        break;
    }
  }

  public abstract RecordAbstract fromString(
      DatabaseSessionInternal db, String iContent, RecordAbstract iRecord, String[] iFields);

  public StringBuilder toString(
      final DBRecord iRecord, final StringBuilder iOutput, final String iFormat) {
    return toString(iRecord, iOutput, iFormat, true);
  }

  public DBRecord fromString(DatabaseSessionInternal db, final String iSource) {
    return fromString(db, iSource, DatabaseRecordThreadLocal.instance().get().newInstance(), null);
  }

  @Override
  public String[] getFieldNames(DatabaseSessionInternal db, EntityImpl reference,
      byte[] iSource) {
    return null;
  }

  @Override
  public RecordAbstract fromStream(
      DatabaseSessionInternal db, final byte[] iSource, final RecordAbstract iRecord,
      final String[] iFields) {

    return fromString(db, new String(iSource, StandardCharsets.UTF_8), iRecord, iFields);
  }

  public byte[] toStream(DatabaseSessionInternal session, final RecordAbstract iRecord) {
    return toString(iRecord, new StringBuilder(2048), null, true)
        .toString()
        .getBytes(StandardCharsets.UTF_8);
  }

  protected abstract StringBuilder toString(
      final DBRecord iRecord,
      final StringBuilder iOutput,
      final String iFormat,
      boolean autoDetectCollectionType);

  public boolean getSupportBinaryEvaluate() {
    return false;
  }
}
