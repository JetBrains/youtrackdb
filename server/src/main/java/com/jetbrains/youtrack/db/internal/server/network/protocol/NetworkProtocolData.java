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
package com.jetbrains.youtrack.db.internal.server.network.protocol;

import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializerFactory;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkBase;

/**
 * Saves all the important information about the network connection. Useful for monitoring and
 * statistics.
 */
public class NetworkProtocolData {

  public String commandInfo = null;
  public String commandDetail = null;
  public String lastDatabase = null;
  public String lastUser = null;
  public String serverInfo = null;
  public String caller = null;
  public String driverName = null;
  public String driverVersion = null;
  public short protocolVersion = -1;
  public int sessionId = -1;
  public String clientId = null;
  public String currentUserId = null;
  private String serializationImpl = null;
  public boolean serverUser = false;
  public String serverUsername = null;
  public CommandRequestText command = null;
  public boolean supportsLegacyPushMessages = true;
  public boolean collectStats = true;
  private RecordSerializerNetwork serializer;

  public String getSerializationImpl() {
    return serializationImpl;
  }

  public void setSerializationImpl(String serializationImpl) {
    if (serializationImpl.equals(RecordSerializerBinary.NAME)) {
      serializationImpl = RecordSerializerNetworkBase.NAME;
    }

    this.serializationImpl = serializationImpl;
    serializer = (RecordSerializerNetwork) RecordSerializerFactory.instance()
        .getFormat(serializationImpl);
  }

  public void setSerializer(RecordSerializerNetwork serializer) {
    this.serializer = serializer;
    this.serializationImpl = serializer.getName();
  }

  public RecordSerializerNetwork getSerializer() {
    return serializer;
  }
}
