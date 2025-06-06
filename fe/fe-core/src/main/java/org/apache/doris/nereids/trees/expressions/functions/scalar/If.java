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

package org.apache.doris.nereids.trees.expressions.functions.scalar;

import org.apache.doris.catalog.FunctionSignature;
import org.apache.doris.nereids.analyzer.Unbound;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.functions.ExplicitlyCastableSignature;
import org.apache.doris.nereids.trees.expressions.shape.TernaryExpression;
import org.apache.doris.nereids.trees.expressions.visitor.ExpressionVisitor;
import org.apache.doris.nereids.types.ArrayType;
import org.apache.doris.nereids.types.BigIntType;
import org.apache.doris.nereids.types.BitmapType;
import org.apache.doris.nereids.types.BooleanType;
import org.apache.doris.nereids.types.DateTimeType;
import org.apache.doris.nereids.types.DateTimeV2Type;
import org.apache.doris.nereids.types.DateType;
import org.apache.doris.nereids.types.DateV2Type;
import org.apache.doris.nereids.types.DecimalV2Type;
import org.apache.doris.nereids.types.DecimalV3Type;
import org.apache.doris.nereids.types.DoubleType;
import org.apache.doris.nereids.types.FloatType;
import org.apache.doris.nereids.types.HllType;
import org.apache.doris.nereids.types.IntegerType;
import org.apache.doris.nereids.types.JsonType;
import org.apache.doris.nereids.types.LargeIntType;
import org.apache.doris.nereids.types.MapType;
import org.apache.doris.nereids.types.NullType;
import org.apache.doris.nereids.types.SmallIntType;
import org.apache.doris.nereids.types.StringType;
import org.apache.doris.nereids.types.TimeV2Type;
import org.apache.doris.nereids.types.TinyIntType;
import org.apache.doris.nereids.types.VarcharType;
import org.apache.doris.nereids.types.coercion.AnyDataType;
import org.apache.doris.nereids.util.TypeCoercionUtils;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * ScalarFunction 'if'. This class is generated by GenerateFunction.
 */
