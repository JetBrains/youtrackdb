package com.jetbrain.youtrack.db.gremlin.internal.io.gryo;

import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import org.apache.tinkerpop.shaded.kryo.Kryo;
import org.apache.tinkerpop.shaded.kryo.Serializer;
import org.apache.tinkerpop.shaded.kryo.io.Input;
import org.apache.tinkerpop.shaded.kryo.io.Output;

/**
 * Created by Enrico Risa on 06/09/2017.
 */
public class RecordIdGyroSerializer extends Serializer<RecordId> {

  public static final RecordIdGyroSerializer INSTANCE = new RecordIdGyroSerializer();

  @Override
  public RecordId read(
      final Kryo kryo, final Input input, final Class<RecordId> tinkerGraphClass) {
    return new RecordId(input.readString());
  }

  @Override
  public void write(final Kryo kryo, final Output output, final RecordId rid) {
    output.writeString(rid.toString());
  }
}
