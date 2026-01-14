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
package com.jetbrains.youtrackdb.internal.core.serialization.serializer;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

@SuppressWarnings("unchecked")
public class JSONWriter {

  private static final String DEF_FORMAT =
      "rid,type,version,class,attribSameRow,indent:2,dateAsLong"; // TODO: added
  private final String format;
  private final Writer out;
  private boolean prettyPrint = false;
  private boolean firstAttribute = true;

  public JSONWriter(final Writer out) {
    this(out, DEF_FORMAT);
  }

  public JSONWriter(final Writer out, final String iJsonFormat) {
    this.out = out;
    this.format = iJsonFormat;
    if (iJsonFormat.contains("prettyPrint")) {
      prettyPrint = true;
    }
  }

  public static String writeValue(DatabaseSessionInternal db, final Object iValue)
      throws IOException {
    return writeValue(db, iValue, DEF_FORMAT);
  }

  public static String writeValue(DatabaseSessionInternal db, Object iValue, final String iFormat)
      throws IOException {
    return writeValue(db, iValue, iFormat, 0, null);
  }

  public static String writeValue(
      DatabaseSessionInternal db, Object iValue, final String iFormat, final int iIndentLevel,
      PropertyTypeInternal valueType)
      throws IOException {
    if (iValue == null) {
      return "null";
    }

    final var buffer = new StringBuilder(64);
    if (iValue instanceof Boolean || iValue instanceof Number) {

      if (iValue instanceof Double && !Double.isFinite((Double) iValue)) {
        buffer.append("null");
      } else if ((iValue instanceof Float && !Float.isFinite((Float) iValue))) {
        buffer.append("null");
      } else {
        buffer.append(iValue);
      }

    } else if (iValue instanceof Identifiable linked) {
      if (((RecordIdInternal) linked.getIdentity()).isValidPosition()) {
        buffer.append('\"');
        ((RecordIdInternal) linked.getIdentity()).toString(buffer);
        buffer.append('\"');
      } else {
        if (iFormat != null && iFormat.contains("shallow")) {
          buffer.append("{}");
        } else {
          try {
            var transaction = db.getActiveTransaction();
            var rec = transaction.load(linked);
            final var embeddedFormat =
                iFormat != null && iFormat.isEmpty()
                    ? "indent:" + iIndentLevel
                    : iFormat + ",indent:" + iIndentLevel;
            buffer.append(rec.toJSON(embeddedFormat));
          } catch (RecordNotFoundException e) {
            buffer.append("null");
          }
        }
      }

    } else if (iValue.getClass().isArray()) {

      if (iValue instanceof byte[] source) {
        buffer.append('\"');

        if (iFormat != null && iFormat.contains("shallow")) {
          buffer.append(source.length);
        } else {
          buffer.append(Base64.getEncoder().encodeToString(source));
        }

        buffer.append('\"');
      } else {
        buffer.append('[');
        var size = Array.getLength(iValue);
        if (iFormat != null && iFormat.contains("shallow")) {
          buffer.append(size);
        } else {
          for (var i = 0; i < size; ++i) {
            if (i > 0) {
              buffer.append(",");
            }
            buffer.append(writeValue(db, Array.get(iValue, i), iFormat));
          }
        }
        buffer.append(']');
      }
    } else if (iValue instanceof Iterator<?>) {
      iteratorToJSON(db, (Iterator<?>) iValue, iFormat, buffer);
    } else if (iValue instanceof Iterable<?>) {
      iteratorToJSON(db, ((Iterable<?>) iValue).iterator(), iFormat, buffer);
    } else if (iValue instanceof Map<?, ?>) {
      mapToJSON(db, (Map<Object, Object>) iValue, iFormat, buffer);
    } else if (iValue instanceof Map.Entry<?, ?> entry) {
      buffer.append('{');
      buffer.append(writeValue(db, entry.getKey(), iFormat));
      buffer.append(":");
      if (iFormat.contains("prettyPrint")) {
        buffer.append(' ');
      }
      buffer.append(writeValue(db, entry.getValue(), iFormat));
      buffer.append('}');
    } else if (iValue instanceof Date) {
      if (iFormat.indexOf("dateAsLong") > -1) {
        buffer.append(((Date) iValue).getTime());
      } else {
        buffer.append('"');
        buffer.append(DateHelper.getDateTimeFormatInstance(db).format(iValue));
        buffer.append('"');
      }
    } else if (iValue instanceof BigDecimal) {
      buffer.append(((BigDecimal) iValue).toPlainString());
    } else if (iValue instanceof Iterable<?>) {
      iteratorToJSON(db, ((Iterable<?>) iValue).iterator(), iFormat, buffer);
    } else {
      // TREAT IT AS STRING
      final var v = iValue.toString();
      buffer.append('"');
      buffer.append(encode(v));
      buffer.append('"');

    }

    return buffer.toString();
  }

