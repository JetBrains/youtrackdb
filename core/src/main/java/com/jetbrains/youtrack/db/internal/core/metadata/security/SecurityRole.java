package com.jetbrains.youtrack.db.internal.core.metadata.security;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule.ResourceGeneric;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/**
 * @since 03/11/14
 */
public interface SecurityRole extends Serializable {

 boolean allow(
      final Rule.ResourceGeneric resourceGeneric,
      String resourceSpecific,
      final int iCRUDOperation);

  boolean hasRule(final Rule.ResourceGeneric resourceGeneric, String resourceSpecific);

  SecurityRole addRule(
      DatabaseSession session, final ResourceGeneric resourceGeneric, String resourceSpecific,
      final int iOperation);

  SecurityRole grant(
      DatabaseSession session, final ResourceGeneric resourceGeneric, String resourceSpecific,
      final int iOperation);

  SecurityRole revoke(
      DatabaseSession session, final ResourceGeneric resourceGeneric, String resourceSpecific,
      final int iOperation);

  @Deprecated
  boolean allow(final String iResource, final int iCRUDOperation);

  @Deprecated
  boolean hasRule(final String iResource);

  @Deprecated
  SecurityRole addRule(DatabaseSession session, final String iResource, final int iOperation);

  @Deprecated
  SecurityRole grant(DatabaseSession session, final String iResource, final int iOperation);

  @Deprecated
  SecurityRole revoke(DatabaseSession session, final String iResource, final int iOperation);

  String getName(DatabaseSession session);

  SecurityRole getParentRole();

 void setParentRole(DatabaseSession session, final SecurityRole iParent);

  Set<Rule> getRuleSet();

 Identifiable getIdentity();

 Map<String, SecurityPolicy> getPolicies(DatabaseSession session);

  SecurityPolicy getPolicy(DatabaseSession session, String resource);
}
