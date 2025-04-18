package com.jetbrains.youtrack.db.internal.core.db.record.ridbag;

import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;


public final class LinkBagDeleter {

  public static void deleteAllRidBags(EntityImpl entity, FrontendTransaction frontendTransaction) {
    var linkBagsToDelete = entity.getLinkBagsToDelete();
    if (linkBagsToDelete != null) {
      for (var linkBag : linkBagsToDelete) {
        linkBag.requestDelete(frontendTransaction);
      }
    }
  }
}
