package com.jetbrains.youtrackdb.internal.client.remote;

import com.jetbrains.youtrackdb.internal.core.config.StorageCollectionConfiguration;

public class StorageCollectionConfigurationRemote implements StorageCollectionConfiguration {

  private final int id;
  private final String name;

  public StorageCollectionConfigurationRemote(int id, String name) {
    this.id = id;
    this.name = name;
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public String getName() {
    return name;
  }

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
