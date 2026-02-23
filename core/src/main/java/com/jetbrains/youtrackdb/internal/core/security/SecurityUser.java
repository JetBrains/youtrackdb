package com.jetbrains.youtrackdb.internal.core.security;


import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityRole;
import java.io.Serializable;
import java.util.Set;

/**
 * Defines a database security user with authentication credentials and role assignments.
 *
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
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric,
      String resourceSpecific,
      final int iOperation);

  SecurityRole checkIfAllowed(
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric,
      String resourceSpecific,
      final int iOperation);

  boolean isRuleDefined(DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric,
      String resourceSpecific);

  @Deprecated
  SecurityRole allow(DatabaseSessionEmbedded session, final String iResource,
      final int iOperation);

  @Deprecated
  SecurityRole checkIfAllowed(DatabaseSessionEmbedded session, final String iResource,
      final int iOperation);

  @Deprecated
  boolean isRuleDefined(DatabaseSessionEmbedded session, final String iResource);

  boolean checkPassword(DatabaseSessionEmbedded session, final String iPassword);

  String getName(DatabaseSessionEmbedded session);

  SecurityUser setName(DatabaseSessionEmbedded session, final String iName);

  String getPassword(DatabaseSessionEmbedded session);

  SecurityUser setPassword(DatabaseSessionEmbedded session, final String iPassword);

  SecurityUser.STATUSES getAccountStatus(DatabaseSessionEmbedded session);

  void setAccountStatus(DatabaseSessionEmbedded session, STATUSES accountStatus);

  Set<? extends SecurityRole> getRoles();

  SecurityUser addRole(DatabaseSessionEmbedded session, final String iRole);

  SecurityUser addRole(DatabaseSessionEmbedded session, final SecurityRole iRole);

  boolean removeRole(DatabaseSessionEmbedded session, final String iRoleName);

  boolean hasRole(DatabaseSessionEmbedded session, final String iRoleName,
      final boolean iIncludeInherited);

  Identifiable getIdentity();

  String getUserType();
}
