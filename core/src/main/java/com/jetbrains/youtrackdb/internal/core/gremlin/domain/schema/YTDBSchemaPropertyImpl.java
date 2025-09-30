package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.YTDBDomainVertexAbstract;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBInTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBOutTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBPTokenInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaPropertyEntity;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class YTDBSchemaPropertyImpl extends
    YTDBDomainVertexSchemaAbstract<SchemaPropertyEntity> implements YTDBSchemaProperty {

  public YTDBSchemaPropertyImpl(YTDBGraphInternal graph, Identifiable identifiable) {
    super(graph, identifiable);
  }

  @Override
  public @Nonnull String name() {
    var entity = propertyReadPreprocessing();
    return entity.getName();
  }

  @Override
  public void name(@Nonnull String propertyName) {
    var entity = propertyWritePreprocessing();
    entity.setName(propertyName);
  }

  @Override
  public @Nonnull String fullName() {
    var entity = propertyReadPreprocessing();
    return entity.getFullName();
  }

  @Override
  public @Nonnull PropertyType propertyType() {
    var entity = propertyReadPreprocessing();
    return entity.getPropertyType();
  }

  @Override
  public void propertyType(@Nonnull PropertyType propertyType) {
    var entity = propertyWritePreprocessing();
    entity.setPropertyType(propertyType);
  }

  @Override
  public @Nonnull String type() {
    var entity = propertyReadPreprocessing();
    return entity.getType();
  }

  @Override
  public void type(@Nonnull String propertyType) {
    var entity = propertyWritePreprocessing();
    entity.setType(propertyType);
  }

  @Override
  public YTDBSchemaClass linkedClass() {
    var entity = propertyReadPreprocessing();
    var linkedClass = entity.getLinkedClass();
    return new YTDBSchemaClassImpl(graph, linkedClass);
  }

  @Override
  public void linkedClass(YTDBSchemaClass linkedClass) {
    var entity = propertyWritePreprocessing();
    if (linkedClass == null) {
      entity.setLinkedClass(null);
      return;
    }

    var linkedClassImpl = (YTDBSchemaClassImpl) linkedClass;
    var linkedClassEntity = linkedClassImpl.getRawEntity();
    entity.setLinkedClass(linkedClassEntity);
  }

  @Override
  public PropertyType linkedPropertyType() {
    var entity = propertyReadPreprocessing();
    return entity.getLinkedPropertyType();
  }

  @Override
  public void linkedPropertyType(@Nullable PropertyType linkedPropertyType) {
    var entity = propertyWritePreprocessing();
    entity.setLinkedPropertyType(linkedPropertyType);
  }

  @Override
  public String linkedType() {
    var entity = propertyReadPreprocessing();
    return entity.getLinkedType();
  }

  @Override
  public void linkedType(@Nullable String type) {
    var entity = propertyWritePreprocessing();
    entity.setLinkedType(type);
  }

  @Override
  public boolean notNull() {
    var entity = propertyReadPreprocessing();
    return entity.isNotNull();
  }

  @Override
  public void notNull(boolean notNull) {
    var entity = propertyWritePreprocessing();
    entity.setNotNull(notNull);
  }

  @Override
  public String collateName() {
    var entity = propertyReadPreprocessing();
    return entity.getCollate();
  }

  @Override
  public void collateName(String collateName) {
    var entity = propertyWritePreprocessing();
    entity.setCollateName(collateName);
  }

  @Override
  public boolean mandatory() {
    var entity = propertyReadPreprocessing();
    return entity.isMandatory();
  }

  @Override
  public void mandatory(boolean mandatory) {
    var entity = propertyWritePreprocessing();
    entity.setMandatory(mandatory);
  }

  @Override
  public boolean readonly() {
    var entity = propertyReadPreprocessing();
    return entity.isReadonly();
  }

  @Override
  public void readonly(boolean readonly) {
    var entity = propertyWritePreprocessing();
    entity.setReadonly(readonly);
  }

  @Override
  public String min() {
    var entity = propertyReadPreprocessing();
    return entity.getMin();
  }

  @Override
  public void min(String min) {
    var entity = propertyWritePreprocessing();
    entity.setMin(min);
  }

  @Override
  public String max() {
    var entity = propertyReadPreprocessing();
    return entity.getMax();
  }

  @Override
  public void max(String max) {
    var entity = propertyWritePreprocessing();
    entity.setMax(max);
  }

  @Override
  public String defaultValue() {
    var entity = propertyReadPreprocessing();
    return entity.getDefaultValue();
  }

  @Override
  public void defaultValue(String defaultValue) {
    var entity = propertyWritePreprocessing();
    entity.setDefaultValue(defaultValue);
  }

  @Override
  public String regexp() {
    var entity = propertyReadPreprocessing();
    return entity.getRegexp();
  }


  @Override
  public void regexp(String regexp) {
    var entity = propertyWritePreprocessing();
    entity.setRegexp(regexp);
  }

  @Override
  public String customProperty(@Nonnull String propertyName) {
    var entity = propertyReadPreprocessing();
    return entity.getCustomProperty(propertyName);
  }


  @Override
  public void customProperty(@Nonnull String propertyName, @Nullable String propertyValue) {
    var entity = propertyWritePreprocessing();
    entity.setCustomProperty(propertyName, propertyValue);
  }

  @Override
  public void removeCustomProperty(@Nonnull String propertyName) {
    var entity = propertyWritePreprocessing();
    entity.removeCustomProperty(propertyName);
  }

  @Override
  public void clearCustomProperties() {
    var entity = propertyWritePreprocessing();
    entity.clearCustomProperties();
  }

  @Override
  public Iterator<String> customPropertyNames() {
    var entity = propertyReadPreprocessing();
    return entity.getCustomPropertyNames();
  }

  @Nonnull
  @Override
  public YTDBSchemaClass declaringClass() {
    var entity = propertyReadPreprocessing();
    return new YTDBSchemaClassImpl(graph, entity.getDeclaringClass());
  }

  @Override
  public @Nonnull String description() {
    var entity = propertyReadPreprocessing();
    return entity.getDescription();
  }

  @Override
  public void description(String description) {
    var entity = propertyWritePreprocessing();
    entity.setDescription(description);
  }

  @SuppressWarnings("rawtypes")
  @Override
  public YTDBPTokenInternal[] pTokens() {
    return YTDBSchemaPropertyPTokenInternal.values();
  }

  @SuppressWarnings("rawtypes")
  @Override
  public YTDBOutTokenInternal[] outTokens() {
    return YTDBSchemaPropertyOutTokenInternal.values();
  }

  @Override
  protected YTDBPTokenInternal<YTDBDomainVertexAbstract<?>> pToken(String name) {
    //noinspection unchecked,rawtypes
    return (YTDBPTokenInternal) YTDBSchemaClassPTokenInternal.valueOf(name);
  }

  @SuppressWarnings("rawtypes")
  @Override
  public YTDBInTokenInternal[] inTokens() {
    return YTDBSchemaPropertyInTokenInternal.values();
  }

  @Override
  public YTDBInTokenInternal<YTDBDomainVertexAbstract<?>> inToken(String label) {
    //noinspection unchecked,rawtypes
    return (YTDBInTokenInternal) YTDBSchemaPropertyInTokenInternal.valueOf(label);
  }

  @Override
  public YTDBOutTokenInternal<YTDBDomainVertexAbstract<?>> outToken(String label) {
    //noinspection unchecked,rawtypes
    return (YTDBOutTokenInternal) YTDBSchemaPropertyOutTokenInternal.valueOf(label);
  }

}