  protected static void iteratorToJSON(
      DatabaseSessionInternal db, final Iterator<?> it, final String iFormat,
      final StringBuilder buffer) throws IOException {
    buffer.append('[');
    if (iFormat != null && iFormat.contains("shallow")) {
      if (it instanceof MultiCollectionIterator<?>) {
        buffer.append(((MultiCollectionIterator<?>) it).size());
      } else {
        // COUNT THE MULTI VALUE
        int i;
        for (i = 0; it.hasNext(); ++i) {
          it.next();
        }
        buffer.append(i);
      }
    } else {
      for (var i = 0; it.hasNext(); ++i) {
        if (i > 0) {
          buffer.append(",");
        }
        buffer.append(writeValue(db, it.next(), iFormat));
      }
    }
    buffer.append(']');
  }

  public static Object encode(final Object iValue) {
    if (iValue instanceof String) {
      return IOUtils.encodeJsonString((String) iValue);
    } else {
      return iValue;
    }
  }

  public static String mapToJSON(
      DatabaseSessionInternal db, final Map<?, ?> iMap, final String iFormat,
      final StringBuilder buffer) {
    try {
      buffer.append('{');
      if (iMap != null) {
        var i = 0;
        Entry<?, ?> entry;
        for (Iterator<?> it = iMap.entrySet().iterator(); it.hasNext(); ++i) {
          entry = (Entry<?, ?>) it.next();
          if (i > 0) {
            buffer.append(",");
          }
          buffer.append(writeValue(db, entry.getKey(), iFormat));
          buffer.append(":");
          buffer.append(writeValue(db, entry.getValue(), iFormat));
        }
      }
      buffer.append('}');
      return buffer.toString();
    } catch (IOException e) {
      throw BaseException.wrapException(
          new SerializationException(db.getDatabaseName(), "Error on serializing map"), e,
          db.getDatabaseName());
    }
  }

  public JSONWriter beginObject() throws IOException {
    beginObject(0, false, null);
    return this;
  }

  public JSONWriter beginObject(final int iIdentLevel) throws IOException {
    beginObject(iIdentLevel, false, null);
    return this;
  }

  public JSONWriter beginObject(final Object iName) throws IOException {
    beginObject(-1, false, iName);
    return this;
  }

  public JSONWriter beginObject(final int iIdentLevel, final boolean iNewLine, final Object iName)
      throws IOException {
    if (!firstAttribute) {
      out.append(",");
    }

    format(iIdentLevel, iNewLine);

    if (iName != null) {
      out.append("\"" + iName + "\":");
      if (prettyPrint) {
        out.append(' ');
      }
    }

    out.append('{');

    firstAttribute = true;
    return this;
  }

  public JSONWriter writeRecord(
      final int iIdentLevel, final boolean iNewLine, final Object iName, final DBRecord iRecord)
      throws IOException {
    if (!firstAttribute) {
      out.append(",");
    }

    format(iIdentLevel, iNewLine);

    if (iName != null) {
      out.append("\"" + iName + "\":");
      if (prettyPrint) {
        out.append(' ');
      }
    }

    out.append(iRecord.toJSON(format));

    firstAttribute = false;
    return this;
  }

  public JSONWriter endObject() throws IOException {
    format(-1, true);
    out.append('}');
    return this;
  }

  public JSONWriter endObject(final int iIdentLevel) throws IOException {
    return endObject(iIdentLevel, true);
  }

  public JSONWriter endObject(final int iIdentLevel, final boolean iNewLine) throws IOException {
    format(iIdentLevel, iNewLine);
    out.append('}');
    firstAttribute = false;
    return this;
  }

  public JSONWriter beginCollection(DatabaseSessionInternal db, final String iName)
      throws IOException {
    return beginCollection(db, -1, false, iName);
  }

  public JSONWriter beginCollection(
      DatabaseSessionInternal db, final int iIdentLevel, final boolean iNewLine, final String iName)
      throws IOException {
    if (!firstAttribute) {
      out.append(",");
    }

    format(iIdentLevel, iNewLine);

    if (iName != null && !iName.isEmpty()) {
      out.append(writeValue(db, iName, format));
      out.append(":");
      if (prettyPrint) {
        out.append(' ');
      }
    }
    out.append("[");

    firstAttribute = true;
    return this;
  }

