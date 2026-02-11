package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
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
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric, String resourceSpecific,
      final int iOperation);

  SecurityRole grant(
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric, String resourceSpecific,
      final int iOperation);

  SecurityRole revoke(
      DatabaseSessionEmbedded session, final ResourceGeneric resourceGeneric, String resourceSpecific,
      final int iOperation);

  @Deprecated
  boolean allow(final String iResource, final int iCRUDOperation);

  @Deprecated
  boolean hasRule(final String iResource);

  @Deprecated
  SecurityRole addRule(DatabaseSessionEmbedded session, final String iResource, final int iOperation);

  @Deprecated
  SecurityRole grant(DatabaseSessionEmbedded session, final String iResource, final int iOperation);

  @Deprecated
  SecurityRole revoke(DatabaseSessionEmbedded session, final String iResource, final int iOperation);

  String getName(DatabaseSessionEmbedded session);

  SecurityRole getParentRole();

 void setParentRole(DatabaseSessionEmbedded session, final SecurityRole iParent);

  Set<Rule> getRuleSet();

 Identifiable getIdentity();

 Map<String, SecurityPolicy> getPolicies(DatabaseSessionEmbedded session);

  SecurityPolicy getPolicy(DatabaseSessionEmbedded session, String resource);
}
