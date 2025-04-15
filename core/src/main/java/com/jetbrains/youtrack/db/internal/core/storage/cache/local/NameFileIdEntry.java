package com.jetbrains.youtrack.db.internal.core.storage.cache.local;

final class NameFileIdEntry {

  private final String name;
  private final int fileId;
  private final String fileSystemName;

  public NameFileIdEntry(final String name, final int fileId) {
    this.name = name;
    this.fileId = fileId;
    this.fileSystemName = name;
  }

  public NameFileIdEntry(final String name, final int fileId, final String fileSystemName) {
    this.name = name;
    this.fileId = fileId;
    this.fileSystemName = fileSystemName;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final var that = (NameFileIdEntry) o;

    if (fileId != that.fileId) {
      return false;
    }
    if (!name.equals(that.name)) {
      return false;
    }
    return fileSystemName.equals(that.fileSystemName);
  }

  @Override
  public int hashCode() {
    var result = name.hashCode();
    result = 31 * result + fileId;
    result = 31 * result + fileSystemName.hashCode();
    return result;
  }

  String getName() {
    return name;
  }

  int getFileId() {
    return fileId;
  }

  String getFileSystemName() {
    return fileSystemName;
  }
}
