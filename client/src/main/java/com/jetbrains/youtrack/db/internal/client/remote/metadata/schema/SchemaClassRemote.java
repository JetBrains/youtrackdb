package com.jetbrains.youtrack.db.internal.client.remote.metadata.schema;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrack.db.internal.core.index.IndexException;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaPropertyImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerJackson;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SchemaClassRemote extends SchemaClassImpl {

  protected SchemaClassRemote(SchemaShared iOwner, String iName) {
    super(iOwner, iName);
  }

  @Override
  protected SchemaPropertyImpl addProperty(
      DatabaseSessionInternal session, final String propertyName,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType,
      final SchemaClassImpl linkedClass,
      final boolean unsafe) {
    if (type == null) {
      throw new SchemaException(session, "Property type not defined.");
    }

    if (propertyName == null || propertyName.isEmpty()) {
      throw new SchemaException(session, "Property name is null or empty");
    }

    validatePropertyName(propertyName);
    if (session.getTransactionInternal().isActive()) {
      throw new SchemaException(session,
          "Cannot create property '" + propertyName + "' inside a transaction");
    }

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    if (linkedType != null) {
      SchemaPropertyImpl.checkLinkTypeSupport(type);
    }

    if (linkedClass != null) {
      SchemaPropertyImpl.checkSupportLinkedClass(type);
    }

    acquireSchemaWriteLock(session);
    try {
      final var cmd = new StringBuilder("create property ");
      // CLASS.PROPERTY NAME
      cmd.append('`');
      cmd.append(name);
      cmd.append('`');
      cmd.append('.');
      cmd.append('`');
      cmd.append(propertyName);
      cmd.append('`');

      // TYPE
      cmd.append(' ');
      cmd.append(type.getName());

      if (linkedType != null) {
        // TYPE
        cmd.append(' ');
        cmd.append(linkedType.getName());

      } else if (linkedClass != null) {
        // TYPE
        cmd.append(' ');
        cmd.append('`');
        cmd.append(linkedClass.getName(session));
        cmd.append('`');
      }

      if (unsafe) {
        cmd.append(" unsafe ");
      }

      session.execute(cmd.toString()).close();
      getOwner().reload(session);

      return getProperty(session, propertyName);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }


  public void setCustom(DatabaseSessionInternal session, final String name, final String value) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd = String.format("alter class `%s` custom %s = ?", getName(session), name);
      session.execute(cmd, value).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void clearCustom(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd = String.format("alter class `%s` custom clear", getName(session));
      session.execute(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setSuperClasses(DatabaseSessionInternal session,
      final List<SchemaClassImpl> classes) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    if (classes != null) {
      List<SchemaClassImpl> toCheck = new ArrayList<>(classes);
      toCheck.add(this);
      checkParametersConflict(session, toCheck);
    }
    acquireSchemaWriteLock(session);
    try {
      final var sb = new StringBuilder();
      if (classes != null && !classes.isEmpty()) {
        for (var superClass : classes) {
          sb.append('`').append(superClass.getName(session)).append("`,");
        }
        sb.deleteCharAt(sb.length() - 1);
      } else {
        sb.append("null");
      }

      final var cmd = String.format("alter class `%s` superclasses %s", name, sb);
      session.execute(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void addSuperClass(DatabaseSessionInternal session,
      final SchemaClassImpl superClass) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    checkParametersConflict(session, superClass);
    acquireSchemaWriteLock(session);
    try {

      final var cmd =
          String.format(
              "alter class `%s` superclass +`%s`",
              name, superClass.getName(session));
      session.execute(cmd).close();

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void removeSuperClass(DatabaseSessionInternal session, SchemaClassImpl superClass) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(session);
    try {
      final var cmd =
          String.format(
              "alter class `%s` superclass -`%s`",
              name, superClass != null ? superClass.getName(session) : null);
      session.execute(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void createIndex(DatabaseSessionInternal session, String name, String type,
      ProgressListener progressListener, Map<String, Object> metadata, String algorithm,
      String... fields) {
    if (type == null) {
      throw new IllegalArgumentException("Index type is null");
    }

    type = type.toUpperCase(Locale.ENGLISH);

    if (fields.length == 0) {
      throw new IndexException(session, "List of fields to index cannot be empty.");
    }

    final var localName = this.name;

    for (final var fieldToIndex : fields) {
      final var fieldName =
          decodeClassName(IndexDefinitionFactory.extractFieldName(fieldToIndex));

      if (!fieldName.equals("@rid") && !existsProperty(session, fieldName)) {
        throw new IndexException(session,
            "Index with name '"
                + name
                + "' cannot be created on class '"
                + localName
                + "' because the field '"
                + fieldName
                + "' is absent in class definition");
      }
    }

    var queryBuilder = new StringBuilder();
    queryBuilder.append("create index ").append(name).append(" on ").append(localName).append(" (");
    for (var i = 0; i < fields.length - 1; i++) {
      queryBuilder.append(fields[i]).append(", ");
    }

    queryBuilder.append(fields[fields.length - 1]).append(") ");
    queryBuilder.append(type);

    if (algorithm != null) {
      queryBuilder.append(" engine ").append(algorithm);
    }

    if (metadata != null) {
      queryBuilder.append(" metadata ").append(RecordSerializerJackson.mapToJson(metadata));
    }

    session.execute(queryBuilder.toString()).close();
  }

  public void setName(DatabaseSessionInternal session, final String name) {
    if (getName(session).equals(name)) {
      return;
    }
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    final var wrongCharacter = SchemaShared.checkClassNameIfValid(name);
    var oClass = session.getMetadata().getSchema().getClass(name);
    if (oClass != null) {
      var error =
          String.format(
              "Cannot rename class %s to %s. A Class with name %s exists", this.name, name, name);
      throw new SchemaException(session, error);
    }
    //noinspection ConstantValue
    if (wrongCharacter != null) {
      throw new SchemaException(session,
          "Invalid class name found. Character '"
              + wrongCharacter
              + "' cannot be used in class name '"
              + name
              + "'");
    }
    acquireSchemaWriteLock(session);
    try {

      final var cmd = String.format("alter class `%s` name `%s`", this.name, name);
      session.execute(cmd);

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected SchemaPropertyImpl createPropertyInstance() {
    return new SchemaPropertyRemote(this);
  }

  public void setStrictMode(DatabaseSessionInternal session, final boolean isStrict) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(session);
    try {
      final var cmd = String.format("alter class `%s` strict_mode %s", name, isStrict);
      session.execute(cmd);
    } finally {
      releaseSchemaWriteLock(session);
    }

  }

  public void setDescription(DatabaseSessionInternal session, String iDescription) {
    if (iDescription != null) {
      iDescription = iDescription.trim();
      if (iDescription.isEmpty()) {
        iDescription = null;
      }
    }
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd = String.format("alter class `%s` description ?", name);
      session.execute(cmd, iDescription).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void dropProperty(DatabaseSessionInternal session, final String propertyName) {
    if (session.getTransactionInternal().isActive()) {
      throw new IllegalStateException("Cannot drop a property inside a transaction");
    }

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

    acquireSchemaWriteLock(session);
    try {
      if (!properties.containsKey(propertyName)) {
        throw new SchemaException(session,
            "Property '" + propertyName + "' not found in class " + name + "'");
      }

      session.execute("drop property " + name + '.' + propertyName).close();

    } finally {
      releaseSchemaWriteLock(session);
    }
  }


  public void setOverSize(DatabaseSessionInternal session, final float overSize) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(session);
    try {
      // FORMAT FLOAT LOCALE AGNOSTIC
      final var cmd = Float.toString(overSize);
      session.execute(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setAbstract(DatabaseSessionInternal session, boolean isAbstract) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      final var cmd = String.format("alter class `%s` abstract %s", name, isAbstract);
      session.execute(cmd).close();
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void removeBaseClassInternal(DatabaseSessionInternal session,
      final SchemaClassImpl baseClass) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      if (subclasses == null) {
        return;
      }

      if (subclasses.remove(baseClass)) {
        removePolymorphicClusterIds(session, baseClass);
      }

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setSuperClassesInternal(DatabaseSessionInternal session,
      final List<SchemaClassImpl> classes) {
    List<SchemaClassImpl> newSuperClasses = new ArrayList<SchemaClassImpl>();
    SchemaClassImpl cls;
    for (var superClass : classes) {
      cls = superClass;

      if (newSuperClasses.contains(cls)) {
        throw new SchemaException(session, "Duplicated superclass '" + cls.getName(session) + "'");
      }

      newSuperClasses.add(cls);
    }

    List<SchemaClassImpl> toAddList = new ArrayList<SchemaClassImpl>(newSuperClasses);
    toAddList.removeAll(superClasses);
    List<SchemaClassImpl> toRemoveList = new ArrayList<SchemaClassImpl>(superClasses);
    toRemoveList.removeAll(newSuperClasses);

    for (var toRemove : toRemoveList) {
      toRemove.removeBaseClassInternal(session, this);
    }
    for (var addTo : toAddList) {
      addTo.addBaseClass(session, this);
    }
    superClasses.clear();
    superClasses.addAll(newSuperClasses);
  }

  protected void addClusterIdToIndexes(DatabaseSessionInternal session, int iId) {
  }
}