  public JSONWriter endCollection() throws IOException {
    return endCollection(-1, false);
  }

  public JSONWriter endCollection(final int iIdentLevel, final boolean iNewLine)
      throws IOException {
    format(iIdentLevel, iNewLine);
    firstAttribute = false;
    out.append(']');
    return this;
  }

  public JSONWriter writeObjects(DatabaseSessionInternal db, final String iName, Object[]... iPairs)
      throws IOException {
    return writeObjects(db, -1, false, iName, iPairs);
  }

  public JSONWriter writeObjects(
      DatabaseSessionInternal db, int iIdentLevel, boolean iNewLine, final String iName,
      Object[]... iPairs)
      throws IOException {
    for (var iPair : iPairs) {
      beginObject(iIdentLevel, true, iName);
      for (var k = 0; k < iPair.length; ) {
        writeAttribute(db, iIdentLevel + 1, false, (String) iPair[k++], iPair[k++], format);
      }
      endObject(iIdentLevel, false);
    }
    return this;
  }

  public JSONWriter writeAttribute(DatabaseSessionInternal db, final String iName,
      final Object iValue) throws IOException {
    return writeAttribute(db, -1, false, iName, iValue, format);
  }

  public JSONWriter writeAttribute(
      DatabaseSessionInternal db, final int iIdentLevel, final boolean iNewLine, final String iName,
      final Object iValue)
      throws IOException {
    return writeAttribute(db, iIdentLevel, iNewLine, iName, iValue, format, null);
  }

  public JSONWriter writeAttribute(
      DatabaseSessionInternal db, final int iIdentLevel,
      final boolean iNewLine,
      final String iName,
      final Object iValue,
      final String iFormat)
      throws IOException {
    return writeAttribute(db, iIdentLevel, iNewLine, iName, iValue, iFormat, null);
  }

  public JSONWriter writeAttribute(
      DatabaseSessionInternal db, final int iIdentLevel,
      final boolean iNewLine,
      final String iName,
      final Object iValue,
      final String iFormat,
      PropertyTypeInternal valueType)
      throws IOException {
    if (!firstAttribute) {
      out.append(",");
    }

    format(iIdentLevel, iNewLine);

    if (iName != null) {
      out.append(writeValue(db, iName, iFormat));
      out.append(":");
      if (prettyPrint) {
        out.append(' ');
      }
    }

    if (iFormat != null
        && iFormat.contains("graph")
        && iName != null
        && (iName.startsWith("in_") || iName.startsWith("out_"))
        && (iValue == null || iValue instanceof Identifiable)) {
      // FORCE THE OUTPUT AS COLLECTION
      out.append('[');
      if (iValue instanceof Identifiable) {
        final var shallow = iFormat != null && iFormat.contains("shallow");
        if (shallow) {
          out.append("1");
        } else {
          out.append(writeValue(db, iValue, iFormat));
        }
      }
      out.append(']');
    } else {
      out.append(writeValue(db, iValue, iFormat, iIdentLevel, valueType));
    }

    firstAttribute = false;
    return this;
  }

  public void writeValue(DatabaseSessionInternal db, final int iIdentLevel,
      final boolean iNewLine, final Object iValue)
      throws IOException {
    if (!firstAttribute) {
      out.append(",");
    }

    format(iIdentLevel, iNewLine);

    out.append(writeValue(db, iValue, format));

    firstAttribute = false;
  }

  public JSONWriter flush() throws IOException {
    out.flush();
    return this;
  }

  public JSONWriter close() throws IOException {
    out.close();
    return this;
  }

  public JSONWriter append(final String iText) throws IOException {
    out.append(iText);
    return this;
  }

  public boolean isPrettyPrint() {
    return prettyPrint;
  }

  public JSONWriter setPrettyPrint(boolean prettyPrint) {
    this.prettyPrint = prettyPrint;
    return this;
  }

  public void write(final String iText) throws IOException {
    out.append(iText);
  }

  public void newline() throws IOException {
    if (prettyPrint) {
      out.append("\r\n");
    }
  }

  public void resetAttributes() {
    firstAttribute = true;
  }

  private JSONWriter format(final int iIdentLevel, final boolean iNewLine) throws IOException {
    if (iIdentLevel > -1) {
      if (iNewLine) {
        newline();
      }

      if (prettyPrint) {
        for (var i = 0; i < iIdentLevel; ++i) {
          out.append("  ");
        }
      }
    }
    return this;
  }
}
