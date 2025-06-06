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

package org.apache.doris.nereids.types;

import org.apache.doris.catalog.ScalarType;
import org.apache.doris.catalog.Type;
import org.apache.doris.nereids.exceptions.AnalysisException;
import org.apache.doris.nereids.types.coercion.PrimitiveType;
import org.apache.doris.nereids.types.coercion.RangeScalable;

/**
 * Time v2 type in Nereids.
 */
public class TimeV2Type extends PrimitiveType implements RangeScalable {

    public static final int MAX_SCALE = 6;
    public static final TimeV2Type INSTANCE = new TimeV2Type();

    private static final int WIDTH = 8;
    private final int scale;

    private TimeV2Type(int scale) {
        this.scale = scale;
    }

    private TimeV2Type() {
        scale = 0;
    }

    @Override
    public Type toCatalogDataType() {
        return ScalarType.createTimeV2Type(scale);
    }

    /**
     * create TimeV2Type from scale
     */
    public static TimeV2Type of(int scale) {
        if (scale > MAX_SCALE || scale < 0) {
            throw new AnalysisException("Scale of Datetime/Time must between 0 and 6. Scale was set to: " + scale);
        }
        return new TimeV2Type(scale);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof TimeV2Type) {
            return ((TimeV2Type) o).getScale() == getScale();
        }
        return false;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    public int getScale() {
        return scale;
    }
}
