package com.jetbrains.youtrackdb.internal.core.gremlin.io.gryo;

import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.LinkBagStub;
import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.apache.tinkerpop.shaded.kryo.Kryo;
import org.apache.tinkerpop.shaded.kryo.Serializer;
import org.apache.tinkerpop.shaded.kryo.io.Input;
import org.apache.tinkerpop.shaded.kryo.io.Output;

public class LinkBagGyroSerializer extends Serializer<LinkBag> {

  public static final LinkBagGyroSerializer INSTANCE = new LinkBagGyroSerializer();

  public LinkBagGyroSerializer() {
  }

  @Override
  public LinkBag read(final Kryo kryo, final Input input, final Class<LinkBag> tinkerGraphClass) {
    final var bag = new LinkBagStub();
    final var ids = input.readString().split(";");

    for (final var id : ids) {
      var rid = new RecordId(id);

      if (rid.isNew()) {
        rid = new ChangeableRecordId(rid);
      }

      bag.add(rid);
    }

    return bag;
  }

  @Override
  public void write(final Kryo kryo, final Output output, final LinkBag bag) {
    final var ids = new StringBuilder();
    bag.forEach(rid -> ids.append(rid.getIdentity()).append(";"));
    output.writeString(ids);
  }
}
