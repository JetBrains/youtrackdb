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
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkListImpl;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrack.db.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 *
 */
public class YouTrackDbJdbcResultSet implements java.sql.ResultSet {

  private final YouTrackDbJdbcResultSetMetaData resultSetMetaData;
  private final DatabaseSession session;
  private final List<String> fieldNames;
  private List<Result> records;
  private final YouTrackDbJdbcStatement statement;
  private Result result;

  private int cursor = -1;
  private int rowCount = 0;
  private final int type;
  private final int concurrency;
  private final int holdability;

  private boolean lastReadWasNull = true;


  protected YouTrackDbJdbcResultSet(
      final YouTrackDbJdbcStatement statement,
      DatabaseSession session,
      final ResultSet oResultSet,
      final int type,
      final int concurrency,
      int holdability)
      throws SQLException {

    this.statement = statement;
    this.session = session;
    try {
      records = oResultSet.stream().collect(Collectors.toList());
    } catch (Exception e) {
      throw new SQLException("Error occourred while mapping results ", e);
    }
    oResultSet.close();
    rowCount = records.size();

    activateDatabaseOnCurrentThread();

    if (!records.isEmpty()) {
      result = records.getFirst();
    } else {
      result = new ResultInternal(statement.session);
    }

    fieldNames = extractFieldNames(statement);

    if (type == TYPE_FORWARD_ONLY
        || type == TYPE_SCROLL_INSENSITIVE
        || type == TYPE_SCROLL_SENSITIVE) {
      this.type = type;
    } else {
      throw new SQLException(
          "Bad ResultSet type: "
              + type
              + " instead of one of the following values: "
              + TYPE_FORWARD_ONLY
              + ", "
              + TYPE_SCROLL_INSENSITIVE
              + " or"
              + TYPE_SCROLL_SENSITIVE);
    }

    if (concurrency == CONCUR_READ_ONLY || concurrency == CONCUR_UPDATABLE) {
      this.concurrency = concurrency;
    } else {
      throw new SQLException(
          "Bad ResultSet Concurrency type: "
              + concurrency
              + " instead of one of the following values: "
              + CONCUR_READ_ONLY
              + " or"
              + CONCUR_UPDATABLE);
    }

    if (holdability == HOLD_CURSORS_OVER_COMMIT || holdability == CLOSE_CURSORS_AT_COMMIT) {
      this.holdability = holdability;
    } else {
      throw new SQLException(
          "Bad ResultSet Holdability type: "
              + holdability
              + " instead of one of the following values: "
              + HOLD_CURSORS_OVER_COMMIT
              + " or"
              + CLOSE_CURSORS_AT_COMMIT);
    }

    resultSetMetaData = new YouTrackDbJdbcResultSetMetaData(this, fieldNames, session);
  }

  private List<String> extractFieldNames(YouTrackDbJdbcStatement statement) {
    List<String> fields = new ArrayList<>();
    if (statement.sql != null && !statement.sql.isEmpty()) {
      try {

        YouTrackDBSql osql = null;
        DatabaseSessionInternal db = null;
        try {
          db =
              (DatabaseSessionInternal)
                  ((YouTrackDbJdbcConnection) statement.getConnection()).getDatabaseSession();
          if (db == null) {
            osql = new YouTrackDBSql(new ByteArrayInputStream(statement.sql.getBytes()));
          } else {
            osql =
                new YouTrackDBSql(
                    new ByteArrayInputStream(statement.sql.getBytes()),
                    db.getStorageInfo().getConfiguration().getCharset());
          }
        } catch (UnsupportedEncodingException e) {
          LogManager.instance()
              .warn(
                  this,
                  "Invalid charset for database "
                      + db
                      + " "
                      + db.getStorageInfo().getConfiguration().getCharset());
          osql = new YouTrackDBSql(new ByteArrayInputStream(statement.sql.getBytes()));
        } catch (Exception e) {
          throw new RuntimeException(e);
        }

        final var select = osql.SelectStatement();
        if (select.getProjection() != null) {
          var isMappable =
              select.getProjection().getItems().stream()
                  .peek(i -> fields.add(i.getProjectionAliasAsString()))
                  .allMatch(i -> i.getExpression().isBaseIdentifier());
          if (!isMappable) {
            fields.clear();
          }
        }

      } catch (ParseException e) {
        // NOOP
      }
    }
    if (fields.isEmpty()) {
      fields.addAll(result.getPropertyNames());
    }
    return fields;
  }

