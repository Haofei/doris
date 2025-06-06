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

package org.apache.doris.nereids.trees.expressions.functions.window;

import org.apache.doris.catalog.FunctionSignature;
import org.apache.doris.nereids.exceptions.AnalysisException;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.functions.ExplicitlyCastableSignature;
import org.apache.doris.nereids.trees.expressions.literal.BigIntLiteral;
import org.apache.doris.nereids.trees.expressions.literal.Literal;
import org.apache.doris.nereids.trees.expressions.literal.NullLiteral;
import org.apache.doris.nereids.trees.expressions.shape.TernaryExpression;
import org.apache.doris.nereids.trees.expressions.visitor.ExpressionVisitor;
import org.apache.doris.nereids.types.BigIntType;
import org.apache.doris.nereids.types.DataType;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * Window function: Lead()
 */
public class Lead extends WindowFunction implements TernaryExpression, ExplicitlyCastableSignature,
        RequireTrivialTypes {

    static {
        List<FunctionSignature> signatures = Lists.newArrayList();
        trivialTypes.forEach(t -> {
            signatures.add(FunctionSignature.ret(t).args(t, BigIntType.INSTANCE, t));
            signatures.add(FunctionSignature.ret(t).args(t, BigIntType.INSTANCE));
            signatures.add(FunctionSignature.ret(t).args(t));
        });
        SIGNATURES = ImmutableList.copyOf(signatures);
    }

    private static final List<FunctionSignature> SIGNATURES;

    public Lead(Expression child, Expression offset, Expression defaultValue) {
        super("lead", child, offset, defaultValue);
    }

    public Lead(Expression child, Expression offset) {
        this(child, offset, new NullLiteral(child.getDataType()));
    }

    public Lead(Expression child) {
        this(child, new BigIntLiteral(1L), new NullLiteral(child.getDataType()));
    }

    public Expression getOffset() {
        Preconditions.checkArgument(children.size() == 3);
        return child(1);
    }

    public Expression getDefaultValue() {
        Preconditions.checkArgument(children.size() == 3);
        return child(2);
    }

    @Override
    public boolean nullable() {
        if (children.size() == 3 && child(2).nullable()) {
            return true;
        }
        return child(0).nullable();
    }

    @Override
    public <R, C> R accept(ExpressionVisitor<R, C> visitor, C context) {
        return visitor.visitLead(this, context);
    }

    @Override
    public void checkLegalityBeforeTypeCoercion() {
        if (children().size() == 1) {
            return;
        }
        if (children().size() >= 2) {
            checkValidParams(getOffset());
            if (getOffset() instanceof Literal) {
                if (((Literal) getOffset()).getDouble() < 0) {
                    throw new AnalysisException(
                            "The offset parameter of LEAD must be a constant positive integer: " + this.toSql());
                }
            } else {
                throw new AnalysisException(
                    "The offset parameter of LAG must be a constant positive integer: " + this.toSql());
            }
        }
    }

    @Override
    public List<FunctionSignature> getSignatures() {
        return SIGNATURES;
    }

    @Override
    public Lead withChildren(List<Expression> children) {
        Preconditions.checkArgument(children.size() >= 1 && children.size() <= 3);
        if (children.size() == 1) {
            return new Lead(children.get(0));
        } else if (children.size() == 2) {
            return new Lead(children.get(0), children.get(1));
        } else {
            return new Lead(children.get(0), children.get(1), children.get(2));
        }
    }

    @Override
    public DataType getDataType() {
        return child(0).getDataType();
    }
}
