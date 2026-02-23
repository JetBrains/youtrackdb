/*
 * Copyright 2018 YouTrackDB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.db.record.TrackedCollection;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.GlobalProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Collection;
import java.util.Map;
import java.util.TimeZone;
import javax.annotation.Nullable;

/**
 * Utility constants and helper classes for binary record serialization.
 */
public class HelperClasses {

  public static final String CHARSET_UTF_8 = "UTF-8";
  protected static final RecordIdInternal NULL_RECORD_ID = new RecordId(-2,
      RID.COLLECTION_POS_INVALID);
  public static final long MILLISEC_PER_DAY = 86400000;

  public static class Tuple<T1, T2> {

    private final T1 firstVal;
    private final T2 secondVal;

    Tuple(T1 firstVal, T2 secondVal) {
      this.firstVal = firstVal;
      this.secondVal = secondVal;
    }

    public T1 getFirstVal() {
      return firstVal;
    }

    public T2 getSecondVal() {
      return secondVal;
    }
  }

  protected static class RecordInfo {

    public int fieldStartOffset;
    public int fieldLength;
    public PropertyTypeInternal fieldType;
  }

  protected static class MapRecordInfo extends RecordInfo {

    public String key;
    public PropertyTypeInternal keyType;
  }

  @Nullable
  public static PropertyTypeInternal readOType(final BytesContainer bytes, boolean justRunThrough) {
    if (justRunThrough) {
      bytes.offset++;
      return null;
    }

    var typeId = readByte(bytes);
    if (typeId == -1) {
      return null;
    }

    return PropertyTypeInternal.getById(typeId);
  }

  public static void writeOType(BytesContainer bytes, int pos, PropertyTypeInternal type) {
    if (type != null) {
      bytes.bytes[pos] = (byte) type.getId();
    } else {
      bytes.bytes[pos] = -1;
    }
  }

  @Nullable
  public static PropertyTypeInternal readType(BytesContainer bytes) {
    var typeId = bytes.bytes[bytes.offset++];
    if (typeId == -1) {
      return null;
    }
    return PropertyTypeInternal.getById(typeId);
  }

  public static byte[] readBinary(final BytesContainer bytes) {
    final var n = VarIntSerializer.readAsInteger(bytes);
    final var newValue = new byte[n];
    System.arraycopy(bytes.bytes, bytes.offset, newValue, 0, newValue.length);
    bytes.skip(n);
    return newValue;
  }

  public static String readString(final BytesContainer bytes) {
    final var len = VarIntSerializer.readAsInteger(bytes);
    if (len == 0) {
      return "";
    }
    final var res = stringFromBytes(bytes.bytes, bytes.offset, len);
    bytes.skip(len);
    return res;
  }

  public static int readInteger(final BytesContainer container) {
    final var value =
        IntegerSerializer.deserializeLiteral(container.bytes, container.offset);
    container.offset += IntegerSerializer.INT_SIZE;
    return value;
  }

  public static byte readByte(final BytesContainer container) {
    return container.bytes[container.offset++];
  }

  public static long readLong(final BytesContainer container) {
    final var value =
        LongSerializer.deserializeLiteral(container.bytes, container.offset);
    container.offset += LongSerializer.LONG_SIZE;
    return value;
  }

  @Nullable
  public static RecordIdInternal readOptimizedLink(final BytesContainer bytes,
      boolean justRunThrough) {
    var collectionId = VarIntSerializer.readAsInteger(bytes);
    var collectionPos = VarIntSerializer.readAsLong(bytes);
    if (justRunThrough) {
      return null;
    } else {
      return new RecordId(collectionId, collectionPos);
    }
  }

  public static String stringFromBytes(final byte[] bytes, final int offset, final int len) {
    return new String(bytes, offset, len, StandardCharsets.UTF_8);
  }

