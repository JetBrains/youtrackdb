package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.YTDBDomainVertexAbstract;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaPropertyEntity;

import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Iterator;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

public abstract class YTDBDomainVertexSchemaAbstract<T extends EntityImpl> extends
    YTDBDomainVertexAbstract<T> {

  public YTDBDomainVertexSchemaAbstract(
      YTDBGraphInternal graph,
      Identifiable identifiable) {
    super(graph, identifiable);
  }

  protected T propertyReadPreprocessing() {
    var tx = graph.tx();
    tx.readWrite();

    var session = tx.getDatabaseSession();
    session.checkSecurity(ResourceGeneric.SCHEMA, Role.PERMISSION_READ);
    return getRawEntity();
  }

  protected T propertyWritePreprocessing() {
    var tx = graph.tx();
    tx.readWrite();

    var session = tx.getDatabaseSession();
    session.checkSecurity(ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    return getRawEntity();
  }


  protected Iterator<YTDBSchemaClass> mapToDomainClassIterator(
      Iterator<SchemaClassEntity> schemaClassIterator) {
    return IteratorUtils.map(schemaClassIterator,
        schemaClass -> new YTDBSchemaClassImpl(graph, schemaClass));
  }

  protected Iterator<YTDBSchemaProperty> mapToDomainPropertyIterator(
      Iterator<SchemaPropertyEntity> schemaPropertyIterator) {
    return IteratorUtils.map(schemaPropertyIterator,
        schemaProperty -> new YTDBSchemaPropertyImpl(
            graph, schemaProperty));
  }
}
