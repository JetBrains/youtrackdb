package com.jetbrains.youtrackdb.internal.core.db.config;

import java.util.ArrayList;
import java.util.List;

public class UDPUnicastConfiguration {

  public record Address(String address, int port) {

  }

  private boolean enabled = true;
  private int port = 4321;
  private List<Address> discoveryAddresses = new ArrayList<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public List<Address> getDiscoveryAddresses() {
    return discoveryAddresses;
  }

  public void setDiscoveryAddresses(List<Address> discoveryAddresses) {
    this.discoveryAddresses = discoveryAddresses;
  }

  public static UDPUnicastConfigurationBuilder builder() {
    return new UDPUnicastConfigurationBuilder();
  }
}
