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
package com.jetbrains.youtrack.db.internal.tools.console;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.ImmutableRecordId;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class TableFormatter {

  public enum ALIGNMENT {
    LEFT,
    CENTER,
    RIGHT
  }

  protected static final String MORE = "...";
  protected static final SimpleDateFormat DEF_DATEFORMAT =
      new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

  protected Pair<String, Boolean> columnSorting = null;
  protected final Map<String, ALIGNMENT> columnAlignment = new HashMap<>();
  protected final Map<String, Map<String, String>> columnMetadata =
      new HashMap<>();
  protected final Set<String> columnHidden = new HashSet<>();
  protected final Set<String> prefixedColumns =
      new LinkedHashSet<>(Arrays.asList("#", "@RID", "@CLASS"));
  protected final OTableOutput out;
  protected int maxMultiValueEntries = 10;
  protected int minColumnSize = 4;
  protected int maxWidthSize = 150;
  protected String nullValue = "";

  private Map<String, String> footer;

  public interface OTableOutput {

    void onMessage(String text);
  }

  public TableFormatter(final OTableOutput iConsole) {
    this.out = iConsole;
  }

  public void writeRecords(List<RawPair<RID, Object>> resultSet,
      final int limit, DatabaseSessionInternal session) {
    writeRecords(resultSet, limit, null, session);
  }

  public void writeRecords(
      List<RawPair<RID, Object>> resultSet,
      final int limit,
      final CallableFunction<Object, Object> iAfterDump, DatabaseSessionInternal session) {
    final var columns = parseColumns(resultSet, limit, session);

    if (columnSorting != null) {
      resultSet.sort(
          (o1, o2) -> {
            @SuppressWarnings("unchecked") var rec1 = (Map<String, Object>) o1.second;
            @SuppressWarnings("unchecked") var rec2 = (Map<String, Object>) o2.second;

            final var value1 = rec1.get(columnSorting.getKey());
            final var value2 = rec2.get(columnSorting.getKey());
            final boolean ascending = columnSorting.getValue();

            final int result;
            if (value2 == null) {
              result = 1;
            } else if (value1 == null) {
              result = 0;
            } else //noinspection rawtypes
              if (value1 instanceof Comparable comparable) {
                //noinspection unchecked
                result = comparable.compareTo(value2);
              } else {
                result = value1.toString().compareTo(value2.toString());
              }

            return ascending ? result : result * -1;
          });
    }

    var fetched = 0;
    for (var record : resultSet) {
      dumpRecordInTable(fetched++, record.first, record.second, columns, session);
      if (iAfterDump != null) {
        iAfterDump.call(record.second);
      }

      if (limit > -1 && fetched >= limit) {
        printHeaderLine(columns);
        out.onMessage(
            "\nLIMIT EXCEEDED: resultset contains more items not displayed (limit=" + limit + ")");
        return;
      }
    }

    if (fetched > 0) {
      printHeaderLine(columns);
    }

    if (footer != null) {
      dumpRecordInTable(-1, null, footer, columns, session);
      printHeaderLine(columns);
    }
  }

  public void setColumnAlignment(final String column, final ALIGNMENT alignment) {
    columnAlignment.put(column, alignment);
  }

  public TableFormatter setMaxWidthSize(final int maxWidthSize) {
    this.maxWidthSize = maxWidthSize;
    return this;
  }

  public TableFormatter setMaxMultiValueEntries(final int maxMultiValueEntries) {
    this.maxMultiValueEntries = maxMultiValueEntries;
    return this;
  }

  public void dumpRecordInTable(
      final int iIndex, RID rid, Object iRecord,
      final Map<String, Integer> iColumns, DatabaseSessionInternal session) {
    if (iIndex == 0) {
      printHeader(iColumns);
    }

    // FORMAT THE LINE DYNAMICALLY
    List<String> vargs = new ArrayList<>();
    try {
      final var format = new StringBuilder(maxWidthSize);

      format.append('|');

      var i = 0;
      for (var col : iColumns.entrySet()) {
        final var columnName = col.getKey();
        final int columnWidth = col.getValue();

        if (i++ > 0) {
          format.append('|');
        }

        format.append("%-").append(columnWidth).append("s");

        var value = getFieldValue(iIndex, rid, iRecord, columnName, session);
        String valueAsString = null;

        if (value != null) {
          valueAsString = value.toString();
          if (valueAsString.length() > columnWidth) {
            // APPEND ...
            valueAsString = valueAsString.substring(0, columnWidth - 3) + MORE;
          }
        }

        valueAsString = formatCell(columnName, columnWidth, valueAsString);

        vargs.add(valueAsString);
      }

      format.append('|');

      out.onMessage(String.format("\n" + format, vargs.toArray()));

    } catch (Exception t) {
      out.onMessage(
          String.format(
              "%3d|%9s|%s\n",
              iIndex, iRecord, "Error on loading record due to: " + t));
    }
  }

  protected String formatCell(
      final String columnName, final int columnWidth, String valueAsString) {
    if (valueAsString == null) {
      valueAsString = nullValue;
    }

    final var alignment = columnAlignment.get(columnName);
    if (alignment != null) {
      switch (alignment) {
        case LEFT:
          break;
        case CENTER: {
          final var room = columnWidth - valueAsString.length();
          if (room > 1) {
            var valueAsStringBuilder = new StringBuilder(valueAsString);
            for (var k = 0; k < room / 2; ++k) {
              valueAsStringBuilder.insert(0, " ");
            }
            valueAsString = valueAsStringBuilder.toString();
          }
          break;
        }
        case RIGHT: {
          final var room = columnWidth - valueAsString.length();
          if (room > 0) {
            var valueAsStringBuilder = new StringBuilder(valueAsString);
            for (var k = 0; k < room; ++k) {
              valueAsStringBuilder.insert(0, " ");
            }
            valueAsString = valueAsStringBuilder.toString();
          }
          break;
        }
      }
    }
    return valueAsString;
  }

  private Object getFieldValue(
      final int iIndex, RID rid, Object record,
      final String iColumnName, DatabaseSessionInternal session) {

    Object value;

    if (iColumnName.equals("#"))
    // RECORD NUMBER
    {
      value = iIndex > -1 ? iIndex : "";
    } else if (iColumnName.equals("@RID"))
    // RID
    {
      value = rid;
    } else if (record instanceof Map<?, ?> map) {
      value = map.get(iColumnName);
    } else if (record instanceof byte[] blob) {
      value = "<binary> (size=" + blob.length + " bytes)";
    } else {
      value = record;
    }

    return getPrettyFieldValue(value, maxMultiValueEntries, session);
  }

  public static String getPrettyFieldMultiValue(
      final Iterator<?> iterator, final int maxMultiValueEntries, DatabaseSessionInternal session) {
    final var value = new StringBuilder("[");
    for (var i = 0; iterator.hasNext(); i++) {
      if (i >= maxMultiValueEntries) {
        if (iterator instanceof Sizeable sizeable && sizeable.isSizeable()) {
          value.append("(size=");
          value.append(sizeable.size());
          value.append(")");
        } else {
          value.append("(more)");
        }

        break;
      }

      if (i > 0) {
        value.append(',');
      }

      value.append(getPrettyFieldValue(iterator.next(), maxMultiValueEntries, session));
    }

    value.append("]");

    return value.toString();
  }

  public void setFooter(final Map<String, String> footer) {
    this.footer = footer;
  }

  public static Object getPrettyFieldValue(Object value, final int multiValueMaxEntries,
      DatabaseSessionInternal session) {
    if (value instanceof MultiCollectionIterator<?>) {
      value =
          getPrettyFieldMultiValue(
              ((MultiCollectionIterator<?>) value).iterator(), multiValueMaxEntries, session);
    } else if (value instanceof RidBag) {
      value = getPrettyFieldMultiValue(((RidBag) value).iterator(), multiValueMaxEntries, session);
    } else if (value instanceof Iterator) {
      value = getPrettyFieldMultiValue((Iterator<?>) value, multiValueMaxEntries, session);
    } else if (value instanceof Collection<?>) {
      value = getPrettyFieldMultiValue(((Collection<?>) value).iterator(), multiValueMaxEntries,
          session);
    } else if (value instanceof Identifiable identifiable) {
      if (identifiable.equals(ImmutableRecordId.EMPTY_RECORD_ID)) {
        value = value.toString();
      } else {
        value = identifiable.getIdentity().toString();
      }
    } else if (value instanceof Date) {
      value = DateHelper.getDateTimeFormatInstance(session).format((Date) value);
    } else if (value instanceof byte[]) {
      value = "byte[" + ((byte[]) value).length + "]";
    }

    return value;
  }

  private void printHeader(final Map<String, Integer> iColumns) {
    final var columnRow = new StringBuilder("\n");
    final Map<String, StringBuilder> metadataRows = new HashMap<>();

    // INIT METADATA
    final var allMetadataNames = new LinkedHashSet<String>();

    for (var entry : columnMetadata.entrySet()) {
      for (var entry2 : entry.getValue().entrySet()) {
        allMetadataNames.add(entry2.getKey());

        var metadataRow = metadataRows.get(entry2.getKey());
        if (metadataRow == null) {
          metadataRow = new StringBuilder("\n");
          metadataRows.put(entry2.getKey(), metadataRow);
        }
      }
    }

    printHeaderLine(iColumns);
    var i = 0;

    columnRow.append('|');
    if (!metadataRows.isEmpty()) {
      for (var buffer : metadataRows.values()) {
        buffer.append('|');
      }
    }

    for (var column : iColumns.entrySet()) {
      var colName = column.getKey();

      if (columnHidden.contains(colName)) {
        continue;
      }

      if (i > 0) {
        columnRow.append('|');
        if (!metadataRows.isEmpty()) {
          for (var buffer : metadataRows.values()) {
            buffer.append('|');
          }
        }
      }

      if (colName.length() > column.getValue()) {
        colName = colName.substring(0, column.getValue());
      }
      columnRow.append(
          String.format(
              "%-" + column.getValue() + "s", formatCell(colName, column.getValue(), colName)));

      if (!metadataRows.isEmpty()) {
        // METADATA VALUE
        for (var metadataName : allMetadataNames) {
          final var buffer = metadataRows.get(metadataName);
          final var metadataColumn = columnMetadata.get(column.getKey());
          var metadataValue = metadataColumn != null ? metadataColumn.get(metadataName) : null;
          if (metadataValue == null) {
            metadataValue = "";
          }

          if (metadataValue.length() > column.getValue()) {
            metadataValue = metadataValue.substring(0, column.getValue());
          }
          buffer.append(
              String.format(
                  "%-" + column.getValue() + "s",
                  formatCell(colName, column.getValue(), metadataValue)));
        }
      }

      ++i;
    }

    columnRow.append('|');
    if (!metadataRows.isEmpty()) {
      for (var buffer : metadataRows.values()) {
        buffer.append('|');
      }
    }

    if (!metadataRows.isEmpty()) {
      // PRINT METADATA IF ANY
      for (var buffer : metadataRows.values()) {
        out.onMessage(buffer.toString());
      }
      printHeaderLine(iColumns);
    }

    out.onMessage(columnRow.toString());

    printHeaderLine(iColumns);
  }

  private void printHeaderLine(final Map<String, Integer> iColumns) {
    final var buffer = new StringBuilder("\n");

    if (!iColumns.isEmpty()) {
      buffer.append('+');

      var i = 0;
      for (var column : iColumns.entrySet()) {
        final var colName = column.getKey();
        if (columnHidden.contains(colName)) {
          continue;
        }

        if (i++ > 0) {
          buffer.append("+");
        }

        buffer.append("-".repeat(Math.max(0, column.getValue())));
      }

      buffer.append('+');
    }

    out.onMessage(buffer.toString());
  }

  /**
   * Fill the column map computing the maximum size for a field.
   */
  private Map<String, Integer> parseColumns(
      List<RawPair<RID, Object>> resultSet,
      final int limit, DatabaseSessionInternal session) {
    final Map<String, Integer> columns = new LinkedHashMap<>();

    for (var c : prefixedColumns) {
      columns.put(c, minColumnSize);
    }

    var tempRids = false;
    var hasClass = false;

    var fetched = 0;
    for (var entry : resultSet) {

      var rid = entry.first;
      var record = entry.second;

      for (var c : prefixedColumns) {
        columns.put(c,
            getColumnSize(fetched, rid, record, c, columns.get(c), session));
      }

      if (record instanceof Map) {
        // PARSE ALL THE DOCUMENT'S FIELDS
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) record;
        for (var mapEntry : map.entrySet()) {
          var key = mapEntry.getKey();
          columns.put(key,
              getColumnSize(fetched, rid, mapEntry.getValue(), key, columns.get(key), session));
        }

        if (!hasClass && map.containsKey("@class")) {
          hasClass = true;
        }

      } else if (record instanceof byte[]) {
        // UNIQUE BINARY FIELD
        columns.put("value", maxWidthSize - 15);
      } else {
        throw new IllegalStateException("Unexpected value: " + record);
      }

      if (!tempRids && (rid == null || !rid.isPersistent())) {
        tempRids = true;
      }

      if (limit > -1 && fetched++ >= limit) {
        break;
      }
    }

    if (tempRids) {
      columns.remove("@RID");
    }

    if (!hasClass) {
      columns.remove("@CLASS");
    }

    if (footer != null) {
      // PARSE ALL THE DOCUMENT'S FIELDS
      for (var fieldName : footer.keySet()) {
        columns.put(fieldName,
            getColumnSize(fetched, null, footer, fieldName,
                columns.get(fieldName), session));
      }
    }

    // COMPUTE MAXIMUM WIDTH
    var width = 0;
    for (var col : columns.entrySet()) {
      width += col.getValue();
    }

    if (width > maxWidthSize) {
      // SCALE COLUMNS AUTOMATICALLY
      final List<Entry<String, Integer>> orderedColumns =
          new ArrayList<>(columns.entrySet());
      orderedColumns.sort(Entry.comparingByValue());

      // START CUTTING THE BIGGEST ONES
      Collections.reverse(orderedColumns);
      while (width > maxWidthSize) {
        var oldWidth = width;

        for (var entry : orderedColumns) {
          final var redux = entry.getValue() * 10 / 100;

          if (entry.getValue() - redux < minColumnSize)
          // RESTART FROM THE LARGEST COLUMN
          {
            break;
          }

          entry.setValue(entry.getValue() - redux);

          width -= redux;
          if (width <= maxWidthSize) {
            break;
          }
        }

        if (width == oldWidth)
        // REACHED THE MINIMUM
        {
          break;
        }
      }

      // POPULATE THE COLUMNS WITH THE REDUXED VALUES
      columns.clear();
      for (var c : prefixedColumns) {
        columns.put(c, minColumnSize);
      }
      Collections.reverse(orderedColumns);
      for (var col : orderedColumns)
      // if (!col.getKey().equals("#") && !col.getKey().equals("@RID"))
      {
        columns.put(col.getKey(), col.getValue());
      }
    }

    if (tempRids) {
      columns.remove("@RID");
    }
    if (!hasClass) {
      columns.remove("@CLASS");
    }

    for (var c : columnHidden) {
      columns.remove(c);
    }

    return columns;
  }

  private Integer getColumnSize(
      final Integer iIndex, RID rid, final Object iRecord,
      final String fieldName,
      final Integer origSize, DatabaseSessionInternal session) {
    int newColumnSize;
    if (origSize == null)
    // START FROM THE FIELD NAME SIZE
    {
      newColumnSize = fieldName.length();
    } else {
      newColumnSize = Math.max(origSize, fieldName.length());
    }

    final var metadata = columnMetadata.get(fieldName);
    if (metadata != null) {
      // UPDATE WIDTH WITH METADATA VALUES
      for (var v : metadata.values()) {
        if (v != null) {
          if (v.length() > newColumnSize) {
            newColumnSize = v.length();
          }
        }
      }
    }

    final var fieldValue = getFieldValue(iIndex, rid, iRecord, fieldName, session);

    if (fieldValue != null) {
      final var fieldValueAsString = fieldValue.toString();
      if (fieldValueAsString.length() > newColumnSize) {
        newColumnSize = fieldValueAsString.length();
      }
    }

    if (newColumnSize < minColumnSize)
    // SET THE MINIMUM SIZE
    {
      newColumnSize = minColumnSize;
    }

    return newColumnSize;
  }
}
