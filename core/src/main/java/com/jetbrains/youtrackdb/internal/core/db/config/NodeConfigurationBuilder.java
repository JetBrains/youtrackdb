package com.jetbrains.youtrackdb.internal.core.db.config;

import java.util.UUID;

public class NodeConfigurationBuilder {

  private int quorum = 2;
  private String nodeName = UUID.randomUUID().toString();
  private String groupName = "YouTrackDB";
  private Integer tcpPort = null;
  private String groupPassword = "YouTrackDB";
  private MulticastConfguration multicastConfguration;
  private UDPUnicastConfiguration unicastConfiguration;

  protected NodeConfigurationBuilder() {
  }

  public NodeConfigurationBuilder setQuorum(int quorum) {
    this.quorum = quorum;
    return this;
  }

  public NodeConfigurationBuilder setNodeName(String nodeName) {
    this.nodeName = nodeName;
    return this;
  }

  public NodeConfigurationBuilder setGroupName(String groupName) {
    this.groupName = groupName;
    return this;
  }

  public NodeConfigurationBuilder setTcpPort(int tcpPort) {
    this.tcpPort = tcpPort;
    return this;
  }

  public NodeConfigurationBuilder setGroupPassword(String groupPassword) {
    this.groupPassword = groupPassword;
    return this;
  }

  public NodeConfigurationBuilder setMulticast(MulticastConfguration multicast) {
    multicastConfguration = multicast;
    return this;
  }

  public NodeConfigurationBuilder setUnicast(UDPUnicastConfiguration config) {
    this.unicastConfiguration = config;
    return this;
  }

  public NodeConfiguration build() {
    if (multicastConfguration != null) {
      return new NodeConfiguration(
          nodeName, groupName, groupPassword, quorum, tcpPort, multicastConfguration);
    } else if (unicastConfiguration != null) {
      return new NodeConfiguration(
          nodeName, groupName, groupPassword, quorum, tcpPort, unicastConfiguration);
    } else {
      // empty multicast as fallback... review...
      return new NodeConfiguration(
          nodeName, groupName, groupPassword, quorum, tcpPort, new MulticastConfguration());
    }
  }
}
