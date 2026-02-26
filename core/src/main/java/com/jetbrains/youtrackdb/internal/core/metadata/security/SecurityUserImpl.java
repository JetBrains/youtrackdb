/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.metadata.security;


import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.security.SecurityManager;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import com.jetbrains.youtrackdb.internal.core.type.IdentityWrapper;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Contains the user settings about security and permissions. Each user has one or more roles
 * associated. Roles contains the permission rules that define what the user can access and what he
 * cannot.
 *
 * @see Role
 */
public class SecurityUserImpl extends IdentityWrapper implements SecurityUser {

  public static final String ADMIN = "admin";
  public static final String CLASS_NAME = "OUser";
  public static final String PASSWORD_PROPERTY = "password";
  public static final String DATABASE_USER = "Database";
  public static final String ROLES_PROPERTY = "roles";
  public static final String NAME_PROPERTY = "name";
  public static final String STATUS_PROPERTY = "status";

  private volatile String name;
  private volatile String password;
  private volatile STATUSES status;
  protected final Set<Role> roles = ConcurrentHashMap.newKeySet();

  public SecurityUserImpl(DatabaseSessionEmbedded session, final String userName) {
    super(session, CLASS_NAME);

    this.name = userName;
    this.status = STATUSES.ACTIVE;
  }

  public SecurityUserImpl(DatabaseSessionEmbedded db, String userName,
      final String userPassword) {
    super(db, CLASS_NAME);

    this.name = userName;
    this.password = userPassword;
    this.status = STATUSES.ACTIVE;

    save(db);
  }

  /**
   * Create the user by reading the source entity.
   */
  public SecurityUserImpl(DatabaseSessionEmbedded session, final EntityImpl source) {
    super(source);

    this.name = source.getProperty(NAME_PROPERTY);
    this.password = source.getProperty(PASSWORD_PROPERTY);
    this.status = STATUSES.valueOf(source.getProperty(STATUS_PROPERTY));

    var storedRoles = source.<Set<Identifiable>>getProperty(ROLES_PROPERTY);

    var security = session.getMetadata().getSecurity();
    for (var storeRole : storedRoles) {
      roles.add(security.getRole(storeRole));
    }
  }

  @Override
  protected void toEntity(@Nonnull DatabaseSessionEmbedded session, @Nonnull EntityImpl entity) {
    entity.setProperty(NAME_PROPERTY, name);
    entity.setProperty(PASSWORD_PROPERTY, password);
    entity.setProperty(STATUS_PROPERTY, status.name());

    var storedRoles = entity.getOrCreateLinkSet(ROLES_PROPERTY);
    storedRoles.clear();
    storedRoles.addAll(roles);
  }

  public static String encryptPassword(final String password) {
    return SecurityManager.createHash(
        password,
        GlobalConfiguration.SECURITY_USER_PASSWORD_DEFAULT_ALGORITHM.getValueAsString(),
        true);
  }

  public static void encodePassword(
      DatabaseSessionEmbedded session, final EntityImpl entity) {
    final String name = entity.getProperty(NAME_PROPERTY);
    if (name == null) {
      throw new SecurityException(session.getDatabaseName(), "User name not found");
    }

    final String password = entity.getProperty("password");

    if (password == null) {
      throw new SecurityException(session.getDatabaseName(),
          "User '" + entity.getProperty(NAME_PROPERTY) + "' has no password");
    }
    var security = session.getSharedContext().getYouTrackDB().getSecuritySystem();
    security.validatePassword(name, password);

    if (!(!password.isEmpty() && password.charAt(0) == '{')) {
      entity.setProperty("password", encryptPassword(password));
    }
  }

  /**
   * Checks if the user has the permission to access to the requested resource for the requested
   * operation.
   *
   * @param operation Requested operation
   * @return The role that has granted the permission if any, otherwise a SecurityAccessException
   * exception is raised
   */
  @Override
  public Role allow(
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric,
      String resourceSpecific,
      final int operation) {
    var roles = getRoles();
    if (roles == null || roles.isEmpty()) {
      throw new SecurityAccessException(
          session.getDatabaseName(),
          "User '" + name + "' has no role defined");
    }

    final var role = checkIfAllowed(session, resourceGeneric, resourceSpecific, operation);
    if (role == null) {
      throw new SecurityAccessException(
          session.getDatabaseName(),
          "User '"
              + name
              + "' does not have permission to execute the operation '"
              + Role.permissionToString(operation)
              + "' against the resource: "
              + resourceGeneric
              + "."
              + resourceSpecific);
    }

    return role;
  }

