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

#include <bit>
#include <bitset>
#include <cstdint>
#include <type_traits>

#include "common/exception.h"
#include "common/status.h"
#include "vec/core/types.h"
#include "vec/functions/function_unary_arithmetic.h"
#include "vec/functions/simple_function_factory.h"

namespace doris::vectorized {
#include "common/compile_check_begin.h"
struct NameBitCount {
    static constexpr auto name = "bit_count";
};

template <typename T>
struct BitCountImpl {
    // No unsigned type in Java. So we need signed number as return type
    // Int8_MAX = 127
    static constexpr PrimitiveType ResultType = sizeof(T) * 8 >= 128 ? TYPE_SMALLINT : TYPE_TINYINT;

    static inline typename PrimitiveTypeTraits<ResultType>::CppType apply(T a) {
        if constexpr (std::is_same_v<T, Int128> || std::is_same_v<T, Int64> ||
                      std::is_same_v<T, Int32> || std::is_same_v<T, Int16> ||
                      std::is_same_v<T, Int8>) {
            // ResultType already check the length
            return cast_set<typename PrimitiveTypeTraits<ResultType>::CppType, int, false>(
                    std::popcount(static_cast<std::make_unsigned_t<T>>(a)));
        } else {
            throw Exception(ErrorCode::INVALID_ARGUMENT,
                            "bit_count only support using INTEGER as operator");
        }
    }
};

using FunctionBitCount = FunctionUnaryArithmetic<BitCountImpl, NameBitCount>;

void register_function_bit_count(SimpleFunctionFactory& factory) {
    factory.register_function<FunctionBitCount>();
}

} // namespace doris::vectorized
