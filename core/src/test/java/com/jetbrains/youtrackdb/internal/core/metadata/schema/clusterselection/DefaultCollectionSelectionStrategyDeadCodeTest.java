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
package com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.CollectionSelectionStrategy;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.lang.reflect.Modifier;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Shape pin for {@link DefaultCollectionSelectionStrategy}. PSI all-scope
 * {@code ReferencesSearch} confirms the only production reference is the SPI service file
 * {@code META-INF/services/.../CollectionSelectionStrategy} (second entry); the class is
 * never instantiated through any code path that has a live caller because no public API on
 * {@link SchemaClass} switches the cluster-selection strategy by name.
 *
 * <p>Sibling pin to {@link BalancedCollectionSelectionStrategyDeadCodeTest} — the two are
 * scheduled for lockstep deletion together with {@link CollectionSelectionFactory}'s
 * SPI-registry plumbing and the corresponding service-file entries. See the
 * sibling's class Javadoc for the lockstep deletion group.
 *
 * <p>The {@link DefaultCollectionSelectionStrategy} contract is "always return the first
 * configured cluster" — this test pins:
 *
 * <ul>
 *   <li>{@link #getName()} returns the literal {@code "default"}.</li>
 *   <li>The two-argument form reads {@code SchemaClass.getCollectionIds()} and returns the
 *       first element.</li>
 *   <li>The four-argument form reads the supplied {@code selection} array's first element
 *       directly when the entity-typed input is supplied — wait, actually the production
 *       implementation BYPASSES the {@code selection} parameter and reads from
 *       {@code iClass.getCollectionIds()} on both overloads. Pin this somewhat-surprising
 *       behaviour so a future refactor that "fixes" the 4-arg form to honour the
 *       {@code selection} parameter is caught and explicitly decided on.</li>
 * </ul>
 *
 * <p>WHEN-FIXED: Track 22 — delete this class and this test together with the rest of the
 * cluster-selection lockstep group.
 *
 * <p>Standalone: no database session is needed; the production methods do not consult the
 * session at all (they only read from {@link SchemaClass#getCollectionIds()}).
 */
public class DefaultCollectionSelectionStrategyDeadCodeTest {

  @Test
  public void classIsPublicNonAbstractAndImplementsCollectionSelectionStrategy() {
    var clazz = DefaultCollectionSelectionStrategy.class;
    var mods = clazz.getModifiers();
    assertTrue("must be public", Modifier.isPublic(mods));
    assertTrue("must NOT be abstract", !Modifier.isAbstract(mods));
    assertTrue("must implement CollectionSelectionStrategy",
        CollectionSelectionStrategy.class.isAssignableFrom(clazz));
  }

  @Test
  public void getNameReturnsDefaultConstant() {
    // The "default" literal is the SPI-registry key used by
    // CollectionSelectionFactory.registerStrategy(); pin it byte-for-byte.
    var s = new DefaultCollectionSelectionStrategy();
    assertEquals("getName() must return the literal 'default' (SPI-registry key)",
        "default", s.getName());
    assertEquals("the public NAME constant must match the getName() return value",
        DefaultCollectionSelectionStrategy.NAME, s.getName());
  }

  @Test
  public void getCollectionTwoArgFormReturnsFirstClusterIdFromSchemaClass() {
    // Pin the "always first cluster" contract for the two-argument form.
    var session = Mockito.mock(DatabaseSessionEmbedded.class);
    var clazz = Mockito.mock(SchemaClass.class);
    var entity = Mockito.mock(EntityImpl.class);
    Mockito.when(clazz.getCollectionIds()).thenReturn(new int[] {7, 11, 13});

    var s = new DefaultCollectionSelectionStrategy();
    int picked = s.getCollection(session, clazz, entity);

    assertEquals("two-arg form must always return the first cluster id", 7, picked);
    // The strategy never consults the session — pin that observable so a future refactor
    // is flagged.
    Mockito.verifyNoInteractions(session);
    Mockito.verify(clazz).getCollectionIds();
  }

  @Test
  public void getCollectionFourArgFormIgnoresSelectionAndReturnsFirstClusterIdFromSchemaClass() {
    // Surprising-but-true contract: the 4-arg overload reads from iClass.getCollectionIds()
    // and ignores the supplied selection array. Pin this so a Track 22 deletion that
    // "harmonises" with the documented behaviour ("Returns always the first collection
    // configured") is caught and explicitly migrated. Use distinct values so the test
    // cannot pass coincidentally if both arrays were consulted.
    var session = Mockito.mock(DatabaseSessionEmbedded.class);
    var clazz = Mockito.mock(SchemaClass.class);
    var entity = Mockito.mock(EntityImpl.class);
    Mockito.when(clazz.getCollectionIds()).thenReturn(new int[] {7, 11, 13});

    var s = new DefaultCollectionSelectionStrategy();
    int picked = s.getCollection(session, clazz, new int[] {99, 100}, entity);

    assertEquals("4-arg form returns first iClass.getCollectionIds() entry, NOT selection[0]",
        7, picked);
    Mockito.verifyNoInteractions(session);
  }
}
