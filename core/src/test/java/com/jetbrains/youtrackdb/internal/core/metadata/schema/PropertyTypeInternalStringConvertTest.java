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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.List;
import org.junit.Test;

/**
 * Drives the per-arm {@link PropertyTypeInternal#STRING} convert body.
 *
 * <p>The STRING arm is structurally distinct from every other enum constant: it is the only
 * convert that <b>cannot</b> throw on a wrong-type input — the {@code default} case calls
 * {@code value.toString()} on whatever scalar reaches it. The arm has four observable
 * branches that are easy to break with a refactor and silently corrupt user data:
 *
 * <ul>
 *   <li>{@code null} → {@code null} (early return);</li>
 *   <li>{@code String s} → identity (the production code returns the same reference, not a
 *       defensive copy — pin via {@code assertSame});</li>
 *   <li>{@code Collection<?>} of size 1 whose sole element is a {@code String} → unwrap to
 *       that string. This non-obvious "single-element collection passes through" path is the
 *       most likely silent-deletion candidate;</li>
 *   <li>everything else → {@code value.toString()}: scalars, multi-element collections, and
 *       single-element collections whose element is NOT a String. A regression that drops the
 *       size-1-with-String guard would route a {@code List.of("hello")} through {@code toString}
 *       and produce {@code "[hello]"} instead of {@code "hello"}, corrupting any property
 *       assigned through this path.
 * </ul>
 *
 * <p>Standalone: the convert path takes no session, no linkedType, no linkedClass — every
 * argument except {@code value} is null.
 */
public class PropertyTypeInternalStringConvertTest {

  @Test
  public void nullReturnsNull() {
    assertNull(PropertyTypeInternal.STRING.convert(null, null, null, null));
  }

  @Test
  public void stringScalarPassesThroughAsIdentity() {
    // The production body returns the input String reference verbatim — no defensive copy. Pin
    // identity (assertSame) so a future change introducing a defensive copy on the convert path
    // is a deliberate, visible event.
    var s = "hello";
    var converted = PropertyTypeInternal.STRING.convert(s, null, null, null);
    assertSame("STRING.convert(String) must return the input reference unchanged",
        s, converted);
  }

  @Test
  public void singletonStringCollectionUnwrapsToFirstElement() {
    // Pin the "single-element Collection<String> is unwrapped" path — the most likely
    // silent-deletion candidate because it is gated on an instanceof guard plus a size==1
    // predicate. A user who passes List.of("only") to a STRING-typed property gets the bare
    // string out, NOT the toString-wrapped "[only]" form.
    var unwrapped = PropertyTypeInternal.STRING.convert(List.of("only"), null, null, null);
    assertEquals("only", unwrapped);
  }

  @Test
  public void singletonNonStringCollectionFallsThroughToToString() {
    // size()==1 but the element is NOT a String. The instanceof-String guard fails and the
    // pattern-switch routes this to the default arm, which calls value.toString() on the
    // outer Collection. Pin the resulting String shape ("[42]") so a regression that drops
    // the instanceof guard (silently unwrapping Integer-into-int) is caught.
    var converted = PropertyTypeInternal.STRING.convert(List.of(42), null, null, null);
    assertEquals("[42]", converted);
  }

  @Test
  public void multiElementCollectionUsesToString() {
    // size != 1 fails the size==1 guard; the default arm produces the List.toString() shape.
    // A regression that broadens the unwrap to "always pick first" would corrupt this path.
    var converted = PropertyTypeInternal.STRING.convert(List.of("a", "b"), null, null, null);
    assertEquals("[a, b]", converted);
  }

  @Test
  public void nonCollectionScalarUsesToString() {
    // The default arm: any non-Collection, non-String, non-null value gets toString-ed. Pin
    // the contract for an Integer to keep the most common scalar path falsifiable.
    var converted = PropertyTypeInternal.STRING.convert(Integer.valueOf(42), null, null, null);
    assertEquals("42", converted);
  }
}
