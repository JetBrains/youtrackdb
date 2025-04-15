package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
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

  protected SchemaPropertyEmbedded(SchemaClassImpl oClassImpl, GlobalProperty global) {
    super(oClassImpl, global);
  }

  public void setType(DatabaseSessionInternal session, final PropertyTypeInternal type) {
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
  protected void setTypeInternal(DatabaseSessionInternal session,
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

  public void setName(DatabaseSessionInternal session, final String name) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setNameInternal(session, name);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setNameInternal(DatabaseSessionInternal session, final String name) {
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
  public void setDescription(DatabaseSessionInternal session,
      final String iDescription) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setDescriptionInternal(session, iDescription);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setDescriptionInternal(DatabaseSessionInternal session,
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

  public void setCollate(DatabaseSessionInternal session, String collate) {
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

  protected void setCollateInternal(DatabaseSessionInternal session, String iCollate) {
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

          final var fields = definition.getFields();
          if (fields.contains(getName(session))) {
            indexesToRecreate.add(index);
          }
        }

        if (!indexesToRecreate.isEmpty()) {
          LogManager.instance()
              .info(
                  this,
                  "Collate value was changed, following indexes will be rebuilt %s",
                  indexesToRecreate);

          final var indexManager = ((DatabaseSessionEmbedded) session).getSharedContext()
              .getIndexManager();
          for (var indexToRecreate : indexesToRecreate) {
            final var indexMetadata = session.computeInTxInternal(transaction ->
                indexToRecreate
                    .loadMetadata(transaction, indexToRecreate.getConfiguration(session)));

            final var fields = indexMetadata.getIndexDefinition().getFields();
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

  public void clearCustom(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      clearCustomInternal(session);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void clearCustomInternal(DatabaseSessionInternal session) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      customFields = null;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setCustom(DatabaseSessionInternal session, final String name,
      final String value) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setCustomInternal(session, name, value);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setCustomInternal(DatabaseSessionInternal session, final String iName,
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

  public void setRegexp(DatabaseSessionInternal session, final String regexp) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setRegexpInternal(session, regexp);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setRegexpInternal(DatabaseSessionInternal session, final String regexp) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      this.regexp = regexp;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setLinkedClass(DatabaseSessionInternal session,
      final SchemaClassImpl linkedClass) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    checkSupportLinkedClass(PropertyTypeInternal.convertFromPublicType(getType(session)));

    acquireSchemaWriteLock(session);
    try {
      setLinkedClassInternal(session, linkedClass);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setLinkedClassInternal(DatabaseSessionInternal session,
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

  public void setLinkedType(DatabaseSessionInternal session,
      final PropertyTypeInternal linkedType) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    checkLinkTypeSupport(PropertyTypeInternal.convertFromPublicType(getType(session)));

    acquireSchemaWriteLock(session);
    try {
      setLinkedTypeInternal(session, linkedType);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setLinkedTypeInternal(DatabaseSessionInternal session,
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

  public void setNotNull(DatabaseSessionInternal session, final boolean isNotNull) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setNotNullInternal(session, isNotNull);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setNotNullInternal(DatabaseSessionInternal session, final boolean isNotNull) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      notNull = isNotNull;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setDefaultValue(DatabaseSessionInternal session,
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

  protected void setDefaultValueInternal(DatabaseSessionInternal session,
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

  public void setMax(DatabaseSessionInternal session, final String max) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    checkCorrectLimitValue(session, max);

    acquireSchemaWriteLock(session);
    try {
      setMaxInternal(session, max);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  private void checkCorrectLimitValue(DatabaseSessionInternal session, final String value) {
    if (value != null) {
      if (this.getType(session).equals(PropertyType.STRING)
          || this.getType(session).equals(PropertyType.LINKBAG)
          || this.getType(session).equals(PropertyType.BINARY)
          || this.getType(session).equals(PropertyType.EMBEDDEDLIST)
          || this.getType(session).equals(PropertyType.EMBEDDEDSET)
          || this.getType(session).equals(PropertyType.LINKLIST)
          || this.getType(session).equals(PropertyType.LINKSET)
          || this.getType(session).equals(PropertyType.LINKBAG)
          || this.getType(session).equals(PropertyType.EMBEDDEDMAP)
          || this.getType(session).equals(PropertyType.LINKMAP)) {
        PropertyTypeInternal.convert(session, value, Integer.class);
      } else if (this.getType(session).equals(PropertyType.DATE)
          || this.getType(session).equals(PropertyType.BYTE)
          || this.getType(session).equals(PropertyType.SHORT)
          || this.getType(session).equals(PropertyType.INTEGER)
          || this.getType(session).equals(PropertyType.LONG)
          || this.getType(session).equals(PropertyType.FLOAT)
          || this.getType(session).equals(PropertyType.DOUBLE)
          || this.getType(session).equals(PropertyType.DECIMAL)
          || this.getType(session).equals(PropertyType.DATETIME)) {
        PropertyTypeInternal.convert(session, value,
            PropertyTypeInternal.convertFromPublicType(this.getType(session)).getDefaultJavaType());
      }
    }
  }

  protected void setMaxInternal(DatabaseSessionInternal sesisson, final String max) {
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

  public void setMin(DatabaseSessionInternal session, final String min) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    checkCorrectLimitValue(session, min);

    acquireSchemaWriteLock(session);
    try {
      setMinInternal(session, min);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setMinInternal(DatabaseSessionInternal session, final String min) {
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

  public void setReadonly(DatabaseSessionInternal session, final boolean isReadonly) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setReadonlyInternal(session, isReadonly);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setReadonlyInternal(DatabaseSessionInternal session, final boolean isReadonly) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      this.readonly = isReadonly;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setMandatory(DatabaseSessionInternal session,
      final boolean isMandatory) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setMandatoryInternal(session, isMandatory);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setMandatoryInternal(DatabaseSessionInternal session,
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
