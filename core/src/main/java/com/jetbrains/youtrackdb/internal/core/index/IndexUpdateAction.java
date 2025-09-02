package com.jetbrains.youtrackdb.internal.core.index;

public abstract class IndexUpdateAction<V> {

  private static final IndexUpdateAction nothing =
      new IndexUpdateAction() {
        @Override
        public boolean isNothing() {
          return true;
        }

        @Override
        public boolean isChange() {
          return false;
        }

        @Override
        public boolean isRemove() {
          return false;
        }

        @Override
        public Object getValue() {
          throw new UnsupportedOperationException();
        }
      };

  private static final IndexUpdateAction remove =
      new IndexUpdateAction() {
        @Override
        public boolean isNothing() {
          return false;
        }

        @Override
        public boolean isChange() {
          return false;
        }

        @Override
        public boolean isRemove() {
          return true;
        }

        @Override
        public Object getValue() {
          throw new UnsupportedOperationException();
        }
      };

  public static IndexUpdateAction nothing() {
    return nothing;
  }

  public static IndexUpdateAction remove() {
    return remove;
  }

  public static <V> IndexUpdateAction<V> changed(V newValue) {
    return new IndexUpdateAction() {
      @Override
      public boolean isChange() {
        return true;
      }

      @Override
      public boolean isRemove() {
        return false;
      }

      @Override
      public boolean isNothing() {
        return false;
      }

      @Override
      public Object getValue() {
        return newValue;
      }
    };
  }

  public abstract boolean isChange();

  public abstract boolean isRemove();

  public abstract boolean isNothing();

  public abstract V getValue();
}
