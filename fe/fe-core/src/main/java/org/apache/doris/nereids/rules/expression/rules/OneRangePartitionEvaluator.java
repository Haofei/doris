// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.expression.rules;

import org.apache.doris.analysis.LiteralExpr;
import org.apache.doris.catalog.PartitionKey;
import org.apache.doris.catalog.RangePartitionItem;
import org.apache.doris.catalog.Type;
import org.apache.doris.common.Pair;
import org.apache.doris.nereids.CascadesContext;
import org.apache.doris.nereids.exceptions.AnalysisException;
import org.apache.doris.nereids.rules.expression.ExpressionRewriteContext;
import org.apache.doris.nereids.rules.expression.rules.OneRangePartitionEvaluator.EvaluateRangeInput;
import org.apache.doris.nereids.rules.expression.rules.OneRangePartitionEvaluator.EvaluateRangeResult;
import org.apache.doris.nereids.rules.expression.rules.PartitionRangeExpander.PartitionSlotType;
import org.apache.doris.nereids.trees.expressions.And;
import org.apache.doris.nereids.trees.expressions.EqualTo;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.GreaterThan;
import org.apache.doris.nereids.trees.expressions.GreaterThanEqual;
import org.apache.doris.nereids.trees.expressions.InPredicate;
import org.apache.doris.nereids.trees.expressions.IsNull;
import org.apache.doris.nereids.trees.expressions.LessThan;
import org.apache.doris.nereids.trees.expressions.LessThanEqual;
import org.apache.doris.nereids.trees.expressions.Not;
import org.apache.doris.nereids.trees.expressions.NullSafeEqual;
import org.apache.doris.nereids.trees.expressions.Or;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.functions.Monotonic;
import org.apache.doris.nereids.trees.expressions.functions.scalar.NonNullable;
import org.apache.doris.nereids.trees.expressions.functions.scalar.Nullable;
import org.apache.doris.nereids.trees.expressions.literal.BooleanLiteral;
import org.apache.doris.nereids.trees.expressions.literal.Literal;
import org.apache.doris.nereids.trees.expressions.literal.MaxLiteral;
import org.apache.doris.nereids.trees.expressions.literal.NullLiteral;
import org.apache.doris.nereids.trees.expressions.visitor.ExpressionVisitor;
import org.apache.doris.nereids.types.BooleanType;
import org.apache.doris.nereids.util.ExpressionUtils;
import org.apache.doris.nereids.util.Utils;

import com.google.common.base.Preconditions;
import com.google.common.collect.BoundType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * OneRangePartitionEvaluator.
 *
 * you can see the process steps in the comment of PartitionSlotInput.columnRanges
 */
