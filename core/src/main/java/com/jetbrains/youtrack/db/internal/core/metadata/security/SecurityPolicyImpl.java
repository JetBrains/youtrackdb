package com.jetbrains.youtrack.db.internal.core.metadata.security;

import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
import javax.annotation.Nonnull;

public class SecurityPolicyImpl implements SecurityPolicy {

  private Entity entity;

  public SecurityPolicyImpl(Entity entity) {
    this.entity = entity;
  }

  @Override
  public RID getIdentity() {
    return entity.getIdentity();
  }

  public Entity getEntity(@Nonnull DatabaseSessionInternal session) {
    return entity;
  }

  public void setEntity(Entity entity) {
    this.entity = entity;
  }

  public String getName(@Nonnull DatabaseSessionInternal session) {
    return getEntity(session).getProperty("name");
  }

  public void setName(@Nonnull DatabaseSessionInternal session, String name) {
    getEntity(session).setProperty("name", name);
  }

  public boolean isActive(@Nonnull DatabaseSessionInternal session) {
    return Boolean.TRUE.equals(this.getEntity(session).getProperty("active"));
  }

  public void setActive(@Nonnull DatabaseSessionInternal session, Boolean active) {
    this.getEntity(session).setProperty("active", active);
  }

  public String getCreateRule(@Nonnull DatabaseSessionInternal session) {
    var element = getEntity(session);
    return element == null ? null : element.getProperty("create");
  }

  public void setCreateRule(@Nonnull DatabaseSessionInternal session, String rule)
      throws IllegalArgumentException {
    validatePredicate(rule);
    getEntity(session).setProperty("create", rule);
  }

  public String getReadRule(@Nonnull DatabaseSessionInternal session) {
    var element = getEntity(session);
    return element == null ? null : element.getProperty("read");
  }

  public void setReadRule(@Nonnull DatabaseSessionInternal session, String rule)
      throws IllegalArgumentException {
    validatePredicate(rule);
    getEntity(session).setProperty("read", rule);
  }

  public String getBeforeUpdateRule(@Nonnull DatabaseSessionInternal session) {
    var element = getEntity(session);
    return element == null ? null : element.getProperty("beforeUpdate");
  }

  public void setBeforeUpdateRule(@Nonnull DatabaseSessionInternal session, String rule)
      throws IllegalArgumentException {
    validatePredicate(rule);
    getEntity(session).setProperty("beforeUpdate", rule);
  }

  public String getAfterUpdateRule(@Nonnull DatabaseSessionInternal session) {
    var element = getEntity(session);
    return element == null ? null : element.getProperty("afterUpdate");
  }

  public void setAfterUpdateRule(@Nonnull DatabaseSessionInternal session, String rule)
      throws IllegalArgumentException {
    validatePredicate(rule);
    getEntity(session).setProperty("afterUpdate", rule);
  }

  public String getDeleteRule(@Nonnull DatabaseSessionInternal session) {
    var element = getEntity(session);
    return element == null ? null : element.getProperty("delete");
  }

  public void setDeleteRule(@Nonnull DatabaseSessionInternal session, String rule)
      throws IllegalArgumentException {
    validatePredicate(rule);
    getEntity(session).setProperty("delete", rule);
  }

  public String getExecuteRule(@Nonnull DatabaseSessionInternal session) {
    var element = getEntity(session);
    return element == null ? null : element.getProperty("execute");
  }

  public void setExecuteRule(@Nonnull DatabaseSessionInternal session, String rule)
      throws IllegalArgumentException {
    validatePredicate(rule);
    getEntity(session).setProperty("execute", rule);
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
}
