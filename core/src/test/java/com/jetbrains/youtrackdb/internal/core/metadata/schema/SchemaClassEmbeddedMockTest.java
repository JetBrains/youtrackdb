package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.transaction.TxConsumer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.SharedContext;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.IndexManagerEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataInternal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

// this test relies heavily on iternal implementation so it will be updated every time the internals is changed,
// but also this is the easiest way to spot the exact place where the error occurs
public class SchemaClassEmbeddedMockTest {

  private final SchemaShared ownerMock = mock(SchemaShared.class);
  private final SchemaClassEmbedded a = new SchemaClassEmbedded(ownerMock, "Test");

  @Test
  public void shouldAddSuperClassNames() {
    var sessionMock = mock(DatabaseSessionInternal.class);
    var superId = new RecordId(1, 1);
    var underlyingClassMock = mock(SchemaClassImpl.class);
    when(underlyingClassMock.getName()).thenReturn("Super");
    a.setLazySuperClassesInternal(sessionMock,
        List.of(LazySchemaClass.fromTemplate(superId, underlyingClassMock, false)), true);
    assertThat(a.getSuperClassesNames(sessionMock)).containsExactly("Super");
  }

  @Test
  public void shouldNotAllowDuplicateClassesByName() {
    var sessionMock = mock(DatabaseSessionInternal.class);
    var superId = new RecordId(1, 1);
    var underlyingClassMock = mock(SchemaClassImpl.class);
    when(underlyingClassMock.getName()).thenReturn("Super");
    var duplicateSuperId = new RecordId(1, 2);
    var duplicateSuperClassMock = mock(SchemaClassImpl.class);
    when(duplicateSuperClassMock.getName()).thenReturn("Super");
    assertThatThrownBy(() ->
        a.setLazySuperClassesInternal(sessionMock,
            List.of(
                LazySchemaClass.fromTemplate(superId, underlyingClassMock, false),
                LazySchemaClass.fromTemplate(duplicateSuperId, duplicateSuperClassMock, false)
            ),
            true)
    )
        .isInstanceOf(SchemaException.class)
        .hasMessage("Duplicated superclass 'Super'");
  }

