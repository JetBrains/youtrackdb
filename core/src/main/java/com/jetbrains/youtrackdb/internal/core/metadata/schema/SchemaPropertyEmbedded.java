package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 *
 */
public class SchemaPropertyEmbedded extends SchemaPropertyImpl {

  protected SchemaPropertyEmbedded(SchemaClassImpl owner) {
    super(owner);
  }

  protected SchemaPropertyEmbedded(SchemaClassImpl oClassImpl, GlobalPropertyImpl global) {
    super(oClassImpl, global);
  }

  @Override
  public void setType(DatabaseSessionEmbedded session, final PropertyTypeInternal type) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setTypeInternal(session, type);
    } finally {
      releaseSchemaWriteLock(session);
    }
    owner.fireDatabaseMigration(session, globalRef.getName(),
        PropertyTypeInternal.convertFromPublicType(globalRef.getType()));
  }

  /**
   * Change the type. It checks for compatibility between the change of type.
   */
  protected void setTypeInternal(DatabaseSessionEmbedded session,
      final PropertyTypeInternal iType) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      if (iType == PropertyTypeInternal.convertFromPublicType(globalRef.getType()))
      // NO CHANGES
      {
        return;
      }

      if (!iType.getCastable()
          .contains(PropertyTypeInternal.convertFromPublicType(globalRef.getType()))) {
        throw new IllegalArgumentException(
            "Cannot change property type from " + globalRef.getType() + " to " + iType);
      }

      this.globalRef = owner.owner.findOrCreateGlobalProperty(this.globalRef.getName(), iType);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setName(DatabaseSessionEmbedded session, final String name) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setNameInternal(session, name);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setNameInternal(DatabaseSessionEmbedded session, final String name) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    var oldName = this.globalRef.getName();
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      owner.renameProperty(oldName, name);
      this.globalRef = owner.owner.findOrCreateGlobalProperty(name,
          PropertyTypeInternal.convertFromPublicType(this.globalRef.getType()));
    } finally {
      releaseSchemaWriteLock(session);
    }
    owner.firePropertyNameMigration(session, oldName, name,
        PropertyTypeInternal.convertFromPublicType(this.globalRef.getType()));
  }

  @Override
  public void setDescription(DatabaseSessionEmbedded session,
      final String iDescription) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setDescriptionInternal(session, iDescription);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setDescriptionInternal(DatabaseSessionEmbedded session,
      final String iDescription) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      this.description = iDescription;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setCollate(DatabaseSessionEmbedded session, String collate) {
    if (collate == null) {
      collate = DefaultCollate.NAME;
    }

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setCollateInternal(session, collate);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setCollateInternal(DatabaseSessionEmbedded session, String iCollate) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      final var oldCollate = this.collate;

      if (iCollate == null) {
        iCollate = DefaultCollate.NAME;
      }

      collate = SQLEngine.getCollate(iCollate);

      if ((this.collate != null && !this.collate.equals(oldCollate))
          || (this.collate == null && oldCollate != null)) {
        final var indexes = owner.getClassIndexesInternal(session);
        final List<Index> indexesToRecreate = new ArrayList<>();

        for (var index : indexes) {
          var definition = index.getDefinition();

          final var fields = definition.getProperties();
          if (fields.contains(getName())) {
            indexesToRecreate.add(index);
          }
        }

        if (!indexesToRecreate.isEmpty()) {
          LogManager.instance()
              .info(
                  this,
                  "Collate value was changed, following indexes will be rebuilt %s",
                  indexesToRecreate);

          final var indexManager = session.getSharedContext()
              .getIndexManager();
          for (var indexToRecreate : indexesToRecreate) {
            final var indexMetadata = session.computeInTxInternal(transaction ->
                indexToRecreate
                    .loadMetadata(transaction, indexToRecreate.getConfiguration(session)));

            final var fields = indexMetadata.getIndexDefinition().getProperties();
            final var fieldsToIndex = fields.toArray(new String[0]);

            indexManager.dropIndex(session, indexMetadata.getName());
            owner.createIndex(session,
                indexMetadata.getName(),
                indexMetadata.getType(),
                null,
                indexToRecreate.getMetadata(),
                indexMetadata.getAlgorithm(), fieldsToIndex);
          }
        }
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void clearCustom(DatabaseSessionEmbedded session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      clearCustomInternal(session);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void clearCustomInternal(DatabaseSessionEmbedded session) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      customFields = null;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setCustom(DatabaseSessionEmbedded session, final String name,
      final String value) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setCustomInternal(session, name, value);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setCustomInternal(DatabaseSessionEmbedded session, final String iName,
      final String iValue) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      if (customFields == null) {
        customFields = new HashMap<>();
      }
      if (iValue == null || "null".equalsIgnoreCase(iValue)) {
        customFields.remove(iName);
      } else {
        customFields.put(iName, iValue);
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setRegexp(DatabaseSessionEmbedded session, final String regexp) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setRegexpInternal(session, regexp);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setRegexpInternal(DatabaseSessionEmbedded session, final String regexp) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      this.regexp = regexp;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setLinkedClass(DatabaseSessionEmbedded session,
      final SchemaClassImpl linkedClass) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    checkSupportLinkedClass(PropertyTypeInternal.convertFromPublicType(getType()));

    acquireSchemaWriteLock(session);
    try {
      setLinkedClassInternal(session, linkedClass);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setLinkedClassInternal(DatabaseSessionEmbedded session,
      final SchemaClassImpl iLinkedClass) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      this.linkedClass = iLinkedClass;

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setLinkedType(DatabaseSessionEmbedded session,
      final PropertyTypeInternal linkedType) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    checkLinkTypeSupport(PropertyTypeInternal.convertFromPublicType(getType()));

    acquireSchemaWriteLock(session);
    try {
      setLinkedTypeInternal(session, linkedType);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setLinkedTypeInternal(DatabaseSessionEmbedded session,
      final PropertyTypeInternal iLinkedType) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);
      this.linkedType = iLinkedType;

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setNotNull(DatabaseSessionEmbedded session, final boolean isNotNull) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setNotNullInternal(session, isNotNull);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setNotNullInternal(DatabaseSessionEmbedded session, final boolean isNotNull) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      notNull = isNotNull;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setDefaultValue(DatabaseSessionEmbedded session,
      final String defaultValue) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setDefaultValueInternal(session, defaultValue);
    } catch (Exception e) {
      LogManager.instance().error(this, "Error on setting default value", e);
      throw e;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setDefaultValueInternal(DatabaseSessionEmbedded session,
      final String defaultValue) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      this.defaultValue = defaultValue;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setMax(DatabaseSessionEmbedded session, final String max) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    checkCorrectLimitValue(session, max);

    acquireSchemaWriteLock(session);
    try {
      setMaxInternal(session, max);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  private void checkCorrectLimitValue(DatabaseSessionEmbedded session, final String value) {
    if (value != null) {
      if (this.getType().equals(PropertyType.STRING)
          || this.getType().equals(PropertyType.LINKBAG)
          || this.getType().equals(PropertyType.BINARY)
          || this.getType().equals(PropertyType.EMBEDDEDLIST)
          || this.getType().equals(PropertyType.EMBEDDEDSET)
          || this.getType().equals(PropertyType.LINKLIST)
          || this.getType().equals(PropertyType.LINKSET)
          || this.getType().equals(PropertyType.LINKBAG)
          || this.getType().equals(PropertyType.EMBEDDEDMAP)
          || this.getType().equals(PropertyType.LINKMAP)) {
        PropertyTypeInternal.convert(session, value, Integer.class);
      } else if (this.getType().equals(PropertyType.DATE)
          || this.getType().equals(PropertyType.BYTE)
          || this.getType().equals(PropertyType.SHORT)
          || this.getType().equals(PropertyType.INTEGER)
          || this.getType().equals(PropertyType.LONG)
          || this.getType().equals(PropertyType.FLOAT)
          || this.getType().equals(PropertyType.DOUBLE)
          || this.getType().equals(PropertyType.DECIMAL)
          || this.getType().equals(PropertyType.DATETIME)) {
        PropertyTypeInternal.convert(session, value,
            PropertyTypeInternal.convertFromPublicType(this.getType()).getDefaultJavaType());
      }
    }
  }

  protected void setMaxInternal(DatabaseSessionEmbedded sesisson, final String max) {
    sesisson.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(sesisson);
    try {
      checkEmbedded(sesisson);

      checkForDateFormat(sesisson, max);
      this.max = max;
    } finally {
      releaseSchemaWriteLock(sesisson);
    }
  }

  @Override
  public void setMin(DatabaseSessionEmbedded session, final String min) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    checkCorrectLimitValue(session, min);

    acquireSchemaWriteLock(session);
    try {
      setMinInternal(session, min);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setMinInternal(DatabaseSessionEmbedded session, final String min) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      checkForDateFormat(session, min);
      this.min = min;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setReadonly(DatabaseSessionEmbedded session, final boolean isReadonly) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setReadonlyInternal(session, isReadonly);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setReadonlyInternal(DatabaseSessionEmbedded session, final boolean isReadonly) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      this.readonly = isReadonly;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setMandatory(DatabaseSessionEmbedded session,
      final boolean isMandatory) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setMandatoryInternal(session, isMandatory);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setMandatoryInternal(DatabaseSessionEmbedded session,
      final boolean isMandatory) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);
      this.mandatory = isMandatory;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }
}
