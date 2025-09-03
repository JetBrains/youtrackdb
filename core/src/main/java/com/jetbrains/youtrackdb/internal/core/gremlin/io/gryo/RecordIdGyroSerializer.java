package com.jetbrains.youtrackdb.internal.core.gremlin.io.gryo;

import com.jetbrains.youtrackdb.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
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
    var rid = new RecordId(input.readString());

    if (rid.isNew()) {
      return new ChangeableRecordId(rid);
    }

    return rid;
  }

  @Override
  public void write(final Kryo kryo, final Output output, final RecordId rid) {
    output.writeString(rid.toString());
  }
}
