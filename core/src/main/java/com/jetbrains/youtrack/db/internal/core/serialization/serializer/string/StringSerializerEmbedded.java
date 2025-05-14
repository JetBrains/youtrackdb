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
package com.jetbrains.youtrack.db.internal.core.serialization.serializer.string;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.record.impl.EmbeddedEntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrack.db.internal.core.serialization.SerializableStream;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerSchemaAware2CSV;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StringSerializerEmbedded implements StringSerializer {

  private static final Logger logger = LoggerFactory.getLogger(StringSerializerEmbedded.class);

  public static final StringSerializerEmbedded INSTANCE = new StringSerializerEmbedded();
  public static final String NAME = "em";
  public static final String SEPARATOR = "|";
  public static final char SHORT_FORM_PREFIX = '!';

  /**
   * Re-Create any object if the class has a public constructor that accepts a String as unique
   * parameter.
   */
  @Nullable
  public Object fromStream(DatabaseSessionEmbedded session, final String iStream) {
    if (iStream == null || iStream.isEmpty())
    // NULL VALUE
    {
      return null;
    }

    final EntityImpl instance = new EmbeddedEntityImpl(session);
    RecordSerializerSchemaAware2CSV.INSTANCE.fromStream(session,
        iStream.getBytes(StandardCharsets.UTF_8), instance, null);

    final String className = instance.getProperty(EntitySerializable.CLASS_NAME);
    if (className == null) {
      return instance;
    }

    Class<?> clazz = null;
    try {
      clazz = Class.forName(className);
    } catch (ClassNotFoundException e) {
      LogManager.instance()
          .debug(
              this,
              "Class name provided in embedded entity " + className + " does not exist.",
              logger, e);
    }

    if (clazz == null) {
      return instance;
    }

    return instance;
  }

  /**
   * Serialize the class name size + class name + object content
   */
  public StringWriter toStream(DatabaseSessionInternal session, final StringWriter iOutput,
      Object iValue) {
    if (iValue != null) {
      if (iValue instanceof EntitySerializable) {
        iValue = ((EntitySerializable) iValue).toEntity(session);
      }

      if (!(iValue instanceof SerializableStream stream)) {
        throw new SerializationException(session,
            "Cannot serialize the object since it's not implements the SerializableStream"
                + " interface");
      }

      iOutput.append(iValue.getClass().getName());
      iOutput.append(SEPARATOR);
      iOutput.append(new String(stream.toStream(), StandardCharsets.UTF_8));
    }

    return iOutput;
  }

  public String getName() {
    return NAME;
  }
}
