/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrack.db.internal.core.fetch;

import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FetchPlan {

  private static final String ANY_WILDCARD = "*";

  private final Map<String, FetchPlanLevel> fetchPlan = new HashMap<String, FetchPlanLevel>();
  private final Map<String, FetchPlanLevel> fetchPlanStartsWith =
      new HashMap<String, FetchPlanLevel>();

  private static class FetchPlanLevel {

    public int depthLevelFrom;
    public int depthLevelTo;
    public int level;

    public FetchPlanLevel(final int iFrom, final int iTo, final int iLevel) {
      depthLevelFrom = iFrom;
      depthLevelTo = iTo;
      level = iLevel;
    }
  }

  public FetchPlan(final String iFetchPlan) {
    fetchPlan.put(ANY_WILDCARD, new FetchPlanLevel(0, 0, 0));

    if (iFetchPlan != null && !iFetchPlan.isEmpty()) {
      // CHECK IF THERE IS SOME FETCH-DEPTH
      final var planParts = StringSerializerHelper.split(iFetchPlan, ' ');
      if (!planParts.isEmpty()) {
        for (var planPart : planParts) {
          final var parts = StringSerializerHelper.split(planPart, ':');
          if (parts.size() != 2) {
            throw new IllegalArgumentException("Wrong fetch plan: " + planPart);
          }

          var key = parts.get(0);
          final var level = Integer.parseInt(parts.get(1));

          final FetchPlanLevel fp;

          if (key.startsWith("[")) {
            // EXTRACT DEPTH LEVEL
            final var endLevel = key.indexOf(']');
            if (endLevel == -1) {
              throw new IllegalArgumentException(
                  "Missing closing square bracket on depth level in fetch plan: " + key);
            }

            final var range = key.substring(1, endLevel);
            key = key.substring(endLevel + 1);

            if (key.indexOf('.') > -1) {
              throw new IllegalArgumentException(
                  "Nested levels (fields separated by dot) are not allowed on fetch plan when"
                      + " dynamic depth level is specified (square brackets): "
                      + key);
            }

            final var indexRanges = StringSerializerHelper.smartSplit(range, '-', ' ');
            if (indexRanges.size() > 1) {
              // MULTI VALUES RANGE
              var from = indexRanges.get(0);
              var to = indexRanges.get(1);

              final var rangeFrom = from != null && !from.isEmpty() ? Integer.parseInt(from) : 0;
              final var rangeTo = to != null && !to.isEmpty() ? Integer.parseInt(to) : -1;

              fp = new FetchPlanLevel(rangeFrom, rangeTo, level);
            } else if (range.equals("*"))
            // CREATE FETCH PLAN WITH INFINITE DEPTH
            {
              fp = new FetchPlanLevel(0, -1, level);
            } else {
              // CREATE FETCH PLAN WITH ONE LEVEL ONLY OF DEPTH
              final var v = Integer.parseInt(range);
              fp = new FetchPlanLevel(v, v, level);
            }
          } else {
            if (level == -1)
            // CREATE FETCH PLAN FOR INFINITE LEVEL
            {
              fp = new FetchPlanLevel(0, -1, level);
            } else
            // CREATE FETCH PLAN FOR FIRST LEVEL ONLY
            {
              fp = new FetchPlanLevel(0, 0, level);
            }
          }

          if (key.length() > 1 && key.endsWith(ANY_WILDCARD)) {
            fetchPlanStartsWith.put(key.substring(0, key.length() - 1), fp);
          } else {
            fetchPlan.put(key, fp);
          }
        }
      }
    }
  }

  public int getDepthLevel(final String iFieldPath, final int iCurrentLevel) {
    final var value = fetchPlan.get(ANY_WILDCARD);
    final Integer defDepthLevel = value.level;

    final var fpParts = iFieldPath.split("\\.");

    for (var fpLevel : fetchPlan.entrySet()) {
      final var fpLevelKey = fpLevel.getKey();
      final var fpLevelValue = fpLevel.getValue();

      if (iCurrentLevel >= fpLevelValue.depthLevelFrom
          && (fpLevelValue.depthLevelTo == -1 || iCurrentLevel <= fpLevelValue.depthLevelTo)) {
        // IT'S IN RANGE
        if (iFieldPath.equals(fpLevelKey))
        // GET THE FETCH PLAN FOR THE GENERIC FIELD IF SPECIFIED
        {
          return fpLevelValue.level;
        } else if (fpLevelKey.startsWith(iFieldPath))
        // SETS THE FETCH LEVEL TO 1 (LOADS ALL DOCUMENT FIELDS)
        {
          return 1;
        }

        for (var i = 0; i < fpParts.length; ++i) {
          if (i >= fpLevelValue.depthLevelFrom
              && (fpLevelValue.depthLevelTo == -1 || i <= fpLevelValue.depthLevelTo)) {
            // IT'S IN RANGE
            if (fpParts[i].equals(fpLevelKey))
            // GET THE FETCH PLAN FOR THE GENERIC FIELD IF SPECIFIED
            {
              return fpLevelValue.level;
            }
          }
        }
      } else {
        if (iFieldPath.equals(fpLevelKey))
        // GET THE FETCH PLAN FOR THE GENERIC FIELD IF SPECIFIED
        {
          return fpLevelValue.level;
        } else if (fpLevelKey.startsWith(iFieldPath))
        // SETS THE FETCH LEVEL TO 1 (LOADS ALL DOCUMENT FIELDS)
        {
          return 1;
        }
      }
    }

    if (!fetchPlanStartsWith.isEmpty()) {
      for (var fpLevel : fetchPlanStartsWith.entrySet()) {
        final var fpLevelKey = fpLevel.getKey();
        final var fpLevelValue = fpLevel.getValue();

        if (iCurrentLevel >= fpLevelValue.depthLevelFrom
            && (fpLevelValue.depthLevelTo == -1 || iCurrentLevel <= fpLevelValue.depthLevelTo)) {
          // IT'S IN RANGE
          for (var i = 0; i < fpParts.length; ++i) {
            if (fpParts[i].startsWith(fpLevelKey)) {
              return fpLevelValue.level;
            }
          }
        }
      }
    }

    return defDepthLevel.intValue();
  }

  public boolean has(final String iFieldPath, final int iCurrentLevel) {
    final var fpParts = iFieldPath.split("\\.");

    for (var fpLevel : fetchPlan.entrySet()) {
      final var fpLevelKey = fpLevel.getKey();
      final var fpLevelValue = fpLevel.getValue();

      if (iCurrentLevel >= fpLevelValue.depthLevelFrom
          && (fpLevelValue.depthLevelTo == -1 || iCurrentLevel <= fpLevelValue.depthLevelTo)) {
        if (iFieldPath.equals(fpLevelKey))
        // GET THE FETCH PLAN FOR THE GENERIC FIELD IF SPECIFIED
        {
          return true;
        } else if (fpLevelKey.startsWith(iFieldPath))
        // SETS THE FETCH LEVEL TO 1 (LOADS ALL DOCUMENT FIELDS)
        {
          return true;
        }

        for (var i = 0; i < fpParts.length; ++i) {
          if (i >= fpLevelValue.depthLevelFrom
              && (fpLevelValue.depthLevelTo == -1 || i <= fpLevelValue.depthLevelTo)) {
            // IT'S IN RANGE
            if (fpParts[i].equals(fpLevelKey))
            // GET THE FETCH PLAN FOR THE GENERIC FIELD IF SPECIFIED
            {
              return true;
            }
          }
        }
      } else {
        if (iFieldPath.equals(fpLevelKey))
        // GET THE FETCH PLAN FOR THE GENERIC FIELD IF SPECIFIED
        {
          return true;
        } else if (fpLevelKey.startsWith(iFieldPath))
        // SETS THE FETCH LEVEL TO 1 (LOADS ALL DOCUMENT FIELDS)
        {
          return true;
        }
      }
    }

    if (!fetchPlanStartsWith.isEmpty()) {
      for (var fpLevel : fetchPlanStartsWith.entrySet()) {
        final var fpLevelKey = fpLevel.getKey();
        final var fpLevelValue = fpLevel.getValue();

        if (iCurrentLevel >= fpLevelValue.depthLevelFrom
            && (fpLevelValue.depthLevelTo == -1 || iCurrentLevel <= fpLevelValue.depthLevelTo)) {
          // IT'S IN RANGE
          for (var i = 0; i < fpParts.length; ++i) {
            if (fpParts[i].startsWith(fpLevelKey)) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }
}
