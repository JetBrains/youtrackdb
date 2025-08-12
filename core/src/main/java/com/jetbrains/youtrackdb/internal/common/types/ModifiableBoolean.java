package com.jetbrains.youtrackdb.internal.common.types;

/**
 * This internal API please do not use it.
 * <p>
 * <p>
 * href="mailto:lomakin.andrey@gmail.com">Andrey Lomakin</a>
 *
 * @since 19/12/14
 */
public class ModifiableBoolean {

  private boolean value;

  public ModifiableBoolean() {
    value = false;
  }

  public ModifiableBoolean(boolean value) {
    this.value = value;
  }

  public boolean getValue() {
    return value;
  }

  public void setValue(boolean value) {
    this.value = value;
  }
}
