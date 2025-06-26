package com.jetbrains.youtrack.db.internal.common.collection.closabledictionary;

import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public class ClosableLRUListTest {

  @Test
  public void tesMoveToTail() {
    var lruList = new ClosableLRUList<Long, CIItem>();
    var firstEntry = new ClosableEntry<Long, CIItem>(new CIItem());

    Assert.assertFalse(lruList.contains(firstEntry));
    Assert.assertEquals(lruList.size(), 0);

    lruList.moveToTheTail(firstEntry);
    Assert.assertEquals(lruList.size(), 1);
    Assert.assertTrue(lruList.contains(firstEntry));
    assertContent(lruList, new ClosableEntry[]{firstEntry});

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    var secondEntry = new ClosableEntry<Long, CIItem>(new CIItem());

    lruList.moveToTheTail(secondEntry);

    Assert.assertEquals(lruList.size(), 2);
    Assert.assertTrue(lruList.contains(firstEntry));
    Assert.assertTrue(lruList.contains(secondEntry));
    assertContent(lruList, new ClosableEntry[]{firstEntry, secondEntry});

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    var thirdEntry = new ClosableEntry<Long, CIItem>(new CIItem());
    lruList.moveToTheTail(thirdEntry);

    Assert.assertEquals(lruList.size(), 3);
    Assert.assertTrue(lruList.contains(firstEntry));
    Assert.assertTrue(lruList.contains(secondEntry));
    Assert.assertTrue(lruList.contains(thirdEntry));
    assertContent(lruList, new ClosableEntry[]{firstEntry, secondEntry, thirdEntry});

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    lruList.moveToTheTail(secondEntry);
    Assert.assertEquals(lruList.size(), 3);
    Assert.assertTrue(lruList.contains(firstEntry));
    Assert.assertTrue(lruList.contains(secondEntry));
    Assert.assertTrue(lruList.contains(thirdEntry));
    assertContent(lruList, new ClosableEntry[]{firstEntry, thirdEntry, secondEntry});

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    lruList.moveToTheTail(firstEntry);
    Assert.assertEquals(lruList.size(), 3);
    Assert.assertTrue(lruList.contains(firstEntry));
    Assert.assertTrue(lruList.contains(secondEntry));
    Assert.assertTrue(lruList.contains(thirdEntry));
    assertContent(lruList, new ClosableEntry[]{thirdEntry, secondEntry, firstEntry});

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    lruList.moveToTheTail(firstEntry);
    Assert.assertEquals(lruList.size(), 3);
    Assert.assertTrue(lruList.contains(firstEntry));
    Assert.assertTrue(lruList.contains(secondEntry));
    Assert.assertTrue(lruList.contains(thirdEntry));
    assertContent(lruList, new ClosableEntry[]{thirdEntry, secondEntry, firstEntry});

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());
  }

  @Test
  public void tesRemove() {
    var lruList = new ClosableLRUList<Long, CIItem>();

    var firstEntry = new ClosableEntry<Long, CIItem>(new CIItem());
    var secondEntry = new ClosableEntry<Long, CIItem>(new CIItem());
    var thirdEntry = new ClosableEntry<Long, CIItem>(new CIItem());

    lruList.moveToTheTail(firstEntry);
    lruList.moveToTheTail(secondEntry);
    lruList.moveToTheTail(thirdEntry);

    lruList.remove(firstEntry);
    assertContent(lruList, new ClosableEntry[]{secondEntry, thirdEntry});

    Assert.assertEquals(lruList.size(), 2);
    Assert.assertFalse(lruList.contains(firstEntry));
    Assert.assertTrue(lruList.contains(secondEntry));
    Assert.assertTrue(lruList.contains(thirdEntry));

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    lruList.remove(thirdEntry);
    assertContent(lruList, new ClosableEntry[]{secondEntry});

    Assert.assertEquals(lruList.size(), 1);
    Assert.assertFalse(lruList.contains(firstEntry));
    Assert.assertTrue(lruList.contains(secondEntry));
    Assert.assertFalse(lruList.contains(thirdEntry));

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    lruList.remove(secondEntry);
    assertContent(lruList, new ClosableEntry[]{});

    Assert.assertEquals(lruList.size(), 0);
    Assert.assertFalse(lruList.contains(firstEntry));
    Assert.assertFalse(lruList.contains(secondEntry));
    Assert.assertFalse(lruList.contains(thirdEntry));

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    lruList.remove(secondEntry);
    assertContent(lruList, new ClosableEntry[]{});

    Assert.assertEquals(lruList.size(), 0);
    Assert.assertFalse(lruList.contains(firstEntry));
    Assert.assertFalse(lruList.contains(secondEntry));
    Assert.assertFalse(lruList.contains(thirdEntry));

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    lruList.moveToTheTail(firstEntry);
    lruList.moveToTheTail(secondEntry);
    lruList.moveToTheTail(thirdEntry);

    lruList.remove(secondEntry);
    assertContent(lruList, new ClosableEntry[]{firstEntry, thirdEntry});

    Assert.assertEquals(lruList.size(), 2);
    Assert.assertTrue(lruList.contains(firstEntry));
    Assert.assertFalse(lruList.contains(secondEntry));
    Assert.assertTrue(lruList.contains(thirdEntry));

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    lruList.moveToTheTail(secondEntry);
    assertContent(lruList, new ClosableEntry[]{firstEntry, thirdEntry, secondEntry});
    Assert.assertEquals(lruList.size(), 3);

    lruList.remove(secondEntry);
    assertContent(lruList, new ClosableEntry[]{firstEntry, thirdEntry});

    Assert.assertEquals(lruList.size(), 2);
    Assert.assertTrue(lruList.contains(firstEntry));
    Assert.assertFalse(lruList.contains(secondEntry));
    Assert.assertTrue(lruList.contains(thirdEntry));

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());
  }

  @Test
  public void testPool() {
    var lruList = new ClosableLRUList<Long, CIItem>();

    var firstEntry = new ClosableEntry<Long, CIItem>(new CIItem());
    var secondEntry = new ClosableEntry<Long, CIItem>(new CIItem());
    var thirdEntry = new ClosableEntry<Long, CIItem>(new CIItem());

    lruList.moveToTheTail(firstEntry);
    lruList.moveToTheTail(secondEntry);
    lruList.moveToTheTail(thirdEntry);

    var removed = lruList.poll();
    Assert.assertSame(removed, firstEntry);
    Assert.assertEquals(lruList.size(), 2);

    Assert.assertFalse(lruList.contains(firstEntry));
    Assert.assertTrue(lruList.contains(secondEntry));
    Assert.assertTrue(lruList.contains(thirdEntry));

    assertContent(lruList, new ClosableEntry[]{secondEntry, thirdEntry});

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    removed = lruList.poll();
    Assert.assertSame(removed, secondEntry);
    Assert.assertEquals(lruList.size(), 1);

    Assert.assertFalse(lruList.contains(firstEntry));
    Assert.assertFalse(lruList.contains(secondEntry));
    Assert.assertTrue(lruList.contains(thirdEntry));

    assertContent(lruList, new ClosableEntry[]{thirdEntry});

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    removed = lruList.poll();
    Assert.assertSame(removed, thirdEntry);
    Assert.assertEquals(lruList.size(), 0);

    Assert.assertFalse(lruList.contains(firstEntry));
    Assert.assertFalse(lruList.contains(secondEntry));
    Assert.assertFalse(lruList.contains(thirdEntry));

    assertContent(lruList, new ClosableEntry[]{});

    Assert.assertTrue(lruList.assertBackwardStructure());
    Assert.assertTrue(lruList.assertForwardStructure());

    removed = lruList.poll();
    Assert.assertNull(removed);
  }

  public class CIItem implements ClosableItem {

    private volatile boolean open = true;

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }

    @Override
    public void open() {
      open = true;
    }
  }

  private void assertContent(
      ClosableLRUList<Long, CIItem> lruList, ClosableEntry<Long, CIItem>[] entries) {
    final var entryList = Arrays.asList(entries);

    final var iterator = entryList.iterator();
    for (var entry : lruList) {
      Assert.assertTrue(iterator.hasNext());
      Assert.assertSame(entry, iterator.next());
    }

    Assert.assertFalse(iterator.hasNext());
  }
}
