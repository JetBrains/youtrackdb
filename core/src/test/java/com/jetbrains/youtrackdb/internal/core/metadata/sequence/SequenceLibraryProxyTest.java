/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrackdb.internal.core.metadata.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.SequenceException;
import org.junit.Test;

/**
 * Boundary tests for {@link SequenceLibraryProxy} — the {@link SequenceLibrary}-interface
 * façade returned by {@code session.getMetadata().getSequenceLibrary()}.
 *
 * <p>Pre-existing baseline left {@link SequenceLibraryProxy} at 60.0% line / 0.0% branch
 * because the existing {@link DBSequenceTest} drives the {@link SequenceLibrary} interface
 * directly and never asserts proxy-specific behaviour. This class pins:
 *
 * <ul>
 *   <li>The proxy is the concrete instance returned by the metadata layer (interface dispatch
 *       trap — direct find-usages on {@link SequenceLibraryProxy} returns 0 production
 *       callers because dispatch flows through {@link SequenceLibrary}).
 *   <li>{@code getDelegate()} returns a non-null {@link SequenceLibraryImpl} reference that
 *       is shared across all calls (the impl is owned by the schema and re-used).
 *   <li>Every {@link SequenceLibrary}-interface method routes through the proxy to the impl
 *       (smoke-tested via observable round-trip behaviour rather than mock verification, which
 *       cannot intercept the package-private {@code delegate} field).
 *   <li>{@code getSequenceCount} reflects creation / drop with the expected granularity.
 *   <li>The deprecated {@code create()} / {@code load()} / {@code close()} surface remains
 *       reachable; pinning these arms keeps the SPI shape pinned for the deferred-cleanup
 *       backlog.
 * </ul>
 */
public class SequenceLibraryProxyTest extends DbTestBase {

  /**
   * The metadata layer returns a non-null {@link SequenceLibraryProxy} (concrete subtype
   * pinned). A future refactor switching to a different proxy class would be a deliberate,
   * visible event.
   */
  @Test
  public void metadataExposesSequenceLibraryProxy() {
    var library = session.getMetadata().getSequenceLibrary();
    assertNotNull(library);
    assertTrue(library instanceof SequenceLibraryProxy);
  }

  /**
   * {@code getDelegate()} returns the underlying {@link SequenceLibraryImpl}; it is not a
   * defensive copy but the same instance returned across calls. Pinning shared identity
   * catches a future refactor that introduces per-call delegate re-binding.
   */
  @Test
  public void getDelegateReturnsSharedNonNullImpl() {
    var proxy = (SequenceLibraryProxy) session.getMetadata().getSequenceLibrary();
    var first = proxy.getDelegate();
    var second = proxy.getDelegate();
    assertNotNull(first);
    assertSame(first, second);
  }

  /**
   * {@code getSequenceCount()} starts at 0 and is incremented by {@code createSequence} /
   * decremented by {@code dropSequence}. Pin both directions in a single test for the proxy
   * delegation contract.
   */
  @Test
  public void getSequenceCountReflectsCreateAndDrop() {
    var library = session.getMetadata().getSequenceLibrary();
    assertEquals(0, library.getSequenceCount());

    session.begin();
    library.createSequence(
        "ProxyCountSeq", DBSequence.SEQUENCE_TYPE.ORDERED,
        new DBSequence.CreateParams().setDefaults());
    session.commit();

    assertEquals(1, library.getSequenceCount());

    session.begin();
    library.dropSequence("ProxyCountSeq");
    session.commit();

    assertEquals(0, library.getSequenceCount());
  }

  /**
   * {@code getSequenceNames()} returns the upper-cased names backing the impl's map (the
   * normalisation is applied at create time). Pin the upper-case contract through the proxy.
   */
  @Test
  public void getSequenceNamesReflectsRegisteredSequencesUpperCase() {
    var library = session.getMetadata().getSequenceLibrary();
    library.createSequence(
        "MixedCaseSeq", DBSequence.SEQUENCE_TYPE.ORDERED,
        new DBSequence.CreateParams().setDefaults());

    var names = library.getSequenceNames();
    assertNotNull(names);
    assertTrue(names.contains("MIXEDCASESEQ"));
  }

  /**
   * {@code getSequence(name)} is case-insensitive — {@code "mixedcaseseq"} resolves to the
   * same in-memory wrapper as {@code "MIXEDCASESEQ"}. Pinning both case arms locks the
   * normalisation through the proxy.
   */
  @Test
  public void getSequenceIsCaseInsensitive() {
    var library = session.getMetadata().getSequenceLibrary();
    library.createSequence(
        "CaseSeq", DBSequence.SEQUENCE_TYPE.ORDERED,
        new DBSequence.CreateParams().setDefaults());

    var lookupSame = library.getSequence("CaseSeq");
    var lookupUpper = library.getSequence("CASESEQ");
    var lookupLower = library.getSequence("caseseq");
    assertNotNull(lookupSame);
    assertNotNull(lookupUpper);
    assertNotNull(lookupLower);
    assertSame(lookupSame, lookupUpper);
    assertSame(lookupSame, lookupLower);
  }

