package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.LinkSerializer;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper for serializing and deserializing RID bag change entries.
 */
public class ChangeSerializationHelper {

  public static final ChangeSerializationHelper INSTANCE = new ChangeSerializationHelper();

  public static Change createChangeInstance(byte type, int value) {
    if (type == AbsoluteChange.TYPE) {
      return new AbsoluteChange(value);
    }

    throw new IllegalArgumentException("Unknown change type: " + type);
  }

  public static Change deserializeChange(final byte[] stream, final int offset) {
    var value =
        IntegerSerializer.deserializeLiteral(stream, offset + ByteSerializer.BYTE_SIZE);
    return createChangeInstance(ByteSerializer.INSTANCE.deserializeLiteral(stream, offset), value);
  }

  public static Map<RID, Change> deserializeChanges(DatabaseSessionEmbedded session,
      final byte[] stream, int offset) {
    final var count = IntegerSerializer.deserializeLiteral(stream, offset);
    offset += IntegerSerializer.INT_SIZE;

    final var res = new HashMap<RID, Change>();
    for (var i = 0; i < count; i++) {
      var rid = LinkSerializer.staticDeserialize(stream, offset);
      offset += LinkSerializer.RID_SIZE;
      var change = ChangeSerializationHelper.deserializeChange(stream, offset);
      offset += Change.SIZE;

      RID identifiable;
      if (rid.isTemporary()) {
        identifiable = session.refreshRid(rid);
      } else {
        identifiable = rid;
      }

      res.put(identifiable, change);
    }

    return res;
  }

  public static void serializeChanges(
      DatabaseSessionEmbedded db, Map<RID, Change> changes, byte[] stream, int offset) {
    IntegerSerializer.serializeLiteral(changes.size(), stream, offset);
    offset += IntegerSerializer.INT_SIZE;

    for (var entry : changes.entrySet()) {
      var rid = entry.getKey();
      if (!rid.isPersistent()) {
        rid = db.refreshRid(rid);
      }

      LinkSerializer.staticSerialize(rid, stream, offset);
      offset += LinkSerializer.staticGetObjectSize();

      offset += entry.getValue().serialize(stream, offset);
    }
  }

  public static int getChangesSerializedSize(int changesCount) {
    return changesCount * (LinkSerializer.RID_SIZE + Change.SIZE);
  }
}
