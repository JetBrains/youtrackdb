/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.command;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.MemoryStream;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl.index.CompositeKeySerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerStringAbstract;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Text based Command Request abstract class.
 */
@SuppressWarnings("serial")
public abstract class CommandRequestTextAbstract extends CommandRequestAbstract
    implements CommandRequestText {

  protected String text;

  protected CommandRequestTextAbstract() {
  }

  protected CommandRequestTextAbstract(final String iText) {
    if (iText == null) {
      throw new IllegalArgumentException("Text cannot be null");
    }

    text = iText.trim();
  }


  @Override
  public String getText() {
    return text;
  }

  @Override
  public CommandRequestText setText(final String iText) {
    this.text = iText;
    return this;
  }

  @Override
  public CommandRequestText fromStream(DatabaseSessionEmbedded session, final byte[] iStream,
      RecordSerializerNetwork serializer)
      throws SerializationException {
    final var buffer = new MemoryStream(iStream);
    fromStream(session, buffer, serializer);
    return this;
  }

  @Override
  public byte[] toStream(DatabaseSessionInternal session, RecordSerializerNetwork serializer)
      throws SerializationException {
    final var buffer = new MemoryStream();
    return toStream(buffer, session);
  }

  @Override
  public String toString() {
    return "?." + text;
  }

  protected byte[] toStream(final MemoryStream buffer, DatabaseSessionInternal session) {
    buffer.setUtf8(text);

    if (parameters == null || parameters.isEmpty()) {
      // simple params are absent
      buffer.set(false);
      // composite keys are absent
      buffer.set(false);
    } else {
      final Map<Object, Object> params = new HashMap<Object, Object>();
      final Map<Object, List<Object>> compositeKeyParams = new HashMap<Object, List<Object>>();

      for (final var paramEntry : parameters.entrySet()) {
        if (paramEntry.getValue() instanceof CompositeKey compositeKey) {
          compositeKeyParams.put(paramEntry.getKey(), compositeKey.getKeys());
        } else {
          params.put(paramEntry.getKey(), paramEntry.getValue());
        }
      }

      buffer.set(!params.isEmpty());
      if (!params.isEmpty()) {
        final var param = (EntityImpl) session.newEmbeddedEntity();
        param.setProperty("parameters", params);
        buffer.set(param.toStream());
      }

      buffer.set(!compositeKeyParams.isEmpty());
      if (!compositeKeyParams.isEmpty()) {
        final var compositeKey = (EntityImpl) session.newEmbeddedEntity();
        compositeKey.setProperty("compositeKeyParams", compositeKeyParams);
        buffer.set(compositeKey.toStream());
      }
    }

    return buffer.toByteArray();
  }

  protected void fromStream(DatabaseSessionEmbedded session, final MemoryStream buffer,
      RecordSerializer serializer) {
    text = buffer.getAsString();

    parameters = null;

    final var simpleParams = buffer.getAsBoolean();
    if (simpleParams) {
      final var paramBuffer = buffer.getAsByteArray();
      final var param = (EntityImpl) session.newEmbeddedEntity();
      if (serializer != null) {
        serializer.fromStream(session, paramBuffer, param, null);
      } else {
        param.fromStream(paramBuffer);
      }

      Map<Object, Object> params = param.getProperty("params");
      parameters = new HashMap<Object, Object>();
      if (params != null) {
        for (var p : params.entrySet()) {
          final Object value;
          if (p.getValue() instanceof String) {
            value = RecordSerializerStringAbstract.getTypeValue(session, (String) p.getValue());
          } else {
            value = p.getValue();
          }

          if (p.getKey() instanceof String && Character.isDigit(((String) p.getKey()).charAt(0))) {
            parameters.put(Integer.parseInt((String) p.getKey()), value);
          } else {
            parameters.put(p.getKey(), value);
          }
        }
      } else {
        params = param.getProperty("parameters");
        for (var p : params.entrySet()) {
          if (p.getKey() instanceof String && Character.isDigit(((String) p.getKey()).charAt(0))) {
            parameters.put(Integer.parseInt((String) p.getKey()), p.getValue());
          } else {
            parameters.put(p.getKey(), p.getValue());
          }
        }
      }
    }

    final var compositeKeyParamsPresent = buffer.getAsBoolean();
    if (compositeKeyParamsPresent) {
      final var paramBuffer = buffer.getAsByteArray();
      final var param = (EntityImpl) session.newEmbeddedEntity();
      if (serializer != null) {
        serializer.fromStream(session, paramBuffer, param, null);
      } else {
        param.fromStream(paramBuffer);
      }

      final Map<Object, Object> compositeKeyParams = param.getProperty("compositeKeyParams");

      if (parameters == null) {
        parameters = new HashMap<Object, Object>();
      }

      for (final var p : compositeKeyParams.entrySet()) {
        if (p.getValue() instanceof List) {
          final var compositeKey = new CompositeKey((List<?>) p.getValue());
          if (p.getKey() instanceof String && Character.isDigit(((String) p.getKey()).charAt(0))) {
            parameters.put(Integer.parseInt((String) p.getKey()), compositeKey);
          } else {
            parameters.put(p.getKey(), compositeKey);
          }

        } else {
          final Object value =
              CompositeKeySerializer.INSTANCE.deserialize(session.getSerializerFactory(),
                  StringSerializerHelper.getBinaryContent(p.getValue()), 0);

          if (p.getKey() instanceof String && Character.isDigit(((String) p.getKey()).charAt(0))) {
            parameters.put(Integer.parseInt((String) p.getKey()), value);
          } else {
            parameters.put(p.getKey(), value);
          }
        }
      }
    }
  }
}
