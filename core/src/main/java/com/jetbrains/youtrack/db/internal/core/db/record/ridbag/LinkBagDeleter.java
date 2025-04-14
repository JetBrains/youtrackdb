package com.jetbrains.youtrack.db.internal.core.db.record.ridbag;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;


/**
 *
 */
public final class LinkBagDeleter {

  public static void deleteAllRidBags(EntityImpl entity) {
    var linkBagsToDelete = entity.getLinkBagsToDelete();
    if (linkBagsToDelete != null) {
      for (var ridBag : linkBagsToDelete) {
        ridBag.requestDelete();
      }
    }
  }
}