public class OneRangePartitionEvaluator<K>
        extends ExpressionVisitor<EvaluateRangeResult, EvaluateRangeInput>
        implements OnePartitionEvaluator<K> {
    private final K partitionIdent;
    private final List<Slot> partitionSlots;
    private final RangePartitionItem partitionItem;
    private final ExpressionRewriteContext expressionRewriteContext;
    private final List<PartitionSlotType> partitionSlotTypes;
    private final List<Literal> lowers;
    private final List<Literal> uppers;
    private final List<List<Expression>> inputs;
    // whether the Expression in partition range may be null.
    private final Map<Expression, Boolean> partitionSlotContainsNull;
    private final Map<Slot, PartitionSlotType> slotToType;

    /** OneRangePartitionEvaluator */
    public OneRangePartitionEvaluator(K partitionIdent, List<Slot> partitionSlots,
            RangePartitionItem partitionItem, CascadesContext cascadesContext, int expandThreshold) {
        this.partitionIdent = partitionIdent;
        this.partitionSlots = Objects.requireNonNull(partitionSlots, "partitionSlots cannot be null");
        this.partitionItem = Objects.requireNonNull(partitionItem, "partitionItem cannot be null");
        this.expressionRewriteContext = new ExpressionRewriteContext(
                Objects.requireNonNull(cascadesContext, "cascadesContext cannot be null"));

        Range<PartitionKey> range = partitionItem.getItems();
        this.lowers = toNereidsLiterals(range.lowerEndpoint());
        this.uppers = toNereidsLiterals(range.upperEndpoint());

        this.partitionSlotTypes = PartitionRangeExpander.computePartitionSlotTypes(lowers, uppers);

        if (partitionSlots.size() == 1) {
            // fast path
            Slot partSlot = partitionSlots.get(0);
            this.slotToType = ImmutableMap.of(partSlot, partitionSlotTypes.get(0));
            this.partitionSlotContainsNull = new HashMap<>();
            partitionSlotContainsNull.put(partSlot, range.lowerEndpoint().getKeys().get(0).isMinValue());
        } else {
            // slow path
            this.slotToType = Maps.newHashMap();
            for (int i = 0; i < partitionSlots.size(); i++) {
                slotToType.put(partitionSlots.get(i), partitionSlotTypes.get(i));
            }

            this.partitionSlotContainsNull = Maps.newHashMap();
            for (int i = 0; i < partitionSlots.size(); i++) {
                Slot slot = partitionSlots.get(i);
                if (!slot.nullable()) {
                    partitionSlotContainsNull.put(slot, false);
                    continue;
                }
                PartitionSlotType partitionSlotType = partitionSlotTypes.get(i);
                boolean maybeNull;
                switch (partitionSlotType) {
                    case CONST:
                    case RANGE:
                        maybeNull = range.lowerEndpoint().getKeys().get(i).isMinValue();
                        break;
                    case OTHER:
                        maybeNull = true;
                        break;
                    default:
                        throw new AnalysisException("Unknown partition slot type: " + partitionSlotType);
                }
                partitionSlotContainsNull.put(slot, maybeNull);
            }
        }

        List<List<Expression>> expandInputs = PartitionRangeExpander.tryExpandRange(
                partitionSlots, lowers, uppers, partitionSlotTypes, expandThreshold);
        // after expand range, we will get 2 dimension list like list:
        // part_col1: [1], part_col2:[4, 5, 6], we should combine it to
        // [1, 4], [1, 5], [1, 6] as inputs
        this.inputs = Utils.allCombinations(expandInputs);
    }

    @Override
    public K getPartitionIdent() {
        return partitionIdent;
    }

    @Override
    public List<Map<Slot, PartitionSlotInput>> getOnePartitionInputs() {
        if (partitionSlots.size() == 1 && inputs.size() == 1 && inputs.get(0).size() == 1
                && inputs.get(0).get(0) instanceof Literal) {
            // fast path
            return computeSinglePartitionValueInputs();
        } else {
            // slow path
            return commonComputeOnePartitionInputs();
        }
    }

    @Override
    public Expression evaluate(Expression expression, Map<Slot, PartitionSlotInput> currentInputs) {
        Map<Expression, ColumnRange> defaultColumnRanges = currentInputs.values().iterator().next().columnRanges;
        Map<Expression, ColumnRange> rangeMap = new HashMap<>(defaultColumnRanges);
        EvaluateRangeResult result = expression.accept(this, new EvaluateRangeInput(currentInputs, rangeMap));
        return result.result;
    }

    @Override
    public EvaluateRangeResult visit(Expression expr, EvaluateRangeInput context) {
        return evaluateChildrenThenThis(expr, context);
    }

    @Override
    public EvaluateRangeResult visitSlot(Slot slot, EvaluateRangeInput context) {
        // try to replace partition slot to literal
        PartitionSlotInput slotResult = context.slotToInput.get(slot);
        Preconditions.checkState(slotResult != null);
        Preconditions.checkState(slotResult.columnRanges.containsKey(slot));
        return new EvaluateRangeResult(slotResult.result, ImmutableMap.of(slot, slotResult.columnRanges.get(slot)),
                ImmutableList.of());
    }

    @Override
    public EvaluateRangeResult visitGreaterThan(GreaterThan greaterThan, EvaluateRangeInput context) {
        EvaluateRangeResult result = evaluateChildrenThenThis(greaterThan, context);
        if (!(result.result instanceof GreaterThan)) {
            return result;
        }
        greaterThan = (GreaterThan) result.result;
        if (!(greaterThan.left() instanceof Literal) && greaterThan.right() instanceof Literal) {
            Expression expr = greaterThan.left();
            Map<Expression, ColumnRange> leftColumnRanges = result.childrenResult.get(0).columnRanges;
            if (leftColumnRanges.containsKey(expr)) {
                ColumnRange greaterThenRange = ColumnRange.greaterThan((Literal) greaterThan.right());
                result = intersectSlotRange(result, leftColumnRanges, expr, greaterThenRange);
            }
        } else if (greaterThan.left() instanceof Literal && !(greaterThan.right() instanceof Literal)) {
            Expression expr = greaterThan.right();
            Map<Expression, ColumnRange> rightColumnRanges = result.childrenResult.get(1).columnRanges;
            if (rightColumnRanges.containsKey(expr)) {
                ColumnRange lessThenRange = ColumnRange.lessThen((Literal) greaterThan.left());
                result = intersectSlotRange(result, rightColumnRanges, expr, lessThenRange);
            }
        }
        return result;
    }

    @Override
    public EvaluateRangeResult visitGreaterThanEqual(GreaterThanEqual greaterThanEqual, EvaluateRangeInput context) {
        EvaluateRangeResult result = evaluateChildrenThenThis(greaterThanEqual, context);
        if (!(result.result instanceof GreaterThanEqual)) {
            return result;
        }
        greaterThanEqual = (GreaterThanEqual) result.result;
        if (!(greaterThanEqual.left() instanceof Literal) && greaterThanEqual.right() instanceof Literal) {
            Expression expr = greaterThanEqual.left();
            Map<Expression, ColumnRange> leftColumnRanges = result.childrenResult.get(0).columnRanges;
            if (leftColumnRanges.containsKey(expr)) {
                ColumnRange atLeastRange = ColumnRange.atLeast((Literal) greaterThanEqual.right());
                result = intersectSlotRange(result, leftColumnRanges, expr, atLeastRange);
            }
        } else if (greaterThanEqual.left() instanceof Literal && !(greaterThanEqual.right() instanceof Literal)) {
            Expression expr = greaterThanEqual.right();
            Map<Expression, ColumnRange> rightColumnRanges = result.childrenResult.get(1).columnRanges;
            if (rightColumnRanges.containsKey(expr)) {
                ColumnRange atMostRange = ColumnRange.atMost((Literal) greaterThanEqual.left());
                result = intersectSlotRange(result, rightColumnRanges, expr, atMostRange);
            }
        }
        return result;
    }

    @Override
    public EvaluateRangeResult visitLessThan(LessThan lessThan, EvaluateRangeInput context) {
        EvaluateRangeResult result = evaluateChildrenThenThis(lessThan, context);
        if (!(result.result instanceof LessThan)) {
            return result;
        }
        lessThan = (LessThan) result.result;
        if (!(lessThan.left() instanceof Literal) && lessThan.right() instanceof Literal) {
            Expression expr = lessThan.left();
            Map<Expression, ColumnRange> leftColumnRanges = result.childrenResult.get(0).columnRanges;
            if (leftColumnRanges.containsKey(expr)) {
                ColumnRange greaterThenRange = ColumnRange.lessThen((Literal) lessThan.right());
                result = intersectSlotRange(result, leftColumnRanges, expr, greaterThenRange);
            }
        } else if (lessThan.left() instanceof Literal && !(lessThan.right() instanceof Literal)) {
            Expression expr = lessThan.right();
            Map<Expression, ColumnRange> rightColumnRanges = result.childrenResult.get(1).columnRanges;
            if (rightColumnRanges.containsKey(expr)) {
                ColumnRange lessThenRange = ColumnRange.greaterThan((Literal) lessThan.left());
                result = intersectSlotRange(result, rightColumnRanges, expr, lessThenRange);
            }
        }
        return result;
    }

    @Override
    public EvaluateRangeResult visitLessThanEqual(LessThanEqual lessThanEqual, EvaluateRangeInput context) {
        EvaluateRangeResult result = evaluateChildrenThenThis(lessThanEqual, context);
        if (!(result.result instanceof LessThanEqual)) {
            return result;
        }
        lessThanEqual = (LessThanEqual) result.result;
        if (!(lessThanEqual.left() instanceof Literal) && lessThanEqual.right() instanceof Literal) {
            Expression expr = lessThanEqual.left();
            Map<Expression, ColumnRange> leftColumnRanges = result.childrenResult.get(0).columnRanges;
            if (leftColumnRanges.containsKey(expr)) {
                ColumnRange atLeastRange = ColumnRange.atMost((Literal) lessThanEqual.right());
                result = intersectSlotRange(result, leftColumnRanges, expr, atLeastRange);
            }
        } else if (lessThanEqual.left() instanceof Literal && !(lessThanEqual.right() instanceof Literal)) {
            Expression expr = lessThanEqual.right();
            Map<Expression, ColumnRange> rightColumnRanges = result.childrenResult.get(1).columnRanges;
            if (rightColumnRanges.containsKey(expr)) {
                ColumnRange atMostRange = ColumnRange.atLeast((Literal) lessThanEqual.left());
                result = intersectSlotRange(result, rightColumnRanges, expr, atMostRange);
            }
        }
        return result;
    }

    @Override
    public EvaluateRangeResult visitEqualTo(EqualTo equalTo, EvaluateRangeInput context) {
        EvaluateRangeResult result = evaluateChildrenThenThis(equalTo, context);
        if (!(result.result instanceof EqualTo)) {
            return result;
        }
        boolean isRejectNot = false;
        if (!(equalTo.left() instanceof Literal) && equalTo.right() instanceof Literal) {
            Expression expr = equalTo.left();
            Map<Expression, ColumnRange> leftColumnRanges = result.childrenResult.get(0).columnRanges;
            if (leftColumnRanges.containsKey(expr)) {
                ColumnRange atLeastRange = ColumnRange.singleton((Literal) equalTo.right());
                result = intersectSlotRange(result, leftColumnRanges, expr, atLeastRange);
                if (leftColumnRanges.get(expr).isSingleton()) {
                    isRejectNot = true;
                }
            }
        } else if (equalTo.left() instanceof Literal && !(equalTo.right() instanceof Literal)) {
            Expression expr = equalTo.right();
            Map<Expression, ColumnRange> rightColumnRanges = result.childrenResult.get(1).columnRanges;
            if (rightColumnRanges.containsKey(expr)) {
                ColumnRange atMostRange = ColumnRange.singleton((Literal) equalTo.left());
                result = intersectSlotRange(result, rightColumnRanges, expr, atMostRange);
                if (rightColumnRanges.get(expr).isSingleton()) {
                    isRejectNot = true;
                }
            }
        } else {
            isRejectNot = false;
        }
        if (!isRejectNot) {
            result = result.withRejectNot(false);
        }
        return result;
    }

    @Override
    public EvaluateRangeResult visitNullSafeEqual(NullSafeEqual nullSafeEqual, EvaluateRangeInput context) {
        EvaluateRangeResult result = evaluateChildrenThenThis(nullSafeEqual, context);
        if (!(result.result instanceof NullSafeEqual)) {
            return result;
        }
        // "A <=> null" has been convert to "A is null" or false by NullSafeEqualToEqual rule
        // so we don't consider "A <=> null" here
        if (!(nullSafeEqual.left() instanceof Literal) && nullSafeEqual.right() instanceof Literal) {
            // A <=> literal -> A = literal and A is not null
            return visit(ExpressionUtils.and(new EqualTo(nullSafeEqual.left(), nullSafeEqual.right()),
                    new Not(new IsNull(nullSafeEqual.left()))), context);
        } else if (nullSafeEqual.left() instanceof Literal && !(nullSafeEqual.right() instanceof Slot)) {
            // literal <=> A -> literal = A and A is not null
            return visit(ExpressionUtils.and(new EqualTo(nullSafeEqual.left(), nullSafeEqual.right()),
                    new Not(new IsNull(nullSafeEqual.right()))), context);
        } else {
            return result.withRejectNot(false);
        }
    }

    @Override
    public EvaluateRangeResult visitInPredicate(InPredicate inPredicate, EvaluateRangeInput context) {
        if (!inPredicate.optionsAreLiterals()) {
            EvaluateRangeResult result = evaluateChildrenThenThis(inPredicate, context);
            if (!(result.result instanceof InPredicate)) {
                return result;
            }
            result = result.withRejectNot(false);
            return result;
        }

        EvaluateRangeResult leftResult = inPredicate.getCompareExpr().accept(this, context);
        Map<Expression, ColumnRange> exprRanges = leftResult.columnRanges;
        if (!exprRanges.containsKey(inPredicate.getCompareExpr())) {
            EvaluateRangeResult result = evaluateChildrenThenThis(inPredicate, context);
            if (!(result.result instanceof InPredicate)) {
                return result;
            }
            result = result.withRejectNot(false);
            return result;
        }

        Expression compareExpr = inPredicate.getCompareExpr();
        ColumnRange compareExprRange = exprRanges.get(compareExpr);
        ColumnRange optionsRange = inPredicate.getLiteralOptionsRangeSet();

        ColumnRange intersect = optionsRange.intersect(compareExprRange);
        Map<Expression, ColumnRange> newColumnRanges = replaceExprRange(exprRanges, compareExpr, intersect);

        EvaluateRangeResult result;
        if (intersect.isEmptyRange()) {
            result = new EvaluateRangeResult(BooleanLiteral.FALSE, newColumnRanges, ImmutableList.of(leftResult));
        } else {
            result = new EvaluateRangeResult(inPredicate, newColumnRanges, ImmutableList.of(leftResult));
        }
        result = result.withRejectNot(false);
        return result;
    }

    @Override
    public EvaluateRangeResult visitIsNull(IsNull isNull, EvaluateRangeInput context) {
        EvaluateRangeResult result = evaluateChildrenThenThis(isNull, context);
        if (!(result.result instanceof IsNull)) {
            return result;
        }
        result = result.withRejectNot(false);
        Expression child = isNull.child();
        if (!partitionSlotContainsNull.containsKey(child)) {
            return result;
        }
        if (!partitionSlotContainsNull.get(child)) {
            return new EvaluateRangeResult(BooleanLiteral.FALSE,
                    result.columnRanges, result.childrenResult, false);
        }
        return result;
    }

    @Override
    public EvaluateRangeResult visitAnd(And and, EvaluateRangeInput context) {
        EvaluateRangeResult result = evaluateChildrenThenThis(and, context);

        EvaluateRangeResult andResult = result.childrenResult.get(0);
        for (int i = 1; i < result.childrenResult.size(); i++) {
            andResult = mergeRanges(result.result, andResult, result.childrenResult.get(i),
                    context.rangeMap,
                    (leftRange, rightRange) -> leftRange.intersect(rightRange));
        }

        andResult = returnFalseIfExistEmptyRange(andResult);
        if (andResult.result.equals(BooleanLiteral.FALSE)) {
            return andResult;
        }

        // shrink range and prune the other type: if previous column is literal and equals to the bound
        andResult = determinateRangeOfOtherType(andResult, lowers, true);
        andResult = determinateRangeOfOtherType(andResult, uppers, false);
        return andResult;
    }

    @Override
    public EvaluateRangeResult visitOr(Or or, EvaluateRangeInput context) {
        EvaluateRangeResult result = evaluateChildrenThenThis(or, context);
        if (result.result.equals(BooleanLiteral.FALSE)) {
            return result;
        }

        List<EvaluateRangeResult> nonFalseResults = new ArrayList<>();
        for (EvaluateRangeResult evaluateRangeResult : result.childrenResult) {
            if (!evaluateRangeResult.result.equals(BooleanLiteral.FALSE)) {
                nonFalseResults.add(evaluateRangeResult);
            }
        }

        if (nonFalseResults.size() == 1) {
            return new EvaluateRangeResult(result.result, nonFalseResults.get(0).columnRanges, result.childrenResult);
        }

        EvaluateRangeResult orResult = nonFalseResults.get(0);
        for (int i = 1; i < nonFalseResults.size(); i++) {
            orResult = mergeRanges(result.result, orResult, nonFalseResults.get(i),
                    context.rangeMap,
                    (leftRange, rightRange) -> leftRange.union(rightRange));
        }
        return returnFalseIfExistEmptyRange(orResult);
    }

    @Override
    public EvaluateRangeResult visitNot(Not not, EvaluateRangeInput context) {
        EvaluateRangeResult result = evaluateChildrenThenThis(not, context);
        if (result.isRejectNot() && !result.result.equals(BooleanLiteral.TRUE)) {
            Map<Expression, ColumnRange> newRanges = Maps.newHashMap();
            for (Map.Entry<Expression, ColumnRange> entry : result.childrenResult.get(0).columnRanges.entrySet()) {
                Expression expr = entry.getKey();
                ColumnRange childRange = entry.getValue();
                ColumnRange partitionRange = context.rangeMap.containsKey(expr)
                        ? context.rangeMap.get(expr) : ColumnRange.all();
                newRanges.put(expr, partitionRange.intersect(childRange.complete()));
            }
            result = new EvaluateRangeResult(result.result, newRanges, result.childrenResult);
        }
        return returnFalseIfExistEmptyRange(result);
    }

    private EvaluateRangeResult evaluateChildrenThenThis(Expression expr, EvaluateRangeInput context) {
        // evaluate children
        List<Expression> children = expr.children();
        ImmutableList.Builder<Expression> newChildren = ImmutableList.builderWithExpectedSize(children.size());
        List<EvaluateRangeResult> childrenResults = new ArrayList<>(children.size());
        boolean hasNewChildren = false;

        for (int i = 0; i < children.size(); i++) {
            Expression child = children.get(i);
            EvaluateRangeResult childResult;
            if (child instanceof Monotonic) {
                childResult = visitMonotonic(child, context);
            } else {
                childResult = child.accept(this, context);
            }
            if (!childResult.result.equals(child)) {
                hasNewChildren = true;
            }
            childrenResults.add(childResult);
            newChildren.add(childResult.result);
        }
        if (hasNewChildren) {
            expr = expr.withChildren(newChildren.build());
        }

        // evaluate this
        expr = FoldConstantRuleOnFE.evaluate(expr, expressionRewriteContext);
        return new EvaluateRangeResult(expr, ImmutableMap.of(), childrenResults);
    }

    private EvaluateRangeResult returnFalseIfExistEmptyRange(EvaluateRangeResult result) {
        Expression expr = result.result;
        if (expr.getDataType() instanceof BooleanType && !(expr instanceof Literal)
                && result.columnRanges.values().stream().anyMatch(ColumnRange::isEmptyRange)) {
            return new EvaluateRangeResult(BooleanLiteral.FALSE, result.columnRanges, result.childrenResult);
        }
        return result;
    }

    private EvaluateRangeResult intersectSlotRange(EvaluateRangeResult originResult,
            Map<Expression, ColumnRange> columnRanges, Expression expr, ColumnRange otherRange) {
        ColumnRange columnRange = columnRanges.get(expr);
        ColumnRange intersect = columnRange.intersect(otherRange);

        Map<Expression, ColumnRange> newColumnRanges = replaceExprRange(columnRanges, expr, intersect);

        if (intersect.isEmptyRange()) {
            return new EvaluateRangeResult(BooleanLiteral.FALSE, newColumnRanges, originResult.childrenResult);
        } else {
            return new EvaluateRangeResult(originResult.result, newColumnRanges, originResult.childrenResult);
        }
    }

    private EvaluateRangeResult determinateRangeOfOtherType(
            EvaluateRangeResult context, List<Literal> partitionBound, boolean isLowerBound) {
        if (context.result instanceof Literal) {
            return context;
        }

        Slot qualifiedSlot = null;
        ColumnRange qualifiedRange = null;
        for (int i = 0; i < partitionSlotTypes.size(); i++) {
            PartitionSlotType partitionSlotType = partitionSlotTypes.get(i);
            Slot slot = partitionSlots.get(i);
            if (!context.columnRanges.containsKey(slot)) {
                return context;
            }
            switch (partitionSlotType) {
                case CONST: continue;
                case RANGE:
                    ColumnRange columnRange = context.columnRanges.get(slot);
                    if (!columnRange.isSingleton()
                            || !columnRange.getLowerBound().getValue().equals(partitionBound.get(i))) {
                        return context;
                    }
                    continue;
                case OTHER:
                    columnRange = context.columnRanges.get(slot);
                    if (columnRange.isSingleton()
                            && columnRange.getLowerBound().getValue().equals(partitionBound.get(i))) {
                        continue;
                    }

                    qualifiedSlot = slot;
                    if (isLowerBound) {
                        qualifiedRange = ColumnRange.atLeast(partitionBound.get(i));
                    } else {
                        qualifiedRange = i + 1 == partitionSlots.size()
                                ? ColumnRange.lessThen(partitionBound.get(i))
                                : ColumnRange.atMost(partitionBound.get(i));
                    }
                    break;
                default:
                    throw new AnalysisException("Unknown partition slot type: " + partitionSlotType);
            }
        }

        if (qualifiedSlot != null) {
            ColumnRange origin = context.columnRanges.get(qualifiedSlot);
            ColumnRange newRange = origin.intersect(qualifiedRange);

            Map<Expression, ColumnRange> newRanges = replaceExprRange(context.columnRanges, qualifiedSlot, newRange);

            if (newRange.isEmptyRange()) {
                return new EvaluateRangeResult(BooleanLiteral.FALSE, newRanges, context.childrenResult);
            } else {
                return new EvaluateRangeResult(context.result, newRanges, context.childrenResult);
            }
        }
        return context;
    }

    private Map<Expression, ColumnRange> replaceExprRange(Map<Expression, ColumnRange> originRange, Expression expr,
            ColumnRange range) {
        LinkedHashMap<Expression, ColumnRange> newRanges = Maps.newLinkedHashMap(originRange);
        newRanges.put(expr, range);
        return ImmutableMap.copyOf(newRanges);
    }

    private EvaluateRangeResult mergeRanges(
            Expression originResult, EvaluateRangeResult left, EvaluateRangeResult right,
            Map<Expression, ColumnRange> rangeMap,
            BiFunction<ColumnRange, ColumnRange, ColumnRange> mergeFunction) {

        Map<Expression, ColumnRange> leftRanges = left.columnRanges;
        Map<Expression, ColumnRange> rightRanges = right.columnRanges;

        if (leftRanges.equals(rightRanges)) {
            return new EvaluateRangeResult(originResult, leftRanges, ImmutableList.of(left, right));
        }
        Set<Expression> exprs = ImmutableSet.<Expression>builder()
                .addAll(leftRanges.keySet())
                .addAll(rightRanges.keySet())
                .build();

        Map<Expression, ColumnRange> mergedRange = exprs.stream()
                .map(expr -> Pair.of(expr, mergeFunction.apply(
                        leftRanges.containsKey(expr) ? leftRanges.get(expr) : rangeMap.get(expr),
                        rightRanges.containsKey(expr) ? rightRanges.get(expr) : rangeMap.get(expr))))
                .collect(ImmutableMap.toImmutableMap(Pair::key, Pair::value));
        return new EvaluateRangeResult(originResult, mergedRange, ImmutableList.of(left, right));
    }

    private List<Literal> toNereidsLiterals(PartitionKey partitionKey) {
        if (partitionKey.getKeys().size() == 1) {
            // fast path
            return toSingleNereidsLiteral(partitionKey);
        }

        // slow path
        return toMultiNereidsLiterals(partitionKey);
    }

    private List<Literal> toSingleNereidsLiteral(PartitionKey partitionKey) {
        List<LiteralExpr> keys = partitionKey.getKeys();
        LiteralExpr literalExpr = keys.get(0);
        Type type = literalExpr.getType();
        return ImmutableList.of(Literal.fromLegacyLiteral(literalExpr, type));
    }

    private List<Literal> toMultiNereidsLiterals(PartitionKey partitionKey) {
        List<LiteralExpr> keys = partitionKey.getKeys();
        List<Literal> literals = Lists.newArrayListWithCapacity(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            LiteralExpr literalExpr = keys.get(i);
            Type type = literalExpr.getType();
            literals.add(Literal.fromLegacyLiteral(literalExpr, type));
        }
        return literals;
    }

    private boolean isPartitionSlot(Slot slot) {
        return slotToType.containsKey(slot);
    }

    private Optional<PartitionSlotType> getPartitionSlotType(Slot slot) {
        return Optional.ofNullable(slotToType.get(slot));
    }

    private Map<Slot, PartitionSlotInput> fillSlotRangesToInputs(
            Map<Slot, PartitionSlotInput> inputs) {

        Builder<Expression, ColumnRange> allColumnRangesBuilder =
                ImmutableMap.builderWithExpectedSize(16);
        for (Entry<Slot, PartitionSlotInput> entry : inputs.entrySet()) {
            allColumnRangesBuilder.put(entry.getKey(), entry.getValue().columnRanges.get(entry.getKey()));
        }

        Map<Expression, ColumnRange> allColumnRanges = allColumnRangesBuilder.build();

        Builder<Slot, PartitionSlotInput> partitionSlotInputs =
                ImmutableMap.builderWithExpectedSize(16);
        for (Slot slot : inputs.keySet()) {
            partitionSlotInputs.put(slot, new PartitionSlotInput(inputs.get(slot).result, allColumnRanges));
        }
        return partitionSlotInputs.build();
    }

    /** EvaluateRangeInput */
    public static class EvaluateRangeInput {
        private final Map<Slot, PartitionSlotInput> slotToInput;
        private final Map<Expression, ColumnRange> rangeMap;

        public EvaluateRangeInput(Map<Slot, PartitionSlotInput> slotToInput, Map<Expression, ColumnRange> rangeMap) {
            this.slotToInput = slotToInput;
            this.rangeMap = rangeMap;
        }
    }

    /**
     * EvaluateRangeResult.
     *
     * bind expression and ColumnRange, so we can not only compute expression tree, but also compute range.
     * if column range is empty range, the predicate should return BooleanLiteral.FALSE, means this partition
     * can be pruned.
     */
    public static class EvaluateRangeResult {
        private final Expression result;
        private final Map<Expression, ColumnRange> columnRanges;
        // private final Map<Slot, ColumnRange> columnRanges;
        private final List<EvaluateRangeResult> childrenResult;

        // rejectNot = true, if \exist e \in R, pred(e)=true, then we have \forAll e \in R, !pred(e)=false
        // that is, if pred holds true over R, then !pred does not hold true over R.
        // example 1. rejectNot=false
        //      R=(1,10), pred: k = 5. "k = 5" holds true over R, and "NOT k = 5" holds true over R.
        // example 2. rejectNot=false
        //      R=(1,10), pred: k = 11. "k=10" dose not holds over R
        // example 3. rejectNot=false
        //      R=(1,10), pred: k in (4, 5). "k in (4, 5)" holds true over R, and "NOT k in (4, 5)" holds over R
        // example 3. rejectNot=true
        //      R=(1,10), pred: k < 11. "k<11" holds true over R, and "NOT k<11" dose not hold over R
        private final boolean rejectNot;

        public EvaluateRangeResult(Expression result, Map<Expression, ColumnRange> columnRanges,
                                   List<EvaluateRangeResult> childrenResult, boolean rejectNot) {
            this.result = result;
            this.columnRanges = columnRanges;
            this.childrenResult = childrenResult;
            this.rejectNot = rejectNot;
        }

        public EvaluateRangeResult(Expression result, Map<Expression, ColumnRange> columnRanges,
                List<EvaluateRangeResult> childrenResult) {
            this(result, columnRanges, childrenResult, allIsRejectNot(childrenResult));
        }

        public EvaluateRangeResult withRejectNot(boolean rejectNot) {
            return new EvaluateRangeResult(result, columnRanges, childrenResult, rejectNot);
        }

        public boolean isRejectNot() {
            return rejectNot;
        }

        private static boolean allIsRejectNot(List<EvaluateRangeResult> childrenResult) {
            for (EvaluateRangeResult evaluateRangeResult : childrenResult) {
                if (!evaluateRangeResult.isRejectNot()) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public boolean isDefaultPartition() {
        return partitionItem.isDefaultPartition();
    }

    private List<Map<Slot, PartitionSlotInput>> computeSinglePartitionValueInputs() {
        Slot partitionSlot = partitionSlots.get(0);
        Literal literal = (Literal) inputs.get(0).get(0);
        ColumnRange slotRange = ColumnRange.singleton(literal);
        ImmutableMap<Expression, ColumnRange> slotToRange = ImmutableMap.of(partitionSlot, slotRange);
        Map<Slot, PartitionSlotInput> slotToInputs =
                ImmutableMap.of(partitionSlot, new PartitionSlotInput(literal, slotToRange));
        return ImmutableList.of(slotToInputs);
    }

    private List<Map<Slot, PartitionSlotInput>> commonComputeOnePartitionInputs() {
        List<Map<Slot, PartitionSlotInput>> onePartitionInputs = Lists.newArrayListWithCapacity(inputs.size());
        for (List<Expression> input : inputs) {
            boolean previousIsLowerBoundLiteral = true;
            boolean previousIsUpperBoundLiteral = true;
            Builder<Slot, PartitionSlotInput> slotToInputs = ImmutableMap.builderWithExpectedSize(16);
            for (int i = 0; i < partitionSlots.size(); ++i) {
                Slot partitionSlot = partitionSlots.get(i);
                // partitionSlot will be replaced to this expression
                Expression expression = input.get(i);
                ColumnRange slotRange = null;
                PartitionSlotType partitionSlotType = partitionSlotTypes.get(i);
                if (expression instanceof Literal) {
                    // const or expanded range
                    slotRange = ColumnRange.singleton((Literal) expression);
                    if (!expression.equals(lowers.get(i))) {
                        previousIsLowerBoundLiteral = false;
                    }
                    if (!expression.equals(uppers.get(i))) {
                        previousIsUpperBoundLiteral = false;
                    }
                } else {
                    // un expanded range
                    switch (partitionSlotType) {
                        case RANGE:
                            boolean isLastPartitionColumn = i + 1 == partitionSlots.size();
                            BoundType rightBoundType = isLastPartitionColumn
                                    ? BoundType.OPEN : BoundType.CLOSED;
                            slotRange = ColumnRange.range(
                                    lowers.get(i), BoundType.CLOSED, uppers.get(i), rightBoundType);
                            break;
                        case OTHER:
                            if (previousIsLowerBoundLiteral) {
                                slotRange = ColumnRange.atLeast(lowers.get(i));
                            } else if (previousIsUpperBoundLiteral) {
                                slotRange = ColumnRange.lessThen(uppers.get(i));
                            } else {
                                // unknown range
                                slotRange = ColumnRange.all();
                            }
                            break;
                        default:
                            throw new AnalysisException("Unknown partition slot type: " + partitionSlotType);
                    }
                    previousIsLowerBoundLiteral = false;
                    previousIsUpperBoundLiteral = false;
                }
                ImmutableMap<Expression, ColumnRange> slotToRange = ImmutableMap.of(partitionSlot, slotRange);
                slotToInputs.put(partitionSlot, new PartitionSlotInput(expression, slotToRange));
            }

            Map<Slot, PartitionSlotInput> slotPartitionSlotInputMap = fillSlotRangesToInputs(slotToInputs.build());
            onePartitionInputs.add(slotPartitionSlotInputMap);
        }
        return onePartitionInputs;
    }

    public EvaluateRangeResult visitMonotonic(Expression monotonic, EvaluateRangeInput context) {
        EvaluateRangeResult rangeResult = evaluateChildrenThenThis(monotonic, context);
        if (!rangeResult.result.getClass().equals(monotonic.getClass())) {
            return rangeResult;
        }
        return computeMonotonicFunctionRange(rangeResult, context);
    }

    private EvaluateRangeResult computeMonotonicFunctionRange(EvaluateRangeResult result, EvaluateRangeInput context) {
        Monotonic func = (Monotonic) result.result;
        if (context.rangeMap.containsKey(func)) {
            return new EvaluateRangeResult((Expression) func, ImmutableMap.of((Expression) func,
                    context.rangeMap.get(func)), result.childrenResult);
        }
        int childIndex = func.getMonotonicFunctionChildIndex();
        Expression funcChild = func.child(childIndex);
        boolean isNullable = partitionSlotContainsNull.getOrDefault(funcChild, true);
        Expression withNullable = func.withConstantArgs(isNullable ? new Nullable(funcChild)
                : new NonNullable(funcChild));
        partitionSlotContainsNull.put((Expression) func, withNullable.nullable());

        if (!result.childrenResult.get(childIndex).columnRanges.containsKey(funcChild)) {
            return result;
        }
        ColumnRange childRange = result.childrenResult.get(childIndex).columnRanges.get(funcChild);
        if (childRange.isEmptyRange() || childRange.asRanges().size() != 1
                || (!childRange.span().hasLowerBound() && !childRange.span().hasUpperBound())) {
            return result;
        }
        Range<ColumnBound> span = childRange.span();
        // null means positive infinity or negative infinity
        Literal lower = span.hasLowerBound() ? span.lowerEndpoint().getValue() : null;
        Literal upper = span.hasUpperBound() && !(span.upperEndpoint().getValue() instanceof MaxLiteral)
                ? span.upperEndpoint().getValue() : null;
        if (!func.isMonotonic(lower, upper)) {
            return result;
        }
        Expression lowerValue = lower != null ? FoldConstantRuleOnFE.evaluate(func.withConstantArgs(lower),
                expressionRewriteContext) : null;
        Expression upperValue = upper != null ? FoldConstantRuleOnFE.evaluate(func.withConstantArgs(upper),
                expressionRewriteContext) : null;
        if (!checkFoldConstantValueIsValid(lowerValue, upperValue)) {
            return result;
        }

        if (!func.isPositive()) {
            Expression temp = lowerValue;
            lowerValue = upperValue;
            upperValue = temp;
        }
        LinkedHashMap<Expression, ColumnRange> newRanges = Maps.newLinkedHashMap();
        ColumnRange newRange = ColumnRange.all();
        if (lowerValue instanceof Literal && upperValue instanceof Literal && lowerValue.equals(upperValue)) {
            newRange = ColumnRange.singleton((Literal) lowerValue);
            context.rangeMap.put((Expression) func, newRange);
            newRanges.put((Expression) func, newRange);
            return new EvaluateRangeResult(lowerValue, newRanges, result.childrenResult);
        } else {
            if (lowerValue instanceof Literal) {
                newRange = newRange.withLowerBound((Literal) lowerValue);
            }
            if (upperValue instanceof Literal) {
                newRange = newRange.withUpperBound((Literal) upperValue);
            }
            if (newRange.isEmptyRange() || !newRange.span().hasLowerBound() && !newRange.span().hasUpperBound()) {
                return result;
            }
            context.rangeMap.put((Expression) func, newRange);
            newRanges.put((Expression) func, newRange);
            return new EvaluateRangeResult((Expression) func, newRanges, result.childrenResult);
        }
    }

    // only allow literal(except NullLiteral) and null
    private boolean checkFoldConstantValueIsValid(Expression lowerValue, Expression upperValue) {
        if (lowerValue instanceof NullLiteral || upperValue instanceof NullLiteral) {
            return false;
        }
        return (lowerValue == null || lowerValue instanceof Literal)
                && (upperValue == null || upperValue instanceof Literal);
    }
}
