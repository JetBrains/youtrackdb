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
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string;

import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

@SuppressWarnings("serial")
public abstract class RecordSerializerStringAbstract {

  private static final char DECIMAL_SEPARATOR = '.';
  private static final String MAX_INTEGER_AS_STRING = String.valueOf(Integer.MAX_VALUE);
  private static final int MAX_INTEGER_DIGITS = MAX_INTEGER_AS_STRING.length();

  @Nullable
  public static Object fieldTypeFromStream(
      DatabaseSessionEmbedded session, final EntityImpl entity, PropertyTypeInternal iType,
      final Object iValue) {
    if (iValue == null) {
      return null;
    }

    if (iType == null) {
      iType = PropertyTypeInternal.EMBEDDED;
    }

    switch (iType) {
      case STRING, INTEGER, BOOLEAN, FLOAT, DECIMAL, LONG, DOUBLE, SHORT, BYTE, BINARY, DATE,
          DATETIME, LINK -> {
        return simpleValueFromStream(session, iValue, iType);
      }
      case EMBEDDED -> {
        // EMBEDED RECORD
        return null;
      }
      case EMBEDDEDSET, EMBEDDEDLIST -> {
        return null;
      }
      case EMBEDDEDMAP -> {
        final var value = (String) iValue;
        return RecordSerializerCSVAbstract.embeddedMapFromStream(session,
            entity, null, value, null);
      }
    }

    throw new IllegalArgumentException(
        "Type " + iType + " not supported to convert value: " + iValue);
  }

  public static Object convertValue(DatabaseSessionEmbedded session,
      final String iValue, final PropertyTypeInternal iExpectedType) {
    final var v = getTypeValue(session, iValue);
    return PropertyTypeInternal.convert(session, v, iExpectedType.getDefaultJavaType());
  }

