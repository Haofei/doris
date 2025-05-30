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

import org.codehaus.groovy.runtime.IOGroovyMethods

suite("test_index_index_V2_file_size", "nonConcurrent") {
    def isCloudMode = isCloudMode()
    def tableName = "test_index_index_V2_file_size"
    def backendId_to_backendIP = [:]
    def backendId_to_backendHttpPort = [:]
    getBackendIpHttpPort(backendId_to_backendIP, backendId_to_backendHttpPort);

    def set_be_config = { key, value ->
        for (String backend_id: backendId_to_backendIP.keySet()) {
            def (code, out, err) = update_be_config(backendId_to_backendIP.get(backend_id), backendId_to_backendHttpPort.get(backend_id), key, value)
            logger.info("update config: code=" + code + ", out=" + out + ", err=" + err)
        }
    }

    def get_rowset_count = { tablets ->
        int rowsetCount = 0
        for (def tablet in tablets) {
            def (code, out, err) = curl("GET", tablet.CompactionStatus)
            logger.info("Show tablets status: code=" + code + ", out=" + out + ", err=" + err)
            assertEquals(code, 0)
            def tabletJson = parseJson(out.trim())
            assert tabletJson.rowsets instanceof List
            rowsetCount +=((List<String>) tabletJson.rowsets).size()
        }
        return rowsetCount
    }

    boolean invertedIndexCompactionEnable = false
    try {
        String backend_id;
        backend_id = backendId_to_backendIP.keySet()[0]
        def (code, out, err) = show_be_config(backendId_to_backendIP.get(backend_id), backendId_to_backendHttpPort.get(backend_id))

        logger.info("Show config: code=" + code + ", out=" + out + ", err=" + err)
        assertEquals(code, 0)
        def configList = parseJson(out.trim())
        assert configList instanceof List

        for (Object ele in (List) configList) {
            assert ele instanceof List<String>
            if (((List<String>) ele)[0] == "inverted_index_compaction_enable") {
                invertedIndexCompactionEnable = Boolean.parseBoolean(((List<String>) ele)[2])
                logger.info("inverted_index_compaction_enable: ${((List<String>) ele)[2]}")
            }
        }
        set_be_config.call("inverted_index_compaction_enable", "true")

        sql """ DROP TABLE IF EXISTS ${tableName}; """
        sql """
            CREATE TABLE ${tableName} (
                `id` int(11) NULL,
                `name` varchar(255) NULL,
                `hobbies` text NULL,
                `score` int(11) NULL,
                index index_name (name) using inverted,
                index index_hobbies (hobbies) using inverted properties("support_phrase" = "true", "parser" = "english", "lower_case" = "true"),
                index index_score (score) using inverted
            ) ENGINE=OLAP
            DUPLICATE KEY(`id`)
            COMMENT 'OLAP'
            DISTRIBUTED BY HASH(`id`) BUCKETS 1
            PROPERTIES ( "replication_num" = "1", "disable_auto_compaction" = "true");
        """

        //TabletId,ReplicaId,BackendId,SchemaHash,Version,LstSuccessVersion,LstFailedVersion,LstFailedTime,LocalDataSize,RemoteDataSize,RowCount,State,LstConsistencyCheckTime,CheckVersion,VersionCount,PathHash,MetaUrl,CompactionStatus
        def tablets = sql_return_maparray """ show tablets from ${tableName}; """
        sql """ set enable_common_expr_pushdown = true """

        sql """ INSERT INTO ${tableName} VALUES (1, "andy", "andy love apple", 100); """
        sql """ INSERT INTO ${tableName} VALUES (1, "bason", "bason hate pear", 99); """
        sql """ INSERT INTO ${tableName} VALUES (2, "andy", "andy love apple", 100); """
        sql """ INSERT INTO ${tableName} VALUES (2, "bason", "bason hate pear", 99); """
        sql """ INSERT INTO ${tableName} VALUES (3, "andy", "andy love apple", 100); """
        sql """ INSERT INTO ${tableName} VALUES (3, "bason", "bason hate pear", 99); """

        GetDebugPoint().enableDebugPointForAllBEs("match.invert_index_not_support_execute_match")

        qt_sql """ select * from ${tableName} order by id, name, hobbies, score """
        qt_sql """ select * from ${tableName} where name match "andy" order by id, name, hobbies, score """
        qt_sql """ select * from ${tableName} where hobbies match "pear" order by id, name, hobbies, score """
        qt_sql """ select * from ${tableName} where score < 100 order by id, name, hobbies, score """

        // trigger full compactions for all tablets in ${tableName}
        trigger_and_wait_compaction(tableName, "full")

        def dedup_tablets = deduplicate_tablets(tablets)

        // In the p0 testing environment, there are no expected operations such as scaling down BE (backend) services
        // if tablets or dedup_tablets is empty, exception is thrown, and case fail
        int replicaNum = Math.floor(tablets.size() / dedup_tablets.size())
        if (replicaNum != 1 && replicaNum != 3)
        {
            assert(false);
        }

        // after full compaction, there is only 1 rowset.
        def count = get_rowset_count.call(tablets);
        if (isCloudMode) {
            assert (count == (1 + 1) * replicaNum)
        } else {
            assert (count == 1 * replicaNum)
        }

        qt_sql """ select * from ${tableName} order by id, name, hobbies, score """
        qt_sql """ select * from ${tableName} where name match "andy" order by id, name, hobbies, score """
        qt_sql """ select * from ${tableName} where hobbies match "pear" order by id, name, hobbies, score """
        qt_sql """ select * from ${tableName} where score < 100 order by id, name, hobbies, score """

        // insert more data and trigger full compaction again
        sql """ INSERT INTO ${tableName} VALUES (1, "andy", "andy love apple", 100); """
        sql """ INSERT INTO ${tableName} VALUES (1, "bason", "bason hate pear", 99); """
        sql """ INSERT INTO ${tableName} VALUES (2, "andy", "andy love apple", 100); """
        sql """ INSERT INTO ${tableName} VALUES (2, "bason", "bason hate pear", 99); """
        sql """ INSERT INTO ${tableName} VALUES (3, "andy", "andy love apple", 100); """
        sql """ INSERT INTO ${tableName} VALUES (3, "bason", "bason hate pear", 99); """

        set_be_config.call("inverted_index_compaction_enable", "false")
        // trigger full compactions for all tablets in ${tableName}
        trigger_and_wait_compaction(tableName, "full")

        // after full compaction, there is only 1 rowset.
        count = get_rowset_count.call(tablets);
        if (isCloudMode) {
            assert (count == (1 + 1) * replicaNum)
        } else {
            assert (count == 1 * replicaNum)
        }

        qt_sql """ select * from ${tableName} order by id, name, hobbies, score """
        qt_sql """ select * from ${tableName} where name match "andy" order by id, name, hobbies, score """
        qt_sql """ select * from ${tableName} where hobbies match "pear" order by id, name, hobbies, score """
        qt_sql """ select * from ${tableName} where score < 100 order by id, name, hobbies, score """
    } finally {
        GetDebugPoint().disableDebugPointForAllBEs("match.invert_index_not_support_execute_match")
        set_be_config.call("inverted_index_compaction_enable", invertedIndexCompactionEnable.toString())
    }
}
