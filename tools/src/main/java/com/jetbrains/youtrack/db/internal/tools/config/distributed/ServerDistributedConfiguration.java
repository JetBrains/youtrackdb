package com.jetbrains.youtrack.db.internal.tools.config.distributed;

import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "distributed")
public class ServerDistributedConfiguration {

  @XmlAttribute
  public Boolean enabled = false;

  @XmlElement(name = "node-name")
  public String nodeName;

  @XmlElement
  public Integer quorum;

  @XmlElementRef(type = ServerDistributedNetworkConfiguration.class)
  public ServerDistributedNetworkConfiguration network =
      new ServerDistributedNetworkConfiguration();

  @XmlElementRef(type = ServerDistributedGroupConfiguration.class)
  public ServerDistributedGroupConfiguration group = new ServerDistributedGroupConfiguration();
}