  public static void fieldTypeToString(
      DatabaseSessionEmbedded session, final StringWriter iBuffer, PropertyTypeInternal iType,
      final Object iValue) {
    if (iValue == null) {
      return;
    }

    if (iType == null) {
      if (iValue instanceof RID) {
        iType = PropertyTypeInternal.LINK;
      } else {
        iType = PropertyTypeInternal.EMBEDDED;
      }
    }

    switch (iType) {
      case STRING, BOOLEAN, INTEGER, FLOAT, DECIMAL, LONG, DOUBLE, SHORT, BYTE, BINARY, DATE,
          DATETIME -> simpleValueToStream(session, iBuffer, iType, iValue);
      case LINK -> {
        if (iValue instanceof RecordIdInternal) {
          iBuffer.append(iValue.toString());
        } else {
          iBuffer.append(((Identifiable) iValue).getIdentity().toString());
        }
      }
      case EMBEDDEDSET, EMBEDDEDLIST, EMBEDDEDMAP, EMBEDDED -> {
        // no-op
      }
      default ->
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
  @Nullable
  public static PropertyTypeInternal getType(final String iValue) {
    if (iValue.length() == 0) {
      return null;
    }

    final var firstChar = iValue.charAt(0);

    if (firstChar == RID.PREFIX)
    // RID
    {
      return PropertyTypeInternal.LINK;
    } else if (firstChar == '\'' || firstChar == '"') {
      return PropertyTypeInternal.STRING;
    } else if (firstChar == StringSerializerHelper.BINARY_BEGINEND) {
      return PropertyTypeInternal.BINARY;
    } else if (firstChar == StringSerializerHelper.EMBEDDED_BEGIN) {
      return PropertyTypeInternal.EMBEDDED;
    } else if (firstChar == StringSerializerHelper.LIST_BEGIN) {
      return PropertyTypeInternal.EMBEDDEDLIST;
    } else if (firstChar == StringSerializerHelper.SET_BEGIN) {
      return PropertyTypeInternal.EMBEDDEDSET;
    } else if (firstChar == StringSerializerHelper.MAP_BEGIN) {
      return PropertyTypeInternal.EMBEDDEDMAP;
    }

    // BOOLEAN?
    if (iValue.equalsIgnoreCase("true") || iValue.equalsIgnoreCase("false")) {
      return PropertyTypeInternal.BOOLEAN;
    }

    // NUMBER OR STRING?
    var integer = true;
    for (var index = 0; index < iValue.length(); ++index) {
      final var c = iValue.charAt(index);
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
              return index != (iValue.length() - 1) ? PropertyTypeInternal.STRING
                  : PropertyTypeInternal.FLOAT;
            } else if (c == 'c') {
              return index != (iValue.length() - 1) ? PropertyTypeInternal.STRING
                  : PropertyTypeInternal.DECIMAL;
            } else if (c == 'l') {
              return index != (iValue.length() - 1) ? PropertyTypeInternal.STRING
                  : PropertyTypeInternal.LONG;
            } else if (c == 'd') {
              return index != (iValue.length() - 1) ? PropertyTypeInternal.STRING
                  : PropertyTypeInternal.DOUBLE;
            } else if (c == 'b') {
              return index != (iValue.length() - 1) ? PropertyTypeInternal.STRING
                  : PropertyTypeInternal.BYTE;
            } else if (c == 'a') {
              return index != (iValue.length() - 1) ? PropertyTypeInternal.STRING
                  : PropertyTypeInternal.DATE;
            } else if (c == 't') {
              return index != (iValue.length() - 1) ? PropertyTypeInternal.STRING
                  : PropertyTypeInternal.DATETIME;
            } else if (c == 's') {
              return index != (iValue.length() - 1) ? PropertyTypeInternal.STRING
                  : PropertyTypeInternal.SHORT;
            } else if (c == 'e') { // eg. 1e-06
              try {
                Double.parseDouble(iValue);
                return PropertyTypeInternal.DOUBLE;
              } catch (Exception ignore) {
                return PropertyTypeInternal.STRING;
              }
            }
          }

          return PropertyTypeInternal.STRING;
        }
      }
    }

    if (integer) {
      // AUTO CONVERT TO LONG IF THE INTEGER IS TOO BIG
      final var numberLength = iValue.length();
      if (numberLength > MAX_INTEGER_DIGITS
          || (numberLength == MAX_INTEGER_DIGITS && iValue.compareTo(MAX_INTEGER_AS_STRING) > 0)) {
        return PropertyTypeInternal.LONG;
      }

      return PropertyTypeInternal.INTEGER;
    }

    // CHECK IF THE DECIMAL NUMBER IS A FLOAT OR DOUBLE
    final var dou = Double.parseDouble(iValue);
    if (dou <= Float.MAX_VALUE
        && dou >= Float.MIN_VALUE
        && Double.toString(dou).equals(Float.toString((float) dou))
        && (double) Double.valueOf(dou).floatValue() == dou) {
      return PropertyTypeInternal.FLOAT;
    } else if (!Double.toString(dou).equals(iValue)) {
      return PropertyTypeInternal.DECIMAL;
    }

    return PropertyTypeInternal.DOUBLE;
  }

  /**
   * Parses a string returning the value with the closer type. Numbers by default are INTEGER if
   * haven't decimal separator, otherwise FLOAT. To treat all the number types numbers are postponed
   * with a character that tells the type: b=byte, s=short, l=long, f=float, d=double, t=date. If
   * starts with # it's a RecordID. Most of the code is equals to getType() but has been copied to
   * speed-up it.
   *
   * @param db     the active database session, used for RID resolution
   * @param iValue Value to parse
   * @return The closest type recognized
   */
  @Nullable
  public static Object getTypeValue(DatabaseSessionEmbedded db, final String iValue) {
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
        final var coll = new ArrayList<String>();
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
      return RecordIdInternal.fromString(iValue, false);
    }

    var integer = true;
    char c;

    var stringStarBySign = false;

    for (var index = 0; index < iValue.length(); ++index) {
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

            final var v = iValue.substring(0, index);

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

  public static Object simpleValueFromStream(DatabaseSessionEmbedded db, final Object iValue,
      final PropertyTypeInternal iType) {
    switch (iType) {
      case STRING -> {
        if (iValue instanceof String) {
          final var s = IOUtils.getStringContent(iValue);
          return StringSerializerHelper.decode(s);
        }
        return iValue.toString();
      }
      case INTEGER -> {
        if (iValue instanceof Integer) {
          return iValue;
        }
        return Integer.valueOf(iValue.toString());
      }
      case BOOLEAN -> {
        if (iValue instanceof Boolean) {
          return iValue;
        }
        return Boolean.valueOf(iValue.toString());
      }
      case FLOAT -> {
        if (iValue instanceof Float) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);
      }
      case DECIMAL -> {
        if (iValue instanceof BigDecimal) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);
      }
      case LONG -> {
        if (iValue instanceof Long) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);
      }
      case DOUBLE -> {
        if (iValue instanceof Double) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);
      }
      case SHORT -> {
        if (iValue instanceof Short) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);
      }
      case BYTE -> {
        if (iValue instanceof Byte) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);
      }
      case BINARY -> {
        return StringSerializerHelper.getBinaryContent(iValue);
      }
      case DATE, DATETIME -> {
        if (iValue instanceof Date) {
          return iValue;
        }
        return convertValue(db, (String) iValue, iType);
      }
      case LINK -> {
        if (iValue instanceof RID) {
          return iValue.toString();
        } else if (iValue instanceof String s) {
          return RecordIdInternal.fromString(s, false);
        } else {
          return ((DBRecord) iValue).getIdentity().toString();
        }
      }
    }

    throw new IllegalArgumentException("Type " + iType + " is not simple type.");
  }

  public static void simpleValueToStream(
      DatabaseSessionEmbedded session, final StringWriter iBuffer, final PropertyTypeInternal iType,
      final Object iValue) {
    if (iValue == null || iType == null) {
      return;
    }
    switch (iType) {
      case STRING -> {
        iBuffer.append('"');
        iBuffer.append(StringSerializerHelper.encode(iValue.toString()));
        iBuffer.append('"');
      }
      case BOOLEAN, INTEGER -> iBuffer.append(iValue.toString());
      case FLOAT -> {
        iBuffer.append(iValue.toString());
        iBuffer.append('f');
      }
      case DECIMAL -> {
        if (iValue instanceof BigDecimal bigDecimal) {
          iBuffer.append(bigDecimal.toPlainString());
        } else {
          iBuffer.append(iValue.toString());
        }
        iBuffer.append('c');
      }
      case LONG -> {
        iBuffer.append(iValue.toString());
        iBuffer.append('l');
      }
      case DOUBLE -> {
        iBuffer.append(iValue.toString());
        iBuffer.append('d');
      }
      case SHORT -> {
        iBuffer.append(iValue.toString());
        iBuffer.append('s');
      }
      case BYTE -> {
        if (iValue instanceof Character character) {
          iBuffer.append(character);
        } else if (iValue instanceof String s) {
          iBuffer.append(s.charAt(0));
        } else {
          iBuffer.append(iValue.toString());
        }
        iBuffer.append('b');
      }
      case BINARY -> {
        iBuffer.append(StringSerializerHelper.BINARY_BEGINEND);
        if (iValue instanceof Byte b) {
          iBuffer.append(
              Base64.getEncoder().encodeToString(new byte[]{b.byteValue()}));
        } else {
          iBuffer.append(Base64.getEncoder().encodeToString((byte[]) iValue));
        }
        iBuffer.append(StringSerializerHelper.BINARY_BEGINEND);
      }
      case DATE -> {
        if (iValue instanceof Date date) {
          // RESET HOURS, MINUTES, SECONDS AND MILLISECONDS
          final var calendar = DateHelper.getDatabaseCalendar(session);
          calendar.setTime(date);
          calendar.set(Calendar.HOUR_OF_DAY, 0);
          calendar.set(Calendar.MINUTE, 0);
          calendar.set(Calendar.SECOND, 0);
          calendar.set(Calendar.MILLISECOND, 0);

          iBuffer.append(String.valueOf(calendar.getTimeInMillis()));
        } else {
          iBuffer.append(iValue.toString());
        }
        iBuffer.append('a');
      }
      case DATETIME -> {
        if (iValue instanceof Date date) {
          iBuffer.append(String.valueOf(date.getTime()));
        } else {
          iBuffer.append(iValue.toString());
        }
        iBuffer.append('t');
      }
    }
  }

  public abstract <T extends DBRecord> T fromString(
      DatabaseSessionEmbedded session, String iContent, RecordAbstract iRecord, String[] iFields);

  public StringWriter toString(
      DatabaseSessionEmbedded db, final DBRecord iRecord, final StringWriter iOutput,
      final String iFormat) {
    return toString(db, iRecord, iOutput, iFormat, true);
  }

  public <T extends DBRecord> T fromString(DatabaseSessionEmbedded session, final String iSource) {
    return fromString(session, iSource, null, null);
  }

  public RecordAbstract fromStream(
      DatabaseSessionEmbedded db, final byte[] iSource, final RecordAbstract iRecord,
      final String[] iFields) {

    return fromString(db, new String(iSource, StandardCharsets.UTF_8), iRecord, iFields);
  }

  public byte[] toStream(DatabaseSessionEmbedded session, final RecordAbstract iRecord) {
    return toString(session, iRecord, new StringWriter(2048), null, true)
        .toString()
        .getBytes(StandardCharsets.UTF_8);
  }

  protected abstract StringWriter toString(
      DatabaseSessionEmbedded session, final DBRecord iRecord,
      final StringWriter iOutput,
      final String iFormat,
      boolean autoDetectCollectionType);

  public boolean getSupportBinaryEvaluate() {
    return false;
  }
}
