package com.jetbrains.youtrackdb.internal.core.db.config;

public class MulticastConfigurationBuilder {

  private final MulticastConfguration confguration = new MulticastConfguration();

  public MulticastConfigurationBuilder setEnabled(boolean enabled) {
    confguration.setEnabled(enabled);
    return this;
  }

  public MulticastConfigurationBuilder setDiscoveryPorts(int[] discoveryPorts) {
    confguration.setDiscoveryPorts(discoveryPorts);
    return this;
  }

  public MulticastConfigurationBuilder setPort(int port) {
    confguration.setPort(port);
    return this;
  }

  public MulticastConfigurationBuilder setIp(String ip) {
    confguration.setIp(ip);
    return this;
  }

  public MulticastConfguration build() {
    return new MulticastConfguration(
        confguration.isEnabled(),
        confguration.getIp(),
        confguration.getPort(),
        confguration.getDiscoveryPorts());
  }
}
