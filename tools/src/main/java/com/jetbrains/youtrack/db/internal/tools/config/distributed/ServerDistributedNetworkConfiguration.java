package com.jetbrains.youtrack.db.internal.tools.config.distributed;

import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "network")
public class ServerDistributedNetworkConfiguration {

  @XmlElementRef(type = ServerDistributedNetworkMulticastConfiguration.class)
  public ServerDistributedNetworkMulticastConfiguration multicast =
      new ServerDistributedNetworkMulticastConfiguration();
}
