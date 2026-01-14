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

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import java.util.List;
import java.util.Set;

/**
 * Manages users and roles.
 */
public interface Security {
  @Deprecated
  String IDENTITY_CLASSNAME = Identity.CLASS_NAME;

  @Deprecated
  boolean isAllowed(final Set<Identifiable> iAllowAll, final Set<Identifiable> iAllowOperation);

  @Deprecated
  SecurityUser authenticate(String iUsername, String iUserPassword);

  @Deprecated
  SecurityUser authenticate(final Token authToken);

  SecurityUser getUser(String iUserName);

  SecurityUserImpl getUser(final RID iUserId);

  SecurityUserImpl createUser(String iUserName, String iUserPassword, String... iRoles);

  SecurityUserImpl createUser(String iUserName, String iUserPassword, Role... iRoles);

  boolean dropUser(String iUserName);

  Role getRole(String iRoleName);

  Role getRole(Identifiable role);

  Role createRole(String iRoleName);

  Role createRole(String iRoleName, Role iParent);

  void dropRole(String iRoleName);

  List<EntityImpl> getAllUsers();

  List<EntityImpl> getAllRoles();
}
