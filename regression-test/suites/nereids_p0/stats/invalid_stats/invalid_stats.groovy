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

suite("invalid_stats") {
    multi_sql """
        SET enable_nereids_planner=true;
        SET enable_fallback_to_original_planner=false;
        set disable_nereids_rules=PRUNE_EMPTY_PARTITION;
        set ignore_shape_nodes=PhysicalProject;
        set enable_parallel_result_sink=true;
        set runtime_filter_mode=off;
        
        drop table if exists region;
        CREATE TABLE region  (
        r_regionkey      int NOT NULL,
        r_name       VARCHAR(25) NOT NULL,
        r_comment    VARCHAR(152)
        )ENGINE=OLAP
        DUPLICATE KEY(`r_regionkey`)
        COMMENT "OLAP"
        DISTRIBUTED BY HASH(`r_regionkey`) BUCKETS 1
        PROPERTIES (
            "replication_num" = "1"
        );

        drop table if exists nation;
         CREATE TABLE `nation` (
        `n_nationkey` int(11) NOT NULL,
        `n_name`      varchar(25) NOT NULL,
        `n_regionkey` int(11) NOT NULL,
        `n_comment`   varchar(152) NULL
        ) ENGINE=OLAP
        DUPLICATE KEY(`N_NATIONKEY`)
        COMMENT "OLAP"
        DISTRIBUTED BY HASH(`N_NATIONKEY`) BUCKETS 1
        PROPERTIES (
            "replication_num" = "1"
        );
        alter table nation modify column n_nationkey set stats ('ndv'='25', 'num_nulls'='0', 'min_value'='0', 'max_value'='24', 'row_count'='25');

        alter table region modify column r_regionkey set stats ('ndv'='5', 'num_nulls'='0', 'min_value'='0', 'max_value'='4', 'row_count'='5');

    """

    qt_reorder_1 "explain shape plan select r_regionkey from region join nation on r_regionkey=n_regionkey"
   
    sql "alter table region modify column r_regionkey set stats ('ndv'='0', 'num_nulls'='0', 'min_value'='0', 'max_value'='4', 'row_count'='0');"
    
    // r_regionkey stats invalid: ndv=0, but min or max is not null
    qt_ndv_min_max_invalid "explain shape plan select r_regionkey from region join nation on r_regionkey=n_regionkey"
    
    // inject normal stats and check join order is nation-region
    sql "alter table region modify column r_regionkey set stats ('ndv'='5', 'num_nulls'='0', 'min_value'='0', 'max_value'='4', 'row_count'='5');"
    
    qt_reorder_2 "explain shape plan select r_regionkey from region join nation on r_regionkey=n_regionkey"

    // r_regionkey stats invalid: ndv > 10*row
    sql "alter table region modify column r_regionkey set stats ('ndv'='10', 'num_nulls'='0', 'min_value'='0', 'max_value'='4', 'row_count'='1');"
    qt_order_3 "explain shape plan select r_regionkey from region join nation on r_regionkey=n_regionkey"
    
    sql "alter table region modify column r_regionkey set stats ('ndv'='11', 'num_nulls'='0', 'min_value'='0', 'max_value'='4', 'row_count'='1');"
    qt_ndv_row_invalid "explain shape plan select r_regionkey from region join nation on r_regionkey=n_regionkey"

}
