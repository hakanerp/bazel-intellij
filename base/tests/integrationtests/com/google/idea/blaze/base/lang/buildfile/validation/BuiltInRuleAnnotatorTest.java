/*
 * Copyright 2017 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.lang.buildfile.validation;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.idea.blaze.base.lang.buildfile.BuildFileIntegrationTestCase;
import com.google.idea.blaze.base.lang.buildfile.language.semantics.AttributeDefinition;
import com.google.idea.blaze.base.lang.buildfile.language.semantics.BuildLanguageSpec;
import com.google.idea.blaze.base.lang.buildfile.language.semantics.BuildLanguageSpecProvider;
import com.google.idea.blaze.base.lang.buildfile.language.semantics.RuleDefinition;
import com.google.idea.blaze.base.lang.buildfile.psi.BuildFile;
import com.google.idea.blaze.base.lang.buildfile.psi.FuncallExpression;
import com.google.idea.blaze.base.lang.buildfile.psi.util.PsiUtils;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.repackaged.devtools.build.lib.query2.proto.proto2api.Build.Attribute.Discriminator;
import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.AnnotationSession;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration tests for {@link BuiltInRuleAnnotator}. */
@RunWith(JUnit4.class)
public class BuiltInRuleAnnotatorTest extends BuildFileIntegrationTestCase {

  private static final AttributeDefinition NAME_ATTRIBUTE =
      new AttributeDefinition("name", Discriminator.STRING, true, null, null);

  private static final AttributeDefinition SRCS_ATTRIBUTE =
      new AttributeDefinition("srcs", Discriminator.LABEL_LIST, false, null, null);

  private static final AttributeDefinition NEVERLINK_ATTRIBUTE =
      new AttributeDefinition("neverlink", Discriminator.BOOLEAN, false, null, null);

  private static final RuleDefinition JAVA_TEST =
      new RuleDefinition(
          "java_test",
          ImmutableMap.of(
              "name", NAME_ATTRIBUTE, "srcs", SRCS_ATTRIBUTE, "neverlink", NEVERLINK_ATTRIBUTE),
          null);

  private MockBuildLanguageSpecProvider specProvider;

  @Before
  public final void before() {
    specProvider = new MockBuildLanguageSpecProvider();
    registerApplicationService(BuildLanguageSpecProvider.class, specProvider);
  }

  @Test
  public void testUnrecognizedRuleTypeIgnored() {
    setRules("java_library", "java_binary");
    BuildFile file =
        createBuildFile(
            new WorkspacePath("java/com/google/BUILD"), "java_import(name = 'import',)");

    assertNoErrors(file);
  }

  @Test
  public void testNoErrorsForValidStandardRule() {
    specProvider.setRules(ImmutableMap.of(JAVA_TEST.name, JAVA_TEST));
    BuildFile file =
        createBuildFile(
            new WorkspacePath("java/com/google/BUILD"),
            "java_test(",
            "    name = 'import',",
            "    srcs = ['src'],",
            "    neverlink = 0,",
            ")");
    assertNoErrors(file);
  }

  @Test
  public void testGlobTreatedAsList() {
    specProvider.setRules(ImmutableMap.of(JAVA_TEST.name, JAVA_TEST));
    BuildFile file =
        createBuildFile(
            new WorkspacePath("java/com/google/BUILD"),
            "java_test(",
            "    name = 'import',",
            "    srcs = glob(['src']),",
            "    neverlink = 0,",
            ")");
    assertNoErrors(file);
  }

  @Test
  public void testMissingMandatoryAttributeError() {
    specProvider.setRules(ImmutableMap.of(JAVA_TEST.name, JAVA_TEST));
    BuildFile file =
        createBuildFile(
            new WorkspacePath("java/com/google/BUILD"),
            "java_test(",
            "    srcs = ['src'],",
            "    neverlink = 0,",
            ")");
    assertHasError(file, "Target missing required attribute(s): name");
  }

  @Test
  public void testInvalidAttributeTypeError() {
    specProvider.setRules(ImmutableMap.of(JAVA_TEST.name, JAVA_TEST));
    BuildFile file =
        createBuildFile(
            new WorkspacePath("java/com/google/BUILD"),
            "java_test(",
            "    name = 'import',",
            "    srcs = 'src',",
            "    neverlink = 0,",
            ")");
    assertHasError(
        file,
        String.format(
            "Invalid value for attribute 'srcs'. Expected a value of type '%s'",
            Discriminator.LABEL_LIST));
  }

  @Test
  public void testInvalidAttributeTypeError2() {
    specProvider.setRules(ImmutableMap.of(JAVA_TEST.name, JAVA_TEST));
    BuildFile file =
        createBuildFile(
            new WorkspacePath("java/com/google/BUILD"),
            "java_test(",
            "    name = ['import'],",
            "    srcs = ['src'],",
            "    neverlink = 0,",
            ")");
    assertHasError(
        file,
        String.format(
            "Invalid value for attribute 'name'. Expected a value of type '%s'",
            Discriminator.STRING));
  }

