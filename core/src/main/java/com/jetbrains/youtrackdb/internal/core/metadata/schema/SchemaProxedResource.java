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
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.ProxedResource;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The common routing base for the three schema proxies ({@link SchemaProxy},
 * {@link SchemaClassProxy}, {@link SchemaPropertyProxy}). It funnels every proxy method through one
 * {@link #resolve()} (read) or {@link #resolveForWrite()} (write) helper so a schema-changing
 * transaction transparently sees and mutates its own private copy of the schema rather than the
 * committed shared instance.
 *
 * <p>The captured {@code delegate} a proxy holds is a direct reference to the {@link SchemaShared} /
 * {@link SchemaClassImpl} / {@link SchemaPropertyImpl} it stood for at creation. Dereferencing it
 * directly would read or mutate shared state during a schema transaction, breaking isolation. The
 * resolve seam fixes this with a three-tier policy:
 *
 * <ul>
 *   <li><b>Tier 1 — snapshot reads.</b> The immutable schema snapshot is a separate read family
 *       that does not flow through this base (for example {@link SchemaProxy#makeSnapshot()}); it is
 *       deliberately left routing to the committed instance and is not the seam's concern.
 *   <li><b>Tier 2 — captured-delegate fast path.</b> When no schema write-view is seeded for the
 *       current transaction (the common case: no schema transaction in progress), {@link #resolve()}
 *       returns the captured {@code delegate} unchanged, so behaviour is identical to the pre-seam
 *       code.
 *   <li><b>Tier 3 — name-rebind into the tx-local copy.</b> Once a schema write has been routed in
 *       the current transaction, a tx-local {@link SchemaShared} copy exists
 *       ({@link DatabaseSessionEmbedded#getTxSchemaState()}). Both reads and writes then re-resolve
 *       the captured delegate <em>by name</em> against that copy ({@link #rebindToTxLocal}), so the
 *       transaction sees its own uncommitted schema through every method, and a captured pre-tx
 *       proxy can never hand a committed-shared impl into the private graph.
 * </ul>
 *
 * <p>A write seeds the tx-local copy on its first routed mutation ({@link #resolveForWrite()} calls
 * {@link DatabaseSessionEmbedded#ensureTxSchemaState()}); a read never seeds. Outside any
 * transaction a write keeps the legacy top-level path — {@link #resolveForWrite()} returns the
 * captured {@code delegate} unchanged so the existing self-commit save path is untouched (the
 * de-guarding of those entry points and the commit-time promotion are later steps).
 *
 * <p><b>Impl-typed arguments.</b> A write method that links another schema impl into the graph (a
 * superclass, a linked class) must re-resolve that impl by name against the tx-local copy first, or
 * a committed-shared object would leak into the private graph. {@link #reresolveClassImpl} and
 * {@link #reresolvePropertyImpl} perform that name re-resolution; callers use them on every impl
 * argument before passing it to a tx-local mutation.
 */
public abstract class SchemaProxedResource<T> extends ProxedResource<T> {

  protected SchemaProxedResource(final T delegate, @Nonnull final DatabaseSessionEmbedded session) {
    super(delegate, session);
  }

  /**
   * Resolves the delegate for a <em>read</em>. Tier 2 (no schema write-view) returns the captured
   * delegate; tier 3 (a tx-local copy exists for the current transaction) re-resolves it by name
   * against that copy. A read never seeds the tx-local copy.
   */
  protected final T resolve() {
    var view = session.getTxSchemaState();
    if (view == null) {
      return delegate;
    }
    return rebindToTxLocal(view.getTxLocalSchema());
  }

  /**
   * Resolves the delegate for a <em>write</em>. Outside a transaction the legacy top-level path is
   * kept (the captured delegate is returned unchanged). Inside a transaction the tx-local copy is
   * seeded on the first such call ({@link DatabaseSessionEmbedded#ensureTxSchemaState()}) and the
   * delegate is re-resolved by name against it, so the mutation lands on the private copy.
   */
  protected final T resolveForWrite() {
    if (!session.getTransactionInternal().isActive()) {
      // No user transaction: a schema write keeps the legacy top-level save path. Seeding a
      // tx-local copy here would have no transaction to defer its commit to.
      return delegate;
    }
    return rebindToTxLocal(session.ensureTxSchemaState().getTxLocalSchema());
  }

  /**
   * Re-resolves this proxy's captured delegate by name against the given tx-local schema copy. Each
   * subclass implements the lookup for its own type: a {@link SchemaProxy} resolves to the copy
   * itself, a {@link SchemaClassProxy} to the copy's class of the same name, a
   * {@link SchemaPropertyProxy} to the same-named property on the copy's owner class.
   */
  protected abstract T rebindToTxLocal(@Nonnull SchemaShared txLocalSchema);

  /**
   * Re-resolves a {@link SchemaClassImpl} argument by name against the given tx-local schema copy so
   * a committed-shared class object is never linked into the private graph. A {@code null} argument
   * stays {@code null}. Throws when the named class is absent from the copy, surfacing a stale or
   * cross-schema impl loudly rather than silently linking shared state.
   */
  @Nullable protected static SchemaClassImpl reresolveClassImpl(@Nonnull SchemaShared txLocalSchema,
      @Nullable SchemaClassImpl impl) {
    if (impl == null) {
      return null;
    }
    if (impl.getOwner() == txLocalSchema) {
      // Already a tx-local object (for example a class created earlier in the same transaction).
      return impl;
    }
    var resolved = txLocalSchema.getClass(impl.getName());
    if (resolved == null) {
      throw new IllegalStateException(
          "Schema class '" + impl.getName() + "' is not present in the transaction-local schema"
              + " view; a shared schema impl cannot be linked into the transaction's private copy");
    }
    return resolved;
  }

  /**
   * Re-resolves a {@link SchemaPropertyImpl} argument by name against the given tx-local schema copy
   * (owner class by name, then property by name on it). A {@code null} argument stays {@code null}.
   * Throws when the owner class or the property is absent from the copy.
   */
  @Nullable protected static SchemaPropertyImpl reresolvePropertyImpl(@Nonnull SchemaShared txLocalSchema,
      @Nullable SchemaPropertyImpl impl) {
    if (impl == null) {
      return null;
    }
    var ownerClass = impl.getOwnerClass();
    if (ownerClass != null && ownerClass.getOwner() == txLocalSchema) {
      return impl;
    }
    if (ownerClass == null) {
      throw new IllegalStateException(
          "Schema property '" + impl.getName() + "' has no owner class and cannot be re-resolved"
              + " against the transaction-local schema view");
    }
    var resolvedOwner = txLocalSchema.getClass(ownerClass.getName());
    if (resolvedOwner == null) {
      throw new IllegalStateException(
          "Owner class '" + ownerClass.getName() + "' of property '" + impl.getName() + "' is not"
              + " present in the transaction-local schema view");
    }
    var resolved = resolvedOwner.getPropertyInternal(impl.getName());
    if (resolved == null) {
      throw new IllegalStateException(
          "Property '" + impl.getName() + "' is not present on class '" + ownerClass.getName()
              + "' in the transaction-local schema view");
    }
    return resolved;
  }
}
