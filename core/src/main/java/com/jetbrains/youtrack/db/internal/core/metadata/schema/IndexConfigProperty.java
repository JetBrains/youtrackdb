package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.schema.Collate;
import com.jetbrains.youtrack.db.internal.core.index.PropertyMapIndexDefinition.INDEX_BY;

public class IndexConfigProperty {

  protected final String name;
  protected final PropertyTypeInternal type;
  protected final PropertyTypeInternal linkedType;
  protected final Collate collate;
  protected final INDEX_BY index_by;

  public IndexConfigProperty(
      String name, PropertyTypeInternal type, PropertyTypeInternal linkedType, Collate collate,
      INDEX_BY index_by) {
    this.name = name;
    this.type = type;
    this.linkedType = linkedType;
    this.collate = collate;
    this.index_by = index_by;
  }

  public Collate getCollate() {
    return collate;
  }

  public PropertyTypeInternal getLinkedType() {
    return linkedType;
  }

  public String getName() {
    return name;
  }

  public PropertyTypeInternal getType() {
    return type;
  }

  public INDEX_BY getIndexBy() {
    return index_by;
  }

  public IndexConfigProperty copy() {
    return new IndexConfigProperty(
        this.name, this.type, this.linkedType, this.collate, this.index_by);
  }
}
