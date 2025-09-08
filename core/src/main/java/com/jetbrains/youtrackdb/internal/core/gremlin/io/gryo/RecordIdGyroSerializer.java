package com.jetbrains.youtrackdb.internal.core.gremlin.io.gryo;

import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import org.apache.tinkerpop.shaded.kryo.Kryo;
import org.apache.tinkerpop.shaded.kryo.Serializer;
import org.apache.tinkerpop.shaded.kryo.io.Input;
import org.apache.tinkerpop.shaded.kryo.io.Output;

/**
 * Created by Enrico Risa on 06/09/2017.
 */
public class RecordIdGyroSerializer extends Serializer<RecordIdInternal> {

  public static final RecordIdGyroSerializer INSTANCE = new RecordIdGyroSerializer();

  @Override
  public RecordIdInternal read(
      final Kryo kryo, final Input input, final Class<RecordIdInternal> tinkerGraphClass) {
    return RecordIdInternal.fromString(input.readString(), true);
  }

  @Override
  public void write(final Kryo kryo, final Output output, final RecordIdInternal rid) {
    output.writeString(rid.toString());
  }
}