  /**
   * Checks if the user has the permission to access to the requested resource for the requested
   * operation.
   *
   * @param operation Requested operation
   * @return The role that has granted the permission if any, otherwise null
   */
  @Nullable
  @Override
  public Role checkIfAllowed(
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric,
      String resourceSpecific,
      final int operation) {
    var roles = getRoles();

    for (var r : roles) {
      if (r == null) {
        LogManager.instance()
            .warn(
                this,
                "User '%s' has a null role, ignoring it. Consider fixing this user's roles before"
                    + " continuing",
                getName(session));
      } else if (r.allow(resourceGeneric, resourceSpecific, operation)) {
        return r;
      }
    }

    return null;
  }

  @Override
  @Deprecated
  public SecurityRole allow(DatabaseSessionEmbedded session, String iResource, int iOperation) {
    final var resourceSpecific = Rule.mapLegacyResourceToSpecificResource(iResource);
    final var resourceGeneric =
        Rule.mapLegacyResourceToGenericResource(iResource);

    if (resourceSpecific == null || resourceSpecific.equals("*")) {
      return allow(session, resourceGeneric, null, iOperation);
    }

    return allow(session, resourceGeneric, resourceSpecific, iOperation);
  }

  @Override
  @Deprecated
  public SecurityRole checkIfAllowed(DatabaseSessionEmbedded session, String iResource,
      int iOperation) {
    final var resourceSpecific = Rule.mapLegacyResourceToSpecificResource(iResource);
    final var resourceGeneric =
        Rule.mapLegacyResourceToGenericResource(iResource);

    if (resourceSpecific == null || resourceSpecific.equals("*")) {
      return checkIfAllowed(session, resourceGeneric, null, iOperation);
    }

    return checkIfAllowed(session, resourceGeneric, resourceSpecific, iOperation);
  }

  @Override
  @Deprecated
  public boolean isRuleDefined(DatabaseSessionEmbedded session, String iResource) {
    final var resourceSpecific = Rule.mapLegacyResourceToSpecificResource(iResource);
    final var resourceGeneric =
        Rule.mapLegacyResourceToGenericResource(iResource);

    if (resourceSpecific == null || resourceSpecific.equals("*")) {
      return isRuleDefined(session, resourceGeneric, null);
    }

    return isRuleDefined(session, resourceGeneric, resourceSpecific);
  }

  /**
   * Checks if a rule was defined for the user.
   *
   * @return True is a rule is defined, otherwise false
   */
  @Override
  public boolean isRuleDefined(
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric,
      String resourceSpecific) {
    var roles = getRoles();
    for (var r : roles) {
      if (r == null) {
        LogManager.instance()
            .warn(
                this,
                "User '%s' has a null role, bypass it. Consider to fix this user roles before to"
                    + " continue",
                getName(session));
      } else if (r.hasRule(resourceGeneric, resourceSpecific)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public boolean checkPassword(DatabaseSessionEmbedded session, final String iPassword) {
    return SecurityManager.checkPassword(iPassword, password);
  }

  @Override
  public String getName(DatabaseSessionEmbedded session) {
    return name;
  }

  @Override
  public SecurityUserImpl setName(DatabaseSessionEmbedded session, final String iName) {
    this.name = iName;
    return this;
  }

  @Override
  public String getPassword(DatabaseSessionEmbedded session) {
    return password;
  }

  @Override
  public SecurityUserImpl setPassword(DatabaseSessionEmbedded session, final String password) {
    this.password = password;
    return this;
  }

  @Override
  public STATUSES getAccountStatus(DatabaseSessionEmbedded session) {
    return status;
  }

  @Override
  public void setAccountStatus(DatabaseSessionEmbedded session, STATUSES accountStatus) {
    this.status = accountStatus;
  }

  @Override
  public Set<Role> getRoles() {
    return Collections.unmodifiableSet(roles);
  }

  @Override
  public SecurityUserImpl addRole(DatabaseSessionEmbedded session, final String iRole) {
    if (iRole != null) {
      var role = session.getMetadata().getSecurity().getRole(iRole);
      if (role != null) {
        addRole(session, role);
      } else {
        throw new SecurityException(session.getDatabaseName(), "Role '" + iRole + "' not found");
      }
    }
    return this;
  }

  @Override
  public SecurityUserImpl addRole(DatabaseSessionEmbedded session, final SecurityRole role) {
    if (role != null) {
      roles.add((Role) role);
    }

    return this;
  }

  @Override
  public boolean removeRole(DatabaseSessionEmbedded session, final String roleName) {
    return roles.removeIf(role -> role.getName(session).equals(roleName));
  }

  @Override
  public boolean hasRole(DatabaseSessionEmbedded session, final String roleName,
      final boolean includeInherited) {
    for (final var role : roles) {
      if (role.getName(session).equals(roleName)) {
        return true;
      }

      if (includeInherited) {
        var r = role.getParentRole();
        while (r != null) {
          if (r.getName(session).equals(roleName)) {
            return true;
          }
          r = r.getParentRole();
        }
      }
    }

    return false;
  }

  @Override
  public String getUserType() {
    return DATABASE_USER;
  }
}
