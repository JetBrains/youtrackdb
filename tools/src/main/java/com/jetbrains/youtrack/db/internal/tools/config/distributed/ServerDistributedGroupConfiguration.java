package com.jetbrains.youtrack.db.internal.tools.config.distributed;

import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "group")
public class ServerDistributedGroupConfiguration {

  @XmlElement
  public String name;
  @XmlElement
  public String password;
}
