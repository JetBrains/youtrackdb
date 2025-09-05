package com.jetbrains.youtrackdb.internal.core.id;

public interface IdentityChangeListener {

  void onBeforeIdentityChange(Object source);

  void onAfterIdentityChange(Object source);
}
