package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.core.id.ChangeableIdentity;
import com.jetbrains.youtrackdb.internal.core.id.IdentityChangeListener;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nonnull;

public final class StorageComponentId implements ChangeableIdentity,
    Comparable<StorageComponentId> {

  private Set<IdentityChangeListener> identityChangeListeners;
  private int id;

  public StorageComponentId(int id) {
    this.id = id;
  }

  public int getId() {
    return id;
  }

  public void setId(int id) {
    if (this.id > 0) {
      throw new IllegalStateException("Cannot change identity of a persistent object");
    }
    if (this.id == id) {
      return;
    }

    fireBeforeIdentityChange();
    this.id = id;
    fireAfterIdentityChange();
  }

  public boolean isTemporary() {
    return id < 0;
  }

  @Override
  public void addIdentityChangeListener(IdentityChangeListener identityChangeListeners) {
    if (!canChangeIdentity()) {
      return;
    }

    if (this.identityChangeListeners == null) {
      this.identityChangeListeners = Collections.newSetFromMap(new WeakHashMap<>());
    }

    this.identityChangeListeners.add(identityChangeListeners);
  }

  @Override
  public void removeIdentityChangeListener(IdentityChangeListener identityChangeListener) {
    if (this.identityChangeListeners != null) {
      this.identityChangeListeners.remove(identityChangeListener);

      if (this.identityChangeListeners.isEmpty()) {
        this.identityChangeListeners = null;
      }
    }
  }

  private void fireBeforeIdentityChange() {
    if (this.identityChangeListeners != null) {
      for (var listener : this.identityChangeListeners) {
        listener.onBeforeIdentityChange(this);
      }
    }
  }

  private void fireAfterIdentityChange() {
    if (this.identityChangeListeners != null) {
      for (var listener : this.identityChangeListeners) {
        listener.onAfterIdentityChange(this);
      }
    }
  }


  @Override
  public boolean canChangeIdentity() {
    return isTemporary();
  }

  @Override
  public int hashCode() {
    return Integer.hashCode(id);
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var that = (StorageComponentId) o;
    return id == that.id;
  }

  @Override
  public String toString() {
    return "CollectionId{id=" + id + '}';
  }

  @Override
  public int compareTo(@Nonnull StorageComponentId o) {
    return Integer.compare(id, o.id);
  }
}
