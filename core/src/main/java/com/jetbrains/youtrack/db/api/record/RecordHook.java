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
package com.jetbrains.youtrack.db.api.record;

import javax.annotation.Nonnull;

/**
 * Hook interface to catch all events regarding records.
 *
 * @see RecordHookAbstract
 */
public interface RecordHook {

  enum HOOK_POSITION {
    FIRST,
    EARLY,
    REGULAR,
    LATE,
    LAST
  }

  enum TYPE {
    ANY,

    BEFORE_CREATE,
    BEFORE_READ,
    BEFORE_UPDATE,
    BEFORE_DELETE,
    AFTER_CREATE,
    AFTER_READ,
    AFTER_UPDATE,
    AFTER_DELETE,

    CREATE_FAILED,
    READ_FAILED,
    UPDATE_FAILED,
    DELETE_FAILED,

    FINALIZE_UPDATE,
    FINALIZE_CREATION,
    FINALIZE_DELETION
  }

  enum RESULT {
    RECORD_NOT_CHANGED,
    RECORD_CHANGED,
    SKIP,
    SKIP_IO
  }

  /**
   * Defines available scopes for scoped hooks.
   *
   * <p>Basically, each scope defines some subset of {@link RecordHook.TYPE}, this limits the set
   * of events the hook interested in and lowers the number of useless hook invocations.
   *
   * @see RecordHook#getScopes()
   */
  enum SCOPE {
    /**
     * The create scope, includes: {@link RecordHook.TYPE#BEFORE_CREATE},
     * {@link RecordHook.TYPE#AFTER_CREATE}, {@link RecordHook.TYPE#FINALIZE_CREATION}, and
     * {@link RecordHook.TYPE#CREATE_FAILED}.
     */
    CREATE,

    /**
     * The read scope, includes: {@link RecordHook.TYPE#BEFORE_READ},
     * {@link RecordHook.TYPE#AFTER_READ}, and {@link RecordHook.TYPE#READ_FAILED}.
     */
    READ,

    /**
     * The update scope, includes: {@link RecordHook.TYPE#BEFORE_UPDATE},
     * {@link RecordHook.TYPE#AFTER_UPDATE}, {@link RecordHook.TYPE#FINALIZE_UPDATE}, and
     * {@link RecordHook.TYPE#UPDATE_FAILED}.
     */
    UPDATE,

    /**
     * The delete scope, includes: {@link RecordHook.TYPE#BEFORE_DELETE},
     * {@link RecordHook.TYPE#AFTER_DELETE}, {@link RecordHook.TYPE#DELETE_FAILED} and
     * {@link RecordHook.TYPE#FINALIZE_DELETION}.
     */
    DELETE;

    /**
     * Maps the {@link RecordHook.TYPE} to {@link RecordHook.SCOPE}.
     *
     * @param type the hook type to map.
     * @return the mapped scope.
     */
    @Nonnull
    public static SCOPE typeToScope(@Nonnull TYPE type) {
      return switch (type) {
        case BEFORE_CREATE, AFTER_CREATE, CREATE_FAILED, FINALIZE_CREATION -> SCOPE.CREATE;
        case BEFORE_READ, AFTER_READ, READ_FAILED -> SCOPE.READ;
        case BEFORE_UPDATE, AFTER_UPDATE, UPDATE_FAILED, FINALIZE_UPDATE -> SCOPE.UPDATE;
        case BEFORE_DELETE, AFTER_DELETE, DELETE_FAILED, FINALIZE_DELETION -> SCOPE.DELETE;
        default -> throw new IllegalStateException("Unexpected hook type.");
      };
    }
  }

  void onUnregister();

  RESULT onTrigger(@Nonnull TYPE iType,
      @Nonnull DBRecord iRecord);

  /**
   * Returns the array of scopes this hook interested in. By default, all available scopes are
   * returned, implement/override this method to limit the scopes this hook may participate to lower
   * the number of useless invocations of this hook.
   *
   * <p>Limiting the hook to proper scopes may give huge performance boost, especially if the
   * hook's {@link #onTrigger(TYPE, DBRecord)} dispatcher implementation is heavy.
   * In extreme cases, you may override the {@link #onTrigger(TYPE, DBRecord)} to
   * act directly on event's {@link RecordHook.TYPE} and exit early, scopes are just a more handy
   * alternative to this.
   *
   * @return the scopes of this hook.
   * @see RecordHook.SCOPE
   */
  @Nonnull
  default SCOPE[] getScopes() {
    return SCOPE.values();
  }
}
