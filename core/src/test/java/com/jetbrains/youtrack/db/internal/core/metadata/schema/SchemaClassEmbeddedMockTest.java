package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.clusterselection.ClusterSelectionFactory;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
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
    DatabaseSessionInternal sessionMock = mock(DatabaseSessionInternal.class);
    RecordId superId = new RecordId(1, 1);
    SchemaClassImpl underlyingClassMock = mock(SchemaClassImpl.class);
    when(underlyingClassMock.getName()).thenReturn("Super");
    a.setLazySuperClassesInternal(sessionMock,
        List.of(LazySchemaClass.fromTemplate(superId, underlyingClassMock)));
    assertThat(a.getSuperClassesNames()).containsExactly("Super");
  }

  @Test
  public void shouldNotAllowDuplicateClassesByName() {
    DatabaseSessionInternal sessionMock = mock(DatabaseSessionInternal.class);
    RecordId superId = new RecordId(1, 1);
    SchemaClassImpl underlyingClassMock = mock(SchemaClassImpl.class);
    when(underlyingClassMock.getName()).thenReturn("Super");
    RecordId duplicateSuperId = new RecordId(1, 2);
    SchemaClassImpl duplicateSuperClassMock = mock(SchemaClassImpl.class);
    when(duplicateSuperClassMock.getName()).thenReturn("Super");
    assertThatThrownBy(() ->
        a.setLazySuperClassesInternal(sessionMock,
            List.of(
                LazySchemaClass.fromTemplate(superId, underlyingClassMock),
                LazySchemaClass.fromTemplate(duplicateSuperId, duplicateSuperClassMock)
            ))
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
        LazySchemaClass.fromTemplate(superId, existingSuperClassMock));
    a.setLazySuperClassesInternal(sessionMock,
        List.of(
            LazySchemaClass.fromTemplate(newSuperId, newSuperClassMock)
        ));
    assertThat(a.getSuperClassesNames()).containsExactly("New Super");
  }

  @Test
  public void shouldAddPolymorphicCollectionIdsFromBaseClassToSuperWhenSuperClassIsAddedToBaseClass() {
    DatabaseSessionInternal sessionMock = mock(DatabaseSessionInternal.class);
    MetadataInternal metadataMock = mock(MetadataInternal.class);
    when(sessionMock.getMetadata()).thenReturn(metadataMock);
    RecordId superId = new RecordId(1, 1);
    SchemaClassImpl superClass = new SchemaClassEmbedded(ownerMock, "Super");
    a.polymorphicClusterIds = new int[]{1};
    superClass.polymorphicClusterIds = new int[]{2};
    a.setLazySuperClassesInternal(sessionMock,
        List.of(LazySchemaClass.fromTemplate(superId, superClass)));
    assertThat(a.polymorphicClusterIds).containsExactly(1);
    assertThat(superClass.polymorphicClusterIds).containsExactly(1, 2);
  }

  @Test
  public void shouldRemovePolymorphicCollectionIdsFromBaseClassToSuperWhenSuperClassIsRemovedFromBaseClass() {
    DatabaseSessionInternal sessionMock = mock(DatabaseSessionInternal.class);
    MetadataInternal metadataMock = mock(MetadataInternal.class);
    when(sessionMock.getMetadata()).thenReturn(metadataMock);
    RecordId superId = new RecordId(1, 1);
    RecordId subId = new RecordId(1, 2);
    SchemaClassImpl superClass = new SchemaClassEmbedded(ownerMock, "Super");
    a.superClasses.put("Super", LazySchemaClass.fromTemplate(superId, superClass));
    a.polymorphicClusterIds = new int[]{1};
    superClass.polymorphicClusterIds = new int[]{1, 2};
    superClass.subclasses.put("Test", LazySchemaClass.fromTemplate(subId, a));
    a.setLazySuperClassesInternal(sessionMock, Collections.emptyList());
    assertThat(a.polymorphicClusterIds).containsExactly(1);
    assertThat(superClass.polymorphicClusterIds).containsExactly(2);
  }

  @Test
  public void shouldAddPolymorphicCollectionIdsFromBaseClassToAllSuperRecursively() {
    DatabaseSessionInternal sessionMock = mock(DatabaseSessionInternal.class);
    MetadataInternal metadataMock = mock(MetadataInternal.class);
    when(sessionMock.getMetadata()).thenReturn(metadataMock);
    RecordId superId = new RecordId(1, 1);
    SchemaClassImpl superClass = new SchemaClassEmbedded(ownerMock, "Super");
    RecordId superSuperId = new RecordId(1, 2);
    SchemaClassImpl superSuperClass = new SchemaClassEmbedded(ownerMock, "SuperSuper");
    superClass.superClasses.put("SuperSuper",
        LazySchemaClass.fromTemplate(superSuperId, superSuperClass));
    a.polymorphicClusterIds = new int[]{1};
    superClass.polymorphicClusterIds = new int[]{2};
    superSuperClass.polymorphicClusterIds = new int[]{3};
    a.setLazySuperClassesInternal(sessionMock,
        List.of(LazySchemaClass.fromTemplate(superId, superClass)));
    assertThat(a.polymorphicClusterIds).containsExactly(1);
    assertThat(superClass.polymorphicClusterIds).containsExactly(1, 2);
    assertThat(superSuperClass.polymorphicClusterIds).containsExactly(1, 3);
  }

  @Test
  public void shouldNotLoadInheritanceTreeForSuperClassToPreventInfiniteRecursion() {
    DatabaseSessionInternal sessionMock = mock(DatabaseSessionInternal.class);
    MetadataInternal metadataMock = mock(MetadataInternal.class);
    when(sessionMock.getMetadata()).thenReturn(metadataMock);

    // run transaction which will allow to load the class
    doAnswer(invocationOnMock -> {
      invocationOnMock.<Runnable>getArgument(0).run();
      return null;
    }).when(sessionMock).executeInTx(any());

    ClusterSelectionFactory clusterSelectionFactoryMock = mock(ClusterSelectionFactory.class);
    when(ownerMock.getClusterSelectionFactory()).thenReturn(clusterSelectionFactoryMock);
    LazySchemaClass lazyTestClassMock = mock(LazySchemaClass.class);
    // subclass name is normalized, so it's test and not Test
    when(ownerMock.getLazyClass("Test")).thenReturn(lazyTestClassMock);

    when(lazyTestClassMock.getDelegate()).thenReturn(a);

    RecordId superId = new RecordId(1, 1);
    Map<String, Object> existingEntityContent = Map.of(
        "name", "Super",
        "defaultClusterId", 1,
        "clusterIds", new int[]{1, 2},
        "superClasses", Collections.emptyList(),
        "subClasses", List.of("Test")
    );
    EntityImpl existingClassEntityMock = mock(EntityImpl.class);
    when(existingClassEntityMock.field(any())).thenAnswer(
        invocationOnMock -> existingEntityContent.get(invocationOnMock.getArgument(0)));
    doReturn(existingClassEntityMock).when(sessionMock).load(superId);

    RecordId newSuperId = new RecordId(1, 2);
    Map<String, Object> newEntityContent = Map.of(
        "name", "NewSuper",
        "defaultClusterId", 1,
        "clusterIds", new int[]{1, 2},
        "superClasses", Collections.emptyList(),
        "subClasses", List.of("Test")
    );
    EntityImpl newClassEntityMock = mock(EntityImpl.class);
    when(newClassEntityMock.field(any())).thenAnswer(
        invocationOnMock -> newEntityContent.get(invocationOnMock.getArgument(0)));
    doReturn(newClassEntityMock).when(sessionMock).load(newSuperId);

    a.polymorphicClusterIds = new int[]{1};
    SchemaClassImpl superClassTemplate = new SchemaClassEmbedded(ownerMock, "Super");
    LazySchemaClass existingLazySuperClass = LazySchemaClass.fromTemplate(superId,
        superClassTemplate);
    a.superClasses.put("super", existingLazySuperClass);

    SchemaClassImpl newSuperClassTemplate = new SchemaClassEmbedded(ownerMock, "Super");
    LazySchemaClass newLazySuperClass = LazySchemaClass.fromTemplate(newSuperId,
        newSuperClassTemplate);
    a.setLazySuperClassesInternal(sessionMock, List.of(
        existingLazySuperClass,
        newLazySuperClass
    ));

    verify(lazyTestClassMock, times(0)).loadIfNeeded(sessionMock);

    assertThat(a.polymorphicClusterIds).containsExactly(1);
    assertThat(superClassTemplate.polymorphicClusterIds).containsExactly(1, 2);
  }
}