  @Test
  public void shouldAddNewClassesIfItWasNotThereBeforeAndRemoveOldClassesIfTheyAreNotOnTheNewList() {
    DatabaseSessionInternal sessionMock = mock(DatabaseSessionInternal.class);
    RecordId superId = new RecordId(1, 1);
    SchemaClassImpl existingSuperClassMock = mock(SchemaClassImpl.class);
    when(existingSuperClassMock.getName()).thenReturn("Existing Super");
    RecordId newSuperId = new RecordId(1, 1);
    SchemaClassImpl newSuperClassMock = mock(SchemaClassImpl.class);
    when(newSuperClassMock.getName()).thenReturn("New Super");
    a.superClasses.put("Existing Super",
        LazySchemaClass.fromTemplate(superId, existingSuperClassMock, false));
    a.setLazySuperClassesInternal(sessionMock,
        List.of(
            LazySchemaClass.fromTemplate(newSuperId, newSuperClassMock, false)
        ), false);
    assertThat(a.getSuperClassesNames(sessionMock)).containsExactly("New Super");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void shouldAddPolymorphicCollectionIdsFromBaseClassToSuperWhenSuperClassIsAddedToBaseClass() {
    var sessionMock = mock(DatabaseSessionEmbedded.class);
    var metadataMock = mock(MetadataDefault.class);
    when(sessionMock.getMetadata()).thenReturn(metadataMock);
    var sharedContextMock = mock(SharedContext.class);
    var indexManagerMock = mock(IndexManagerEmbedded.class);
    when(sharedContextMock.getIndexManager()).thenReturn(indexManagerMock);
    when(sessionMock.getSharedContext()).thenReturn(sharedContextMock);
    var superId = new RecordId(1, 1);
    SchemaClassImpl superClass = new SchemaClassEmbedded(ownerMock, "Super");
    a.polymorphicCollectionIds = new int[]{1};
    superClass.polymorphicCollectionIds = new int[]{2};
    a.setLazySuperClassesInternal(sessionMock,
        List.of(LazySchemaClass.fromTemplate(superId, superClass, false)),
        true
    );
    assertThat(a.polymorphicCollectionIds).containsExactly(1);
    assertThat(superClass.polymorphicCollectionIds).containsExactly(1, 2);
  }

  @Test
  public void shouldRemovePolymorphicCollectionIdsFromBaseClassToSuperWhenSuperClassIsRemovedFromBaseClass() {
    var sessionMock = mock(DatabaseSessionInternal.class);
    var metadataMock = mock(MetadataInternal.class);
    when(sessionMock.getMetadata()).thenReturn(metadataMock);
    var superId = new RecordId(1, 1);
    var subId = new RecordId(1, 2);
    SchemaClassImpl superClass = new SchemaClassEmbedded(ownerMock, "Super");
    a.superClasses.put("Super", LazySchemaClass.fromTemplate(superId, superClass, false));
    a.polymorphicCollectionIds = new int[]{1};
    superClass.polymorphicCollectionIds = new int[]{1, 2};
    superClass.subclasses.put("Test", LazySchemaClass.fromTemplate(subId, a, false));
    a.setLazySuperClassesInternal(sessionMock, Collections.emptyList(), false);
    assertThat(a.polymorphicCollectionIds).containsExactly(1);
    assertThat(superClass.polymorphicCollectionIds).containsExactly(2);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void shouldAddPolymorphicCollectionIdsFromBaseClassToAllSuperRecursively() {
    var sessionMock = mock(DatabaseSessionEmbedded.class);
    var metadataMock = mock(MetadataDefault.class);
    var sharedContextMock = mock(SharedContext.class);
    var indexManagerMock = mock(IndexManagerEmbedded.class);
    when(sharedContextMock.getIndexManager()).thenReturn(indexManagerMock);
    when(sessionMock.getSharedContext()).thenReturn(sharedContextMock);
    when(sessionMock.getMetadata()).thenReturn(metadataMock);
    var superId = new RecordId(1, 1);
    SchemaClassImpl superClass = new SchemaClassEmbedded(ownerMock, "Super");
    var superSuperId = new RecordId(1, 2);
    SchemaClassImpl superSuperClass = new SchemaClassEmbedded(ownerMock, "SuperSuper");
    superClass.superClasses.put("SuperSuper",
        LazySchemaClass.fromTemplate(superSuperId, superSuperClass, false));
    a.polymorphicCollectionIds = new int[]{1};
    superClass.polymorphicCollectionIds = new int[]{2};
    superSuperClass.polymorphicCollectionIds = new int[]{3};
    a.setLazySuperClassesInternal(sessionMock,
        List.of(LazySchemaClass.fromTemplate(superId, superClass, false)),
        true
    );
    assertThat(a.polymorphicCollectionIds).containsExactly(1);
    assertThat(superClass.polymorphicCollectionIds).containsExactly(1, 2);
    assertThat(superSuperClass.polymorphicCollectionIds).containsExactly(1, 3);
  }

  @Test
  public void shouldNotLoadInheritanceTreeForSuperClassToPreventInfiniteRecursion() {
    DatabaseSessionInternal sessionMock = mock(DatabaseSessionInternal.class);
    MetadataInternal metadataMock = mock(MetadataInternal.class);
    when(sessionMock.getMetadata()).thenReturn(metadataMock);

    // run transaction which will allow to load the class
    doAnswer(invocationOnMock -> {
      invocationOnMock.<TxConsumer<?, ?>>getArgument(0).accept(null);
      return null;
    }).when(sessionMock).executeInTx(any());

//    CollectionSelectionFactory clusterSelectionFactoryMock = mock(CollectionSelectionStrategy.class);
//    when(ownerMock.getClusterSelectionFactory()).thenReturn(clusterSelectionFactoryMock);
    LazySchemaClass lazyTestClassMock = mock(LazySchemaClass.class);
    // subclass name is normalized, so it's test and not Test
    when(ownerMock.getLazyClass("Test")).thenReturn(lazyTestClassMock);

    when(lazyTestClassMock.getDelegate()).thenReturn(a);

    RecordId superId = new RecordId(1, 1);
    Map<String, Object> existingEntityContent = Map.of(
        "name", "Super",
        "defaultCollectionId", 1,
        "collectionIds", new int[]{1, 2},
        "superClasses", Collections.emptyList(),
        "subClasses", List.of("Test")
    );
    var existingClassEntityMock = mock(Entity.class);
    when(existingClassEntityMock.getProperty(any())).thenAnswer(
        invocationOnMock -> existingEntityContent.get(invocationOnMock.getArgument(0)));
    when(existingClassEntityMock.getIdentity()).thenReturn(superId);
    doReturn(existingClassEntityMock).when(sessionMock).load(superId);

    RecordId newSuperId = new RecordId(1, 2);
    Map<String, Object> newEntityContent = Map.of(
        "name", "NewSuper",
        "defaultCollectionId", 1,
        "collectionIds", new int[]{1, 2},
        "superClasses", Collections.emptyList(),
        "subClasses", List.of("Test")
    );
    var newClassEntityMock = mock(Entity.class);
    when(newClassEntityMock.getProperty(any())).thenAnswer(
        invocationOnMock -> newEntityContent.get(invocationOnMock.getArgument(0)));
    when(newClassEntityMock.getIdentity()).thenReturn(newSuperId);
    doReturn(newClassEntityMock).when(sessionMock).load(newSuperId);

    a.polymorphicCollectionIds = new int[]{1};
    SchemaClassImpl superClassTemplate = new SchemaClassEmbedded(ownerMock, "Super");
    var existingLazySuperClass = LazySchemaClass.fromTemplate(superId,
        superClassTemplate, false);
    a.superClasses.put("Super", existingLazySuperClass);

    SchemaClassImpl newSuperClassTemplate = new SchemaClassEmbedded(ownerMock, "NewSuper");
    var newLazySuperClass = LazySchemaClass.fromTemplate(newSuperId,
        newSuperClassTemplate, false);
    a.setLazySuperClassesInternal(sessionMock, List.of(
            existingLazySuperClass,
            newLazySuperClass
        ),
        false
    );

    verify(lazyTestClassMock, times(0)).loadIfNeeded(sessionMock);

    assertThat(a.polymorphicCollectionIds).containsExactly(1);
    assertThat(superClassTemplate.polymorphicCollectionIds).containsExactly(1, 2);
  }
}