  public static String stringFromBytesIntern(DatabaseSessionEmbedded session, final byte[] bytes,
      final int offset, final int len) {
    try {
      var context = session.getSharedContext();
      if (context != null) {
        var cache = context.getStringCache();
        if (cache != null) {
          return cache.getString(bytes, offset, len);
        }
      }

      return new String(bytes, offset, len, StandardCharsets.UTF_8).intern();
    } catch (UnsupportedEncodingException e) {
      throw BaseException.wrapException(
          new SerializationException(session.getDatabaseName(), "Error on string decoding"),
          e, session.getDatabaseName());
    }
  }

  public static byte[] bytesFromString(final String toWrite) {
    return toWrite.getBytes(StandardCharsets.UTF_8);
  }

  public static long convertDayToTimezone(TimeZone from, TimeZone to, long time) {
    var fromCalendar = Calendar.getInstance(from);
    fromCalendar.setTimeInMillis(time);
    var toCalendar = Calendar.getInstance(to);
    toCalendar.setTimeInMillis(0);
    toCalendar.set(Calendar.ERA, fromCalendar.get(Calendar.ERA));
    toCalendar.set(Calendar.YEAR, fromCalendar.get(Calendar.YEAR));
    toCalendar.set(Calendar.MONTH, fromCalendar.get(Calendar.MONTH));
    toCalendar.set(Calendar.DAY_OF_MONTH, fromCalendar.get(Calendar.DAY_OF_MONTH));
    toCalendar.set(Calendar.HOUR_OF_DAY, 0);
    toCalendar.set(Calendar.MINUTE, 0);
    toCalendar.set(Calendar.SECOND, 0);
    toCalendar.set(Calendar.MILLISECOND, 0);
    return toCalendar.getTimeInMillis();
  }

  public static GlobalProperty getGlobalProperty(final EntityImpl entity, final int len) {
    final var id = (len * -1) - 1;
    return entity.getGlobalPropertyById(id);
  }

  public static int writeBinary(final BytesContainer bytes, final byte[] valueBytes) {
    final var pointer = VarIntSerializer.write(bytes, valueBytes.length);
    final var start = bytes.alloc(valueBytes.length);
    System.arraycopy(valueBytes, 0, bytes.bytes, start, valueBytes.length);
    return pointer;
  }

  public static int writeOptimizedLink(DatabaseSessionEmbedded session, final BytesContainer bytes,
      Identifiable link) {
    var rid = link.getIdentity();
    if (!rid.isPersistent()) {
      rid = session.refreshRid(rid);
    }
    if (rid.getCollectionId() < 0) {
      throw new DatabaseException(session.getDatabaseName(),
          "Impossible to serialize invalid link " + link.getIdentity());
    }

    final var pos = VarIntSerializer.write(bytes, rid.getCollectionId());
    VarIntSerializer.write(bytes, rid.getCollectionPosition());

    return pos;
  }

  public static int writeNullLink(final BytesContainer bytes) {
    final var pos = VarIntSerializer.write(bytes, NULL_RECORD_ID.getIdentity().getCollectionId());
    VarIntSerializer.write(bytes, NULL_RECORD_ID.getIdentity().getCollectionPosition());
    return pos;
  }

  public static PropertyTypeInternal getTypeFromValueEmbedded(final Object fieldValue) {
    var type = PropertyTypeInternal.getTypeByValue(fieldValue);
    if (type == PropertyTypeInternal.LINK
        && fieldValue instanceof EntityImpl
        && !((EntityImpl) fieldValue).getIdentity().isValidPosition()) {
      type = PropertyTypeInternal.EMBEDDED;
    }
    return type;
  }

  public static int writeLinkCollection(
      DatabaseSessionEmbedded db, final BytesContainer bytes,
      final Collection<Identifiable> value) {
    var pointer = bytes.alloc(1);
    VarIntSerializer.write(bytes, value.size());

    for (var itemValue : value) {
      assert itemValue.getIdentity().isPersistent();
      writeOptimizedLink(db, bytes, itemValue);
    }

    return pointer;
  }

