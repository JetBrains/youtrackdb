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

package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary;

import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.MILLISEC_PER_DAY;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.convertDayToTimezone;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readByte;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readInteger;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readLong;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readOptimizedLink;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.readString;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.HelperClasses.stringFromBytes;

import com.jetbrains.youtrack.db.api.schema.Collate;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.serialization.types.DecimalSerializer;
import com.jetbrains.youtrack.db.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrack.db.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

/**
 * Implementation v0 of comparator based on protocol v0.
 */
public class BinaryComparatorV0 implements BinaryComparator {

  public BinaryComparatorV0() {
  }

  public boolean isBinaryComparable(final PropertyTypeInternal iType) {
    return switch (iType) {
      case INTEGER, LONG, DATETIME, SHORT, STRING, DOUBLE, FLOAT, BYTE, BOOLEAN, DATE, BINARY, LINK,
           DECIMAL -> true;
      default -> false;
    };
  }

  /**
   * Compares if 2 field values are the same.
   *
   * @param session current database session
   * @param iField1 First value to compare
   * @param iField2 Second value to compare
   * @return true if they match, otherwise false
   */
  @Override
  public boolean isEqual(DatabaseSessionInternal session, final BinaryField iField1,
      final BinaryField iField2) {
    final var fieldValue1 = iField1.bytes;
    final var offset1 = fieldValue1.offset;

    final var fieldValue2 = iField2.bytes;
    final var offset2 = fieldValue2.offset;

    try {
      switch (iField1.type) {
        case INTEGER: {
          final var value1 = VarIntSerializer.readAsInteger(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return value1 == value2;
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return value1 == value2;
            }
            case DATE: {
              final var value2 =
                  (VarIntSerializer.readAsLong(fieldValue2) * MILLISEC_PER_DAY);
              return value1 == value2;
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return value1 == value2;
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return value1 == value2;
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return value1 == value2;
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return value1 == value2;
            }
            case STRING: {
              return Integer.parseInt(readString(fieldValue2)) == value1;
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                      fieldValue2.bytes, fieldValue2.offset);
              return value1 == value2.intValue();
            }
          }
          break;
        }

        case LONG: {
          final var value1 = VarIntSerializer.readAsLong(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return value1 == value2;
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return value1 == value2;
            }
            case DATE: {
              final var value2 =
                  (VarIntSerializer.readAsLong(fieldValue2) * MILLISEC_PER_DAY);
              return value1 == value2;
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return value1 == value2;
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return value1 == value2;
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return value1 == value2;
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return value1 == value2;
            }
            case STRING: {
              return Long.parseLong(readString(fieldValue2)) == value1;
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                      fieldValue2.bytes, fieldValue2.offset);
              return value1 == value2.longValue();
            }
          }
          break;
        }

        case SHORT: {
          final var value1 = VarIntSerializer.readAsShort(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return value1 == value2;
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return value1 == value2;
            }
            case DATE: {
              final var value2 =
                  (VarIntSerializer.readAsLong(fieldValue2) * MILLISEC_PER_DAY);
              return value1 == value2;
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return value1 == value2;
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return value1 == value2;
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return value1 == value2;
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return value1 == value2;
            }
            case STRING: {
              return Short.parseShort(readString(fieldValue2)) == value1;
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                      fieldValue2.bytes, fieldValue2.offset);
              return value1 == value2.shortValue();
            }
          }
          break;
        }

        case STRING: {
          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return Integer.parseInt(readString(fieldValue1)) == value2;
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return Long.parseLong(readString(fieldValue1)) == value2;
            }
            case DATE: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2) * MILLISEC_PER_DAY;
              return Long.parseLong(readString(fieldValue1)) == value2;
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return Short.parseShort(readString(fieldValue1)) == value2;
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return Byte.parseByte(readString(fieldValue1)) == value2;
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return Float.parseFloat(readString(fieldValue1)) == value2;
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return Double.parseDouble(readString(fieldValue1)) == value2;
            }
            case STRING: {
              final var len1 = VarIntSerializer.readAsInteger(fieldValue1);
              final var len2 = VarIntSerializer.readAsInteger(fieldValue2);

              if (len1 != len2) {
                return false;
              }

              final Collate collate;
              if (iField1.collate != null
                  && !DefaultCollate.NAME.equals(iField1.collate.getName())) {
                collate = iField1.collate;
              } else if (iField2.collate != null
                  && !DefaultCollate.NAME.equals(iField2.collate.getName())) {
                collate = iField2.collate;
              } else {
                collate = null;
              }

              if (collate != null) {
                final var str1 =
                    (String)
                        collate.transform(
                            stringFromBytes(fieldValue1.bytes, fieldValue1.offset, len1));
                final var str2 =
                    (String)
                        collate.transform(
                            stringFromBytes(fieldValue2.bytes, fieldValue2.offset, len2));

                return str1.equals(str2);

              } else {
                for (var i = 0; i < len1; ++i) {
                  if (fieldValue1.bytes[fieldValue1.offset + i]
                      != fieldValue2.bytes[fieldValue2.offset + i]) {
                    return false;
                  }
                }
              }
              return true;
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                      fieldValue2.bytes, fieldValue2.offset);
              return new BigDecimal(readString(fieldValue1)).equals(value2);
            }
            case BOOLEAN: {
              final var value2 = readByte(fieldValue2) == 1;
              return Boolean.parseBoolean(readString(fieldValue1)) == value2;
            }
          }
          break;
        }

        case DOUBLE: {
          final var value1AsLong = readLong(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value1 = Double.longBitsToDouble(value1AsLong);
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return value1 == value2;
            }
            case LONG:
            case DATETIME: {
              final var value1 = Double.longBitsToDouble(value1AsLong);
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return value1 == value2;
            }
            case SHORT: {
              final var value1 = Double.longBitsToDouble(value1AsLong);
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return value1 == value2;
            }
            case BYTE: {
              final var value1 = Double.longBitsToDouble(value1AsLong);
              final var value2 = readByte(fieldValue2);
              return value1 == value2;
            }
            case FLOAT: {
              final var value1 = Double.longBitsToDouble(value1AsLong);
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return value1 == value2;
            }
            case DOUBLE: {
              final double value2AsLong = readLong(fieldValue2);
              return value1AsLong == value2AsLong;
            }
            case STRING: {
              final var value1 = Double.longBitsToDouble(value1AsLong);
              return Double.parseDouble(readString(fieldValue2)) == value1;
            }
            case DECIMAL: {
              final var value1 = Double.longBitsToDouble(value1AsLong);
              final var value2 =
                  DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                      fieldValue2.bytes, fieldValue2.offset);
              return value1 == value2.doubleValue();
            }
          }
          break;
        }

        case FLOAT: {
          final var value1AsInt = readInteger(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value1 = Float.intBitsToFloat(value1AsInt);
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return value1 == value2;
            }
            case LONG:
            case DATETIME: {
              final var value1 = Float.intBitsToFloat(value1AsInt);
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return value1 == value2;
            }
            case SHORT: {
              final var value1 = Float.intBitsToFloat(value1AsInt);
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return value1 == value2;
            }
            case BYTE: {
              final var value1 = Float.intBitsToFloat(value1AsInt);
              final var value2 = readByte(fieldValue2);
              return value1 == value2;
            }
            case FLOAT: {
              final float value2AsInt = readInteger(fieldValue2);
              return value1AsInt == value2AsInt;
            }
            case DOUBLE: {
              final var value1 = Float.intBitsToFloat(value1AsInt);
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return value1 == value2;
            }
            case STRING: {
              final var value1 = Float.intBitsToFloat(value1AsInt);
              return Float.parseFloat(readString(fieldValue2)) == value1;
            }
            case DECIMAL: {
              final var value1 = Float.intBitsToFloat(value1AsInt);
              final var value2 =
                  DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                      fieldValue2.bytes, fieldValue2.offset);
              return value1 == value2.floatValue();
            }
          }
          break;
        }

        case BYTE: {
          final var value1 = readByte(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return value1 == value2;
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return value1 == value2;
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return value1 == value2;
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return value1 == value2;
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return value1 == value2;
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return value1 == value2;
            }
            case STRING: {
              final var value2 = Byte.parseByte((readString(fieldValue2)));
              return value1 == value2;
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                      fieldValue2.bytes, fieldValue2.offset);
              return value1 == value2.byteValue();
            }
          }
          break;
        }

        case BOOLEAN: {
          final var value1 = readByte(fieldValue1) == 1;

          switch (iField2.type) {
            case BOOLEAN: {
              final var value2 = readByte(fieldValue2) == 1;
              return value1 == value2;
            }
            case STRING: {
              final var str = readString(fieldValue2);
              return Boolean.parseBoolean(str) == value1;
            }
          }
          break;
        }

        case DATE: {
          final var value1 = VarIntSerializer.readAsLong(fieldValue1) * MILLISEC_PER_DAY;

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return value1 == value2;
            }
            case LONG:
            case DATETIME: {
              var value2 = VarIntSerializer.readAsLong(fieldValue2);
              value2 =
                  convertDayToTimezone(
                      DateHelper.getDatabaseTimeZone(session), TimeZone.getTimeZone("GMT"), value2);
              return value1 == value2;
            }
            case DATE: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2) * MILLISEC_PER_DAY;
              return value1 == value2;
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return value1 == value2;
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return value1 == value2;
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return value1 == value2;
            }
            case STRING: {
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                      fieldValue2.bytes, fieldValue2.offset);
              return value1 == value2.longValue();
            }
          }
          break;
        }

        case DATETIME: {
          final var value1 = VarIntSerializer.readAsLong(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return value1 == value2;
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return value1 == value2;
            }
            case DATE: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2) * MILLISEC_PER_DAY;
              return value1 == value2;
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return value1 == value2;
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return value1 == value2;
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return value1 == value2;
            }
            case STRING: {
              final var value2AsString = readString(fieldValue2);

              if (IOUtils.isLong(value2AsString)) {
                final var value2 = Long.parseLong(value2AsString);
                return value1 == value2;
              }

              try {
                final DateFormat dateFormat;
                if (session != null) {
                  dateFormat = DateHelper.getDateTimeFormatInstance(session);
                } else {
                  dateFormat =
                      new SimpleDateFormat(StorageConfiguration.DEFAULT_DATETIME_FORMAT);
                }

                final var value2AsDate = dateFormat.parse(value2AsString);
                final var value2 = value2AsDate.getTime();
                return value1 == value2;
              } catch (ParseException ignore) {
                try {
                  final SimpleDateFormat dateFormat;
                  if (session != null) {
                    dateFormat = session.getStorageInfo().getConfiguration()
                        .getDateFormatInstance();
                  } else {
                    dateFormat =
                        new SimpleDateFormat(StorageConfiguration.DEFAULT_DATE_FORMAT);
                  }

                  final var value2AsDate = dateFormat.parse(value2AsString);
                  final var value2 = value2AsDate.getTime();
                  return value1 == value2;
                } catch (ParseException ignored) {
                  return new Date(value1).toString().equals(value2AsString);
                }
              }
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                      fieldValue2.bytes, fieldValue2.offset);
              return value1 == value2.longValue();
            }
          }
          break;
        }

        case BINARY: {
          if (Objects.requireNonNull(iField2.type) == PropertyTypeInternal.BINARY) {
            final var length1 = VarIntSerializer.readAsInteger(fieldValue1);
            final var length2 = VarIntSerializer.readAsInteger(fieldValue2);
            if (length1 != length2) {
              return false;
            }

            for (var i = 0; i < length1; ++i) {
              if (fieldValue1.bytes[fieldValue1.offset + i]
                  != fieldValue2.bytes[fieldValue2.offset + i]) {
                return false;
              }
            }
            return true;
          }
          break;
        }

        case LINK: {
          switch (iField2.type) {
            case LINK: {
              final var collectionId1 = VarIntSerializer.readAsInteger(fieldValue1);
              final var collectionId2 = VarIntSerializer.readAsInteger(fieldValue2);
              if (collectionId1 != collectionId2) {
                return false;
              }

              final var collectionPos1 = VarIntSerializer.readAsLong(fieldValue1);
              final var collectionPos2 = VarIntSerializer.readAsLong(fieldValue2);
              if (collectionPos1 == collectionPos2) {
                return true;
              }
              break;
            }
            case STRING: {
              return readOptimizedLink(fieldValue1, false)
                  .toString()
                  .equals(readString(fieldValue2));
            }
          }
          break;
        }

        case DECIMAL: {
          final var value1 =
              DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                  fieldValue1.bytes, fieldValue1.offset);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return value1.equals(new BigDecimal(value2));
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return value1.equals(new BigDecimal(value2));
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return value1.equals(new BigDecimal(value2));
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return value1.equals(new BigDecimal(value2));
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return value1.equals(new BigDecimal(value2));
            }
            case STRING: {
              return value1.toString().equals(readString(fieldValue2));
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                      fieldValue2.bytes, fieldValue2.offset);
              return value1.equals(value2);
            }
          }
          break;
        }
      }
    } finally {
      fieldValue1.offset = offset1;
      fieldValue2.offset = offset2;
    }

    return false;
  }

  /**
   * Compares two values executing also conversion between types.
   *
   * @param session current database session
   * @param iField1 First value to compare
   * @param iField2 Second value to compare
   * @return 0 if they matches, >0 if first value is major than second, <0 in case is minor
   */
  @Override
  public int compare(DatabaseSessionInternal session, final BinaryField iField1,
      final BinaryField iField2) {
    final var fieldValue1 = iField1.bytes;
    final var offset1 = fieldValue1.offset;

    final var fieldValue2 = iField2.bytes;
    final var offset2 = fieldValue2.offset;

    try {
      switch (iField1.type) {
        case INTEGER: {
          final var value1 = VarIntSerializer.readAsInteger(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return Integer.compare(value1, value2);
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case DATE: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2) * MILLISEC_PER_DAY;
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case STRING: {
              final var value2 = readString(fieldValue2);
              return Integer.toString(value1).compareTo(value2);
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE
                      .deserialize(session.getSerializerFactory(), fieldValue2.bytes,
                          fieldValue2.offset)
                      .intValue();
              return Integer.compare(value1, value2);
            }
          }
          break;
        }

        case LONG: {
          final var value1 = VarIntSerializer.readAsLong(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return Long.compare(value1, value2);
            }
            case DATE: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2) * MILLISEC_PER_DAY;
              return Long.compare(value1, value2);
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case STRING: {
              final var value2 = readString(fieldValue2);
              return Long.toString(value1).compareTo(value2);
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE
                      .deserialize(session.getSerializerFactory(), fieldValue2.bytes,
                          fieldValue2.offset)
                      .longValue();
              return Long.compare(value1, value2);
            }
          }
          break;
        }

        case SHORT: {
          final var value1 = VarIntSerializer.readAsShort(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case DATE: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2) * MILLISEC_PER_DAY;
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return Short.compare(value1, value2);
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case STRING: {
              final var value2 = readString(fieldValue2);
              return Short.toString(value1).compareTo(value2);
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE
                      .deserialize(session.getSerializerFactory(), fieldValue2.bytes,
                          fieldValue2.offset)
                      .shortValue();
              return Short.compare(value1, value2);
            }
          }
          break;
        }

        case STRING: {
          final var value1 = readString(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return value1.compareTo(Integer.toString(value2));
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return value1.compareTo(Long.toString(value2));
            }
            case DATE: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2) * MILLISEC_PER_DAY;
              return value1.compareTo(Long.toString(value2));
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return value1.compareTo(Short.toString(value2));
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return value1.compareTo(Byte.toString(value2));
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return value1.compareTo(Float.toString(value2));
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return value1.compareTo(Double.toString(value2));
            }
            case STRING: {
              final var value2 = readString(fieldValue2);

              final Collate collate;
              if (iField1.collate != null
                  && !DefaultCollate.NAME.equals(iField1.collate.getName())) {
                collate = iField1.collate;
              } else if (iField2.collate != null
                  && !DefaultCollate.NAME.equals(iField2.collate.getName())) {
                collate = iField2.collate;
              } else {
                collate = null;
              }

              if (collate != null) {
                final var str1 = (String) collate.transform(value1);
                final var str2 = (String) collate.transform(value2);
                return str1.compareTo(str2);
              }

              return value1.compareTo(value2);
            }
            case BOOLEAN: {
              final var value2 = readByte(fieldValue2) == 1;
              return value1.compareTo(Boolean.toString(value2));
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                      fieldValue2.bytes, fieldValue2.offset);
              return new BigDecimal(value1).compareTo(value2);
            }
          }
          break;
        }

        case DOUBLE: {
          final var value1 = Double.longBitsToDouble(readLong(fieldValue1));

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return Double.compare(value1, value2);
            }
            case STRING: {
              final var value2 = readString(fieldValue2);
              return Double.toString(value1).compareTo(value2);
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE
                      .deserialize(session.getSerializerFactory(), fieldValue2.bytes,
                          fieldValue2.offset)
                      .doubleValue();
              return Double.compare(value1, value2);
            }
          }
          break;
        }

        case FLOAT: {
          final var value1 = Float.intBitsToFloat(readInteger(fieldValue1));

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return Float.compare(value1, value2);
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case STRING, DECIMAL: {
              final var value2 = readString(fieldValue2);
              return Float.toString(value1).compareTo(value2);
            }
          }
          break;
        }

        case BYTE: {
          final var value1 = readByte(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return Byte.compare(value1, value2);
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case STRING: {
              final var value2 = readString(fieldValue2);
              return Byte.toString(value1).compareTo(value2);
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE
                      .deserialize(session.getSerializerFactory(), fieldValue2.bytes,
                          fieldValue2.offset)
                      .byteValue();
              return Byte.compare(value1, value2);
            }
          }
          break;
        }

        case BOOLEAN: {
          final var value1 = readByte(fieldValue1) == 1;

          switch (iField2.type) {
            case BOOLEAN: {
              final var value2 = readByte(fieldValue2) == 1;
              return (value1 == value2) ? 0 : value1 ? 1 : -1;
            }
            case STRING: {
              final var value2 = Boolean.parseBoolean(readString(fieldValue2));
              return (value1 == value2) ? 0 : value1 ? 1 : -1;
            }
          }
          break;
        }

        case DATETIME: {
          final var value1 = VarIntSerializer.readAsLong(fieldValue1);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return Long.compare(value1, value2);
            }
            case DATE: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2) * MILLISEC_PER_DAY;
              return Long.compare(value1, value2);
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case STRING: {
              final var value2AsString = readString(fieldValue2);

              if (IOUtils.isLong(value2AsString)) {
                final var value2 = Long.parseLong(value2AsString);
                return Long.compare(value1, value2);
              }
              try {
                final var dateFormat = DateHelper.getDateTimeFormatInstance(session);
                final var value2AsDate = dateFormat.parse(value2AsString);
                final var value2 = value2AsDate.getTime();
                return Long.compare(value1, value2);
              } catch (ParseException ignored) {
                try {
                  var dateFormat = DateHelper.getDateFormatInstance(session);

                  final var value2AsDate = dateFormat.parse(value2AsString);
                  final var value2 = value2AsDate.getTime();
                  return Long.compare(value1, value2);
                } catch (ParseException ignore) {
                  return new Date(value1).toString().compareTo(value2AsString);
                }
              }
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE
                      .deserialize(session.getSerializerFactory(), fieldValue2.bytes,
                          fieldValue2.offset)
                      .longValue();
              return Long.compare(value1, value2);
            }
          }
          break;
        }

        case DATE: {
          final var value1 = VarIntSerializer.readAsLong(fieldValue1) * MILLISEC_PER_DAY;

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return Long.compare(value1, value2);
            }
            case DATE: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2) * MILLISEC_PER_DAY;
              return Long.compare(value1, value2);
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return (value1 < value2) ? -1 : ((value1 == value2) ? 0 : 1);
            }
            case STRING: {
              final var value2AsString = readString(fieldValue2);

              if (IOUtils.isLong(value2AsString)) {
                final var value2 = Long.parseLong(value2AsString);
                return Long.compare(value1, value2);
              }

              try {
                final DateFormat dateFormat;
                if (session != null) {
                  dateFormat = DateHelper.getDateFormatInstance(session);
                } else {
                  dateFormat = new SimpleDateFormat(StorageConfiguration.DEFAULT_DATE_FORMAT);
                }
                final var value2AsDate = dateFormat.parse(value2AsString);
                var value2 = value2AsDate.getTime();
                value2 =
                    convertDayToTimezone(
                        DateHelper.getDatabaseTimeZone(session), TimeZone.getTimeZone("GMT"),
                        value2);
                return Long.compare(value1, value2);
              } catch (ParseException ignore) {
                try {
                  final DateFormat dateFormat;
                  if (session != null) {
                    dateFormat = DateHelper.getDateFormatInstance(session);
                  } else {
                    dateFormat =
                        new SimpleDateFormat(StorageConfiguration.DEFAULT_DATETIME_FORMAT);
                  }

                  final var value2AsDate = dateFormat.parse(value2AsString);
                  var value2 = value2AsDate.getTime();
                  value2 =
                      convertDayToTimezone(
                          DateHelper.getDatabaseTimeZone(session),
                          TimeZone.getTimeZone("GMT"),
                          value2);
                  return Long.compare(value1, value2);
                } catch (ParseException ignored) {
                  return new Date(value1).toString().compareTo(value2AsString);
                }
              }
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE
                      .deserialize(session.getSerializerFactory(), fieldValue2.bytes,
                          fieldValue2.offset)
                      .longValue();
              return Long.compare(value1, value2);
            }
          }
          break;
        }

        case BINARY: {
          if (Objects.requireNonNull(iField2.type) == PropertyTypeInternal.BINARY) {
            final var length1 = VarIntSerializer.readAsInteger(fieldValue1);
            final var length2 = VarIntSerializer.readAsInteger(fieldValue2);

            final var max = Math.min(length1, length2);
            for (var i = 0; i < max; ++i) {
              final var b1 = fieldValue1.bytes[fieldValue1.offset + i];
              final var b2 = fieldValue2.bytes[fieldValue2.offset + i];

              if (b1 > b2) {
                return 1;
              } else if (b2 > b1) {
                return -1;
              }
            }

            if (length1 > length2) {
              return 1;
            } else if (length2 > length1) {
              return -1;
            }

            // EQUALS
            return 0;
          }
          break;
        }

        case LINK: {
          switch (iField2.type) {
            case LINK: {
              final var collectionId1 = VarIntSerializer.readAsInteger(fieldValue1);
              final var collectionId2 = VarIntSerializer.readAsInteger(fieldValue2);
              if (collectionId1 > collectionId2) {
                return 1;
              } else if (collectionId1 < collectionId2) {
                return -1;
              } else {
                final var collectionPos1 = VarIntSerializer.readAsLong(fieldValue1);
                final var collectionPos2 = VarIntSerializer.readAsLong(fieldValue2);
                if (collectionPos1 > collectionPos2) {
                  return 1;
                } else if (collectionPos1 < collectionPos2) {
                  return -1;
                }
                return 0;
              }
            }

            case STRING: {
              return readOptimizedLink(fieldValue1, false)
                  .compareTo(new RecordId(readString(fieldValue2)));
            }
          }
          break;
        }

        case DECIMAL: {
          final var value1 =
              DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                  fieldValue1.bytes, fieldValue1.offset);

          switch (iField2.type) {
            case INTEGER: {
              final var value2 = VarIntSerializer.readAsInteger(fieldValue2);
              return value1.compareTo(new BigDecimal(value2));
            }
            case LONG:
            case DATETIME: {
              final var value2 = VarIntSerializer.readAsLong(fieldValue2);
              return value1.compareTo(new BigDecimal(value2));
            }
            case SHORT: {
              final var value2 = VarIntSerializer.readAsShort(fieldValue2);
              return value1.compareTo(new BigDecimal(value2));
            }
            case FLOAT: {
              final var value2 = Float.intBitsToFloat(readInteger(fieldValue2));
              return value1.compareTo(new BigDecimal(value2));
            }
            case DOUBLE: {
              final var value2 = Double.longBitsToDouble(readLong(fieldValue2));
              return value1.compareTo(new BigDecimal(value2));
            }
            case STRING: {
              final var value2 = readString(fieldValue2);
              return value1.toString().compareTo(value2);
            }
            case DECIMAL: {
              final var value2 =
                  DecimalSerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                      fieldValue2.bytes, fieldValue2.offset);
              return value1.compareTo(value2);
            }
            case BYTE: {
              final var value2 = readByte(fieldValue2);
              return value1.compareTo(new BigDecimal(value2));
            }
          }
          break;
        }
      }
    } finally {
      fieldValue1.offset = offset1;
      fieldValue2.offset = offset2;
    }

    // NO COMPARE SUPPORTED, RETURN NON EQUALS
    return 1;
  }
}
