// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.java;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.analysis.config.BuildConfiguration.StrictDepsMode.OFF;
import static com.google.devtools.build.lib.rules.java.JavaCommon.collectJavaCompilationArgs;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.StrictDepsMode;
import com.google.devtools.build.lib.rules.java.JavaConfiguration.JavaClasspathMode;
import com.google.devtools.build.lib.rules.java.JavaRuleOutputJarsProvider.OutputJar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A class to create Java compile actions in a way that is consistent with java_library. Rules that
 * generate source files and emulate java_library on top of that should use this class
 * instead of the lower-level API in JavaCompilationHelper.
 *
 * <p>Rules that want to use this class are required to have an implicit dependency on the
 * Java compiler.
 */
public final class JavaLibraryHelper {
  private final RuleContext ruleContext;

  private Artifact output;
  private final List<Artifact> sourceJars = new ArrayList<>();
  private final List<Artifact> sourceFiles = new ArrayList<>();
  private final List<Artifact> resources = new ArrayList<>();

  /**
   * Contains all the dependencies; these are treated as both compile-time and runtime dependencies.
   */
  private final List<JavaCompilationArgsProvider> deps = new ArrayList<>();
  private final List<JavaCompilationArgsProvider> exports = new ArrayList<>();
  private final List<JavaPluginInfoProvider> plugins = new ArrayList<>();
  private ImmutableList<String> javacOpts = ImmutableList.of();
  private ImmutableList<Artifact> sourcePathEntries = ImmutableList.of();
  private StrictDepsMode strictDepsMode = StrictDepsMode.OFF;
  private JavaClasspathMode classpathMode = JavaClasspathMode.OFF;
  private String injectingRuleKind;
  private boolean neverlink;

  public JavaLibraryHelper(RuleContext ruleContext) {
    this.ruleContext = ruleContext;
    ruleContext.getConfiguration();
    this.classpathMode = ruleContext.getFragment(JavaConfiguration.class).getReduceJavaClasspath();
  }

  public JavaLibraryHelper setNeverlink(boolean neverlink) {
    this.neverlink = neverlink;
    return this;
  }

  /**
   * Sets the final output jar; if this is not set, then the {@link #build} method throws an {@link
   * IllegalStateException}. Note that this class may generate not just the output itself, but also
   * a number of additional intermediate files and outputs.
   */
  public JavaLibraryHelper setOutput(Artifact output) {
    this.output = output;
    return this;
  }

  /**
   * Adds the given source jars. Any .java files in these jars will be compiled.
   */
  public JavaLibraryHelper addSourceJars(Iterable<Artifact> sourceJars) {
    Iterables.addAll(this.sourceJars, sourceJars);
    return this;
  }

  /**
   * Adds the given source jars. Any .java files in these jars will be compiled.
   */
  public JavaLibraryHelper addSourceJars(Artifact... sourceJars) {
    return this.addSourceJars(Arrays.asList(sourceJars));
  }

  public JavaLibraryHelper addResources(Iterable<Artifact> resources) {
    Iterables.addAll(this.resources, resources);
    return this;
  }

  public JavaLibraryHelper addDep(JavaCompilationArgsProvider provider) {
    checkNotNull(provider);
    this.deps.add(provider);
    return this;
  }

  /**
   * Adds the given source files to be compiled.
   */
  public JavaLibraryHelper addSourceFiles(Iterable<Artifact> sourceFiles) {
    Iterables.addAll(this.sourceFiles, sourceFiles);
    return this;
  }

  public JavaLibraryHelper addAllDeps(
      Iterable<JavaCompilationArgsProvider> providers) {
    Iterables.addAll(deps, providers);
    return this;
  }

  public JavaLibraryHelper addExport(JavaCompilationArgsProvider provider) {
    exports.add(provider);
    return this;
  }

  public JavaLibraryHelper addAllExports(Iterable<JavaCompilationArgsProvider> providers) {
    Iterables.addAll(exports, providers);
    return this;
  }

  public JavaLibraryHelper addAllPlugins(Iterable<JavaPluginInfoProvider> providers) {
    Iterables.addAll(plugins, providers);
    return this;
  }

  /**
   * Sets the compiler options.
   */
  public JavaLibraryHelper setJavacOpts(Iterable<String> javacOpts) {
    this.javacOpts = ImmutableList.copyOf(javacOpts);
    return this;
  }

  public JavaLibraryHelper setSourcePathEntries(List<Artifact> sourcepathEntries) {
    this.sourcePathEntries = ImmutableList.copyOf(sourcepathEntries);
    return this;
  }

