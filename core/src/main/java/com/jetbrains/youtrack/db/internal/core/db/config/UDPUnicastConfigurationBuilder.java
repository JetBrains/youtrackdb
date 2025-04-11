package com.jetbrains.youtrack.db.internal.core.db.config;

import java.util.ArrayList;

public class UDPUnicastConfigurationBuilder {

  private final UDPUnicastConfiguration confguration = new UDPUnicastConfiguration();

  public UDPUnicastConfigurationBuilder setEnabled(boolean enabled) {
    confguration.setEnabled(enabled);
    return this;
  }

  public UDPUnicastConfigurationBuilder addAddress(String address, int port) {
    confguration.getDiscoveryAddresses().add(new UDPUnicastConfiguration.Address(address, port));
    return this;
  }

  public UDPUnicastConfigurationBuilder setPort(int port) {
    confguration.setPort(port);
    return this;
  }

  public UDPUnicastConfiguration build() {
    var result = new UDPUnicastConfiguration();
    result.setEnabled(confguration.isEnabled());
    result.setPort(confguration.getPort());
    result.setDiscoveryAddresses(new ArrayList<>(confguration.getDiscoveryAddresses()));
    return result;
  }
}
