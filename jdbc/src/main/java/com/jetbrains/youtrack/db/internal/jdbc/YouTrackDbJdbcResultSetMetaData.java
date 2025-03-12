/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * <p>*
 */
package com.jetbrains.youtrack.db.internal.jdbc;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class YouTrackDbJdbcResultSetMetaData implements ResultSetMetaData {

  private static final Map<PropertyType, Integer> typesSqlTypes = new EnumMap<>(PropertyType.class);

  static {
    typesSqlTypes.put(PropertyType.STRING, Types.VARCHAR);
    typesSqlTypes.put(PropertyType.INTEGER, Types.INTEGER);
    typesSqlTypes.put(PropertyType.FLOAT, Types.FLOAT);
    typesSqlTypes.put(PropertyType.BOOLEAN, Types.BOOLEAN);
    typesSqlTypes.put(PropertyType.LONG, Types.BIGINT);
    typesSqlTypes.put(PropertyType.DOUBLE, Types.DOUBLE);
    typesSqlTypes.put(PropertyType.DECIMAL, Types.DECIMAL);
    typesSqlTypes.put(PropertyType.DATE, Types.DATE);
    typesSqlTypes.put(PropertyType.DATETIME, Types.TIMESTAMP);
    typesSqlTypes.put(PropertyType.BYTE, Types.TINYINT);
    typesSqlTypes.put(PropertyType.SHORT, Types.SMALLINT);

    // NOT SURE ABOUT THE FOLLOWING MAPPINGS
    typesSqlTypes.put(PropertyType.BINARY, Types.BINARY);
    typesSqlTypes.put(PropertyType.EMBEDDED, Types.JAVA_OBJECT);
    typesSqlTypes.put(PropertyType.EMBEDDEDLIST, Types.ARRAY);
    typesSqlTypes.put(PropertyType.EMBEDDEDMAP, Types.JAVA_OBJECT);
    typesSqlTypes.put(PropertyType.EMBEDDEDSET, Types.ARRAY);
    typesSqlTypes.put(PropertyType.LINK, Types.JAVA_OBJECT);
    typesSqlTypes.put(PropertyType.LINKLIST, Types.ARRAY);
    typesSqlTypes.put(PropertyType.LINKMAP, Types.JAVA_OBJECT);
    typesSqlTypes.put(PropertyType.LINKSET, Types.ARRAY);
  }

  private final String[] fieldNames;
  private final YouTrackDbJdbcResultSet resultSet;
  private final DatabaseSession session;

  public YouTrackDbJdbcResultSetMetaData(
      YouTrackDbJdbcResultSet youTrackDbJdbcResultSet, List<String> fieldNames,
      DatabaseSession session) {
    resultSet = youTrackDbJdbcResultSet;
    this.fieldNames = fieldNames.toArray(new String[]{});
    this.session = session;
  }

  public static Integer getSqlType(final PropertyType iType) {
    return typesSqlTypes.get(iType);
  }

  public int getColumnCount() {

    return fieldNames.length;
  }

  @Override
  public String getCatalogName(final int column) {
    // return an empty String according to the method's documentation
    return "";
  }

  @Override
  public String getColumnClassName(final int column) throws SQLException {
    var value = this.resultSet.getObject(column);
    if (value == null) {
      return null;
    }
    return value.getClass().getCanonicalName();
  }

  @Override
  public int getColumnDisplaySize(final int column) {
    return 0;
  }

  @Override
  public String getColumnLabel(final int column) throws SQLException {
    return getColumnName(column);
  }

  @Override
  public String getColumnName(final int column) {
    return fieldNames[column - 1];
  }

  @Override
  public int getColumnType(final int column) throws SQLException {
    final var currentRecord = getCurrentRecord();

    if (column > fieldNames.length) {
      return Types.NULL;
    }

    var fieldName = fieldNames[column - 1];

    PropertyType otype = null;
    if (currentRecord.isEntity()) {
      var entity = currentRecord.castToEntity();
      var schemaClass = entity.getSchemaClass();
      if (schemaClass != null) {
        otype = schemaClass.getProperty(fieldName).getType();
      } else {
        otype = null;
      }
    }

    if (otype == null) {
      var value = currentRecord.getProperty(fieldName);

      switch (value) {
        case null -> {
          return Types.NULL;
        }
        case Blob blob -> {
          // Check if the type is a binary record or a collection of binary
          // records
          return Types.BINARY;
          // Check if the type is a binary record or a collection of binary
          // records
        }
        case LinkList list -> {
          // check if all the list items are instances of RecordBytes
          var iterator = list.listIterator();
          Identifiable listElement;
          var stop = false;
          while (iterator.hasNext() && !stop) {
            listElement = iterator.next();
            if (!(listElement instanceof Blob)) {
              stop = true;
            }
          }
          if (!stop) {
            return Types.BLOB;
          }
        }
        default -> {
        }
      }
      return getSQLTypeFromJavaClass(value);
    } else {
      if (otype == PropertyType.EMBEDDED || otype == PropertyType.LINK) {
        var value = currentRecord.getProperty(fieldName);
        if (value == null) {
          return Types.NULL;
        }
        // 1. Check if the type is another record or a collection of records
        if (value instanceof Blob) {
          return Types.BINARY;
        }
      } else {
        if (otype == PropertyType.EMBEDDEDLIST || otype == PropertyType.LINKLIST) {
          var value = currentRecord.getProperty(fieldName);
          if (value == null) {
            return Types.NULL;
          }
          if (value instanceof LinkList list) {
            // check if all the list items are instances of RecordBytes
            var iterator = list.listIterator();
            Identifiable listElement;
            var stop = false;
            while (iterator.hasNext() && !stop) {
              listElement = iterator.next();
              if (!(listElement instanceof Blob)) {
                stop = true;
              }
            }
            if (stop) {
              return typesSqlTypes.get(otype);
            } else {
              return Types.BLOB;
            }
          }
        }
      }
    }
    return typesSqlTypes.get(otype);
  }

  protected Result getCurrentRecord() throws SQLException {
    final var currentRecord = resultSet.unwrap(Result.class);
    if (currentRecord == null) {
      throw new SQLException("No current record");
    }
    return currentRecord;
  }

  private static int getSQLTypeFromJavaClass(final Object value) {
    return switch (value) {
      case Boolean b -> typesSqlTypes.get(PropertyType.BOOLEAN);
      case Byte b -> typesSqlTypes.get(PropertyType.BYTE);
      case Date date -> typesSqlTypes.get(PropertyType.DATETIME);
      case Double v -> typesSqlTypes.get(PropertyType.DOUBLE);
      case BigDecimal bigDecimal -> typesSqlTypes.get(PropertyType.DECIMAL);
      case Float v -> typesSqlTypes.get(PropertyType.FLOAT);
      case Integer i -> typesSqlTypes.get(PropertyType.INTEGER);
      case Long l -> typesSqlTypes.get(PropertyType.LONG);
      case Short i -> typesSqlTypes.get(PropertyType.SHORT);
      case String s -> typesSqlTypes.get(PropertyType.STRING);
      case List<?> list -> typesSqlTypes.get(PropertyType.EMBEDDEDLIST);
      case null, default -> Types.JAVA_OBJECT;
    };
  }

  @Override
  public String getColumnTypeName(final int column) throws SQLException {
    final var currentRecord = getCurrentRecord();
    var columnLabel = fieldNames[column - 1];

    if (currentRecord.isEntity()) {
      var entity = currentRecord.castToEntity();
      var schemaClass = entity.getSchemaClass();
      if (schemaClass != null) {
        var property = schemaClass.getProperty(columnLabel);
        if (property != null) {
          return property.getType().toString();
        }
      }
    }

    return null;
  }

  public int getPrecision(final int column) {
    return 0;
  }

  public int getScale(final int column) {
    return 0;
  }

  public String getSchemaName(final int column) throws SQLException {
    final var currentRecord = getCurrentRecord();
    if (currentRecord == null || !currentRecord.isEntity()) {
      return "";
    } else {
      return ((EntityImpl) currentRecord.asEntity()).getSession().getDatabaseName();
    }
  }

  public String getTableName(final int column) throws SQLException {
    final var p = getProperty(column);
    return p != null ? p.getOwnerClass().getName() : null;
  }

  public boolean isAutoIncrement(final int column) {
    return false;
  }

  public boolean isCaseSensitive(final int column) throws SQLException {
    final var p = getProperty(column);
    return p != null && p.getCollate().getName().equalsIgnoreCase("ci");
  }

  public boolean isCurrency(final int column) {

    return false;
  }

  public boolean isDefinitelyWritable(final int column) {

    return false;
  }

  public int isNullable(final int column) {
    return columnNullableUnknown;
  }

  public boolean isReadOnly(final int column) throws SQLException {
    final var p = getProperty(column);
    return p != null && p.isReadonly();
  }

  public boolean isSearchable(int column) {
    return true;
  }

  public boolean isSigned(final int column) throws SQLException {
    final var currentRecord = getCurrentRecord();
    PropertyType otype = null;
    if (currentRecord.isEntity()) {
      var entity = currentRecord.castToEntity();
      var schemaClass = entity.getSchemaClass();
      if (schemaClass != null) {
        otype = schemaClass.getProperty(fieldNames[column - 1]).getType();
      } else {
        otype = null;
      }
    }

    return YouTrackDbJdbcResultSetMetaData.isANumericColumn(otype);
  }

  public boolean isWritable(final int column) throws SQLException {
    return !isReadOnly(column);
  }

  public boolean isWrapperFor(final Class<?> iface) {
    return false;
  }

  public <T> T unwrap(final Class<T> iface) {
    return null;
  }

  private static boolean isANumericColumn(final PropertyType type) {
    return type == PropertyType.BYTE
        || type == PropertyType.DOUBLE
        || type == PropertyType.FLOAT
        || type == PropertyType.INTEGER
        || type == PropertyType.LONG
        || type == PropertyType.SHORT;
  }

  protected SchemaProperty getProperty(final int column) throws SQLException {
    var fieldName = getColumnName(column);

    var currentRecord = getCurrentRecord();
    if (currentRecord.isEntity()) {
      var entity = currentRecord.castToEntity();
      var schemaClass = entity.getSchemaClass();
      if (schemaClass != null) {
        return schemaClass.getProperty(fieldName);
      }
    }

    return null;
  }
}
