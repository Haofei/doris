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

#include <fmt/format.h>
#include <glog/logging.h>

#include <cstddef>
#include <memory>
#include <ostream>
#include <utility>

#include "common/status.h"
#include "vec/aggregate_functions/aggregate_function.h"
#include "vec/columns/column.h"
#include "vec/columns/column_vector.h"
#include "vec/core/block.h"
#include "vec/core/column_numbers.h"
#include "vec/core/column_with_type_and_name.h"
#include "vec/core/types.h"
#include "vec/data_types/data_type.h"
#include "vec/functions/array/function_array_utils.h"
#include "vec/functions/function.h"
#include "vec/functions/simple_function_factory.h"

namespace doris {
class FunctionContext;
} // namespace doris

namespace doris::vectorized {

template <typename PopType>
class FunctionArrayPop : public IFunction {
public:
    static FunctionPtr create() { return std::make_shared<PopType>(); }

    /// Get function name.
    String get_name() const override { return PopType::name; }

    bool is_variadic() const override { return false; }

    size_t get_number_of_arguments() const override { return 1; }

    DataTypePtr get_return_type_impl(const DataTypes& arguments) const override {
        DCHECK(arguments[0]->get_primitive_type() == TYPE_ARRAY)
                << "First argument for function: " << PopType::name
                << " should be DataTypeArray but it has type " << arguments[0]->get_name() << ".";
        return arguments[0];
    }

    Status execute_impl(FunctionContext* context, Block& block, const ColumnNumbers& arguments,
                        uint32_t result, size_t input_rows_count) const override {
        auto array_column =
                block.get_by_position(arguments[0]).column->convert_to_full_column_if_const();
        // extract src array column
        ColumnArrayExecutionData src;
        if (!extract_column_array_info(*array_column, src)) {
            return Status::RuntimeError(
                    fmt::format("execute failed, unsupported types for function {}({})", get_name(),
                                block.get_by_position(arguments[0]).type->get_name()));
        }
        // prepare dst array column
        bool is_nullable = src.nested_nullmap_data != nullptr;
        ColumnArrayMutableData dst = create_mutable_data(src.nested_col.get(), is_nullable);
        dst.offsets_ptr->reserve(input_rows_count);
        // start from index depending on the PopType::start_offset
        auto offset_column = ColumnInt64::create(array_column->size(), PopType::start_offset);
        // len - 1
        auto length_column = ColumnInt64::create();
        for (size_t row = 0; row < src.offsets_ptr->size(); ++row) {
            size_t off = (*src.offsets_ptr)[row - 1];
            size_t len = (*src.offsets_ptr)[row] - off;
            length_column->insert_value(len - 1);
        }
        slice_array(dst, src, *offset_column, length_column.get());
        ColumnPtr res_column = assemble_column_array(dst);
        block.replace_by_position(result, std::move(res_column));
        return Status::OK();
    }
};

class FunctionArrayPopback : public FunctionArrayPop<FunctionArrayPopback> {
public:
    static constexpr auto name = "array_popback";
    static constexpr int start_offset = 1;
};

class FunctionArrayPopfront : public FunctionArrayPop<FunctionArrayPopfront> {
public:
    static constexpr auto name = "array_popfront";
    static constexpr int start_offset = 2;
};

void register_function_array_pop(SimpleFunctionFactory& factory) {
    factory.register_function<FunctionArrayPopback>();
    factory.register_function<FunctionArrayPopfront>();
}

} // namespace doris::vectorized
