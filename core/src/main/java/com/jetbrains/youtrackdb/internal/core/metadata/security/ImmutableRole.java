package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * An immutable snapshot of a security role and its associated rules and policies.
 *
 * @since 03/11/14
 */
public class ImmutableRole implements SecurityRole {

  private final SecurityRole parentRole;

  private final Map<Rule.ResourceGeneric, Rule> rules =
      new HashMap<Rule.ResourceGeneric, Rule>();
  private final String name;
  private final RID rid;
  private final Map<String, SecurityPolicy> policies;

  public ImmutableRole(DatabaseSessionEmbedded session, SecurityRole role) {
    if (role.getParentRole() == null) {
      this.parentRole = null;
    } else {
      this.parentRole = new ImmutableRole(session, role.getParentRole());
    }

    this.name = role.getName(session);
    this.rid = role.getIdentity().getIdentity();

    for (var rule : role.getRuleSet()) {
      rules.put(rule.getResourceGeneric(), rule);
    }
    var policies = role.getPolicies(session);
    if (policies != null) {
      Map<String, SecurityPolicy> result = new HashMap<>();
      policies
          .forEach((key, value) -> result.put(key, new ImmutableSecurityPolicy(value)));
      this.policies = result;
    } else {
      this.policies = null;
    }
  }

  public ImmutableRole(
      ImmutableRole parent,
      String name,
      Map<ResourceGeneric, Rule> rules,
      Map<String, SecurityPolicy> policies) {
    this.parentRole = parent;
    this.name = name;
    this.rid = new RecordId(-1, -1);
    this.rules.putAll(rules);
    this.policies = policies;
  }

  public boolean allow(
      final Rule.ResourceGeneric resourceGeneric,
      final String resourceSpecific,
      final int iCRUDOperation) {
    var rule = rules.get(resourceGeneric);
    if (rule == null) {
      rule = rules.get(Rule.ResourceGeneric.ALL);
    }

    if (rule != null) {
      final var allowed = rule.isAllowed(resourceSpecific, iCRUDOperation);
      if (allowed != null) {
        return allowed;
      }
    }

    if (parentRole != null)
    // DELEGATE TO THE PARENT ROLE IF ANY
    {
      return parentRole.allow(resourceGeneric, resourceSpecific, iCRUDOperation);
    }

    return false;
  }

  public boolean hasRule(final Rule.ResourceGeneric resourceGeneric, String resourceSpecific) {
    var rule = rules.get(resourceGeneric);

    if (rule == null) {
      return false;
    }

    return resourceSpecific == null || rule.containsSpecificResource(resourceSpecific);
  }

  public SecurityRole addRule(
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric,
      String resourceSpecific,
      final int iOperation) {
    throw new UnsupportedOperationException();
  }

  public SecurityRole grant(
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric,
      String resourceSpecific,
      final int iOperation) {
    throw new UnsupportedOperationException();
  }

  public Role revoke(
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric,
      String resourceSpecific,
      final int iOperation) {
    throw new UnsupportedOperationException();
  }

  @Deprecated
  @Override
  public boolean allow(String iResource, int iCRUDOperation) {
    final var specificResource = Rule.mapLegacyResourceToSpecificResource(iResource);
    final var resourceGeneric =
        Rule.mapLegacyResourceToGenericResource(iResource);

    if (specificResource == null || specificResource.equals("*")) {
      return allow(resourceGeneric, null, iCRUDOperation);
    }

    return allow(resourceGeneric, specificResource, iCRUDOperation);
  }

  @Deprecated
  @Override
  public boolean hasRule(String iResource) {
    final var specificResource = Rule.mapLegacyResourceToSpecificResource(iResource);
    final var resourceGeneric =
        Rule.mapLegacyResourceToGenericResource(iResource);

    if (specificResource == null || specificResource.equals("*")) {
      return hasRule(resourceGeneric, null);
    }

    return hasRule(resourceGeneric, specificResource);
  }

  @Override
  public SecurityRole addRule(DatabaseSessionEmbedded session, String iResource, int iOperation) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SecurityRole grant(DatabaseSessionEmbedded session, String iResource, int iOperation) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SecurityRole revoke(DatabaseSessionEmbedded session, String iResource, int iOperation) {
    throw new UnsupportedOperationException();
  }

  public String getName(DatabaseSessionEmbedded session) {
    return name;
  }

  public SecurityRole getParentRole() {
    return parentRole;
  }

  public void setParentRole(DatabaseSessionEmbedded session, final SecurityRole iParent) {
    throw new UnsupportedOperationException();
  }

  public Set<Rule> getRuleSet() {
    return new HashSet<>(rules.values());
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public Identifiable getIdentity() {
    return rid;
  }

  @Override
  public Map<String, SecurityPolicy> getPolicies(DatabaseSessionEmbedded session) {
    return policies;
  }

  @Nullable
  @Override
  public SecurityPolicy getPolicy(DatabaseSessionEmbedded session, String resource) {
    if (policies == null) {
      return null;
    }
    return policies.get(resource);
  }
}
