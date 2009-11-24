/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.spockframework.compiler;

import java.util.List;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.stmt.Statement;

import static org.spockframework.compiler.Identifiers.*;
import org.spockframework.compiler.model.*;
import org.spockframework.util.SyntaxException;

import spock.lang.Shared;

/**
 * Given the abstract syntax tree of a Groovy class representing a Spock
 * specification, builds an object model of the specification.
 *
 * @author Peter Niederwieser
 */
public class SpecParser implements GroovyClassVisitor {
  private Spec spec;
  private int fieldCount = 0;
  private int featureMethodCount = 0;

  public Spec build(ClassNode clazz) {
    spec = new Spec(clazz);
    clazz.visitContents(this);
    return spec;
  }

  public void visitClass(ClassNode clazz) {
   throw new UnsupportedOperationException("visitClass");
  }

  public void visitField(FieldNode gField) {
    PropertyNode owner = spec.getAst().getProperty(gField.getName());
    // precaution against internal internal fields that aren't marked as synthetic
    if (gField.getName().startsWith("$")) return;
    if (gField.isStatic() || (gField.isSynthetic() && owner == null)) return;

    Field field = new Field(spec, gField, fieldCount++);
    field.setShared(AstUtil.hasAnnotation(gField, Shared.class));
    field.setOwner(owner);
    spec.getFields().add(field);
  }

  public void visitProperty(PropertyNode node) {}

  public void visitConstructor(ConstructorNode constructor) {
    if (AstUtil.isSynthetic(constructor)) return;

    throw new SyntaxException(constructor,
"Constructors are not allowed; instead, define a 'setup()' or 'setupSpec()' method");
   }

  public void visitMethod(MethodNode method) {
    if (isIgnoredMethod(method)) return;
    
    if (isFixtureMethod(method))
      buildFixtureMethod(method);
    else if (isFeatureMethod(method))
      buildFeatureMethod(method);
    else buildHelperMethod(method);
  }

  private boolean isIgnoredMethod(MethodNode method) {
    return AstUtil.isSynthetic(method);
  }

  // IDEA: check for misspellings other than wrong capitalization
  private static boolean isFixtureMethod(MethodNode method) {
    String name = method.getName();

    for (String fmName : FIXTURE_METHODS) {
      if (!fmName.equalsIgnoreCase(name)) continue;

      if (!fmName.equals(name))
        throw new SyntaxException(method, "Misspelled '%s()' method (wrong capitalization)", fmName);
      if (method.isStatic())
        throw new SyntaxException(method, "Fixture methods must not be static");

      return true;
    }

    return false;
  }

  private void buildFixtureMethod(MethodNode method) {
    FixtureMethod fixtureMethod = new FixtureMethod(spec, method);

    Block block = new AnonymousBlock(fixtureMethod);
    fixtureMethod.addBlock(block);
    List<Statement> stats = AstUtil.getStatements(method);
    block.getAst().addAll(stats);
    stats.clear();

    String name = method.getName();
    if (name.equals(SETUP)) spec.setSetup(fixtureMethod);
    else if (name.equals(CLEANUP)) spec.setCleanup(fixtureMethod);
    else if (name.equals(SETUP_SPEC_METHOD)) spec.setSetupSpec(fixtureMethod);
    else spec.setCleanupSpec(fixtureMethod);
  }

  // IDEA: recognize feature methods by looking at signature only
  // rationale: current solution can sometimes be unintuitive, e.g.
  // for methods with empty body, or for methods with single assert
  // potential indicators for feature methods:
  // - public visibility (and no fixture method)
  // - no visibility modifier (and no fixture method) (source lookup required?)
  // - method name given as string literal (requires source lookup)
  private static boolean isFeatureMethod(MethodNode method) {
    for (Statement stat : AstUtil.getStatements(method)) {
      String label = stat.getStatementLabel();
      if (label == null) continue;

      if (method.isStatic())
        throw new SyntaxException(method, "Feature methods must not be static");

      return true;
    }

    return false;
  }

  private void buildFeatureMethod(MethodNode method) {
    Method feature = new FeatureMethod(spec, method, featureMethodCount++);
    spec.getMethods().add(feature);
    buildBlocks(feature);
  }

  private void buildHelperMethod(MethodNode method) {  
    Method helper = new HelperMethod(spec, method);
    spec.getMethods().add(helper);

    Block block = helper.addBlock(new AnonymousBlock(helper));
    List<Statement> stats = AstUtil.getStatements(method);
    block.getAst().addAll(stats);
    stats.clear();
  }

  private void buildBlocks(Method method) {
    List<Statement> stats = AstUtil.getStatements(method.getAst());
    Block currBlock = method.addBlock(new AnonymousBlock(method));

    for (Statement stat : stats) {
      if (stat.getStatementLabel() == null)
        currBlock.getAst().add(stat);
      else
        currBlock = addBlock(method, stat);
    }
    
    checkIsValidSuccessor(method, BlockParseInfo.METHOD_END,
        method.getAst().getLastLineNumber(), method.getAst().getLastColumnNumber());

    // now that statements have been copied to blocks, the original statement
    // list is cleared; statements will be copied back after rewriting is done
    stats.clear();
  }

  private Block addBlock(Method method, Statement stat) {
    String label = stat.getStatementLabel();

    for (BlockParseInfo blockInfo: BlockParseInfo.values()) {
	  	if (!label.equals(blockInfo.toString())) continue;

      checkIsValidSuccessor(method, blockInfo, stat.getLineNumber(), stat.getColumnNumber());
      Block block = blockInfo.addNewBlock(method);
      String description = getDescription(stat);
      if (description == null)
        block.getAst().add(stat);
      else
        block.getDescriptions().add(description);

      return block;
		}

		throw new SyntaxException(stat, "Unrecognized block label: " + label);
  }

  private String getDescription(Statement stat) {
    ConstantExpression constExpr = AstUtil.getExpression(stat, ConstantExpression.class);
    return constExpr == null || !(constExpr.getValue() instanceof String) ?
        null : (String)constExpr.getValue();
  }

  private void checkIsValidSuccessor(Method method, BlockParseInfo blockInfo, int line, int column) {
    BlockParseInfo oldBlockInfo = method.getLastBlock().getParseInfo();
    if (!oldBlockInfo.getSuccessors(method).contains(blockInfo))
      throw new SyntaxException(line, column, "'%s' is not allowed here; instead, use one of: %s",
          blockInfo, oldBlockInfo.getSuccessors(method), method.getName(), oldBlockInfo, blockInfo);
  }
}