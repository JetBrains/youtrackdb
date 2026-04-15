package com.jetbrains.youtrackdb.internal.common.types;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModifiableBooleanTest {

  @Test
  public void testDefaultConstructorInitializesToFalse() {
    var mb = new ModifiableBoolean();
    assertFalse(mb.getValue());
  }

  @Test
  public void testValueConstructorTrue() {
    var mb = new ModifiableBoolean(true);
    assertTrue(mb.getValue());
  }

  @Test
  public void testValueConstructorFalse() {
    var mb = new ModifiableBoolean(false);
    assertFalse(mb.getValue());
  }

  @Test
  public void testSetValueToTrue() {
    var mb = new ModifiableBoolean();
    mb.setValue(true);
    assertTrue(mb.getValue());
  }

  @Test
  public void testSetValueToFalse() {
    var mb = new ModifiableBoolean(true);
    mb.setValue(false);
    assertFalse(mb.getValue());
  }

  @Test
  public void testSetValueToggle() {
    // Toggle value back and forth.
    var mb = new ModifiableBoolean(false);
    mb.setValue(true);
    assertTrue(mb.getValue());
    mb.setValue(false);
    assertFalse(mb.getValue());
  }
}
