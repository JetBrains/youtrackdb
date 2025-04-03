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

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.SerializableStream;
import java.io.StringWriter;
import java.util.Base64;
import javax.annotation.Nullable;

public class StringSerializerAnyStreamable implements StringSerializer {

  public static final StringSerializerAnyStreamable INSTANCE =
      new StringSerializerAnyStreamable();
  public static final String NAME = "st";

  /**
   * Re-Create any object if the class has a public constructor that accepts a String as unique
   * parameter.
   */
  @Nullable
  public Object fromStream(DatabaseSessionInternal session, final String iStream) {
    if (iStream == null || iStream.length() == 0)
    // NULL VALUE
    {
      return null;
    }

    SerializableStream instance = null;

    var propertyPos = iStream.indexOf(':');
    var pos = iStream.indexOf(StringSerializerEmbedded.SEPARATOR);
    if (pos < 0 || propertyPos > -1 && pos > propertyPos) {
      instance = new EntityImpl(session);
      pos = -1;
    } else {
      final var className = iStream.substring(0, pos);
      try {
        final var clazz = Class.forName(className);
        instance = (SerializableStream) clazz.newInstance();
      } catch (Exception e) {
        final var message = "Error on unmarshalling content. Class: " + className;
        LogManager.instance().error(this, message, e);
        throw BaseException.wrapException(new SerializationException(session, message), e, session);
      }
    }

    instance.fromStream(Base64.getDecoder().decode(iStream.substring(pos + 1)));
    return instance;
  }

  /**
   * Serialize the class name size + class name + object content
   *
   * @param session
   * @param iValue
   */
  public StringWriter toStream(DatabaseSessionInternal session, final StringWriter iOutput,
      Object iValue) {
    if (iValue != null) {
      if (!(iValue instanceof SerializableStream stream)) {
        throw new SerializationException(session,
            "Cannot serialize the object since it's not implements the SerializableStream"
                + " interface");
      }

      iOutput.append(iValue.getClass().getName());
      iOutput.append(StringSerializerEmbedded.SEPARATOR);
      iOutput.append(Base64.getEncoder().encodeToString(stream.toStream()));
    }
    return iOutput;
  }

  public String getName() {
    return NAME;
  }
}
