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
package com.jetbrains.youtrack.db.internal.tools.config;

import jakarta.xml.bind.annotation.XmlAnyElement;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.util.ArrayList;
import java.util.List;

@XmlRootElement(name = "network")
public class ServerNetworkConfiguration {

  @XmlElementWrapper
  @XmlAnyElement
  @XmlElementRef(type = ServerSocketFactoryConfiguration.class)
  public List<ServerSocketFactoryConfiguration> sockets;

  @XmlElementWrapper
  @XmlAnyElement
  @XmlElementRef(type = ServerNetworkProtocolConfiguration.class)
  public List<ServerNetworkProtocolConfiguration> protocols;

  @XmlElementWrapper
  @XmlAnyElement
  @XmlElementRef(type = ServerNetworkListenerConfiguration.class)
  public List<ServerNetworkListenerConfiguration> listeners;

  public ServerNetworkConfiguration() {
  }

  public ServerNetworkConfiguration(Object iObject) {
    protocols = new ArrayList<>();
    protocols.add(
        new ServerNetworkProtocolConfiguration(
            "binary",
            "com.jetbrains.youtrack.db.internal.server.network.protocol.binary.NetworkProtocolBinary"));

    listeners = new ArrayList<>();
    listeners.add(new ServerNetworkListenerConfiguration());
  }
}