  public JavaLibraryHelper setInjectingRuleKind(String injectingRuleKind) {
    this.injectingRuleKind = injectingRuleKind;
    return this;
  }

  /**
   * When in strict mode, compiling the source-jars passed to this JavaLibraryHelper will break if
   * they depend on classes not in any of the {@link
   * JavaCompilationArgsProvider#getDirectCompileTimeJars()} passed in {@link #addDep}, even if they
   * do appear in {@link JavaCompilationArgsProvider#getTransitiveCompileTimeJars()}. That is,
   * depending on a class requires a direct dependency on it.
   *
   * <p>Contrast this with the strictness-parameter to {@link #buildCompilationArgsProvider}, which
   * controls whether others depending on the result of this compilation, can perform strict-deps
   * checks at all.
   */
  public JavaLibraryHelper setCompilationStrictDepsMode(StrictDepsMode strictDepsMode) {
    this.strictDepsMode = strictDepsMode;
    return this;
  }

  /**
   * Creates the compile actions (including the ones for ijar and source jar). Also fills in the
   * {@link JavaRuleOutputJarsProvider.Builder} with the corresponding compilation outputs.
   *
   * @param semantics implementation specific java rules semantics
   * @param javaToolchainProvider used for retrieving misc java tools
   * @param hostJavabase the target of the host javabase used to retrieve the java executable and
   *        its necessary inputs
   * @param jacocoInstrumental jacoco jars needed when running coverage
   * @param outputJarsBuilder populated with the outputs of the created actions
   * @param outputSourceJar if not-null, the output of an source jar action that will be created
   */
  public JavaCompilationArtifacts build(
      JavaSemantics semantics,
      JavaToolchainProvider javaToolchainProvider,
      JavaRuntimeInfo hostJavabase,
      Iterable<Artifact> jacocoInstrumental,
      JavaRuleOutputJarsProvider.Builder outputJarsBuilder,
      boolean createOutputSourceJar,
      @Nullable Artifact outputSourceJar) {
    return build(
        semantics,
        javaToolchainProvider,
        hostJavabase,
        jacocoInstrumental,
        outputJarsBuilder,
        createOutputSourceJar,
        outputSourceJar,
        /* javaInfoBuilder= */ null,
        ImmutableList.of()); // ignored when javaInfoBuilder is null
  }

  public JavaCompilationArtifacts build(
      JavaSemantics semantics,
      JavaToolchainProvider javaToolchainProvider,
      JavaRuntimeInfo hostJavabase,
      Iterable<Artifact> jacocoInstrumental,
      JavaRuleOutputJarsProvider.Builder outputJarsBuilder,
      boolean createOutputSourceJar,
      @Nullable Artifact outputSourceJar,
      @Nullable JavaInfo.Builder javaInfoBuilder,
      Iterable<JavaGenJarsProvider> transitiveJavaGenJars) {
    Preconditions.checkState(output != null, "must have an output file; use setOutput()");
    Preconditions.checkState(
        !createOutputSourceJar || outputSourceJar != null,
        "outputSourceJar cannot be null when createOutputSourceJar is true");
    JavaTargetAttributes.Builder attributes = new JavaTargetAttributes.Builder(semantics);
    attributes.addSourceJars(sourceJars);
    attributes.addSourceFiles(sourceFiles);
    addDepsToAttributes(attributes);
    attributes.setStrictJavaDeps(strictDepsMode);
    attributes.setTargetLabel(ruleContext.getLabel());
    attributes.setInjectingRuleKind(injectingRuleKind);
    attributes.setSourcePath(sourcePathEntries);
    JavaCommon.addPlugins(attributes, plugins);

    for (Artifact resource : resources) {
      attributes.addResource(
          JavaHelper.getJavaResourcePath(semantics, ruleContext, resource), resource);
    }

    if (isStrict() && classpathMode != JavaClasspathMode.OFF) {
      JavaCompilationHelper.addDependencyArtifactsToAttributes(
          attributes, deps);
    }

    JavaCompilationArtifacts.Builder artifactsBuilder = new JavaCompilationArtifacts.Builder();
    JavaCompilationHelper helper =
        new JavaCompilationHelper(ruleContext, semantics, javacOpts, attributes,
            javaToolchainProvider,
            hostJavabase,
            jacocoInstrumental);
    Artifact outputDepsProto = helper.createOutputDepsProtoArtifact(output, artifactsBuilder);

    Artifact manifestProtoOutput = helper.createManifestProtoOutput(output);

    Artifact genSourceJar = null;
    Artifact genClassJar = null;
    if (helper.usesAnnotationProcessing()) {
      genClassJar = helper.createGenJar(output);
      genSourceJar = helper.createGensrcJar(output);
      helper.createGenJarAction(output, manifestProtoOutput, genClassJar, hostJavabase);
    }

    helper.createCompileAction(
        output,
        manifestProtoOutput,
        genSourceJar,
        outputDepsProto,
        /* instrumentationMetadataJar= */ null,
        /* nativeHeaderOutput= */ null);

    artifactsBuilder.addRuntimeJar(output);
    Artifact iJar = helper.createCompileTimeJarAction(output, artifactsBuilder);

    if (createOutputSourceJar) {
      helper.createSourceJarAction(
          outputSourceJar, genSourceJar, javaToolchainProvider, hostJavabase);
    }
    ImmutableList<Artifact> outputSourceJars =
        outputSourceJar == null ? ImmutableList.of() : ImmutableList.of(outputSourceJar);
    outputJarsBuilder
        .addOutputJar(new OutputJar(output, iJar, outputSourceJars))
        .setJdeps(outputDepsProto);

    JavaCompilationArtifacts javaArtifacts = artifactsBuilder.build();
    if (javaInfoBuilder != null) {
      ClasspathConfiguredFragment classpathFragment =
          new ClasspathConfiguredFragment(
              javaArtifacts,
              attributes.build(),
              neverlink,
              JavaCompilationHelper.getBootClasspath(javaToolchainProvider));

      javaInfoBuilder.addProvider(
          JavaCompilationInfoProvider.class,
          new JavaCompilationInfoProvider.Builder()
              .setJavacOpts(javacOpts)
              .setBootClasspath(classpathFragment.getBootClasspath())
              .setCompilationClasspath(classpathFragment.getCompileTimeClasspath())
              .setRuntimeClasspath(classpathFragment.getRuntimeClasspath())
              .build());

      javaInfoBuilder.addProvider(
          JavaGenJarsProvider.class,
          createJavaGenJarsProvider(helper, genClassJar, genSourceJar, transitiveJavaGenJars));
    }

    return javaArtifacts;
  }

