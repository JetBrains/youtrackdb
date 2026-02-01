package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.type.IdentityWrapper;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SecurityPolicyImpl extends IdentityWrapper implements SecurityPolicy {

  private String name;
  private boolean active;
  private String createRule;
  private String readRule;
  private String beforeUpdateRule;
  private String afterUpdateRule;
  private String deleteRule;
  private String executeRule;

  public SecurityPolicyImpl(EntityImpl entity) {
    super(entity);

    this.name = entity.getString("name");
    this.active = entity.getBoolean("active") != null && entity.getBoolean("active");
    this.createRule = entity.getString("create");
    this.readRule = entity.getString("read");
    this.beforeUpdateRule = entity.getString("beforeUpdate");
    this.afterUpdateRule = entity.getString("afterUpdate");
    this.deleteRule = entity.getString("delete");
    this.executeRule = entity.getString("execute");
  }

  @Override
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  @Override
  public boolean isActive() {
    return active;
  }

  public void setActive(Boolean active) {
    this.active = active;
  }

  @Override
  @Nullable
  public String getCreateRule() {
    return createRule;
  }

  public void setCreateRule(String rule)
      throws IllegalArgumentException {
    validatePredicate(rule);
    this.createRule = rule;
  }

  @Override
  @Nullable
  public String getReadRule() {
    return readRule;
  }

  public void setReadRule(String rule)
      throws IllegalArgumentException {
    validatePredicate(rule);
    this.readRule = rule;
  }

  @Override
  @Nullable
  public String getBeforeUpdateRule() {
    return beforeUpdateRule;
  }

  public void setBeforeUpdateRule(String rule)
      throws IllegalArgumentException {
    validatePredicate(rule);
    this.beforeUpdateRule = rule;
  }

  @Override
  @Nullable
  public String getAfterUpdateRule() {
    return afterUpdateRule;
  }

  public void setAfterUpdateRule(String rule)
      throws IllegalArgumentException {
    validatePredicate(rule);
    this.afterUpdateRule = rule;
  }

  @Override
  @Nullable
  public String getDeleteRule() {
    return deleteRule;
  }

  public void setDeleteRule(String rule)
      throws IllegalArgumentException {
    validatePredicate(rule);
    this.deleteRule = rule;
  }

  @Override
  @Nullable
  public String getExecuteRule() {
    return executeRule;
  }

  public void setExecuteRule(String rule)
      throws IllegalArgumentException {
    validatePredicate(rule);
    this.executeRule = rule;
  }

  protected static void validatePredicate(String predicate) throws IllegalArgumentException {
    if (predicate == null || predicate.trim().isEmpty()) {
      return;
    }
    try {
      SQLEngine.parsePredicate(predicate);
    } catch (CommandSQLParsingException ex) {
      throw new IllegalArgumentException("Invalid predicate: " + predicate);
    }
  }

  @Override
  protected void toEntity(@Nonnull DatabaseSessionInternal db, @Nonnull EntityImpl entity) {
    entity.setString("name", name);
    entity.setBoolean("active", active);
    entity.setString("create", createRule);
    entity.setString("read", readRule);
    entity.setString("beforeUpdate", beforeUpdateRule);
    entity.setString("afterUpdate", afterUpdateRule);
    entity.setString("delete", deleteRule);
    entity.setString("execute", executeRule);
  }
}
