package com.jetbrains.youtrackdb.internal.core.db.config;

public class MulticastConfguration {

  private boolean enabled = true;
  private String ip = "230.0.0.0";
  private int port = 4321;
  private int[] discoveryPorts = new int[]{4321};

  public MulticastConfguration() {
  }

  protected MulticastConfguration(boolean enabled, String ip, int port, int[] discoveryPorts) {
    this.enabled = enabled;
    this.ip = ip;
    this.port = port;
    this.discoveryPorts = discoveryPorts;
  }

  public static MulticastConfigurationBuilder builder() {
    return new MulticastConfigurationBuilder();
  }

  protected void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  protected void setDiscoveryPorts(int[] discoveryPorts) {
    this.discoveryPorts = discoveryPorts;
  }

  protected void setPort(int port) {
    this.port = port;
  }

  protected void setIp(String ip) {
    this.ip = ip;
  }

  public String getIp() {
    return ip;
  }

  public int getPort() {
    return port;
  }

  public int[] getDiscoveryPorts() {
    return discoveryPorts;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
