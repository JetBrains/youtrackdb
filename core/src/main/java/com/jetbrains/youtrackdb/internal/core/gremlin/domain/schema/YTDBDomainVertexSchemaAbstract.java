package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.YTDBDomainVertexAbstract;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaClassEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaPropertyEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Iterator;

public abstract class YTDBDomainVertexSchemaAbstract<T extends EntityImpl> extends
    YTDBDomainVertexAbstract<T> {

  public YTDBDomainVertexSchemaAbstract(
      YTDBGraphInternal graph,
      Identifiable identifiable) {
    super(graph, identifiable);
  }

  protected T entityReadPreprocessing() {
    var tx = graph.tx();
    tx.readWrite();

    var session = tx.getDatabaseSession();
    session.checkSecurity(ResourceGeneric.SCHEMA, Role.PERMISSION_READ);
    return getRawEntity();
  }

  protected T entityWritePreprocessing() {
    var tx = graph.tx();
    tx.readWrite();

    var session = tx.getDatabaseSession();
    session.checkSecurity(ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    return getRawEntity();
  }


  protected Iterator<YTDBSchemaClass> mapToDomainClassIterator(
      Iterator<SchemaClassEntity> schemaClassIterator) {
    return YTDBIteratorUtils.map(schemaClassIterator,
        schemaClass -> new YTDBSchemaClassImpl(graph, schemaClass));
  }

  protected Iterator<YTDBSchemaProperty> mapToDomainPropertyIterator(
      Iterator<SchemaPropertyEntity> schemaPropertyIterator) {
    return YTDBIteratorUtils.map(schemaPropertyIterator,
        schemaProperty -> new YTDBSchemaPropertyImpl(
            graph, schemaProperty));
  }
}