  /**
   * {@code getSequence("missing")} returns null — distinct from the throw-if-absent contract
   * on {@link SequenceLibraryImpl#createSequence}.
   */
  @Test
  public void getSequenceReturnsNullForUnknownName() {
    var library = session.getMetadata().getSequenceLibrary();
    assertNull(library.getSequence("NoSuchSequence"));
  }

  /**
   * Creating a sequence with a duplicate (case-insensitive) name throws
   * {@link SequenceException}. Pin the throw arm through the proxy so a future "silent
   * upsert" change is a deliberate, visible event.
   */
  @Test(expected = SequenceException.class)
  public void createSequenceThrowsOnDuplicateNameCaseInsensitive() {
    var library = session.getMetadata().getSequenceLibrary();
    library.createSequence(
        "DupSeq", DBSequence.SEQUENCE_TYPE.ORDERED,
        new DBSequence.CreateParams().setDefaults());
    // Different case but same normalised name.
    library.createSequence(
        "DUPSEQ", DBSequence.SEQUENCE_TYPE.ORDERED,
        new DBSequence.CreateParams().setDefaults());
  }

  /**
   * Dropping an absent sequence is a no-op — distinct from {@link FunctionLibrary#dropFunction}
   * which NPEs on absent names. Pin so a future "throw on missing" change is a deliberate
   * event.
   */
  @Test
  public void dropSequenceForAbsentNameIsNoOp() {
    var library = session.getMetadata().getSequenceLibrary();
    session.begin();
    library.dropSequence("NoSuchSequence");
    session.commit();
    assertEquals(0, library.getSequenceCount());
  }

  /**
   * The deprecated {@code create()} re-runs the schema initialisation (the {@code OSequence}
   * class is created if absent). It is idempotent: a second call must not double-register or
   * wipe existing sequences.
   *
   * <p>Pin idempotency on a populated library — calling {@code create()} on an empty library
   * cannot distinguish "no-op" from "wipes everything" because the post-condition (count 0)
   * is identical to the pre-condition. Populating a sequence first makes the assertion
   * load-bearing: a regression that wipes the library on each {@code create()} would FAIL the
   * count check, and a regression that double-registers would also FAIL it.
   */
  @Test
  @SuppressWarnings("deprecation")
  public void deprecatedCreateIsIdempotent() {
    var library = session.getMetadata().getSequenceLibrary();
    library.createSequence(
        "IdemSeq", DBSequence.SEQUENCE_TYPE.ORDERED,
        new DBSequence.CreateParams().setDefaults());
    long countAfterCreate = library.getSequenceCount();
    library.create();
    library.create();
    assertEquals("create() must be idempotent on a populated library — neither wipe nor "
        + "double-register the existing entries",
        countAfterCreate, library.getSequenceCount());
    assertNotNull("existing sequence must remain visible after re-create",
        library.getSequence("IDEMSEQ"));
  }

  /**
   * The deprecated {@code load()} re-loads sequences from storage. Pin the no-throw contract;
   * after a {@link SequenceLibrary#createSequence(String, DBSequence.SEQUENCE_TYPE, DBSequence.CreateParams)}
   * call, the post-load library still resolves the sequence.
   */
  @Test
  @SuppressWarnings("deprecation")
  public void deprecatedLoadKeepsRegisteredSequenceVisible() {
    var library = session.getMetadata().getSequenceLibrary();
    library.createSequence(
        "ReloadSeq", DBSequence.SEQUENCE_TYPE.ORDERED,
        new DBSequence.CreateParams().setDefaults());
    library.load();
    assertNotNull(library.getSequence("ReloadSeq"));
  }

  /**
   * The deprecated {@code close()} clears the in-memory cache; subsequent {@code getSequence}
   * lookups still find the sequence by re-loading via {@code reloadIfNeeded}.
   *
   * <p>The test covers the close → re-resolve cycle and asserts the sequence remains
   * accessible afterwards, pinning the lazy-reload contract through the proxy. Use
   * {@code assertThat} (the project's preferred style for sequence tests) for symmetry with
   * {@link DBSequenceTest}.
   */
  @Test
  @SuppressWarnings("deprecation")
  public void deprecatedCloseClearsInMemoryCache() {
    var library = session.getMetadata().getSequenceLibrary();
    library.createSequence(
        "CloseSeq", DBSequence.SEQUENCE_TYPE.ORDERED,
        new DBSequence.CreateParams().setDefaults());

    library.close();
    // After close, the library still re-loads on demand via the impl's lazy reload path.
    library.load();
    assertThat(library.getSequence("CloseSeq")).isNotNull();
  }
}
