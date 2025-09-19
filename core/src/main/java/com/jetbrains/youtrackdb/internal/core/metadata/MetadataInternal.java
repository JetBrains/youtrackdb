/*
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.metadata;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Identity;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Internal interface to manage metadata snapshots.
 */
public interface MetadataInternal extends Metadata {

  Set<String> SYSTEM_COLLECTION =
      Collections.unmodifiableSet(
          new HashSet<String>(
              Arrays.asList(
                  SecurityUserImpl.CLASS_NAME.toLowerCase(Locale.ENGLISH),
                  Role.CLASS_NAME.toLowerCase(Locale.ENGLISH),
                  Identity.CLASS_NAME.toLowerCase(Locale.ENGLISH),
                  Function.CLASS_NAME.toLowerCase(Locale.ENGLISH),
                  "internal")));

  void makeThreadLocalSchemaSnapshot();

  void clearThreadLocalSchemaSnapshot();

  void forceClearThreadLocalSchemaSnapshot();

  ImmutableSchema getImmutableSchema(DatabaseSessionEmbedded session);

  /**
   * Reloads the internal objects.
   */
  void reload();

}
