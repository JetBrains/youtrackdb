package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

public record LinkBagValue(int counter, int secondaryCollectionId, long secondaryPosition) {

  public LinkBagValue {
    assert secondaryCollectionId >= 0
        : "secondaryCollectionId must be non-negative, got " + secondaryCollectionId;
    assert secondaryPosition >= 0
        : "secondaryPosition must be non-negative, got " + secondaryPosition;
  }
}
