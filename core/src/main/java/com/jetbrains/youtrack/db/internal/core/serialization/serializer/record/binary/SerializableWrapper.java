package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.serialization.SerializableStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

@SuppressWarnings("serial")
public class SerializableWrapper implements SerializableStream {

  private Serializable serializable;

  public SerializableWrapper() {
  }

  public SerializableWrapper(Serializable ser) {
    this.serializable = ser;
  }

  @Override
  public byte[] toStream() throws SerializationException {
    var output = new ByteArrayOutputStream();
    try {
      var writer = new ObjectOutputStream(output);
      writer.writeObject(serializable);
      writer.close();
    } catch (IOException e) {
      throw BaseException.wrapException(
          new DatabaseException("Error on serialization of Serializable"),
          e, (String) null);
    }
    return output.toByteArray();
  }

  @Override
  public SerializableStream fromStream(byte[] iStream) throws SerializationException {
    var stream = new ByteArrayInputStream(iStream);
    try {
      var reader = new ObjectInputStream(stream);
      serializable = (Serializable) reader.readObject();
      reader.close();
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException("Error on deserialization of Serializable"), e, (String) null);
    }
    return this;
  }

  public Serializable getSerializable() {
    return serializable;
  }
}
