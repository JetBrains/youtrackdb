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
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.index.PropertyMapIndexDefinition.INDEX_BY;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import java.lang.reflect.Modifier;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Shape pin for {@link IndexConfigProperty}. PSI all-scope {@code ReferencesSearch} confirms zero
 * references across the full module graph (production and test sources alike) — the class is a
 * fully orphaned config-record drafted at some point for a property-level index configuration
 * surface that never landed.
 *
 * <p>Pinning here is "structural shape" only: every getter is exercised so that the existing
 * accessors are observable, the no-arg constructor invariants are pinned, and the
 * self-recursive {@link IndexConfigProperty#copy()} contract is verified. None of these tests
 * fabricate behaviour the class does not already exhibit; they merely lock its current
 * contract so the deferred-cleanup track can spot any production caller that happens to land
 * before the deletion.
 *
 * <p>The class has package-protected fields ({@code name}, {@code type}, {@code linkedType},
 * {@code collate}, {@code index_by}) — this test deliberately exercises only the public
 * surface (the constructor + five getters + {@code copy()}) so a Track 22 deletion is a clean
 * one-shot remove without rewiring private-field probes.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@code IndexConfigProperty} class (0 production references)
 * and delete this test file together. There is no live caller to migrate; any reintroduction
 * of an index-property config record can start fresh with a record/POJO that matches the new
 * caller's needs.
 *
 * <p>Standalone: this class needs no database session — it is a plain immutable POJO (the
 * fields are non-final {@code protected} but the public surface never mutates them). The
 * {@link Collate} parameter is passed via Mockito mock to avoid pulling in a real collation
 * implementation.
 */
public class IndexConfigPropertyDeadCodeTest {

  @Test
  public void classExposesExpectedPublicSurface() throws NoSuchMethodException {
    // Modifier check (public, not abstract, not final) plus a positive pin on the public method
    // surface — the modifiers alone would not catch a partial deletion of one of the five
    // getters or the copy() method (the class skeleton would still satisfy public + non-final).
    // Reflective signature lookups give a falsifiable check that survives source-level edits:
    // any deferred-cleanup pass that drops a single member without removing the class outright
    // fails this test, surfacing as a deliberate intent change rather than a silent green pass.
    var clazz = IndexConfigProperty.class;
    var mods = clazz.getModifiers();
    assertTrue("must be public", Modifier.isPublic(mods));
    assertFalse("must NOT be abstract", Modifier.isAbstract(mods));
    assertFalse("must NOT be final (no current production subclasses, but no preemptive ban)",
        Modifier.isFinal(mods));
    assertNotNull("five-arg constructor must exist",
        clazz.getConstructor(String.class, PropertyTypeInternal.class, PropertyTypeInternal.class,
            Collate.class, INDEX_BY.class));
    assertNotNull("getName must exist", clazz.getMethod("getName"));
    assertNotNull("getType must exist", clazz.getMethod("getType"));
    assertNotNull("getLinkedType must exist", clazz.getMethod("getLinkedType"));
    assertNotNull("getCollate must exist", clazz.getMethod("getCollate"));
    assertNotNull("getIndexBy must exist", clazz.getMethod("getIndexBy"));
    assertNotNull("copy must exist", clazz.getMethod("copy"));
  }

  @Test
  public void constructorAcceptsAllNullsAndGettersExposeStoredValues() {
    // The constructor performs no validation — pin the null-tolerant behaviour so a future
    // change that adds null checks is flagged. All five getters must echo the constructor
    // arguments byte-for-byte.
    var p = new IndexConfigProperty(null, null, null, null, null);
    assertNull("name getter must return ctor argument verbatim", p.getName());
    assertNull("type getter must return ctor argument verbatim", p.getType());
    assertNull("linkedType getter must return ctor argument verbatim", p.getLinkedType());
    assertNull("collate getter must return ctor argument verbatim", p.getCollate());
    assertNull("indexBy getter must return ctor argument verbatim", p.getIndexBy());
  }

  @Test
  public void gettersReturnConstructorArgumentsByIdentity() {
    // Identity (assertSame) on the reference-typed fields is load-bearing: a future refactor
    // that wraps/copies the supplied Collate or PropertyTypeInternal would silently break
    // callers that compare the returned reference to a known sentinel. Pin identity, not
    // equality.
    var collate = Mockito.mock(Collate.class);
    var p = new IndexConfigProperty(
        "the-name", PropertyTypeInternal.STRING, PropertyTypeInternal.LINK, collate, INDEX_BY.KEY);
    assertEquals("the-name", p.getName());
    assertSame("type getter must return the exact ctor argument reference",
        PropertyTypeInternal.STRING, p.getType());
    assertSame("linkedType getter must return the exact ctor argument reference",
        PropertyTypeInternal.LINK, p.getLinkedType());
    assertSame("collate getter must return the exact ctor argument reference", collate,
        p.getCollate());
    assertSame("indexBy getter must return the exact ctor argument reference", INDEX_BY.KEY,
        p.getIndexBy());
  }

  @Test
  public void copyReturnsFreshInstanceWithSameValuesByIdentity() {
    // copy() is self-recursive (returns IndexConfigProperty, not Object). Pin the contract:
    // (a) returned instance is NOT the same reference as the source — `assertNotSame` is the
    // load-bearing assertion that distinguishes a true copy from a no-op `return this`;
    // (b) every reference-typed field on the copy is identical to the source's field. The
    // copy ctor passes the underlying references through, so identity holds even for the
    // mocked Collate.
    var collate = Mockito.mock(Collate.class);
    var src = new IndexConfigProperty(
        "src", PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING, collate, INDEX_BY.VALUE);
    var copy = src.copy();

    assertNotSame("copy() must return a fresh instance, not the source reference", src, copy);
    assertEquals("name must round-trip", src.getName(), copy.getName());
    assertSame("type must be reference-equal across the copy", src.getType(), copy.getType());
    assertSame("linkedType must be reference-equal across the copy", src.getLinkedType(),
        copy.getLinkedType());
    assertSame("collate must be reference-equal across the copy", src.getCollate(),
        copy.getCollate());
    assertSame("indexBy must be reference-equal across the copy", src.getIndexBy(),
        copy.getIndexBy());
  }
}
