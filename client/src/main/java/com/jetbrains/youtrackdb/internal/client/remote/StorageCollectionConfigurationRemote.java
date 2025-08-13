package com.jetbrains.youtrackdb.internal.client.remote;

import com.jetbrains.youtrackdb.internal.core.config.StorageCollectionConfiguration;

public record StorageCollectionConfigurationRemote(int id, String name) implements
    StorageCollectionConfiguration {

  @Override
  public String getLocation() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getDataSegmentId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getBinaryVersion() {
    throw new UnsupportedOperationException();
  }
}
