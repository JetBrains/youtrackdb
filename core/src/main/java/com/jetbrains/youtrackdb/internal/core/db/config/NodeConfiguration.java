package com.jetbrains.youtrackdb.internal.core.db.config;

public class NodeConfiguration {

  // Node name is redundant because it can come also from user configuration appart been stored in
  // the node identity
  private String nodeName;
  private String groupName;
  private String groupPassword;
  private int quorum;
  private Integer tcpPort;
  private MulticastConfguration multicast;
  private UDPUnicastConfiguration udpUnicast;

  protected NodeConfiguration() {
  }

  protected NodeConfiguration(
      String nodeName,
      String groupName,
      String groupPassword,
      int quorum,
      Integer tcpPort,
      MulticastConfguration multicast) {
    this.nodeName = nodeName;
    this.groupName = groupName;
    this.groupPassword = groupPassword;
    this.quorum = quorum;
    this.tcpPort = tcpPort;
    this.multicast = multicast;
  }

  protected NodeConfiguration(
      String nodeName,
      String groupName,
      String groupPassword,
      int quorum,
      Integer tcpPort,
      UDPUnicastConfiguration unicastConfig) {
    this.nodeName = nodeName;
    this.groupName = groupName;
    this.groupPassword = groupPassword;
    this.quorum = quorum;
    this.tcpPort = tcpPort;
    this.udpUnicast = unicastConfig;
  }

  public int getQuorum() {
    return quorum;
  }

  protected void setQuorum(int quorum) {
    this.quorum = quorum;
  }

  public String getGroupName() {
    return groupName;
  }

  public void setGroupName(String groupName) {
    this.groupName = groupName;
  }

  public Integer getTcpPort() {
    return tcpPort;
  }

  public void setTcpPort(int tcpPort) {
    this.tcpPort = tcpPort;
  }

  public String getGroupPassword() {
    return groupPassword;
  }

  public void setGroupPassword(String groupPassword) {
    this.groupPassword = groupPassword;
  }

  public MulticastConfguration getMulticast() {
    return multicast;
  }

  protected void setMulticast(MulticastConfguration multicast) {
    this.multicast = multicast;
  }

  public static NodeConfigurationBuilder builder() {
    return new NodeConfigurationBuilder();
  }

  public String getNodeName() {
    return nodeName;
  }

  public UDPUnicastConfiguration getUdpUnicast() {
    return udpUnicast;
  }
}
