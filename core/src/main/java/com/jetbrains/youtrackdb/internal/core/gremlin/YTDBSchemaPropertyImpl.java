package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBDomainObject;
import com.jetbrains.youtrackdb.api.gremlin.embedded.schema.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.schema.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectPToken;
import com.jetbrains.youtrackdb.api.gremlin.tokens.schema.YTDBSchemaPropertyPToken;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaPropertyImpl;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class YTDBSchemaPropertyImpl implements YTDBSchemaProperty {

  private SchemaPropertyImpl schemaProperty;
  private final @Nonnull YTDBGraphInternal graph;

  private RecordId recordId;
  private @Nonnull String fullName;

  public YTDBSchemaPropertyImpl(@Nonnull SchemaPropertyImpl schemaProperty,
      @Nonnull YTDBGraphInternal graph) {
    this.schemaProperty = schemaProperty;
    this.graph = graph;

    this.fullName = graph.computeSchemaCode(schemaProperty::getFullName);
  }

  @Override
  public @Nonnull String name() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.getName();
    });
  }

  @Override
  public void name(@Nonnull String propertyName) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setName(session, propertyName);
      fullName = schemaProperty.getFullName(session);
    });
  }

  @Override
  public @Nonnull String fullName() {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.getFullName(session);
    });
  }

  @Override
  public @Nonnull PropertyType propertyType() {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.getType();
    });
  }

  @Override
  public void propertyType(@Nonnull PropertyType propertyType) {
    checkIfDeleted();
    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setType(session, PropertyTypeInternal.convertFromPublicType(propertyType));
    });
  }

  @Override
  public @Nonnull String type() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.getType().toString();
    });
  }

  @Override
  public void type(@Nonnull String propertyType) {
    checkIfDeleted();
    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      var type = PropertyTypeInternal.valueOf(propertyType);
      schemaProperty.setType(session, type);
    });
  }

  @Override
  public YTDBSchemaClass linkedClass() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      var linkedClass = schemaProperty.getLinkedClass();
      if (linkedClass == null) {
        return null;
      }

      return new YTDBSchemaClassImpl(linkedClass, graph);
    });
  }

  @Override
  public void linkedClass(YTDBSchemaClass linkedClass) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);

      if (linkedClass == null) {
        schemaProperty.setLinkedClass(session, null);
        return;
      }

      var schemaClassImpl = (YTDBSchemaClassImpl) linkedClass;
      schemaProperty.setLinkedClass(session, schemaClassImpl.getSchemaClass());
    });
  }

  @Override
  public PropertyType linkedPropertyType() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.getLinkedType().getPublicPropertyType();
    });
  }

  @Override
  public void linkedPropertyType(@Nullable PropertyType linkedPropertyType) {
    checkIfDeleted();
    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setLinkedType(session,
          PropertyTypeInternal.convertFromPublicType(linkedPropertyType));
    });
  }

  @Override
  public String linkedType() {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      var linkedType = schemaProperty.getLinkedType();
      if (linkedType == null) {
        return null;
      }

      return linkedType.toString();
    });
  }

  @Override
  public void linkedType(@Nullable String type) {
    checkIfDeleted();
    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      if (type == null) {
        schemaProperty.setLinkedType(session, null);
        return;
      }

      var linkedType = PropertyTypeInternal.valueOf(type);
      schemaProperty.setLinkedType(session, linkedType);
    });
  }

  @Override
  public boolean notNull() {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.isNotNull();
    });
  }

  @Override
  public void notNull(boolean notNull) {
    checkIfDeleted();
    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setNotNull(session, notNull);
    });
  }

  @Override
  public String collateName() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      var collate = schemaProperty.getCollate();
      if (collate == null) {
        return null;
      }

      return collate.getName();
    });
  }

  @Override
  public void collateName(String collateName) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setCollate(session, collateName);
    });
  }

  @Override
  public boolean mandatory() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.isMandatory();
    });
  }

  @Override
  public void mandatory(boolean mandatory) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setMandatory(session, mandatory);
    });
  }

  @Override
  public boolean readonly() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.isReadonly();
    });
  }

  @Override
  public void readonly(boolean readonly) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setReadonly(session, readonly);
    });
  }

  @Override
  public String min() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.getMin();
    });
  }

  @Override
  public void min(String min) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setMin(session, min);
    });
  }

  @Override
  public String max() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.getMax();
    });
  }

  @Override
  public void max(String max) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setMax(session, max);
    });
  }

  @Override
  public String defaultValue() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.getDefaultValue();
    });
  }

  @Override
  public void defaultValue(String defaultValue) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setDefaultValue(session, defaultValue);
    });
  }

  @Override
  public String regexp() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.getRegexp();
    });
  }


  @Override
  public void regexp(String regexp) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setRegexp(session, regexp);
    });
  }

  @Override
  public String customProperty(@Nonnull String propertyName) {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.getCustom(propertyName);
    });
  }


  @Override
  public void customProperty(@Nonnull String propertyName, @Nullable String propertyValue) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setCustom(session, propertyName, propertyValue);
    });
  }

  @Override
  public void removeCustomProperty(@Nonnull String propertyName) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.removeCustom(session, propertyName);
    });
  }

  @Override
  public void clearCustomProperties() {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.clearCustom(session);
    });
  }

  @Override
  public Iterator<String> customPropertyNames() {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.getCustomKeys().iterator();
    });
  }

  @Nonnull
  @Override
  public YTDBSchemaClass ownerClass() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      var ownerClass = schemaProperty.getOwnerClass();
      return new YTDBSchemaClassImpl(ownerClass, graph);
    });
  }

  @Override
  public @Nonnull String description() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      return schemaProperty.getDescription();
    });
  }

  @Override
  public void description(String description) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);
      schemaProperty.setDescription(session, description);
    });
  }

  @SuppressWarnings("rawtypes")
  @Override
  public YTDBDomainObjectPToken[] pTokens() {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);

      var basicPTokens = YTDBSchemaPropertyPToken.values();
      var customPropertyNames = schemaProperty.getCustomKeys();
      if (customPropertyNames.isEmpty()) {
        return basicPTokens;
      }

      var pTokens = new YTDBDomainObjectPToken[basicPTokens.length + customPropertyNames.size()];
      System.arraycopy(basicPTokens, 0, pTokens, 0, basicPTokens.length);

      var i = 0;
      for (var customPropertyName : customPropertyNames) {
        pTokens[basicPTokens.length + i] = new YTDBCustomSchemaPropertyPToken(customPropertyName);
        i++;
      }

      return pTokens;
    });
  }

  @Override
  public YTDBDomainObjectPToken<YTDBDomainObject> pToken(String name) {
    checkIfDeleted();
    //noinspection unchecked,rawtypes
    return (YTDBDomainObjectPToken) graph.computeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaReadPermissions(session);
      try {
        return YTDBSchemaPropertyPToken.valueOf(name);
      } catch (IllegalArgumentException e) {
        return new YTDBCustomSchemaPropertyPToken(name);
      }
    });
  }

  @Override
  public RID id() {
    if (recordId == null) {
      recordId = RecordId.tempRecordId();
    }

    return recordId;
  }

  @Override
  public YTDBGraph graph() {
    return graph;
  }

  @Override
  public void remove() {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      YTDBSchemaClassImpl.checkSchemaUpdatePermissions(session);

      var owner = schemaProperty.getOwnerClass();
      owner.dropProperty(session, schemaProperty.getName());
      schemaProperty = null;
    });
  }

  private void checkIfDeleted() {
    if (schemaProperty == null) {
      throw schemaPropertyWasDeleted();
    }
  }

  private IllegalStateException schemaPropertyWasDeleted() {
    return new IllegalStateException("Schema property " + fullName + " has been deleted.");
  }

}