public class If extends ScalarFunction
        implements TernaryExpression, ExplicitlyCastableSignature {

    public static final List<FunctionSignature> SIGNATURES = ImmutableList.of(
            FunctionSignature.ret(NullType.INSTANCE)
                    .args(BooleanType.INSTANCE, NullType.INSTANCE, NullType.INSTANCE),
            FunctionSignature.ret(DateTimeV2Type.SYSTEM_DEFAULT)
                    .args(BooleanType.INSTANCE, DateTimeV2Type.SYSTEM_DEFAULT, DateTimeV2Type.SYSTEM_DEFAULT),
            FunctionSignature.ret(DateV2Type.INSTANCE)
                    .args(BooleanType.INSTANCE, DateV2Type.INSTANCE, DateV2Type.INSTANCE),
            FunctionSignature.ret(BooleanType.INSTANCE)
                    .args(BooleanType.INSTANCE, BooleanType.INSTANCE, BooleanType.INSTANCE),
            FunctionSignature.ret(TinyIntType.INSTANCE)
                    .args(BooleanType.INSTANCE, TinyIntType.INSTANCE, TinyIntType.INSTANCE),
            FunctionSignature.ret(SmallIntType.INSTANCE)
                    .args(BooleanType.INSTANCE, SmallIntType.INSTANCE, SmallIntType.INSTANCE),
            FunctionSignature.ret(IntegerType.INSTANCE)
                    .args(BooleanType.INSTANCE, IntegerType.INSTANCE, IntegerType.INSTANCE),
            FunctionSignature.ret(BigIntType.INSTANCE)
                    .args(BooleanType.INSTANCE, BigIntType.INSTANCE, BigIntType.INSTANCE),
            FunctionSignature.ret(LargeIntType.INSTANCE)
                    .args(BooleanType.INSTANCE, LargeIntType.INSTANCE, LargeIntType.INSTANCE),
            FunctionSignature.ret(FloatType.INSTANCE)
                    .args(BooleanType.INSTANCE, FloatType.INSTANCE, FloatType.INSTANCE),
            FunctionSignature.ret(DoubleType.INSTANCE)
                    .args(BooleanType.INSTANCE, DoubleType.INSTANCE, DoubleType.INSTANCE),
            FunctionSignature.ret(DateTimeType.INSTANCE)
                    .args(BooleanType.INSTANCE, DateTimeType.INSTANCE, DateTimeType.INSTANCE),
            FunctionSignature.ret(DateType.INSTANCE).args(BooleanType.INSTANCE, DateType.INSTANCE,
                    DateType.INSTANCE),
            FunctionSignature.ret(TimeV2Type.INSTANCE).args(BooleanType.INSTANCE, TimeV2Type.INSTANCE,
                    TimeV2Type.INSTANCE),
            FunctionSignature.ret(DecimalV3Type.WILDCARD)
                    .args(BooleanType.INSTANCE, DecimalV3Type.WILDCARD, DecimalV3Type.WILDCARD),
            FunctionSignature.ret(DecimalV2Type.SYSTEM_DEFAULT)
                    .args(BooleanType.INSTANCE, DecimalV2Type.SYSTEM_DEFAULT, DecimalV2Type.SYSTEM_DEFAULT),
            FunctionSignature.ret(BitmapType.INSTANCE)
                    .args(BooleanType.INSTANCE, BitmapType.INSTANCE, BitmapType.INSTANCE),
            FunctionSignature.ret(HllType.INSTANCE).args(BooleanType.INSTANCE, HllType.INSTANCE, HllType.INSTANCE),
            FunctionSignature.retArgType(1)
                    .args(BooleanType.INSTANCE, ArrayType.of(new AnyDataType(0)),
                            ArrayType.of(new AnyDataType(0))),
            FunctionSignature.retArgType(1)
                    .args(BooleanType.INSTANCE, MapType.of(new AnyDataType(0), new AnyDataType(1)),
                            MapType.of(new AnyDataType(0), new AnyDataType(1))),
            FunctionSignature.retArgType(1)
                    .args(BooleanType.INSTANCE, new AnyDataType(0), new AnyDataType(0)),
            // NOTICE string must at least of signature list, because all complex type could implicit cast to string
            FunctionSignature.ret(VarcharType.SYSTEM_DEFAULT)
                    .args(BooleanType.INSTANCE, VarcharType.SYSTEM_DEFAULT, VarcharType.SYSTEM_DEFAULT),
            FunctionSignature.ret(StringType.INSTANCE)
                    .args(BooleanType.INSTANCE, StringType.INSTANCE, StringType.INSTANCE),
            FunctionSignature.ret(JsonType.INSTANCE)
                    .args(BooleanType.INSTANCE, JsonType.INSTANCE, JsonType.INSTANCE)
    );

    /**
     * constructor with 3 arguments.
     */
    public If(Expression arg0, Expression arg1, Expression arg2) {
        super("if", arg0 instanceof Unbound ? arg0 : TypeCoercionUtils.castIfNotSameType(arg0, BooleanType.INSTANCE),
                arg1, arg2);
    }

    /**
     * custom compute nullable.
     */
    @Override
    public boolean nullable() {
        for (int i = 1; i < arity(); i++) {
            if (child(i).nullable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * withChildren.
     */
    @Override
    public If withChildren(List<Expression> children) {
        Preconditions.checkArgument(children.size() == 3);
        return new If(children.get(0), children.get(1), children.get(2));
    }

    @Override
    public <R, C> R accept(ExpressionVisitor<R, C> visitor, C context) {
        return visitor.visitIf(this, context);
    }

    @Override
    public List<FunctionSignature> getSignatures() {
        return SIGNATURES;
    }

    @Override
    public FunctionSignature searchSignature(List<FunctionSignature> signatures) {

        return ExplicitlyCastableSignature.super.searchSignature(signatures);
    }
}