  public static <T extends TrackedCollection<?, Identifiable>> T readLinkCollection(
      final BytesContainer bytes, final T found, boolean justRunThrough) {
    var type = bytes.bytes[bytes.offset++];
    if (type != 0) {
      throw new SerializationException("Invalid type of embedded collection");
    }

    final var items = VarIntSerializer.readAsInteger(bytes);
    for (var i = 0; i < items; i++) {
      var id = readOptimizedLink(bytes, justRunThrough);
      if (!justRunThrough) {
        if (id.equals(NULL_RECORD_ID)) {
          found.addInternal(null);
        } else {
          found.addInternal(id);
        }
      }
    }
    return found;
  }

  public static int writeString(final BytesContainer bytes, final String toWrite) {
    final var nameBytes = bytesFromString(toWrite);
    final var pointer = VarIntSerializer.write(bytes, nameBytes.length);
    final var start = bytes.alloc(nameBytes.length);
    System.arraycopy(nameBytes, 0, bytes.bytes, start, nameBytes.length);
    return pointer;
  }

  public static int writeLinkMap(DatabaseSessionEmbedded db, final BytesContainer bytes,
      final Map<Object, Identifiable> map) {
    final var fullPos = bytes.alloc(1);

    VarIntSerializer.write(bytes, map.size());
    for (var entry : map.entrySet()) {
      writeString(bytes, entry.getKey().toString());
      var entryValue = entry.getValue();
      assert entryValue.getIdentity().isPersistent();
      writeOptimizedLink(db, bytes, entryValue);
    }
    return fullPos;
  }

  public static Map<String, Identifiable> readLinkMap(
      final BytesContainer bytes, final RecordElement owner, boolean justRunThrough) {
    var version = bytes.bytes[bytes.offset++];
    if (version != 0) {
      throw new SerializationException("Invalid version of link map");
    }

    var size = VarIntSerializer.readAsInteger(bytes);
    EntityLinkMapIml result = null;
    if (!justRunThrough) {
      result = new EntityLinkMapIml(owner);
    }
    while ((size--) > 0) {
      final var key = readString(bytes);
      final var value = readOptimizedLink(bytes, justRunThrough);
      if (value.equals(NULL_RECORD_ID)) {
        result.putInternal(key, null);
      } else {
        result.putInternal(key, value);
      }
    }
    return result;
  }

  public static void writeByte(BytesContainer bytes, byte val) {
    var pos = bytes.alloc(ByteSerializer.BYTE_SIZE);
    bytes.bytes[pos] = val;
  }

  public static void writeLinkOptimized(final BytesContainer bytes, Identifiable link) {
    var id = link.getIdentity();
    VarIntSerializer.write(bytes, id.getCollectionId());
    VarIntSerializer.write(bytes, id.getCollectionPosition());
  }

  public static RID readLinkOptimizedEmbedded(DatabaseSessionEmbedded db,
      final BytesContainer bytes) {
    RID rid =
        new RecordId(VarIntSerializer.readAsInteger(bytes),
            VarIntSerializer.readAsLong(bytes));
    if (!rid.isPersistent()) {
      rid = db.refreshRid(rid);
    }

    return rid;
  }

  @Nullable
  public static PropertyTypeInternal getLinkedType(DatabaseSessionEmbedded session,
      SchemaClass clazz,
      PropertyTypeInternal type, String key) {
    if (type != PropertyTypeInternal.EMBEDDEDLIST && type != PropertyTypeInternal.EMBEDDEDSET
        && type != PropertyTypeInternal.EMBEDDEDMAP) {
      return null;
    }
    if (clazz != null) {
      var prop = clazz.getProperty(key);
      if (prop != null) {
        return PropertyTypeInternal.convertFromPublicType(prop.getLinkedType());
      }
    }
    return null;
  }
}
