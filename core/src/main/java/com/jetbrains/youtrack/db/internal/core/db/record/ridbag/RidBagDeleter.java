package com.jetbrains.youtrack.db.internal.core.db.record.ridbag;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;


/**
 *
 */
public final class RidBagDeleter {

  public static void deleteAllRidBags(EntityImpl entity) {
    var ridBagsToDelete = entity.getRidBagsToDelete();

    if (ridBagsToDelete != null) {
      for (var ridBag : ridBagsToDelete) {
        ridBag.delete();
      }
    }
  }
}
