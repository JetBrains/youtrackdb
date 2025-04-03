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
package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkBase;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37Client;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Factory of record serialized.
 */
public class RecordSerializerFactory {

  private static final RecordSerializerFactory instance = new RecordSerializerFactory();
  private final Map<String, RecordSerializer> implementations =
      new HashMap<String, RecordSerializer>();

  private RecordSerializer defaultRecordSerializer;

  public RecordSerializerFactory() {
    register(RecordSerializerBinary.NAME, RecordSerializerBinary.INSTANCE);
    register(RecordSerializerNetworkBase.NAME, RecordSerializerNetworkBase.INSTANCE);
    register(RecordSerializerNetworkV37.NAME, RecordSerializerNetworkV37.INSTANCE);
    register(RecordSerializerNetworkV37Client.NAME, RecordSerializerNetworkV37Client.INSTANCE);

    defaultRecordSerializer =
        getFormat(GlobalConfiguration.DB_ENTITY_SERIALIZER.getValueAsString());
    if (defaultRecordSerializer == null) {
      throw new DatabaseException(
          "Impossible to find serializer with name "
              + GlobalConfiguration.DB_ENTITY_SERIALIZER.getValueAsString());
    }
  }

  /**
   * Registers record serializer implementation.
   *
   * @param iName     Name to register, use JSON to overwrite default JSON serializer
   * @param iInstance Serializer implementation
   */
  public void register(final String iName, final RecordSerializer iInstance) {
    implementations.put(iName, iInstance);
  }

  public Collection<RecordSerializer> getFormats() {
    return implementations.values();
  }

  @Nullable
  public RecordSerializer getFormat(final String iFormatName) {
    if (iFormatName == null) {
      return null;
    }

    return implementations.get(iFormatName);
  }

  public static RecordSerializerFactory instance() {
    return instance;
  }

  public void setDefaultRecordSerializer(RecordSerializer defaultRecordSerializer) {
    this.defaultRecordSerializer = defaultRecordSerializer;
  }

  public RecordSerializer getDefaultRecordSerializer() {
    return defaultRecordSerializer;
  }
}
