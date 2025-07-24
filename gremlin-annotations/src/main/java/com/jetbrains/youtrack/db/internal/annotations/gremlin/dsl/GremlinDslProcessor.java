package com.jetbrains.youtrack.db.internal.annotations.gremlin.dsl;


import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeVariableName;
import com.palantir.javapoet.WildcardTypeName;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.AddEdgeStartStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.AddVertexStartStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.InjectStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversal;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * A custom Java annotation processor for the {@link GremlinDsl} annotation that helps to generate
 * DSLs classes.
 *
 * @author Stephen Mallette (<a href="http://stephen.genoprime.com">...</a>)
 */
@SupportedAnnotationTypes("com.jetbrains.youtrack.db.internal.annotations.gremlin.dsl.GremlinDsl")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class GremlinDslProcessor extends AbstractProcessor {

  private static final Pattern EXTENDS_PATTERN = Pattern.compile(" extends ");

  private Messager messager;
  private Elements elementUtils;
  private Filer filer;
  private Types typeUtils;

  @Override
  public synchronized void init(final ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    messager = processingEnv.getMessager();
    elementUtils = processingEnv.getElementUtils();
    filer = processingEnv.getFiler();
    typeUtils = processingEnv.getTypeUtils();
  }

  @Override
  public boolean process(final Set<? extends TypeElement> annotations,
      final RoundEnvironment roundEnv) {
    try {
      for (var dslElement : roundEnv.getElementsAnnotatedWith(GremlinDsl.class)) {
        validateDSL(dslElement);
        final var ctx = new Context((TypeElement) dslElement);

        // creates the "Traversal" interface using an extension of the GraphTraversal class that has the
        // GremlinDsl annotation on it
        generateTraversalInterface(ctx);

        // create the "DefaultTraversal" class which implements the above generated "Traversal" and can then
        // be used by the "TraversalSource" generated below to spawn new traversal instances.
        generateDefaultTraversal(ctx);

        // create the "TraversalSource" class which is used to spawn traversals from a Graph instance. It will
        // spawn instances of the "DefaultTraversal" generated above.
        generateTraversalSource(ctx);

        // create anonymous traversal for DSL
        generateAnonymousTraversal(ctx);
      }
    } catch (Exception ex) {
      messager.printMessage(Diagnostic.Kind.ERROR, ex.getMessage());
    }

    return true;
  }

  private void generateAnonymousTraversal(final Context ctx) throws IOException {
    final var anonymousClass = TypeSpec.classBuilder("__")
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

    // this class is just static methods - it should not be instantiated
    anonymousClass.addMethod(MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PRIVATE)
        .build());

    // add start() method
    anonymousClass.addMethod(MethodSpec.methodBuilder("start")
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .addTypeVariable(TypeVariableName.get("A"))
        .addStatement("return new $N<>()", ctx.defaultTraversalClazz)
        .returns(ParameterizedTypeName.get(ctx.traversalClassName, TypeVariableName.get("A"),
            TypeVariableName.get("A")))
        .build());

    // process the methods of the GremlinDsl annotated class
    for (var templateMethod : findMethodsOfElement(ctx.annotatedDslType, null)) {
      final var methodAnnotation = Optional.ofNullable(
          templateMethod.getAnnotation(GremlinDsl.AnonymousMethod.class));

      final var methodName = templateMethod.getSimpleName().toString();

      // either use the direct return type of the DSL specification or override it with specification from
      // GremlinDsl.AnonymousMethod
      final var returnType =
          methodAnnotation.isPresent() && methodAnnotation.get().returnTypeParameters().length > 0 ?
              getOverridenReturnTypeDefinition(ctx.traversalClassName,
                  methodAnnotation.get().returnTypeParameters()) :
              getReturnTypeDefinition(ctx.traversalClassName, templateMethod);

      final var methodToAdd = MethodSpec.methodBuilder(methodName)
          .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
          .addExceptions(templateMethod.getThrownTypes().stream().map(TypeName::get)
              .collect(Collectors.toList()))
          .returns(returnType);

      // either use the method type parameter specified from the GremlinDsl.AnonymousMethod or just infer them
      // from the DSL specification. "inferring" relies on convention and sometimes doesn't work for all cases.
      final var startGeneric =
          methodAnnotation.isPresent() && methodAnnotation.get().methodTypeParameters().length > 0 ?
              methodAnnotation.get().methodTypeParameters()[0] : "S";
      if (methodAnnotation.isPresent()
          && methodAnnotation.get().methodTypeParameters().length > 0) {
        Stream.of(methodAnnotation.get().methodTypeParameters()).map(TypeVariableName::get)
            .forEach(methodToAdd::addTypeVariable);
      } else {
        templateMethod.getTypeParameters()
            .forEach(tp -> methodToAdd.addTypeVariable(TypeVariableName.get(tp)));

        // might have to deal with an "S" (in __ it's usually an "A") - how to make this less bound to that convention?
        final var returnTypeArguments = getTypeArguments(templateMethod);
        returnTypeArguments.stream().filter(rtm -> rtm instanceof TypeVariable).forEach(rtm -> {
          if (((TypeVariable) rtm).asElement().getSimpleName().contentEquals("S")) {
            methodToAdd.addTypeVariable(
                TypeVariableName.get(((TypeVariable) rtm).asElement().getSimpleName().toString()));
          }
        });
      }

      addMethodBody(methodToAdd, templateMethod, "return __.<" + startGeneric + ">start().$L(", ")",
          methodName);
      anonymousClass.addMethod(methodToAdd.build());
    }

    // use methods from __ to template them into the DSL __
    final Element anonymousTraversal = elementUtils.getTypeElement(__.class.getCanonicalName());
    final Predicate<ExecutableElement> ignore = ee -> ee.getSimpleName().contentEquals("start");
    for (var templateMethod : findMethodsOfElement(anonymousTraversal, ignore)) {
      final var methodName = templateMethod.getSimpleName().toString();

      final var returnType = getReturnTypeDefinition(ctx.traversalClassName, templateMethod);
      final var methodToAdd = MethodSpec.methodBuilder(methodName)
          .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
          .addExceptions(templateMethod.getThrownTypes().stream().map(TypeName::get)
              .collect(Collectors.toList()))
          .returns(returnType);

      templateMethod.getTypeParameters()
          .forEach(tp -> methodToAdd.addTypeVariable(TypeVariableName.get(tp)));

      if (methodName.equals("__")) {
        for (var param : templateMethod.getParameters()) {
          methodToAdd.addParameter(ParameterSpec.get(param));
        }

        methodToAdd.varargs(true);
        methodToAdd.addStatement("return inject(starts)");
      } else {
        if (templateMethod.getTypeParameters().isEmpty()) {
          final var types = getTypeArguments(templateMethod);
          addMethodBody(methodToAdd, templateMethod, "return __.<$T>start().$L(", ")",
              types.getFirst(),
              methodName);
        } else {
          addMethodBody(methodToAdd, templateMethod, "return __.<A>start().$L(", ")", methodName);
        }
      }

      anonymousClass.addMethod(methodToAdd.build());
    }

    final var traversalSourceJavaFile = JavaFile.builder(ctx.packageName,
        anonymousClass.build()).build();
    traversalSourceJavaFile.writeTo(filer);
  }

  private void generateTraversalSource(final Context ctx) throws IOException {
    final var graphTraversalSourceElement = ctx.traversalSourceDslType;
    final var traversalSourceClass = TypeSpec.classBuilder(ctx.traversalSourceClazz)
        .addModifiers(Modifier.PUBLIC)
        .superclass(TypeName.get(graphTraversalSourceElement.asType()));

    // add the required constructors for instantiation
    traversalSourceClass.addMethod(MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(Graph.class, "graph")
        .addStatement("super($N)", "graph")
        .build());
    traversalSourceClass.addMethod(MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(Graph.class, "graph")
        .addParameter(TraversalStrategies.class, "strategies")
        .addStatement("super($N, $N)", "graph", "strategies")
        .build());
    traversalSourceClass.addMethod(MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(RemoteConnection.class, "connection")
        .addStatement("super($N)", "connection")
        .build());

    // override methods to return the DSL TraversalSource. find GraphTraversalSource class somewhere in the hierarchy
    final var tinkerPopsGraphTraversalSource = findClassAsElement(graphTraversalSourceElement,
        GraphTraversalSource.class);
    final Predicate<ExecutableElement> notGraphTraversalSourceReturnValues = e -> !(
        e.getReturnType().getKind() == TypeKind.DECLARED
            && ((DeclaredType) e.getReturnType()).asElement().getSimpleName()
            .contentEquals(GraphTraversalSource.class.getSimpleName()));
    for (var elementOfGraphTraversalSource : findMethodsOfElement(
        tinkerPopsGraphTraversalSource, notGraphTraversalSourceReturnValues)) {
      // first copy/override methods that return a GraphTraversalSource so that we can instead return
      // the DSL TraversalSource class.
      traversalSourceClass.addMethod(
          constructMethod(elementOfGraphTraversalSource, ctx.traversalSourceClassName, "",
              Modifier.PUBLIC));
    }

    // override methods that return GraphTraversal that come from the user defined extension of GraphTraversal
    if (!graphTraversalSourceElement.getSimpleName()
        .contentEquals(GraphTraversalSource.class.getSimpleName())) {
      final Predicate<ExecutableElement> notGraphTraversalReturnValues = e -> !(
          e.getReturnType().getKind() == TypeKind.DECLARED
              && ((DeclaredType) e.getReturnType()).asElement().getSimpleName()
              .contentEquals(GraphTraversal.class.getSimpleName()));
      for (var templateMethod : findMethodsOfElement(graphTraversalSourceElement,
          notGraphTraversalReturnValues)) {
        final var methodToAdd = MethodSpec.methodBuilder(
                templateMethod.getSimpleName().toString())
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class);

        methodToAdd.addStatement("$T clone = this.clone()", ctx.traversalSourceClassName);
        addMethodBody(methodToAdd, templateMethod, "return new $T (clone, super.$L(",
            ").asAdmin())",
            ctx.defaultTraversalClassName, templateMethod.getSimpleName());
        methodToAdd.returns(getReturnTypeDefinition(ctx.traversalClassName, templateMethod));

        traversalSourceClass.addMethod(methodToAdd.build());
      }
    }

    if (ctx.generateDefaultMethods) {
      // override methods that return GraphTraversal
      traversalSourceClass.addMethod(MethodSpec.methodBuilder("addV")
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(Override.class)
          .addStatement("$N clone = this.clone()", ctx.traversalSourceClazz)
          .addStatement("clone.getBytecode().addStep($T.addV)", GraphTraversal.Symbols.class)
          .addStatement("$N traversal = new $N(clone)", ctx.defaultTraversalClazz,
              ctx.defaultTraversalClazz)
          .addStatement("return ($T) traversal.asAdmin().addStep(new $T(traversal, (String) null))",
              ctx.traversalClassName, AddVertexStartStep.class)
          .returns(ParameterizedTypeName.get(ctx.traversalClassName, ClassName.get(Vertex.class),
              ClassName.get(Vertex.class)))
          .build());
      traversalSourceClass.addMethod(MethodSpec.methodBuilder("addV")
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(Override.class)
          .addParameter(String.class, "label")
          .addStatement("$N clone = this.clone()", ctx.traversalSourceClazz)
          .addStatement("clone.getBytecode().addStep($T.addV, label)", GraphTraversal.Symbols.class)
          .addStatement("$N traversal = new $N(clone)", ctx.defaultTraversalClazz,
              ctx.defaultTraversalClazz)
          .addStatement("return ($T) traversal.asAdmin().addStep(new $T(traversal, label))",
              ctx.traversalClassName, AddVertexStartStep.class)
          .returns(ParameterizedTypeName.get(ctx.traversalClassName, ClassName.get(Vertex.class),
              ClassName.get(Vertex.class)))
          .build());
      traversalSourceClass.addMethod(MethodSpec.methodBuilder("addV")
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(Override.class)
          .addParameter(Traversal.class, "vertexLabelTraversal")
          .addStatement("$N clone = this.clone()", ctx.traversalSourceClazz)
          .addStatement("clone.getBytecode().addStep($T.addV, vertexLabelTraversal)",
              GraphTraversal.Symbols.class)
          .addStatement("$N traversal = new $N(clone)", ctx.defaultTraversalClazz,
              ctx.defaultTraversalClazz)
          .addStatement(
              "return ($T) traversal.asAdmin().addStep(new $T(traversal, vertexLabelTraversal))",
              ctx.traversalClassName, AddVertexStartStep.class)
          .returns(ParameterizedTypeName.get(ctx.traversalClassName, ClassName.get(Vertex.class),
              ClassName.get(Vertex.class)))
          .build());
      traversalSourceClass.addMethod(MethodSpec.methodBuilder("addE")
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(Override.class)
          .addParameter(String.class, "label")
          .addStatement("$N clone = this.clone()", ctx.traversalSourceClazz)
          .addStatement("clone.getBytecode().addStep($T.addE, label)", GraphTraversal.Symbols.class)
          .addStatement("$N traversal = new $N(clone)", ctx.defaultTraversalClazz,
              ctx.defaultTraversalClazz)
          .addStatement("return ($T) traversal.asAdmin().addStep(new $T(traversal, label))",
              ctx.traversalClassName, AddEdgeStartStep.class)
          .returns(ParameterizedTypeName.get(ctx.traversalClassName, ClassName.get(Edge.class),
              ClassName.get(Edge.class)))
          .build());
      traversalSourceClass.addMethod(MethodSpec.methodBuilder("addE")
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(Override.class)
          .addParameter(Traversal.class, "edgeLabelTraversal")
          .addStatement("$N clone = this.clone()", ctx.traversalSourceClazz)
          .addStatement("clone.getBytecode().addStep($T.addE, edgeLabelTraversal)",
              GraphTraversal.Symbols.class)
          .addStatement("$N traversal = new $N(clone)", ctx.defaultTraversalClazz,
              ctx.defaultTraversalClazz)
          .addStatement(
              "return ($T) traversal.asAdmin().addStep(new $T(traversal, edgeLabelTraversal))",
              ctx.traversalClassName, AddEdgeStartStep.class)
          .returns(ParameterizedTypeName.get(ctx.traversalClassName, ClassName.get(Edge.class),
              ClassName.get(Edge.class)))
          .build());
      traversalSourceClass.addMethod(MethodSpec.methodBuilder("V")
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(Override.class)
          .addParameter(Object[].class, "vertexIds")
          .varargs(true)
          .addStatement("$N clone = this.clone()", ctx.traversalSourceClazz)
          .addStatement("clone.getBytecode().addStep($T.V, vertexIds)",
              GraphTraversal.Symbols.class)
          .addStatement("$N traversal = new $N(clone)", ctx.defaultTraversalClazz,
              ctx.defaultTraversalClazz)
          .addStatement(
              "return ($T) traversal.asAdmin().addStep(new $T(traversal, $T.class, true, vertexIds))",
              ctx.traversalClassName, GraphStep.class, Vertex.class)
          .returns(ParameterizedTypeName.get(ctx.traversalClassName, ClassName.get(Vertex.class),
              ClassName.get(Vertex.class)))
          .build());
      traversalSourceClass.addMethod(MethodSpec.methodBuilder("E")
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(Override.class)
          .addParameter(Object[].class, "edgeIds")
          .varargs(true)
          .addStatement("$N clone = this.clone()", ctx.traversalSourceClazz)
          .addStatement("clone.getBytecode().addStep($T.E, edgeIds)", GraphTraversal.Symbols.class)
          .addStatement("$N traversal = new $N(clone)", ctx.defaultTraversalClazz,
              ctx.defaultTraversalClazz)
          .addStatement(
              "return ($T) traversal.asAdmin().addStep(new $T(traversal, $T.class, true, edgeIds))",
              ctx.traversalClassName, GraphStep.class, Edge.class)
          .returns(ParameterizedTypeName.get(ctx.traversalClassName, ClassName.get(Edge.class),
              ClassName.get(Edge.class)))
          .build());
      traversalSourceClass.addMethod(MethodSpec.methodBuilder("inject")
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(Override.class)
          .addParameter(ArrayTypeName.of(TypeVariableName.get("S")), "starts")
          .varargs(true)
          .addTypeVariable(TypeVariableName.get("S"))
          .addStatement("$N clone = this.clone()", ctx.traversalSourceClazz)
          .addStatement("clone.getBytecode().addStep($T.inject, starts)",
              GraphTraversal.Symbols.class)
          .addStatement("$N traversal = new $N(clone)", ctx.defaultTraversalClazz,
              ctx.defaultTraversalClazz)
          .addStatement("return ($T) traversal.asAdmin().addStep(new $T(traversal, starts))",
              ctx.traversalClassName, InjectStep.class)
          .returns(ParameterizedTypeName.get(ctx.traversalClassName, TypeVariableName.get("S"),
              TypeVariableName.get("S")))
          .build());
      traversalSourceClass.addMethod(MethodSpec.methodBuilder("getAnonymousTraversalClass")
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(Override.class)
          .addStatement("return Optional.of(__.class)")
          .returns(ParameterizedTypeName.get(ClassName.get(Optional.class),
              ParameterizedTypeName.get(ClassName.get(Class.class),
                  WildcardTypeName.subtypeOf(Object.class))))
          .build());
    }

    final var traversalSourceJavaFile = JavaFile.builder(ctx.packageName,
        traversalSourceClass.build()).build();
    traversalSourceJavaFile.writeTo(filer);
  }

  private Element findClassAsElement(final Element element, final Class<?> clazz) {
    if (element.getSimpleName().contentEquals(clazz.getSimpleName())) {
      return element;
    }

    final var supertypes = typeUtils.directSupertypes(element.asType());
    return findClassAsElement(typeUtils.asElement(supertypes.getFirst()), clazz);
  }

  private void generateDefaultTraversal(final Context ctx) throws IOException {
    final var defaultTraversalClass = TypeSpec.classBuilder(ctx.defaultTraversalClazz)
        .addModifiers(Modifier.PUBLIC)
        .addTypeVariables(Arrays.asList(TypeVariableName.get("S"), TypeVariableName.get("E")))
        .superclass(TypeName.get(
            elementUtils.getTypeElement(DefaultTraversal.class.getCanonicalName()).asType()))
        .addSuperinterface(
            ParameterizedTypeName.get(ctx.traversalClassName, TypeVariableName.get("S"),
                TypeVariableName.get("E")));

    // add the required constructors for instantiation
    defaultTraversalClass.addMethod(MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addStatement("super()")
        .build());
    defaultTraversalClass.addMethod(MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(Graph.class, "graph")
        .addStatement("super($N)", "graph")
        .build());
    defaultTraversalClass.addMethod(MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(ctx.traversalSourceClassName, "traversalSource")
        .addStatement("super($N)", "traversalSource")
        .build());
    defaultTraversalClass.addMethod(MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(ctx.traversalSourceClassName, "traversalSource")
        .addParameter(ctx.graphTraversalAdminClassName, "traversal")
        .addStatement("super($N, $N.asAdmin())", "traversalSource", "traversal")
        .build());

    // add the override
    defaultTraversalClass.addMethod(MethodSpec.methodBuilder("iterate")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .addStatement("return ($T) super.iterate()", ctx.traversalClassName)
        .returns(ParameterizedTypeName.get(ctx.traversalClassName, TypeVariableName.get("S"),
            TypeVariableName.get("E")))
        .build());
    defaultTraversalClass.addMethod(MethodSpec.methodBuilder("asAdmin")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .addStatement("return ($T) super.asAdmin()", GraphTraversal.Admin.class)
        .returns(
            ParameterizedTypeName.get(ctx.graphTraversalAdminClassName, TypeVariableName.get("S"),
                TypeVariableName.get("E")))
        .build());
    defaultTraversalClass.addMethod(MethodSpec.methodBuilder("clone")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .addStatement("return ($T) super.clone()", ctx.defaultTraversalClassName)
        .returns(ParameterizedTypeName.get(ctx.defaultTraversalClassName, TypeVariableName.get("S"),
            TypeVariableName.get("E")))
        .build());

    final var defaultTraversalJavaFile = JavaFile.builder(ctx.packageName,
        defaultTraversalClass.build()).build();
    defaultTraversalJavaFile.writeTo(filer);
  }

  private void generateTraversalInterface(final Context ctx) throws IOException {
    final var traversalInterface = TypeSpec.interfaceBuilder(ctx.traversalClazz)
        .addModifiers(Modifier.PUBLIC)
        .addTypeVariables(Arrays.asList(TypeVariableName.get("S"), TypeVariableName.get("E")))
        .addSuperinterface(TypeName.get(ctx.annotatedDslType.asType()));

    // process the methods of the GremlinDsl annotated class
    for (var templateMethod : findMethodsOfElement(ctx.annotatedDslType, null)) {
      traversalInterface.addMethod(
          constructMethod(templateMethod, ctx.traversalClassName, ctx.dslName,
              Modifier.PUBLIC, Modifier.DEFAULT));
    }

    // process the methods of GraphTraversal
    final var graphTraversalElement = elementUtils.getTypeElement(
        GraphTraversal.class.getCanonicalName());
    final Predicate<ExecutableElement> ignore = e -> e.getSimpleName().contentEquals("asAdmin")
        || e.getSimpleName().contentEquals("iterate");
    for (var templateMethod : findMethodsOfElement(graphTraversalElement, ignore)) {
      traversalInterface.addMethod(
          constructMethod(templateMethod, ctx.traversalClassName, ctx.dslName,
              Modifier.PUBLIC, Modifier.DEFAULT));
    }

    // there are weird things with generics that require this method to be implemented if it isn't already present
    // in the GremlinDsl annotated class extending from GraphTraversal
    traversalInterface.addMethod(MethodSpec.methodBuilder("iterate")
        .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
        .addAnnotation(Override.class)
        .addStatement("$T.super.iterate()", ClassName.get(ctx.annotatedDslType))
        .addStatement("return this")
        .returns(ParameterizedTypeName.get(ctx.traversalClassName, TypeVariableName.get("S"),
            TypeVariableName.get("E")))
        .build());

    final var traversalJavaFile = JavaFile.builder(ctx.packageName, traversalInterface.build())
        .build();
    traversalJavaFile.writeTo(filer);
  }

  private static MethodSpec constructMethod(final Element element, final ClassName returnClazz,
      final String parent,
      final Modifier... modifiers) {
    final var templateMethod = (ExecutableElement) element;
    final var methodName = templateMethod.getSimpleName().toString();

    final var returnType = getReturnTypeDefinition(returnClazz, templateMethod);
    final var methodToAdd = MethodSpec.methodBuilder(methodName)
        .addModifiers(modifiers)
        .addAnnotation(Override.class)
        .addExceptions(templateMethod.getThrownTypes().stream().map(TypeName::get)
            .collect(Collectors.toList()))
        .returns(returnType);

    templateMethod.getTypeParameters()
        .forEach(tp -> methodToAdd.addTypeVariable(TypeVariableName.get(tp)));

    final var parentCall = parent.isEmpty() ? "" : parent + ".";
    final var body = "return ($T) " + parentCall + "super.$L(";
    addMethodBody(methodToAdd, templateMethod, body, ")", returnClazz, methodName);

    return methodToAdd.build();
  }

  private static void addMethodBody(final MethodSpec.Builder methodToAdd,
      final ExecutableElement templateMethod,
      final String startBody, final String endBody, final Object... statementArgs) {
    final var parameters = templateMethod.getParameters();
    final var body = new StringBuilder(startBody);

    final var numberOfParams = parameters.size();
    for (var ix = 0; ix < numberOfParams; ix++) {
      final var param = parameters.get(ix);
      methodToAdd.addParameter(ParameterSpec.get(param));
      body.append(param.getSimpleName());
      if (ix < numberOfParams - 1) {
        body.append(",");
      }
    }

    body.append(endBody);

    // treat a final array as a varargs param
    if (!parameters.isEmpty()
        && parameters.getLast().asType().getKind() == TypeKind.ARRAY) {
      methodToAdd.varargs(true);
    }

    methodToAdd.addStatement(body.toString(), statementArgs);
  }

  private static TypeName getOverridenReturnTypeDefinition(final ClassName returnClazz,
      final String[] typeValues) {
    return ParameterizedTypeName.get(returnClazz, Stream.of(typeValues).map(tv -> {
      try {
        return ClassName.get(Class.forName(tv));
      } catch (ClassNotFoundException cnfe) {
        if (tv.contains("extends")) {
          final var sides = EXTENDS_PATTERN.split(tv);
          final var name = TypeVariableName.get(sides[0]);
          try {
            name.withBounds(ClassName.get(Class.forName(sides[1])));
          } catch (Exception ex) {
            name.withBounds(TypeVariableName.get(sides[1]));
          }
          return name;
        } else {
          return TypeVariableName.get(tv);
        }
      }
    }).toList().toArray(new TypeName[typeValues.length]));
  }

  private static TypeName getReturnTypeDefinition(final ClassName returnClazz,
      final ExecutableElement templateMethod) {
    final var returnTypeArguments = getTypeArguments(templateMethod);

    // build a return type with appropriate generic declarations (if such declarations are present)
    return returnTypeArguments.isEmpty() ?
        returnClazz :
        ParameterizedTypeName.get(returnClazz,
            returnTypeArguments.stream().map(TypeName::get).toList()
                .toArray(new TypeName[returnTypeArguments.size()]));
  }

  private static void validateDSL(final Element dslElement) throws ProcessorException {
    if (dslElement.getKind() != ElementKind.INTERFACE) {
      throw new ProcessorException(dslElement, "Only interfaces can be annotated with @%s",
          GremlinDsl.class.getSimpleName());
    }

    final var typeElement = (TypeElement) dslElement;
    if (!typeElement.getModifiers().contains(Modifier.PUBLIC)) {
      throw new ProcessorException(dslElement, "The interface %s is not public.",
          typeElement.getQualifiedName());
    }
  }

  private static List<ExecutableElement> findMethodsOfElement(final Element element,
      final Predicate<ExecutableElement> ignore) {
    @SuppressWarnings("RedundantExplicitVariableType") final Predicate<ExecutableElement> test =
        null == ignore ? ee -> false : ignore;
    return element.getEnclosedElements().stream()
        .filter(ee -> ee.getKind() == ElementKind.METHOD)
        .map(ee -> (ExecutableElement) ee)
        .filter(ee -> !test.test(ee)).collect(Collectors.toList());
  }

  private static List<? extends TypeMirror> getTypeArguments(
      final ExecutableElement templateMethod) {
    final var returnTypeMirror = (DeclaredType) templateMethod.getReturnType();
    return returnTypeMirror.getTypeArguments();
  }

  private class Context {

    private final TypeElement annotatedDslType;
    private final String packageName;
    private final String dslName;
    private final String traversalClazz;
    private final ClassName traversalClassName;
    private final String traversalSourceClazz;
    private final ClassName traversalSourceClassName;
    private final String defaultTraversalClazz;
    private final ClassName defaultTraversalClassName;
    private final ClassName graphTraversalAdminClassName;
    private final TypeElement traversalSourceDslType;
    private final boolean generateDefaultMethods;

    public Context(final TypeElement dslElement) {
      annotatedDslType = dslElement;

      // gets the annotation on the dsl class/interface
      var gremlinDslAnnotation = dslElement.getAnnotation(GremlinDsl.class);
      generateDefaultMethods = gremlinDslAnnotation.generateDefaultMethods();

      traversalSourceDslType = elementUtils.getTypeElement(gremlinDslAnnotation.traversalSource());
      packageName = getPackageName(dslElement, gremlinDslAnnotation);

      // create the Traversal implementation interface
      dslName = dslElement.getSimpleName().toString();
      final var dslPrefix = dslName.substring(0,
          dslName.length() - "TraversalDSL".length()); // chop off "TraversalDSL"
      traversalClazz = dslPrefix + "Traversal";
      traversalClassName = ClassName.get(packageName, traversalClazz);
      traversalSourceClazz = dslPrefix + "TraversalSource";
      traversalSourceClassName = ClassName.get(packageName, traversalSourceClazz);
      defaultTraversalClazz = "Default" + traversalClazz;
      defaultTraversalClassName = ClassName.get(packageName, defaultTraversalClazz);
      graphTraversalAdminClassName = ClassName.get(GraphTraversal.Admin.class);
    }

    private String getPackageName(final Element dslElement, final GremlinDsl gremlinDslAnnotation) {
      return gremlinDslAnnotation.packageName().isEmpty() ?
          elementUtils.getPackageOf(dslElement).getQualifiedName().toString() :
          gremlinDslAnnotation.packageName();
    }
  }
}
