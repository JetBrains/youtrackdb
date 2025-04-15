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
package com.jetbrains.youtrack.db.internal.core.metadata;

import com.jetbrains.youtrack.db.internal.core.metadata.function.Function;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Identity;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserImpl;
import java.io.IOException;
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

  ImmutableSchema getImmutableSchemaSnapshot();

  SchemaInternal getSchemaInternal();

  @Deprecated
  void load();

  @Deprecated
  void create() throws IOException;

  /**
   * Reloads the internal objects.
   */
  void reload();

  /**
   * Closes internal objects
   */
  @Deprecated
  void close();
}
