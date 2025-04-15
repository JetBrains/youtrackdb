package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.LinkBagPointer;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.RemoteTreeLinkBag;

public class RecordSerializerNetworkV37Client extends RecordSerializerNetworkV37 {

  public static final RecordSerializerNetworkV37Client INSTANCE =
      new RecordSerializerNetworkV37Client();
  public static final String NAME = "onet_ser_v37_client";

  @Override
  public void writeLinkBag(DatabaseSessionInternal session, BytesContainer bytes, LinkBag bag) {
    if (!bag.isToSerializeEmbedded()) {
      throw new IllegalStateException("TreeLinkBag serialization can be done only by "
          + EntitySerializerDelta.class.getSimpleName());
    }

    super.writeLinkBag(session, bytes, bag);
  }

  @Override
  public void writeLinkSet(DatabaseSessionInternal session, BytesContainer bytes,
      EntityLinkSetImpl set) {
    if (!set.isToSerializeEmbedded()) {
      throw new IllegalStateException("TreeLinkSet serialization can be done only by "
          + EntitySerializerDelta.class.getSimpleName());
    }

    super.writeLinkSet(session, bytes, set);
  }

  @Override
  public LinkBag readLinkBag(DatabaseSessionInternal session, BytesContainer bytes) {
    var b = bytes.bytes[bytes.offset];
    bytes.skip(1);
    if (b == 1) {
      var bag = new LinkBag(session);
      // enable tracking due to timeline issue, which must not be NULL (i.e. tracker.isEnabled()).
      bag.enableTracking(null);

      var size = VarIntSerializer.readAsInteger(bytes);
      for (var i = 0; i < size; i++) {
        Identifiable id = readOptimizedLink(session, bytes);
        bag.add(id.getIdentity());
      }

      bag.disableTracking(null);
      bag.transactionClear();
      return bag;
    } else {
      var linkBagSize = VarIntSerializer.readAsInteger(bytes);
      var fileId = VarIntSerializer.readAsLong(bytes);
      var linkBagId = VarIntSerializer.readAsLong(bytes);
      var pointer = new LinkBagPointer(fileId, linkBagId);
      if (!pointer.isValid()) {
        throw new IllegalStateException("LinkBag pointer is invalid");
      }
      return new LinkBag(session,
          new RemoteTreeLinkBag(session, pointer, Integer.MAX_VALUE, linkBagSize));
    }
  }

  @Override
  public EntityLinkSetImpl readLinkSet(DatabaseSessionInternal session,
      BytesContainer bytes) {
    var b = bytes.bytes[bytes.offset];
    bytes.skip(1);
    if (b == 1) {
      var bag = new EntityLinkSetImpl(session);
      // enable tracking due to timeline issue, which must not be NULL (i.e. tracker.isEnabled()).
      bag.enableTracking(null);

      var size = VarIntSerializer.readAsInteger(bytes);
      for (var i = 0; i < size; i++) {
        Identifiable id = readOptimizedLink(session, bytes);
        bag.add(id.getIdentity());
      }

      bag.disableTracking(null);
      bag.transactionClear();
      return bag;
    } else {
      var linkBagSize = VarIntSerializer.readAsInteger(bytes);
      var fileId = VarIntSerializer.readAsLong(bytes);
      var linkBagId = VarIntSerializer.readAsLong(bytes);

      var pointer = new LinkBagPointer(fileId, linkBagId);
      if (!pointer.isValid()) {
        throw new IllegalStateException("LinkSet with invalid pointer was found");
      }
      return new EntityLinkSetImpl(session,
          new RemoteTreeLinkBag(session, pointer, linkBagSize, 1));
    }
  }
}
