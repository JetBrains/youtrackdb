/*
 *
 * Copyright 2013 Geomatys.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jetbrains.youtrackdb.internal.core.sql.method.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.text.Normalizer;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the SQL <code>normalize()</code> method. Verifies the default NFD + diacritical-mark
 * stripping path, explicit {@link Normalizer.Form} selection, null propagation, and the
 * error path for an unknown form name. Also pins a latent bug in the 2-argument branch
 * (<strong>WHEN-FIXED</strong>: the replaceAll pattern is read from iParams[0] instead of
 * iParams[1] — fixing the typo will flip the test's expected behaviour).
 */
public class SQLMethodNormalizeTest {

  private SQLMethodNormalize method;

  @Before
  public void setup() {
    method = new SQLMethodNormalize();
  }

  @Test
  public void nullInputReturnsNull() {
    assertNull(method.execute(null, null, null, null, null));
  }

  @Test
  public void defaultFormStripsDiacritics() {
    // Default: NFD decomposition + PATTERN_DIACRITICAL_MARKS removal → plain ASCII.
    assertEquals("cafe", method.execute(null, null, null, "café", null));
    assertEquals("resume", method.execute(null, null, null, "résumé", null));
    assertEquals("naive", method.execute(null, null, null, "naïve", null));
  }

  @Test
  public void explicitFormNfdStripsDiacritics() {
    assertEquals("cafe", method.execute(null, null, null, "café", new Object[] {"NFD"}));
  }

  @Test
  public void explicitFormNfcKeepsDiacritics() {
    // Single-argument branch + NFC form does NOT strip marks (they stay composed).
    // Actually: the code applies PATTERN_DIACRITICAL_MARKS regardless, but NFC re-composes
    // before that regex runs, leaving composed chars untouched.
    assertEquals("café", method.execute(null, null, null, "café", new Object[] {"NFC"}));
  }

  @Test
  public void plainAsciiInputUnchanged() {
    // No diacritics → string is already in NFD form and no marks to strip.
    assertEquals("hello", method.execute(null, null, null, "hello", null));
  }

  @Test
  public void emptyStringReturnsEmpty() {
    assertEquals("", method.execute(null, null, null, "", null));
  }

  @Test
  public void unknownFormRaisesIllegalArgument() {
    // Normalizer.Form.valueOf throws IllegalArgumentException for unrecognised names.
    try {
      method.execute(null, null, null, "café", new Object[] {"UNKNOWN"});
      fail("Expected IllegalArgumentException for unknown form");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void twoArgumentBranchBugPinned() {
    // WHEN-FIXED: SQLMethodNormalize.execute second branch uses iParams[0] (the form name)
    // instead of iParams[1] (the user-supplied replacement regex). This test pins the current
    // broken behaviour: the replaceAll regex is read from iParams[0] ("NFD"), so only the
    // literal "NFD" substring is stripped — not the intended iParams[1] pattern. When the
    // bug is fixed (swap iParams[0] → iParams[1]), this assertion will flip and the
    // updated test should assert that iParams[1] drives the replaceAll.
    var result = method.execute(null, null, null, "NFDcafé", new Object[] {"NFD", "[^a-z]"});
    // Current broken behaviour: replaces literal "NFD" → "café" (in NFD decomposed form).
    // After NFD decomposition, "café" is "cafe\u0301". The replaceAll("NFD", "") yields that
    // decomposed form (no NFD substring present).
    assertEquals("cafe\u0301", result);
  }
}
