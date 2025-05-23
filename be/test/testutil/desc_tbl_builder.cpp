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

#include "testutil/desc_tbl_builder.h"

#include <gtest/gtest.h>

#include "common/status.h"
#include "vec/data_types/data_type_nullable.h"
#include "vec/data_types/data_type_struct.h"

namespace doris {

DescriptorTblBuilder::DescriptorTblBuilder(ObjectPool* obj_pool) : _obj_pool(obj_pool) {}

TupleDescBuilder& DescriptorTblBuilder::declare_tuple() {
    TupleDescBuilder* tuple_builder = _obj_pool->add(new TupleDescBuilder());
    _tuples_descs.push_back(tuple_builder);
    return *tuple_builder;
}

// item_id of -1 indicates no itemTupleId
static TSlotDescriptor make_slot_descriptor(int id, int parent_id,
                                            const vectorized::DataTypePtr& type,
                                            const std::string& name, int slot_idx, int item_id) {
    int null_byte = slot_idx / 8;
    int null_bit = slot_idx % 8;
    TSlotDescriptor slot_desc;
    slot_desc.__set_id(id);
    slot_desc.__set_parent(parent_id);
    slot_desc.__set_slotType(type->to_thrift());
    // For now no tests depend on the materialized path being populated correctly.
    // slot_desc.__set_materializedPath(vector<int>());
    slot_desc.__set_byteOffset(0);
    slot_desc.__set_nullIndicatorByte(null_byte);
    slot_desc.__set_nullIndicatorBit(null_bit);
    slot_desc.__set_slotIdx(slot_idx);
    slot_desc.__set_isMaterialized(true);
    slot_desc.__set_colName(name);
    // if (item_id != -1) {
    //     slot_desc.__set_itemTupleId(item_id);
    // }
    return slot_desc;
}

static TTupleDescriptor make_tuple_descriptor(int id) {
    TTupleDescriptor tuple_desc;
    tuple_desc.__set_id(id);
    tuple_desc.__set_byteSize(0);
    tuple_desc.__set_numNullBytes(0);
    return tuple_desc;
}

DescriptorTbl* DescriptorTblBuilder::build() {
    DescriptorTbl* desc_tbl = nullptr;
    TDescriptorTable thrift_desc_tbl;
    int tuple_id = 0;
    int slot_id = 0;

    for (auto& _tuples_desc : _tuples_descs) {
        build_tuple(_tuples_desc->slot_types(), _tuples_desc->slot_names(), &thrift_desc_tbl,
                    &tuple_id, &slot_id);
    }

    Status status = DescriptorTbl::create(_obj_pool, thrift_desc_tbl, &desc_tbl);
    EXPECT_TRUE(status.ok());
    return desc_tbl;
}

TTupleDescriptor DescriptorTblBuilder::build_tuple(
        const std::vector<vectorized::DataTypePtr>& slot_types,
        const std::vector<std::string>& slot_names, TDescriptorTable* thrift_desc_tbl,
        int* next_tuple_id, int* slot_id) {
    // We never materialize struct slots (there's no in-memory representation of structs,
    // instead the materialized fields appear directly in the tuple), but array types can
    // still have a struct item type. In this case, the array item tuple contains the
    // "inlined" struct fields.
    if (slot_types.size() == 1 && slot_types[0]->get_primitive_type() == TYPE_STRUCT) {
        return build_tuple(assert_cast<const vectorized::DataTypeStruct*>(
                                   vectorized::remove_nullable(slot_types[0]).get())
                                   ->get_elements(),
                           assert_cast<const vectorized::DataTypeStruct*>(
                                   vectorized::remove_nullable(slot_types[0]).get())
                                   ->get_element_names(),
                           thrift_desc_tbl, next_tuple_id, slot_id);
    }

    int tuple_id = *next_tuple_id;
    ++(*next_tuple_id);

    for (int i = 0; i < slot_types.size(); ++i) {
        DCHECK_NE(slot_types[i]->get_primitive_type(), TYPE_STRUCT);
        int item_id = -1;

        thrift_desc_tbl->slotDescriptors.push_back(
                make_slot_descriptor(*slot_id, tuple_id, slot_types[i], slot_names[i], i, item_id));
        thrift_desc_tbl->__isset.slotDescriptors = true;
        ++(*slot_id);
    }

    TTupleDescriptor result = make_tuple_descriptor(tuple_id);
    thrift_desc_tbl->tupleDescriptors.push_back(result);
    return result;
}

} // end namespace doris