  private void activateDatabaseOnCurrentThread() {
    statement.session.activateOnCurrentThread();
  }

  public void close() throws SQLException {
    cursor = 0;
    rowCount = 0;
    records = null;
  }

  public boolean first() throws SQLException {
    return absolute(0);
  }

  public boolean last() throws SQLException {
    return absolute(rowCount - 1);
  }

  public boolean next() throws SQLException {
    return absolute(++cursor);
  }

  public boolean previous() throws SQLException {
    return absolute(++cursor);
  }

  public void afterLast() {
    // OUT OF LAST ITEM
    cursor = rowCount;
  }

  public void beforeFirst() {
    // OUT OF FIRST ITEM
    cursor = -1;
  }

  public boolean relative(int iRows) throws SQLException {
    return absolute(cursor + iRows);
  }

  public boolean absolute(int iRowNumber) {
    if (iRowNumber > rowCount - 1) {
      // OUT OF LAST ITEM
      cursor = rowCount;
      return false;
    } else if (iRowNumber < 0) {
      // OUT OF FIRST ITEM
      cursor = -1;
      return false;
    }

    cursor = iRowNumber;
    result = records.get(cursor);
    return true;
  }

  public boolean isAfterLast() {
    return cursor >= rowCount - 1;
  }

  public boolean isBeforeFirst() {
    return cursor < 0;
  }

  public boolean isClosed() throws SQLException {
    return records == null;
  }

  public boolean isFirst() {
    return cursor == 0;
  }

  public boolean isLast() {
    return cursor == rowCount - 1;
  }

  public Statement getStatement() {
    return statement;
  }

  public ResultSetMetaData getMetaData() {
    return resultSetMetaData;
  }

  public void deleteRow() throws SQLException {
    if (result.isEntity()) {
      result.asEntityOrNull().delete();
    } else {
      throw new SQLException("The current record is not an entity and can not be deleted");
    }
  }

  public int findColumn(String columnLabel) throws SQLException {
    var column = 0;
    var i = 0;
    while (i < (fieldNames.size() - 1) && column == 0) {
      if (fieldNames.get(i).equals(columnLabel)) {
        column = i + 1;
      } else {
        i++;
      }
    }
    if (column == 0) {
      throw new SQLException(
          "The column '"
              + columnLabel
              + "' does not exists (Result Set entity: "
              + rowCount
              + ")");
    }
    return column;
  }

  private static int getFieldIndex(final int columnIndex) throws SQLException {
    if (columnIndex < 1) {
      throw new SQLException("The column index cannot be less than 1");
    }
    return columnIndex - 1;
  }