  private JavaGenJarsProvider createJavaGenJarsProvider(
      JavaCompilationHelper helper,
      @Nullable Artifact genClassJar,
      @Nullable Artifact genSourceJar,
      Iterable<JavaGenJarsProvider> transitiveJavaGenJars) {
    return JavaGenJarsProvider.create(
        helper.usesAnnotationProcessing(),
        genClassJar,
        genSourceJar,
        plugins,
        transitiveJavaGenJars);
  }

  /**
   * Returns a JavaCompilationArgsProvider that fully encapsulates this compilation, based on the
   * result of a call to build(). (that is, it contains the compile-time and runtime jars, separated
   * by direct vs transitive jars).
   *
   * @param isReportedAsStrict if true, the result's direct JavaCompilationArgs only contain classes
   *     resulting from compiling the source-jars. If false, the direct JavaCompilationArgs contain
   *     both these classes, as well as any classes from transitive dependencies. A value of 'false'
   *     means this compilation cannot be checked for strict-deps, by any consumer (depending)
   *     compilation. Contrast this with {@link #setCompilationStrictDepsMode}.
   */
  public JavaCompilationArgsProvider buildCompilationArgsProvider(
      JavaCompilationArtifacts artifacts, boolean isReportedAsStrict, boolean isNeverlink) {

    JavaCompilationArgsProvider directArgs =
        collectJavaCompilationArgs(
            /* isNeverLink= */ isNeverlink,
            /* srcLessDepsExport= */ false,
            artifacts,
            deps,
            /* runtimeDeps= */ ImmutableList.of(),
            exports);

    if (!isReportedAsStrict) {
      directArgs = JavaCompilationArgsProvider.makeNonStrict(directArgs);
    }
    return directArgs;
  }

  private void addDepsToAttributes(JavaTargetAttributes.Builder attributes) {
    JavaCompilationArgsProvider argsProvider = JavaCompilationArgsProvider.merge(deps);

    if (isStrict()) {
      attributes.addDirectJars(argsProvider.getDirectCompileTimeJars());
    }

    attributes.addCompileTimeClassPathEntries(argsProvider.getTransitiveCompileTimeJars());
    attributes.addRuntimeClassPathEntries(argsProvider.getRuntimeJars());
    attributes.addInstrumentationMetadataEntries(argsProvider.getInstrumentationMetadata());
  }


  private boolean isStrict() {
    return strictDepsMode != OFF;
  }
}
