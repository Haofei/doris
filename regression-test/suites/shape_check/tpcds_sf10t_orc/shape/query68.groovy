/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

suite("query68") {
    String db = context.config.getDbNameByFile(new File(context.file.parent))
    if (isCloudMode()) {
        return
    }
    sql """
         use ${db};
         set enable_nereids_planner=true;
         set enable_nereids_distribute_planner=false;
         set enable_fallback_to_original_planner=false;
         set exec_mem_limit=21G;
         set be_number_for_test=3;
         set parallel_pipeline_task_num=8;
         set forbid_unknown_col_stats=true;
         set enable_nereids_timeout = false;
         set enable_runtime_filter_prune=false;
         set runtime_filter_type=8;
         set dump_nereids_memo=false;
         set disable_nereids_rules='PRUNE_EMPTY_PARTITION';
         set enable_fold_constant_by_be = false;
         set push_topn_to_agg = true;
         set TOPN_OPT_LIMIT_THRESHOLD = 1024;
         set enable_parallel_result_sink=true;
         """
    qt_ds_shape_68 '''
    explain shape plan
    select  c_last_name
       ,c_first_name
       ,ca_city
       ,bought_city
       ,ss_ticket_number
       ,extended_price
       ,extended_tax
       ,list_price
 from (select ss_ticket_number
             ,ss_customer_sk
             ,ca_city bought_city
             ,sum(ss_ext_sales_price) extended_price 
             ,sum(ss_ext_list_price) list_price
             ,sum(ss_ext_tax) extended_tax 
       from store_sales
           ,date_dim
           ,store
           ,household_demographics
           ,customer_address 
       where store_sales.ss_sold_date_sk = date_dim.d_date_sk
         and store_sales.ss_store_sk = store.s_store_sk  
        and store_sales.ss_hdemo_sk = household_demographics.hd_demo_sk
        and store_sales.ss_addr_sk = customer_address.ca_address_sk
        and date_dim.d_dom between 1 and 2 
        and (household_demographics.hd_dep_count = 4 or
             household_demographics.hd_vehicle_count= 0)
        and date_dim.d_year in (1999,1999+1,1999+2)
        and store.s_city in ('Pleasant Hill','Bethel')
       group by ss_ticket_number
               ,ss_customer_sk
               ,ss_addr_sk,ca_city) dn
      ,customer
      ,customer_address current_addr
 where ss_customer_sk = c_customer_sk
   and customer.c_current_addr_sk = current_addr.ca_address_sk
   and current_addr.ca_city <> bought_city
 order by c_last_name
         ,ss_ticket_number
 limit 100
    '''
}