  public Array getArray(int columnIndex) throws SQLException {
    return getArray(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public Array getArray(String columnLabel) throws SQLException {
    var value = result.getProperty(columnLabel);
    if (!(value instanceof Collection<?> collection)) {
      throw new SQLException(
          "The column '"
              + columnLabel
              + "' does not contain a collection of and can not be converted to an Array");
    }

    Array array = new YouTrackDbJdbcArray(collection);
    lastReadWasNull = false;
    return array;
  }

  public InputStream getAsciiStream(int columnIndex) {
    lastReadWasNull = true;
    return null;
  }

  public InputStream getAsciiStream(final String columnLabel) {
    lastReadWasNull = true;
    return null;
  }

  public BigDecimal getBigDecimal(final int columnIndex) throws SQLException {

    return getBigDecimal(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public BigDecimal getBigDecimal(final String columnLabel) throws SQLException {
    try {
      BigDecimal r = result.getProperty(columnLabel);
      lastReadWasNull = r == null;
      return r;
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the double value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  @Override
  public BigDecimal getBigDecimal(final int columnIndex, final int scale) throws SQLException {
    return getBigDecimal(fieldNames.get(getFieldIndex(columnIndex)), scale);
  }

  @Override
  public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
    try {
      var r = ((BigDecimal) result.getProperty(columnLabel)).setScale(scale);
      lastReadWasNull = r == null;
      return r;
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the double value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public InputStream getBinaryStream(int columnIndex) throws SQLException {
    return getBinaryStream(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public InputStream getBinaryStream(String columnLabel) throws SQLException {
    try {
      var blob = getBlob(columnLabel);
      lastReadWasNull = blob == null;
      return blob != null ? blob.getBinaryStream() : null;
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the binary stream at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public java.sql.Blob getBlob(int columnIndex) throws SQLException {
    return getBlob(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public java.sql.Blob getBlob(String columnLabel) throws SQLException {
    try {
      var value = result.getProperty(columnLabel);

      if (value instanceof RID) {
        var transaction = statement.session.getActiveTransaction();
        value = transaction.load(((RID) value));
      }

      if (value instanceof Blob) {
        lastReadWasNull = false;
        return new YouTrackDbBlob((Blob) value);
      } else if (value instanceof LinkListImpl list) {
        // check if all the list items are instances of RecordBytes
        var iterator = list.listIterator();

        List<Blob> binaryRecordList = new ArrayList<>(list.size());
        while (iterator.hasNext()) {
          var listElement = iterator.next();

          try {
            Blob ob = statement.session.load(listElement.getIdentity());
            binaryRecordList.add(ob);
          } catch (RecordNotFoundException rnf) {
            // ignore
          }
        }
        lastReadWasNull = false;
        return new YouTrackDbBlob(binaryRecordList);
      }

      lastReadWasNull = true;
      return null;
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the BLOB at column '" + columnLabel + "'", e);
    }
  }

  public boolean getBoolean(int columnIndex) throws SQLException {
    return getBoolean(fieldNames.get(getFieldIndex(columnIndex)));
  }

  @SuppressWarnings("boxing")
  public boolean getBoolean(String columnLabel) throws SQLException {
    try {
      Boolean r = result.getProperty(columnLabel);
      lastReadWasNull = r == null;
      return Boolean.TRUE.equals(r);
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the boolean value at column '"
              + columnLabel
              + "' ---> "
              + result.toJSON(),
          e);
    }
  }

  @SuppressWarnings("boxing")
  public byte getByte(int columnIndex) throws SQLException {
    return getByte(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public byte getByte(String columnLabel) throws SQLException {
    try {
      Byte r = result.getProperty(columnLabel);
      lastReadWasNull = r == null;
      return r == null ? 0 : r;
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the byte value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public byte[] getBytes(int columnIndex) throws SQLException {
    return getBytes(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public byte[] getBytes(String columnLabel) throws SQLException {
    try {

      var value = result.getProperty(columnLabel);
      if (value == null) {
        lastReadWasNull = true;
        return null;
      } else {
        if (value instanceof Blob) {
          lastReadWasNull = false;
          return ((RecordAbstract) value).toStream();
        }
        byte[] r = result.getProperty(columnLabel);
        lastReadWasNull = r == null;
        return r;
      }
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the bytes value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public Reader getCharacterStream(int columnIndex) {
    lastReadWasNull = true;
    return null;
  }

  public Reader getCharacterStream(String columnLabel) {
    lastReadWasNull = true;
    return null;
  }

  public Clob getClob(int columnIndex) {
    lastReadWasNull = true;
    return null;
  }

  public Clob getClob(String columnLabel) {
    lastReadWasNull = true;
    return null;
  }

  public int getConcurrency() {
    return concurrency;
  }

  public String getCursorName() {
    return null;
  }

  public Date getDate(int columnIndex) throws SQLException {
    return getDate(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public Date getDate(final String columnLabel) throws SQLException {
    try {
      activateDatabaseOnCurrentThread();

      java.util.Date date = result.getProperty(columnLabel);
      lastReadWasNull = date == null;
      return date != null ? new Date(date.getTime()) : null;
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the date value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public Date getDate(final int columnIndex, final Calendar cal) throws SQLException {
    return getDate(fieldNames.get(getFieldIndex(columnIndex)), cal);
  }

  public Date getDate(String columnLabel, Calendar cal) throws SQLException {
    if (cal == null) {
      throw new SQLException();
    }
    try {
      activateDatabaseOnCurrentThread();

      java.util.Date date = result.getProperty(columnLabel);
      if (date == null) {
        lastReadWasNull = true;
        return null;
      }

      cal.setTimeInMillis(date.getTime());
      lastReadWasNull = false;
      return new Date(cal.getTimeInMillis());
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the date value (calendar) "
              + "at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public double getDouble(final int columnIndex) throws SQLException {
    var fieldIndex = getFieldIndex(columnIndex);
    return getDouble(fieldNames.get(fieldIndex));
  }

  public double getDouble(final String columnLabel) throws SQLException {
    try {
      final Double r = result.getProperty(columnLabel);
      lastReadWasNull = r == null;
      return r != null ? r : 0;
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the double value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public int getFetchDirection() {
    return 0;
  }

  public void setFetchDirection(int direction) {
  }

  public int getFetchSize() {
    return rowCount;
  }

  public void setFetchSize(int rows) {
  }

  public float getFloat(int columnIndex) throws SQLException {

    return getFloat(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public float getFloat(String columnLabel) throws SQLException {
    try {
      final Float r = result.getProperty(columnLabel);
      lastReadWasNull = r == null;
      return r != null ? r : 0;
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the float value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public int getHoldability() {
    return holdability;
  }

  public int getInt(int columnIndex) throws SQLException {
    return getInt(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public int getInt(String columnLabel) throws SQLException {
    if ("@version".equals(columnLabel)) {
      if (result.isEntity()) {
        return result.asEntityOrNull().getVersion();
      } else {
        throw new SQLException(
            "The current record is not an entity so its version can not be retrieved");
      }
    }

    try {
      final Integer r = result.getProperty(columnLabel);
      lastReadWasNull = r == null;
      return r != null ? r : 0;

    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the integer value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public long getLong(int columnIndex) throws SQLException {
    return getLong(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public long getLong(String columnLabel) throws SQLException {

    try {
      final Long r = result.getProperty(columnLabel);
      lastReadWasNull = r == null;
      return r != null ? r : 0;
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the long value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public Reader getNCharacterStream(int columnIndex) {
    lastReadWasNull = true;
    return null;
  }

  public Reader getNCharacterStream(String columnLabel) {
    lastReadWasNull = true;
    return null;
  }

  public NClob getNClob(int columnIndex) {
    lastReadWasNull = true;
    return null;
  }

  public NClob getNClob(String columnLabel) {
    lastReadWasNull = true;
    return null;
  }

  public String getNString(int columnIndex) throws SQLException {
    return getNString(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public String getNString(String columnLabel) throws SQLException {
    try {
      String r = result.getProperty(columnLabel);
      lastReadWasNull = r == null;
      return r;
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the string value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public Object getObject(int columnIndex) throws SQLException {
    return getObject(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public Object getObject(String columnLabel) throws SQLException {

    if ("@rid".equals(columnLabel) || "rid".equals(columnLabel)) {
      lastReadWasNull = false;
      return result.getIdentity().toString();
    }

    if ("@class".equals(columnLabel) || "class".equals(columnLabel)) {
      if (!result.isEntity()) {
        throw new SQLException(
            "The current record is not an entity. Its class can not be retrieved");
      }
      var r = result.asEntity().getSchemaClassName();
      lastReadWasNull = r == null;
      return r;
    }

    try {
      var value = result.getProperty(columnLabel);

      if (value == null) {
        lastReadWasNull = true;
        return null;
      }
      return value;
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the Java Object at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
    throw new SQLFeatureNotSupportedException("This method has not been implemented.");
  }

  public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
    throw new SQLFeatureNotSupportedException("This method has not been implemented.");
  }

  public Ref getRef(int columnIndex) {
    lastReadWasNull = true;
    return null;
  }

  public Ref getRef(String columnLabel) {
    lastReadWasNull = true;
    return null;
  }

  public int getRow() {
    return cursor;
  }

  public RowId getRowId(final int columnIndex) throws SQLException {
    try {
      lastReadWasNull = false;
      if (!result.isEntity()) {
        throw new SQLException("The current record is not an entity and does not have a rowid");
      }
      return new YouTrackDbRowId((RecordId) result.asEntityOrNull().getIdentity());
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the rowid for record '" + result + "'", e);
    }
  }

  public RowId getRowId(String columnLabel) throws SQLException {
    return getRowId(0);
  }

  public SQLXML getSQLXML(int columnIndex) {
    lastReadWasNull = true;
    return null;
  }

  public SQLXML getSQLXML(String columnLabel) {
    lastReadWasNull = true;
    return null;
  }

  public short getShort(int columnIndex) throws SQLException {

    return getShort(fieldNames.get(getFieldIndex(columnIndex)));
  }

  @SuppressWarnings("boxing")
  public short getShort(String columnLabel) throws SQLException {
    try {
      final Short r = result.getProperty(columnLabel);
      lastReadWasNull = r == null;
      return r != null ? r : 0;

    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the short value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public String getString(int columnIndex) throws SQLException {

    return getString(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public String getString(String columnLabel) throws SQLException {

    if ("@rid".equals(columnLabel) || "rid".equals(columnLabel)) {
      if (!result.isEntity()) {
        throw new SQLException(
            "The current record is not an entity. Its identity can not be retrieved");
      }
      lastReadWasNull = false;
      return result.asEntityOrNull().getIdentity().toString();
    }

    if ("@class".equals(columnLabel) || "class".equals(columnLabel)) {
      if (!result.isEntity()) {
        throw new SQLException(
            "The current record is not an entity. Its class can not be retrieved");
      }
      lastReadWasNull = false;
      var className = result.asEntity().getSchemaClassName();
      if (className == null) {
        return "NOCLASS";
      }
      return className;
    }

    try {
      var r = Optional.ofNullable(result.getProperty(columnLabel)).map(v -> "" + v).orElse(null);
      lastReadWasNull = r == null;
      return r;
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the string value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public Time getTime(int columnIndex) throws SQLException {
    return getTime(fieldNames.get(getFieldIndex(columnIndex)));
  }

  public Time getTime(String columnLabel) throws SQLException {
    try {
      java.util.Date date = result.getProperty(columnLabel);
      lastReadWasNull = date == null;
      return getTime(date);
    } catch (Exception e) {
      throw new SQLException(
          "An error occurred during the retrieval of the time value at column '"
              + columnLabel
              + "'",
          e);
    }
  }

  public Time getTime(int columnIndex, Calendar cal) throws SQLException {
    var date = getDate(columnIndex, cal);
    lastReadWasNull = date == null;
    return getTime(date);
  }

  private static Time getTime(java.util.Date date) {
    return date != null ? new Time(date.getTime()) : null;
  }

  public Time getTime(String columnLabel, Calendar cal) throws SQLException {
    var date = getDate(columnLabel, cal);
    lastReadWasNull = date == null;
    return getTime(date);
  }

  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    var date = getDate(columnIndex);
    lastReadWasNull = date == null;
    return getTimestamp(date);
  }

  private static Timestamp getTimestamp(Date date) {
    return date != null ? new Timestamp(date.getTime()) : null;
  }

  public Timestamp getTimestamp(String columnLabel) throws SQLException {
    var date = getDate(columnLabel);
    lastReadWasNull = date == null;
    return getTimestamp(date);
  }

  public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
    var date = getDate(columnIndex, cal);
    lastReadWasNull = date == null;
    return getTimestamp(date);
  }

  public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
    var date = getDate(columnLabel, cal);
    lastReadWasNull = date == null;
    return getTimestamp(date);
  }

  public int getType() {
    return type;
  }

  public URL getURL(int columnIndex) {
    lastReadWasNull = true;
    return null;
  }

  public URL getURL(String columnLabel) {
    lastReadWasNull = true;
    return null;
  }

  public InputStream getUnicodeStream(int columnIndex) {
    lastReadWasNull = true;
    return null;
  }

  public InputStream getUnicodeStream(String columnLabel) {
    lastReadWasNull = true;
    return null;
  }

  public SQLWarning getWarnings() {

    return null;
  }

  public void insertRow() {
  }

  public void moveToCurrentRow() {
  }

  public void moveToInsertRow() {
  }

  public void refreshRow() {
  }

  public boolean rowDeleted() {

    return false;
  }

  public boolean rowInserted() {

    return false;
  }

  public boolean rowUpdated() {

    return false;
  }

  public void updateArray(int columnIndex, Array x) {
  }

  public void updateArray(String columnLabel, Array x) {
  }

  public void updateAsciiStream(int columnIndex, InputStream x) {
  }

  public void updateAsciiStream(String columnLabel, InputStream x) {
  }

  public void updateAsciiStream(int columnIndex, InputStream x, int length) {
  }

  public void updateAsciiStream(String columnLabel, InputStream x, int length) {
  }

  public void updateAsciiStream(int columnIndex, InputStream x, long length) {
  }

  public void updateAsciiStream(String columnLabel, InputStream x, long length) {
  }

  public void updateBigDecimal(int columnIndex, BigDecimal x) {
  }

  public void updateBigDecimal(String columnLabel, BigDecimal x) {
  }

  public void updateBinaryStream(int columnIndex, InputStream x) {
  }

  public void updateBinaryStream(String columnLabel, InputStream x) {
  }

  public void updateBinaryStream(int columnIndex, InputStream x, int length) {
  }

  public void updateBinaryStream(String columnLabel, InputStream x, int length) {
  }

  public void updateBinaryStream(int columnIndex, InputStream x, long length) {
  }

  public void updateBinaryStream(String columnLabel, InputStream x, long length) {
  }

  public void updateBlob(int columnIndex, java.sql.Blob x) {
  }

  public void updateBlob(String columnLabel, java.sql.Blob x) {
  }

  public void updateBlob(int columnIndex, InputStream inputStream) {
  }

  public void updateBlob(String columnLabel, InputStream inputStream) {
  }

  public void updateBlob(int columnIndex, InputStream inputStream, long length) {
  }

  public void updateBlob(String columnLabel, InputStream inputStream, long length) {
  }

  public void updateBoolean(int columnIndex, boolean x) {
  }

  public void updateBoolean(String columnLabel, boolean x) {
  }

  public void updateByte(int columnIndex, byte x) {
  }

  public void updateByte(String columnLabel, byte x) {
  }

  public void updateBytes(int columnIndex, byte[] x) {
  }

  public void updateBytes(String columnLabel, byte[] x) {
  }

  public void updateCharacterStream(int columnIndex, Reader x) {
  }

  public void updateCharacterStream(String columnLabel, Reader reader) {
  }

  public void updateCharacterStream(int columnIndex, Reader x, int length) {
  }

  public void updateCharacterStream(String columnLabel, Reader reader, int length) {
  }

  public void updateCharacterStream(int columnIndex, Reader x, long length) {
  }

  public void updateCharacterStream(String columnLabel, Reader reader, long length) {
  }

  public void updateClob(int columnIndex, Clob x) {
  }

  public void updateClob(String columnLabel, Clob x) {
  }

  public void updateClob(int columnIndex, Reader reader) {
  }

  public void updateClob(String columnLabel, Reader reader) {
  }

  public void updateClob(int columnIndex, Reader reader, long length) {
  }

  public void updateClob(String columnLabel, Reader reader, long length) {
  }

  public void updateDate(int columnIndex, Date x) {
  }

  public void updateDate(String columnLabel, Date x) {
  }

  public void updateDouble(int columnIndex, double x) {
  }

  public void updateDouble(String columnLabel, double x) {
  }

  public void updateFloat(int columnIndex, float x) {
  }

  public void updateFloat(String columnLabel, float x) {
  }

  public void updateInt(int columnIndex, int x) {
  }

  public void updateInt(String columnLabel, int x) {
  }

  public void updateLong(int columnIndex, long x) {
  }

  public void updateLong(String columnLabel, long x) {
  }

  public void updateNCharacterStream(int columnIndex, Reader x) {
  }

  public void updateNCharacterStream(String columnLabel, Reader reader) {
  }

  public void updateNCharacterStream(int columnIndex, Reader x, long length) {
  }

  public void updateNCharacterStream(String columnLabel, Reader reader, long length) {
  }

  public void updateNClob(int columnIndex, NClob nClob) {
  }

  public void updateNClob(String columnLabel, NClob nClob) {
  }

  public void updateNClob(int columnIndex, Reader reader) {
  }

  public void updateNClob(String columnLabel, Reader reader) {
  }

  public void updateNClob(int columnIndex, Reader reader, long length) {
  }

  public void updateNClob(String columnLabel, Reader reader, long length) {
  }

  public void updateNString(int columnIndex, String nString) {
  }

  public void updateNString(String columnLabel, String nString) {
  }

  public void updateNull(int columnIndex) {
  }

  public void updateNull(String columnLabel) {
  }

  public void updateObject(int columnIndex, Object x) {
  }

  public void updateObject(String columnLabel, Object x) {
  }

  public void updateObject(int columnIndex, Object x, int scaleOrLength) {
  }

  public void updateObject(String columnLabel, Object x, int scaleOrLength) {
  }

  public void updateRef(int columnIndex, Ref x) {
  }

  public void updateRef(String columnLabel, Ref x) {
  }

  public void updateRow() {
  }

  public void updateRowId(int columnIndex, RowId x) {
  }

  public void updateRowId(String columnLabel, RowId x) {
  }

  public void updateSQLXML(int columnIndex, SQLXML xmlObject) {
  }

  public void updateSQLXML(String columnLabel, SQLXML xmlObject) {
  }

  public void updateShort(int columnIndex, short x) {
  }

  public void updateShort(String columnLabel, short x) {
  }

  public void updateString(int columnIndex, String x) {
  }

  public void updateString(String columnLabel, String x) {
  }

  public void updateTime(int columnIndex, Time x) {
  }

  public void updateTime(String columnLabel, Time x) {
  }

  public void updateTimestamp(int columnIndex, Timestamp x) {
  }

  public void updateTimestamp(String columnLabel, Timestamp x) {
  }

  public boolean wasNull() {
    return lastReadWasNull;
  }

  public boolean isWrapperFor(Class<?> iface) {
    return EntityImpl.class.isAssignableFrom(iface);
  }

  public <T> T unwrap(Class<T> iface) throws SQLException {
    try {
      return iface.cast(result);
    } catch (ClassCastException e) {
      throw new SQLException(e);
    }
  }

  public void cancelRowUpdates() {
  }

  public void clearWarnings() {
  }

  public <T> T getObject(int arg0, Class<T> arg1) {
    return null;
  }

  public <T> T getObject(String arg0, Class<T> arg1) {
    return null;
  }
}