  @Test
  public void testNoErrorForIfStatement() {
    specProvider.setRules(ImmutableMap.of(JAVA_TEST.name, JAVA_TEST));
    BuildFile file =
        createBuildFile(
            new WorkspacePath("java/com/google/BUILD"),
            "java_test(",
            "    name = 'import',",
            "    srcs = ['src'],",
            "    neverlink = if test : a else b",
            ")");
    // we don't know what the if statement evaluates to
    assertNoErrors(file);
  }

  @Test
  public void testUnknownReferenceIgnored() {
    specProvider.setRules(ImmutableMap.of(JAVA_TEST.name, JAVA_TEST));
    BuildFile file =
        createBuildFile(
            new WorkspacePath("java/com/google/BUILD"),
            "java_test(",
            "    name = 'import',",
            "    srcs = ['src'],",
            "    neverlink = ref,",
            ")");
    assertNoErrors(file);
  }

  @Test
  public void testKnownIncorrectReference() {
    specProvider.setRules(ImmutableMap.of(JAVA_TEST.name, JAVA_TEST));
    BuildFile file =
        createBuildFile(
            new WorkspacePath("java/com/google/BUILD"),
            "ref = []",
            "java_test(",
            "    name = 'import',",
            "    srcs = ['src'],",
            "    neverlink = ref,",
            ")");
    assertHasError(
        file,
        String.format(
            "Invalid value for attribute 'neverlink'. Expected a value of type '%s'",
            Discriminator.BOOLEAN));
  }

  @Test
  public void testValidValueInsideParenthesizedExpression() {
    specProvider.setRules(ImmutableMap.of(JAVA_TEST.name, JAVA_TEST));
    BuildFile file =
        createBuildFile(
            new WorkspacePath("java/com/google/BUILD"),
            "java_test(",
            "    name = ('import' + '_suffix'),",
            ")");
    assertNoErrors(file);
  }

  @Test
  public void testInvalidValueInsideParenthesizedExpression() {
    specProvider.setRules(ImmutableMap.of(JAVA_TEST.name, JAVA_TEST));
    BuildFile file =
        createBuildFile(
            new WorkspacePath("java/com/google/BUILD"), "java_test(", "    name = (1),", ")");
    assertHasError(
        file,
        String.format(
            "Invalid value for attribute 'name'. Expected a value of type '%s'",
            Discriminator.STRING));
  }

  @Test
  public void testUnresolvedValueInsideParenthesizedExpression() {
    specProvider.setRules(ImmutableMap.of(JAVA_TEST.name, JAVA_TEST));
    BuildFile file =
        createBuildFile(
            new WorkspacePath("java/com/google/BUILD"), "java_test(", "    name = (ref),", ")");
    assertNoErrors(file);
  }

  private void setRules(String... ruleNames) {
    ImmutableMap.Builder<String, RuleDefinition> rules = ImmutableMap.builder();
    for (String name : ruleNames) {
      rules.put(name, new RuleDefinition(name, ImmutableMap.of(), null));
    }
    specProvider.setRules(rules.build());
  }

  private static class MockBuildLanguageSpecProvider implements BuildLanguageSpecProvider {

    BuildLanguageSpec languageSpec = new BuildLanguageSpec(ImmutableMap.of());

    void setRules(ImmutableMap<String, RuleDefinition> rules) {
      languageSpec = new BuildLanguageSpec(rules);
    }

    @Nullable
    @Override
    public BuildLanguageSpec getLanguageSpec(Project project) {
      return languageSpec;
    }
  }

  private void assertNoErrors(BuildFile file) {
    assertThat(validateFile(file)).isEmpty();
  }

  private void assertHasError(BuildFile file, String error) {
    assertHasError(validateFile(file), error);
  }

  private static void assertHasError(List<Annotation> annotations, String error) {
    List<String> messages =
        annotations.stream().map(Annotation::getMessage).collect(Collectors.toList());

    assertThat(messages).contains(error);
  }

  private List<Annotation> validateFile(BuildFile file) {
    BuiltInRuleAnnotator annotator = createAnnotator(file);
    PsiUtils.findAllChildrenOfClassRecursive(file, FuncallExpression.class)
        .forEach(annotator::visitFuncallExpression);
    return annotationHolder;
  }

  private BuiltInRuleAnnotator createAnnotator(PsiFile file) {
    annotationHolder = new AnnotationHolderImpl(new AnnotationSession(file));
    return new BuiltInRuleAnnotator() {
      @Override
      protected AnnotationHolder getHolder() {
        return annotationHolder;
      }
    };
  }

  private AnnotationHolderImpl annotationHolder = null;
}