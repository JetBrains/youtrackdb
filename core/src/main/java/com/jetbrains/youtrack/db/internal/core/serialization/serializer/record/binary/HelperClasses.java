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
package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.SharedContext;
import com.jetbrains.youtrack.db.internal.core.db.StringCache;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBagDelegate;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.embedded.EmbeddedRidBag;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.RecordSerializationContext;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.index.sbtreebonsai.local.BonsaiBucketPointer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.sbtree.BonsaiCollectionPointer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.sbtree.Change;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.sbtree.ChangeSerializationHelper;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.sbtree.SBTreeCollectionManager;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.sbtree.SBTreeRidBag;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionAbstract;
import com.jetbrains.youtrack.db.internal.core.tx.TransactionOptimistic;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 *
 */
public class HelperClasses {

  public static final String CHARSET_UTF_8 = "UTF-8";
  protected static final RecordId NULL_RECORD_ID = new RecordId(-2, RID.CLUSTER_POS_INVALID);
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
    public PropertyType fieldType;
  }

  protected static class MapRecordInfo extends RecordInfo {

    public String key;
    public PropertyType keyType;
  }

  public static PropertyType readOType(final BytesContainer bytes, boolean justRunThrough) {
    if (justRunThrough) {
      bytes.offset++;
      return null;
    }
    return PropertyType.getById(readByte(bytes));
  }

  public static void writeOType(BytesContainer bytes, int pos, PropertyType type) {
    bytes.bytes[pos] = (byte) type.getId();
  }

  public static void writeType(BytesContainer bytes, PropertyType type) {
    int pos = bytes.alloc(1);
    bytes.bytes[pos] = (byte) type.getId();
  }

  public static PropertyType readType(BytesContainer bytes) {
    byte typeId = bytes.bytes[bytes.offset++];
    if (typeId == -1) {
      return null;
    }
    return PropertyType.getById(typeId);
  }

  public static byte[] readBinary(final BytesContainer bytes) {
    final int n = VarIntSerializer.readAsInteger(bytes);
    final byte[] newValue = new byte[n];
    System.arraycopy(bytes.bytes, bytes.offset, newValue, 0, newValue.length);
    bytes.skip(n);
    return newValue;
  }

  public static String readString(final BytesContainer bytes) {
    final int len = VarIntSerializer.readAsInteger(bytes);
    if (len == 0) {
      return "";
    }
    final String res = stringFromBytes(bytes.bytes, bytes.offset, len);
    bytes.skip(len);
    return res;
  }

  public static int readInteger(final BytesContainer container) {
    final int value =
        IntegerSerializer.INSTANCE.deserializeLiteral(container.bytes, container.offset);
    container.offset += IntegerSerializer.INT_SIZE;
    return value;
  }

  public static byte readByte(final BytesContainer container) {
    return container.bytes[container.offset++];
  }

  public static long readLong(final BytesContainer container) {
    final long value =
        LongSerializer.INSTANCE.deserializeLiteral(container.bytes, container.offset);
    container.offset += LongSerializer.LONG_SIZE;
    return value;
  }

  public static RecordId readOptimizedLink(final BytesContainer bytes, boolean justRunThrough) {
    int clusterId = VarIntSerializer.readAsInteger(bytes);
    long clusterPos = VarIntSerializer.readAsLong(bytes);
    if (justRunThrough) {
      return null;
    } else {
      return new RecordId(clusterId, clusterPos);
    }
  }

  public static String stringFromBytes(final byte[] bytes, final int offset, final int len) {
    return new String(bytes, offset, len, StandardCharsets.UTF_8);
  }

  public static String stringFromBytesIntern(final byte[] bytes, final int offset, final int len) {
    try {
      DatabaseSessionInternal db = DatabaseRecordThreadLocal.instance().getIfDefined();
      if (db != null) {
        SharedContext context = db.getSharedContext();
        if (context != null) {
          StringCache cache = context.getStringCache();
          if (cache != null) {
            return cache.getString(bytes, offset, len);
          }
        }
      }
      return new String(bytes, offset, len, StandardCharsets.UTF_8).intern();
    } catch (UnsupportedEncodingException e) {
      throw BaseException.wrapException(new SerializationException("Error on string decoding"), e);
    }
  }

  public static byte[] bytesFromString(final String toWrite) {
    return toWrite.getBytes(StandardCharsets.UTF_8);
  }

  public static long convertDayToTimezone(TimeZone from, TimeZone to, long time) {
    Calendar fromCalendar = Calendar.getInstance(from);
    fromCalendar.setTimeInMillis(time);
    Calendar toCalendar = Calendar.getInstance(to);
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
    final int id = (len * -1) - 1;
    return EntityInternalUtils.getGlobalPropertyById(entity, id);
  }

  public static int writeBinary(final BytesContainer bytes, final byte[] valueBytes) {
    final int pointer = VarIntSerializer.write(bytes, valueBytes.length);
    final int start = bytes.alloc(valueBytes.length);
    System.arraycopy(valueBytes, 0, bytes.bytes, start, valueBytes.length);
    return pointer;
  }

  public static int writeOptimizedLink(final BytesContainer bytes, Identifiable link) {
    if (!link.getIdentity().isPersistent()) {
      try {
        link = link.getRecord();
      } catch (RecordNotFoundException ignored) {
        // IGNORE IT WILL FAIL THE ASSERT IN CASE
      }
    }
    if (link.getIdentity().getClusterId() < 0) {
      throw new DatabaseException("Impossible to serialize invalid link " + link.getIdentity());
    }

    final int pos = VarIntSerializer.write(bytes, link.getIdentity().getClusterId());
    VarIntSerializer.write(bytes, link.getIdentity().getClusterPosition());
    return pos;
  }

  public static int writeNullLink(final BytesContainer bytes) {
    final int pos = VarIntSerializer.write(bytes, NULL_RECORD_ID.getIdentity().getClusterId());
    VarIntSerializer.write(bytes, NULL_RECORD_ID.getIdentity().getClusterPosition());
    return pos;
  }

  public static PropertyType getTypeFromValueEmbedded(final Object fieldValue) {
    PropertyType type = PropertyType.getTypeByValue(fieldValue);
    if (type == PropertyType.LINK
        && fieldValue instanceof EntityImpl
        && !((EntityImpl) fieldValue).getIdentity().isValid()) {
      type = PropertyType.EMBEDDED;
    }
    return type;
  }

  public static int writeLinkCollection(
      final BytesContainer bytes, final Collection<Identifiable> value) {
    final int pos = VarIntSerializer.write(bytes, value.size());

    for (Identifiable itemValue : value) {
      // TODO: handle the null links
      if (itemValue == null) {
        writeNullLink(bytes);
      } else {
        writeOptimizedLink(bytes, itemValue);
      }
    }

    return pos;
  }

  public static <T extends TrackedMultiValue<?, Identifiable>> T readLinkCollection(
      final BytesContainer bytes, final T found, boolean justRunThrough) {
    final int items = VarIntSerializer.readAsInteger(bytes);
    for (int i = 0; i < items; i++) {
      RecordId id = readOptimizedLink(bytes, justRunThrough);
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
    final byte[] nameBytes = bytesFromString(toWrite);
    final int pointer = VarIntSerializer.write(bytes, nameBytes.length);
    final int start = bytes.alloc(nameBytes.length);
    System.arraycopy(nameBytes, 0, bytes.bytes, start, nameBytes.length);
    return pointer;
  }

  public static int writeLinkMap(final BytesContainer bytes,
      final Map<Object, Identifiable> map) {
    final int fullPos = VarIntSerializer.write(bytes, map.size());
    for (Map.Entry<Object, Identifiable> entry : map.entrySet()) {
      writeString(bytes, entry.getKey().toString());
      if (entry.getValue() == null) {
        writeNullLink(bytes);
      } else {
        writeOptimizedLink(bytes, entry.getValue());
      }
    }
    return fullPos;
  }

  public static Map<Object, Identifiable> readLinkMap(
      final BytesContainer bytes, final RecordElement owner, boolean justRunThrough) {
    int size = VarIntSerializer.readAsInteger(bytes);
    LinkMap result = null;
    if (!justRunThrough) {
      result = new LinkMap(owner);
    }
    while ((size--) > 0) {
      final String key = readString(bytes);
      final RecordId value = readOptimizedLink(bytes, justRunThrough);
      if (value.equals(NULL_RECORD_ID)) {
        result.putInternal(key, null);
      } else {
        result.putInternal(key, value);
      }
    }
    return result;
  }

  public static void writeByte(BytesContainer bytes, byte val) {
    int pos = bytes.alloc(ByteSerializer.BYTE_SIZE);
    ByteSerializer.INSTANCE.serialize(val, bytes.bytes, pos);
  }

  public static void writeRidBag(BytesContainer bytes, RidBag ridbag) {
    ridbag.checkAndConvert();

    UUID ownerUuid = ridbag.getTemporaryId();

    int positionOffset = bytes.offset;
    final SBTreeCollectionManager sbTreeCollectionManager =
        DatabaseRecordThreadLocal.instance().get().getSbTreeCollectionManager();
    UUID uuid = null;
    if (sbTreeCollectionManager != null) {
      uuid = sbTreeCollectionManager.listenForChanges(ridbag);
    }

    byte configByte = 0;
    if (ridbag.isEmbedded()) {
      configByte |= 1;
    }

    if (uuid != null) {
      configByte |= 2;
    }

    // alloc will move offset and do skip
    int posForWrite = bytes.alloc(ByteSerializer.BYTE_SIZE);
    ByteSerializer.INSTANCE.serialize(configByte, bytes.bytes, posForWrite);

    // removed serializing UUID

    if (ridbag.isEmbedded()) {
      writeEmbeddedRidbag(bytes, ridbag);
    } else {
      writeSBTreeRidbag(bytes, ridbag, ownerUuid);
    }
  }

  protected static void writeEmbeddedRidbag(BytesContainer bytes, RidBag ridbag) {
    DatabaseSessionInternal db = DatabaseRecordThreadLocal.instance().getIfDefined();
    int size = ridbag.size();
    Object[] entries = ((EmbeddedRidBag) ridbag.getDelegate()).getEntries();
    for (int i = 0; i < entries.length; i++) {
      Object entry = entries[i];
      if (entry instanceof Identifiable itemValue) {
        if (db != null
            && !db.isClosed()
            && db.getTransaction().isActive()
            && !itemValue.getIdentity().isPersistent()) {
          itemValue = db.getTransaction().getRecord(itemValue.getIdentity());
        }
        if (itemValue == null || itemValue == FrontendTransactionAbstract.DELETED_RECORD) {
          entries[i] = null;
          // Decrease size, nulls are ignored
          size--;
        } else {
          entries[i] = itemValue.getIdentity();
        }
      }
    }

    VarIntSerializer.write(bytes, size);
    for (int i = 0; i < entries.length; i++) {
      Object entry = entries[i];
      // Obviously this exclude nulls as well
      if (entry instanceof Identifiable) {
        writeLinkOptimized(bytes, ((Identifiable) entry).getIdentity());
      }
    }
  }

  protected static void writeSBTreeRidbag(BytesContainer bytes, RidBag ridbag, UUID ownerUuid) {
    ((SBTreeRidBag) ridbag.getDelegate()).applyNewEntries();

    BonsaiCollectionPointer pointer = ridbag.getPointer();

    final RecordSerializationContext context;
    var db = DatabaseRecordThreadLocal.instance().get();
    var tx = db.getTransaction();
    if (!(tx instanceof TransactionOptimistic optimisticTx)) {
      throw new DatabaseException("Transaction is not active. Changes are not allowed");
    }

    boolean remoteMode = db.isRemote();

    if (remoteMode) {
      context = null;
    } else {
      context = RecordSerializationContext.getContext();
    }

    if (pointer == null && context != null) {
      final int clusterId = getHighLevelDocClusterId(ridbag);
      assert clusterId > -1;
      try {
        final AbstractPaginatedStorage storage =
            (AbstractPaginatedStorage) DatabaseRecordThreadLocal.instance().get().getStorage();
        final AtomicOperation atomicOperation =
            storage.getAtomicOperationsManager().getCurrentOperation();

        assert atomicOperation != null;
        pointer =
            DatabaseRecordThreadLocal.instance()
                .get()
                .getSbTreeCollectionManager()
                .createSBTree(clusterId, atomicOperation, ownerUuid);
      } catch (IOException e) {
        throw BaseException.wrapException(
            new DatabaseException("Error during creation of ridbag"), e);
      }
    }

    ((SBTreeRidBag) ridbag.getDelegate()).setCollectionPointer(pointer);

    VarIntSerializer.write(bytes, pointer.getFileId());
    VarIntSerializer.write(bytes, pointer.getRootPointer().getPageIndex());
    VarIntSerializer.write(bytes, pointer.getRootPointer().getPageOffset());
    VarIntSerializer.write(bytes, 0);

    if (context != null) {
      ((SBTreeRidBag) ridbag.getDelegate()).handleContextSBTree(context, pointer);
      VarIntSerializer.write(bytes, 0);
    } else {
      VarIntSerializer.write(bytes, 0);

      // removed changes serialization
    }
  }

  private static int getHighLevelDocClusterId(RidBag ridbag) {
    RidBagDelegate delegate = ridbag.getDelegate();
    RecordElement owner = delegate.getOwner();
    while (owner != null && owner.getOwner() != null) {
      owner = owner.getOwner();
    }

    if (owner != null) {
      return ((Identifiable) owner).getIdentity().getClusterId();
    }

    return -1;
  }

  public static void writeLinkOptimized(final BytesContainer bytes, Identifiable link) {
    RID id = link.getIdentity();
    VarIntSerializer.write(bytes, id.getClusterId());
    VarIntSerializer.write(bytes, id.getClusterPosition());
  }

  public static RidBag readRidbag(DatabaseSessionInternal session, BytesContainer bytes) {
    byte configByte = ByteSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset++);
    boolean isEmbedded = (configByte & 1) != 0;

    UUID uuid = null;
    // removed deserializing UUID

    RidBag ridbag = null;
    if (isEmbedded) {
      ridbag = new RidBag(session);
      int size = VarIntSerializer.readAsInteger(bytes);
      ridbag.getDelegate().setSize(size);
      for (int i = 0; i < size; i++) {
        Identifiable record = readLinkOptimizedEmbedded(bytes);
        ridbag.getDelegate().addInternal(record);
      }
    } else {
      long fileId = VarIntSerializer.readAsLong(bytes);
      long pageIndex = VarIntSerializer.readAsLong(bytes);
      int pageOffset = VarIntSerializer.readAsInteger(bytes);
      // read bag size
      VarIntSerializer.readAsInteger(bytes);

      BonsaiCollectionPointer pointer = null;
      if (fileId != -1) {
        pointer =
            new BonsaiCollectionPointer(fileId, new BonsaiBucketPointer(pageIndex, pageOffset));
      }

      Map<Identifiable, Change> changes = new HashMap<>();

      int changesSize = VarIntSerializer.readAsInteger(bytes);
      for (int i = 0; i < changesSize; i++) {
        Identifiable recId = readLinkOptimizedSBTree(bytes);
        Change change = deserializeChange(bytes);
        changes.put(recId, change);
      }

      ridbag = new RidBag(session, pointer, changes, uuid);
      ridbag.getDelegate().setSize(-1);
    }
    return ridbag;
  }

  private static Identifiable readLinkOptimizedEmbedded(final BytesContainer bytes) {
    RID rid =
        new RecordId(VarIntSerializer.readAsInteger(bytes), VarIntSerializer.readAsLong(bytes));
    Identifiable identifiable = null;
    if (rid.isTemporary()) {
      try {
        identifiable = rid.getRecord();
      } catch (RecordNotFoundException rnf) {
        identifiable = rid;
      }
    }

    if (identifiable == null) {
      identifiable = rid;
    }

    return identifiable;
  }

  private static Identifiable readLinkOptimizedSBTree(final BytesContainer bytes) {
    RID rid =
        new RecordId(VarIntSerializer.readAsInteger(bytes), VarIntSerializer.readAsLong(bytes));
    Identifiable identifiable;
    if (rid.isTemporary()) {
      try {
        identifiable = rid.getRecord();
      } catch (RecordNotFoundException rnf) {
        identifiable = rid;
      }
    } else {
      identifiable = rid;
    }
    return identifiable;
  }

  private static Change deserializeChange(BytesContainer bytes) {
    byte type = ByteSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset);
    bytes.skip(ByteSerializer.BYTE_SIZE);
    int change = IntegerSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset);
    bytes.skip(IntegerSerializer.INT_SIZE);
    return ChangeSerializationHelper.createChangeInstance(type, change);
  }

  public static PropertyType getLinkedType(DatabaseSession session, SchemaClass clazz,
      PropertyType type, String key) {
    if (type != PropertyType.EMBEDDEDLIST && type != PropertyType.EMBEDDEDSET
        && type != PropertyType.EMBEDDEDMAP) {
      return null;
    }
    if (clazz != null) {
      SchemaProperty prop = clazz.getProperty(session, key);
      if (prop != null) {
        return prop.getLinkedType();
      }
    }
    return null;
  }
}
