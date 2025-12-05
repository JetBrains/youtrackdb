package com.jetbrains.youtrackdb.internal.core.security;


import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityRole;
import java.io.Serializable;
import java.util.Set;

/**
 * @since 03/11/14
 */
public interface SecurityUser extends Serializable {
  String SERVER_USER_TYPE = "Server";
  String DATABASE_USER_TYPE = "Database";
  String SECURITY_USER_TYPE = "Security";

  enum STATUSES {
    SUSPENDED,
    ACTIVE
  }

  SecurityRole allow(
      DatabaseSessionInternal session, final ResourceGeneric resourceGeneric,
      String resourceSpecific,
      final int iOperation);

  SecurityRole checkIfAllowed(
      DatabaseSessionInternal session, final ResourceGeneric resourceGeneric,
      String resourceSpecific,
      final int iOperation);

  boolean isRuleDefined(DatabaseSessionInternal session, final ResourceGeneric resourceGeneric,
      String resourceSpecific);

  @Deprecated
  SecurityRole allow(DatabaseSessionInternal session, final String iResource,
      final int iOperation);

  @Deprecated
  SecurityRole checkIfAllowed(DatabaseSessionInternal session, final String iResource,
      final int iOperation);

  @Deprecated
  boolean isRuleDefined(DatabaseSessionInternal session, final String iResource);

  boolean checkPassword(DatabaseSessionInternal session, final String iPassword);

  String getName(DatabaseSessionInternal session);

  SecurityUser setName(DatabaseSessionInternal session, final String iName);

  String getPassword(DatabaseSessionInternal session);

  SecurityUser setPassword(DatabaseSessionInternal session, final String iPassword);

  SecurityUser.STATUSES getAccountStatus(DatabaseSessionInternal session);

  void setAccountStatus(DatabaseSessionInternal session, STATUSES accountStatus);

  Set<? extends SecurityRole> getRoles();

  SecurityUser addRole(DatabaseSessionInternal session, final String iRole);

  SecurityUser addRole(DatabaseSessionInternal session, final SecurityRole iRole);

  boolean removeRole(DatabaseSessionInternal session, final String iRoleName);

  boolean hasRole(DatabaseSessionInternal session, final String iRoleName,
      final boolean iIncludeInherited);

  Identifiable getIdentity();

  String getUserType();
}
