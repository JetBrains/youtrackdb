package com.jetbrains.youtrack.db.internal.client.remote.metadata.schema;

import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.common.comparator.CaseInsentiveComparator;
import com.jetbrains.youtrack.db.internal.common.util.Collections;
import com.jetbrains.youtrack.db.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaPropertyImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
 *
 */
public class SchemaPropertyRemote extends SchemaPropertyImpl {

  SchemaPropertyRemote(SchemaClassImpl owner) {
    super(owner);
  }

  public SchemaPropertyRemote(SchemaClassImpl oClassImpl, GlobalProperty global) {
    super(oClassImpl, global);
  }

  public void setType(DatabaseSessionInternal session, final PropertyType type) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format(
              "alter property %s type %s", getFullNameQuoted(session),
              quoteString(type.toString()));
      session.command(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setName(DatabaseSessionInternal session, final String name) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format("alter property %s name %s", getFullNameQuoted(session),
              quoteString(name));
      session.command(cmd).close();

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setDescription(DatabaseSessionInternal session, final String iDescription) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format(
              "alter property %s description %s", getFullNameQuoted(session),
              quoteString(iDescription));
      session.command(cmd).close();

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setCollate(DatabaseSessionInternal session, String collate) {
    if (collate == null) {
      collate = DefaultCollate.NAME;
    }

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format("alter property %s collate %s", getFullNameQuoted(session),
              quoteString(collate));
      session.command(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void clearCustom(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd = String.format("alter property %s custom clear",
          getFullNameQuoted(session));
      session.command(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setCustom(DatabaseSessionInternal session, final String name,
      final String value) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format(
              "alter property %s custom %s=%s", getFullNameQuoted(session), name,
              quoteString(value));
      session.command(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setRegexp(DatabaseSessionInternal session, final String regexp) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format("alter property %s regexp %s", getFullNameQuoted(session),
              quoteString(regexp));
      session.command(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setLinkedClass(DatabaseSessionInternal session,
      final SchemaClassImpl linkedClass) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    checkSupportLinkedClass(getType(session));

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format(
              "alter property %s linkedclass %s",
              getFullNameQuoted(session),
              quoteString(linkedClass.getName(session)));
      session.command(cmd).close();

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setLinkedType(DatabaseSessionInternal session,
      final PropertyType linkedType) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    checkLinkTypeSupport(getType(session));

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format(
              "alter property %s linkedtype %s",
              getFullNameQuoted(session), quoteString(linkedType.toString()));
      session.command(cmd).close();

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setNotNull(DatabaseSessionInternal session, final boolean isNotNull) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format("alter property %s notnull %s", getFullNameQuoted(session),
              isNotNull);
      session.command(cmd).close();

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setDefaultValue(DatabaseSessionInternal session,
      final String defaultValue) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format(
              "alter property %s default %s", getFullNameQuoted(session),
              quoteString(defaultValue));
      session.command(cmd).close();

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setMax(DatabaseSessionInternal session, final String max) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format("alter property %s max %s", getFullNameQuoted(session),
              quoteString(max));
      session.command(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setMin(DatabaseSessionInternal session, final String min) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format("alter property %s min %s", getFullNameQuoted(session),
              quoteString(min));
      session.command(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setReadonly(DatabaseSessionInternal session, final boolean isReadonly) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format("alter property %s readonly %s", getFullNameQuoted(session),
              isReadonly);
      session.command(cmd).close();

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setMandatory(DatabaseSessionInternal session,
      final boolean isMandatory) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format("alter property %s mandatory %s", getFullNameQuoted(session),
              isMandatory);
      session.command(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public String createIndex(DatabaseSessionInternal session, String iType) {
    var indexName = getFullName(session);
    owner.createIndex(session, indexName, iType, globalRef.getName());
    return indexName;
  }

  @Override
  public String createIndex(DatabaseSessionInternal session, INDEX_TYPE iType) {
    return createIndex(session, iType.toString());
  }

  @Override
  public String createIndex(DatabaseSessionInternal session, String iType,
      Map<String, Object> metadata) {
    var indexName = getFullName(session);
    owner.createIndex(session,
        indexName, iType, null, metadata, new String[]{globalRef.getName()});
    return indexName;
  }

  @Override
  public String createIndex(DatabaseSessionInternal session, INDEX_TYPE iType,
      Map<String, Object> metadata) {
    return createIndex(session, iType.name(), metadata);
  }

  @Override
  public void dropIndexes(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

    final var indexManager = session.getMetadata().getIndexManagerInternal();

    final var relatedIndexes = new ArrayList<Index>();
    for (final var index : indexManager.getClassIndexes(session, owner.getName(session))) {
      final var definition = index.getDefinition();

      if (Collections.indexOf(
          definition.getFields(), globalRef.getName(), new CaseInsentiveComparator())
          > -1) {
        if (definition instanceof PropertyIndexDefinition) {
          relatedIndexes.add(index);
        } else {
          throw new IllegalArgumentException(
              "This operation applicable only for property indexes. "
                  + index.getName()
                  + " is "
                  + index.getDefinition());
        }
      }
    }

    for (final var index : relatedIndexes) {
      session.getMetadata().getIndexManagerInternal().dropIndex(session, index.getName());
    }
  }

  @Override
  public Collection<String> getAllIndexes(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException("Not supported in remote environment");
  }

  @Override
  public Collection<Index> getAllIndexesInternal(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException("Not supported in remote environment");
  }

  @Override
  public boolean isIndexed(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException("Not supported in remote environment");
  }
}
