package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import javax.annotation.Nonnull;

public interface SecurityPolicy {

  String CLASS_NAME = "OSecurityPolicy";

  enum Scope {
    CREATE,
    READ,
    BEFORE_UPDATE,
    AFTER_UPDATE,
    DELETE,
    EXECUTE
  }

  RID getIdentity();

  String getName();

  boolean isActive();

  String getCreateRule();

  String getReadRule();

  String getBeforeUpdateRule();

  String getAfterUpdateRule();

  String getDeleteRule();

  String getExecuteRule();

  default String get(Scope scope, @Nonnull DatabaseSessionInternal session) {
    return switch (scope) {
      case CREATE -> getCreateRule();
      case READ -> getReadRule();
      case BEFORE_UPDATE -> getBeforeUpdateRule();
      case AFTER_UPDATE -> getAfterUpdateRule();
      case DELETE -> getDeleteRule();
      case EXECUTE -> getExecuteRule();
    };
  }
}
