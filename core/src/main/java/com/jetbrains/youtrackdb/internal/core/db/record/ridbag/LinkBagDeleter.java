package com.jetbrains.youtrackdb.internal.core.db.record.ridbag;

import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;


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
