package com.jetbrain.youtrack.db.gremlin.internal.io.gryo;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import org.apache.tinkerpop.shaded.kryo.Kryo;
import org.apache.tinkerpop.shaded.kryo.Serializer;
import org.apache.tinkerpop.shaded.kryo.io.Input;
import org.apache.tinkerpop.shaded.kryo.io.Output;

public class LinkBagGyroSerializer extends Serializer<LinkBag> {

  private final DatabaseSessionEmbedded session;

  public LinkBagGyroSerializer(DatabaseSessionEmbedded session) {
    this.session = session;
  }

  @Override
  public LinkBag read(final Kryo kryo, final Input input, final Class<LinkBag> tinkerGraphClass) {
    final var bag = new LinkBag(session);
    final var ids = input.readString().split(";");

    for (final var id : ids) {
      bag.add(new RecordId(id));
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
