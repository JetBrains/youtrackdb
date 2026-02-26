package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface SecurityInternal {

  boolean isAllowed(
      DatabaseSessionEmbedded session, Set<Identifiable> iAllowAll,
      Set<Identifiable> iAllowOperation);

  SecurityUser authenticate(DatabaseSessionEmbedded session, String iUsername,
      String iUserPassword);

  SecurityUserImpl createUser(
      DatabaseSessionEmbedded session, String iUserName, String iUserPassword, String[] iRoles);

  SecurityUserImpl createUser(
      DatabaseSessionEmbedded session, String iUserName, String iUserPassword, Role[] iRoles);

  SecurityUser authenticate(DatabaseSessionEmbedded session, Token authToken);

  Role createRole(
      DatabaseSessionEmbedded session,
      String iRoleName,
      Role iParent);

  Role createRole(
      DatabaseSessionEmbedded session, String iRoleName);

  SecurityUser getUser(DatabaseSessionEmbedded session, String iUserName);

  SecurityUserImpl getUser(DatabaseSessionEmbedded session, RID userId);

  Role getRole(DatabaseSessionEmbedded session, String iRoleName);

  Role getRole(DatabaseSessionEmbedded session, Identifiable iRoleRid);

  List<EntityImpl> getAllUsers(DatabaseSessionEmbedded session);

  List<EntityImpl> getAllRoles(DatabaseSessionEmbedded session);

  Map<String, ? extends SecurityPolicy> getSecurityPolicies(DatabaseSessionEmbedded session,
      SecurityRole role);

  /**
   * Returns the security policy policy assigned to a role for a specific resource (not recursive on
   * superclasses, nor on role hierarchy)
   *
   * @param session  an active DB session
   * @param role     the role
   * @param resource the string representation of the security resource, eg.
   *                 "database.class.Person"
   * @return the security policy assigned to the role for the given resource, or null if none
   */
  SecurityPolicy getSecurityPolicy(DatabaseSessionEmbedded session, SecurityRole role,
      String resource);

  /**
   * Sets a security policy for a specific resource on a role
   *
   * @param session  a valid db session to perform the operation (that has permissions to do it)
   * @param role     The role
   * @param resource the string representation of the security resource, eg.
   *                 "database.class.Person"
   * @param policy   The security policy
   */
  void setSecurityPolicy(
      DatabaseSessionEmbedded session, SecurityRole role, String resource,
      SecurityPolicyImpl policy);

  /**
   * creates and saves an empty security policy
   *
   * @param session the session to a DB where the policy has to be created
   * @param name    the policy name
   * @return the newly created empty security policy
   */
  SecurityPolicyImpl createSecurityPolicy(DatabaseSessionEmbedded session, String name);

  SecurityPolicyImpl getSecurityPolicy(DatabaseSessionEmbedded session, String name);

  void saveSecurityPolicy(DatabaseSessionEmbedded session, SecurityPolicyImpl policy);

  void deleteSecurityPolicy(DatabaseSessionEmbedded session, String name);

  /**
   * Removes security policy bound to a role for a specific resource
   *
   * @param session  A valid db session to perform the operation
   * @param role     the role
   * @param resource the string representation of the security resource, eg.
   *                 "database.class.Person"
   */
  void removeSecurityPolicy(DatabaseSessionEmbedded session, Role role, String resource);

  boolean dropUser(DatabaseSessionEmbedded session, String iUserName);

  boolean dropRole(DatabaseSessionEmbedded session, String iRoleName);

  long getVersion(DatabaseSessionEmbedded session);

  void incrementVersion(DatabaseSessionEmbedded session);

  SecurityUserImpl create(DatabaseSessionEmbedded session);

  void load(DatabaseSessionEmbedded session);

  void close();

  /**
   * For property-level security. Returns the list of the properties that are hidden (ie. not
   * allowed to be read) for current session, regarding a specific entity
   *
   * @param session the db session
   * @param entity  the entity to filter
   * @return the list of the properties that are hidden (ie. not allowed to be read) on current
   * entity for current session
   */
  Set<String> getFilteredProperties(DatabaseSessionEmbedded session, EntityImpl entity);

  /**
   * For property-level security
   *
   * @param entity       current entity to check for proeprty-level security
   * @param propertyName the property to check for write access
   */
  boolean isAllowedWrite(DatabaseSessionEmbedded session, EntityImpl entity,
      String propertyName);

  boolean canCreate(DatabaseSessionEmbedded session, DBRecord record);

  boolean canRead(DatabaseSessionEmbedded session, DBRecord record);

  boolean canUpdate(DatabaseSessionEmbedded session, DBRecord record);

  boolean canDelete(DatabaseSessionEmbedded session, DBRecord record);

  boolean canExecute(DatabaseSessionEmbedded session, Function function);

  /**
   * checks if for current session a resource is restricted by security resources (ie. READ policies
   * exist, with predicate different from "TRUE", to access the given resource
   *
   * @param session  The session to check for the existece of policies
   * @param resource a resource string, eg. "database.class.Person"
   * @return true if a restriction of any type exists for this session and this resource. False
   * otherwise
   */
  boolean isReadRestrictedBySecurityPolicy(DatabaseSessionEmbedded session, String resource);

  /**
   * Returns the list of all the filtered properties (for any role defined in the db)
   */
  Set<SecurityResourceProperty> getAllFilteredProperties(DatabaseSessionEmbedded database);

  SecurityUser securityAuthenticate(DatabaseSessionEmbedded session, String userName,
      String password);

  SecurityUser securityAuthenticate(
      DatabaseSessionEmbedded session, AuthenticationInfo authenticationInfo);
}
