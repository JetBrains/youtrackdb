package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrackdb.internal.core.security.SecurityManager;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * An immutable snapshot of a security user and its associated roles.
 *
 * @since 03/11/14
 */
public class ImmutableUser implements SecurityUser {

  private final long version;

  private final String name;
  private final String password;

  private final Set<ImmutableRole> roles = new HashSet<ImmutableRole>();

  private final STATUSES status;
  private final RID rid;
  private final String userType;

  public ImmutableUser(DatabaseSessionEmbedded session, long version, SecurityUser user) {
    this.version = version;
    this.name = user.getName(session);
    this.password = user.getPassword(session);
    this.status = user.getAccountStatus(session);
    this.rid = user.getIdentity().getIdentity();
    this.userType = user.getUserType();

    for (var role : user.getRoles()) {
      roles.add(new ImmutableRole(session, role));
    }
  }

  public ImmutableUser(DatabaseSessionEmbedded session, String name, String userType) {
    this(session, name, "", userType, null);
  }

  public ImmutableUser(DatabaseSessionEmbedded session, String name, String password,
      String userType, SecurityRole role) {
    this.version = 0;
    this.name = name;
    this.password = password;
    this.status = STATUSES.ACTIVE;
    this.rid = new RecordId(-1, -1);
    this.userType = userType;
    if (role != null) {
      ImmutableRole immutableRole;
      if (role instanceof ImmutableRole ir) {
        immutableRole = ir;
      } else {
        immutableRole = new ImmutableRole(session, role);
      }
      roles.add(immutableRole);
    }
  }

  @Override
  public SecurityRole allow(
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric,
      final String resourceSpecific,
      final int iOperation) {
    if (roles.isEmpty()) {
      throw new SecurityAccessException(name, "User '" + name + "' has no role defined");
    }

    final var role = checkIfAllowed(session, resourceGeneric, resourceSpecific,
        iOperation);

    if (role == null) {
      throw new SecurityAccessException(
          name,
          "User '"
              + name
              + "' does not have permission to execute the operation '"
              + Role.permissionToString(iOperation)
              + "' against the resource: "
              + resourceGeneric
              + "."
              + resourceSpecific);
    }

    return role;
  }

  @Override
  @Nullable
  public SecurityRole checkIfAllowed(
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric,
      final String resourceSpecific,
      final int iOperation) {
    for (var r : roles) {
      if (r == null) {
        LogManager.instance()
            .warn(
                this,
                "User '%s' has a null role, ignoring it.  Consider fixing this user's roles before"
                    + " continuing",
                name);
      } else if (r.allow(resourceGeneric, resourceSpecific, iOperation)) {
        return r;
      }
    }

    return null;
  }

  @Override
  public boolean isRuleDefined(
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric,
      String resourceSpecific) {
    for (var r : roles) {
      if (r == null) {
        LogManager.instance()
            .warn(
                this,
                "UseOSecurityAuthenticatorr '%s' has a null role, ignoring it.  Consider fixing"
                    + " this user's roles before continuing",
                name);
      } else if (r.hasRule(resourceGeneric, resourceSpecific)) {
        return true;
      }
    }

    return false;
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
    throw new UnsupportedOperationException();
  }

  @Override
  public String getPassword(DatabaseSessionEmbedded session) {
    return password;
  }

  @Override
  public SecurityUserImpl setPassword(DatabaseSessionEmbedded session, final String iPassword) {
    throw new UnsupportedOperationException();
  }

  @Override
  public STATUSES getAccountStatus(DatabaseSessionEmbedded session) {
    return status;
  }

  @Override
  public void setAccountStatus(DatabaseSessionEmbedded session, STATUSES accountStatus) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<ImmutableRole> getRoles() {
    return Collections.unmodifiableSet(roles);
  }

  @Override
  public SecurityUserImpl addRole(DatabaseSessionEmbedded session, final String iRole) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SecurityUserImpl addRole(DatabaseSessionEmbedded session, final SecurityRole iRole) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean removeRole(DatabaseSessionEmbedded session, final String iRoleName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean hasRole(DatabaseSessionEmbedded session, final String iRoleName,
      final boolean iIncludeInherited) {
    for (final SecurityRole role : roles) {
      if (role.getName(session).equals(iRoleName)) {
        return true;
      }

      if (iIncludeInherited) {
        var r = role.getParentRole();
        while (r != null) {
          if (r.getName(session).equals(iRoleName)) {
            return true;
          }
          r = r.getParentRole();
        }
      }
    }

    return false;
  }

  @Override
  public String toString() {
    return name;
  }

  public long getVersion() {
    return version;
  }

  @Override
  public Identifiable getIdentity() {
    return rid;
  }

  @Override
  public String getUserType() {
    return userType;
  }
}
