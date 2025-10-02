package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexNotUnique;
import com.jetbrains.youtrackdb.internal.core.index.IndexUnique;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity;
import org.jspecify.annotations.NonNull;

public final class IndexFactory {

  private IndexFactory() {
  }

  public static Index newIndexSnapshot(SchemaIndexEntity schemaIndexEntity) {
    var schemaIndex = new SchemaIndexSnapshot(schemaIndexEntity);
    return newIndex(schemaIndexEntity, schemaIndex);
  }

  private static @NonNull Index newIndex(SchemaIndexEntity schemaIndexEntity,
      SchemaIndex schemaIndex) {
    var indexType = schemaIndexEntity.getIndexType();
    var storage = schemaIndexEntity.getSession().getStorage();

    return switch (indexType) {
      case UNIQUE -> new IndexUnique(schemaIndex, storage);
      case NOTUNIQUE -> new IndexNotUnique(schemaIndex, storage);

      case null ->
          throw new DatabaseException(schemaIndexEntity.getSession(), "Index type is not defined");
      default -> throw new DatabaseException(schemaIndexEntity.getSession(),
          "Index type is not supported : " + indexType);
    };
  }

  public static Index newIndexProxy(SchemaIndexEntity schemaIndexEntity) {
    var schemaIndex = new SchemaIndexProxy(schemaIndexEntity);
    return newIndex(schemaIndexEntity, schemaIndex);
  }
}
