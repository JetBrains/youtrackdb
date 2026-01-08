package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenHeader;

/**
 *
 */
public interface Token {

  TokenHeader getHeader();

  boolean getIsVerified();

  void setIsVerified(boolean verified);

  boolean getIsValid();

  void setIsValid(boolean valid);

  String getUserName();

  SecurityUserImpl getUser(DatabaseSessionInternal session);

  String getDatabaseName();

  String getDatabaseType();

  RID getUserId();

  long getExpiry();

  void setExpiry(long expiry);

  boolean isNowValid();

  boolean isCloseToExpire();
}
