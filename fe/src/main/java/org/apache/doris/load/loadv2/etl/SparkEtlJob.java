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

package org.apache.doris.load.loadv2.etl;

import org.apache.doris.load.loadv2.etl.EtlJobConfig.EtlColumn;
import org.apache.doris.load.loadv2.etl.EtlJobConfig.EtlFileGroup;
import org.apache.doris.load.loadv2.etl.EtlJobConfig.EtlIndex;
import org.apache.doris.load.loadv2.etl.EtlJobConfig.EtlTable;

import org.apache.spark.SparkConf;
import org.apache.spark.SparkFiles;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.SparkSession;

import com.google.common.collect.Lists;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.List;
import java.util.Map;

public class SparkEtlJob {
    private static final String SPARK_YARN_STAGING_DIR = "SPARK_YARN_STAGING_DIR";
    private static final String BITMAP_TYPE = "bitmap";

    private EtlJobConfig etlJobConfig;
    private boolean hasBitMapColumns;
    private JavaSparkContext sc;

    private void initSparkEnvironment() {
        SparkConf conf = new SparkConf();
        sc = new JavaSparkContext(conf);
    }

    private void initConfig() {
        String sparkYarnStagingDir = System.getenv(SPARK_YARN_STAGING_DIR);
        String jobConfigFilePath = null;
        if (sparkYarnStagingDir != null) {
            // master type |  deployMode type
            // ------------|----------------
            // yarn        |  cluster
            jobConfigFilePath = sparkYarnStagingDir + "/" + EtlJobConfig.JOB_CONFIG_FILE_NAME;
        } else {
            // master type |  deployMode type
            // ------------|----------------
            // spark://xx  |  client
            // spark://xx  |  cluster
            jobConfigFilePath = "file://" + SparkFiles.get(EtlJobConfig.JOB_CONFIG_FILE_NAME);
        }
        System.err.println("****** spark yarn staging dir: " + sparkYarnStagingDir);
        System.err.println("****** job config file path: " + jobConfigFilePath);

        JavaRDD<String> textFileRdd = sc.textFile(jobConfigFilePath);
        String jobJsonConfigs = String.join("", textFileRdd.collect());
        System.err.println("****** rdd read json configs: " + jobJsonConfigs);

        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES);
        Gson gson = gsonBuilder.create();
        etlJobConfig = gson.fromJson(jobJsonConfigs, EtlJobConfig.class);
        System.err.println("****** etl job configs: " + etlJobConfig.toString());
    }

    private void checkConfig() throws Exception {
        Map<Long, EtlTable> tables = etlJobConfig.tables;

        // spark etl must have only one table with bitmap type column to process.
        hasBitMapColumns = false;
        for (EtlTable table : tables.values()) {
            List<EtlColumn> baseSchema = null;
            for (EtlIndex etlIndex : table.indexes) {
                if (etlIndex.isBaseIndex) {
                    baseSchema = etlIndex.columns;
                }
            }
            for (EtlColumn column : baseSchema) {
                if (column.columnType.equalsIgnoreCase(BITMAP_TYPE)) {
                    hasBitMapColumns = true;
                    break;
                }
            }

            if (hasBitMapColumns) {
                break;
            }
        }

        if (hasBitMapColumns && tables.size() != 1) {
            throw new Exception("spark etl job must have only one table with bitmap type column to process");
        }
    }

    private void processDataFromPathes() {
    }

    private void buildGlobalDictAndEncodeSourceTable(EtlTable table, long tableId, SparkSession spark) {
        List<String> distinctColumnList = Lists.newArrayList();
        List<String> dorisOlapTableColumnList = Lists.newArrayList();
        List<String> mapSideJoinColumns = Lists.newArrayList();
        List<EtlColumn> baseSchema = null;
        for (EtlIndex etlIndex : table.indexes) {
            if (etlIndex.isBaseIndex) {
                baseSchema = etlIndex.columns;
            }
        }
        for (EtlColumn column : baseSchema) {
            if (column.columnType.equalsIgnoreCase(BITMAP_TYPE)) {
                distinctColumnList.add(column.columnName);
            }
            dorisOlapTableColumnList.add(column.columnName);
        }

        EtlFileGroup fileGroup = table.fileGroups.get(0);
        String sourceHiveDBTableName = fileGroup.hiveTableName;
        String dorisHiveDB = sourceHiveDBTableName.split("\\.")[0];
        String sourceHiveFilter = fileGroup.where;

        String taskId = etlJobConfig.outputPath.substring(etlJobConfig.outputPath.lastIndexOf("/") + 1);
        String globalDictTableName = String.format(EtlJobConfig.GLOBAL_DICT_TABLE_NAME, tableId);
        String distinctKeyTableName = String.format(EtlJobConfig.DISTINCT_KEY_TABLE_NAME, tableId, taskId);
        String dorisIntermediateHiveTable = String.format(EtlJobConfig.DORIS_INTERMEDIATE_HIVE_TABLE_NAME,
                                                          tableId, taskId);

        System.err.println("****** distinctColumnList: " + distinctColumnList);
        System.err.println("dorisOlapTableColumnList: " + dorisOlapTableColumnList);
        System.err.println("mapSideJoinColumns: " + mapSideJoinColumns);
        System.err.println("sourceHiveDBTableName: " + sourceHiveDBTableName);
        System.err.println("sourceHiveFilter: " + sourceHiveFilter);
        System.err.println("dorisHiveDB: " + dorisHiveDB);
        System.err.println("distinctKeyTableName: " + distinctKeyTableName);
        System.err.println("globalDictTableName: " + globalDictTableName);
        System.err.println("dorisIntermediateHiveTable: " + dorisIntermediateHiveTable);
        System.err.println("****** hasBitMapColumns: " + hasBitMapColumns);

        /*
        BuildGlobalDict buildGlobalDict = new BuildGlobalDict(distinctColumnList, dorisOlapTableColumnList,
                                                              mapSideJoinColumns, sourceHiveDBTableName,
                                                              sourceHiveFilter, dorisHiveDB, distinctKeyTableName,
                                                              globalDictTableName, dorisIntermediateHiveTable, spark);
        buildGlobalDict.createHiveIntermediateTable();
        buildGlobalDict.extractDistinctColumn();
        buildGlobalDict.buildGlobalDict();
        buildGlobalDict.encodeDorisIntermediateHiveTable();
         */
    }

    private void processDataFromHiveTable() {
        // only one table
        long tableId = -1;
        EtlTable table = null;
        for (Map.Entry<Long, EtlTable> entry : etlJobConfig.tables.entrySet()) {
            tableId = entry.getKey();
            table = entry.getValue();
            break;
        }

        SparkSession spark = SparkSession.builder().appName("Spark Etl").getOrCreate();

        // build global dict and and encode source hive table
        buildGlobalDictAndEncodeSourceTable(table, tableId, spark);

        // data partition sort and aggregation
    }

    private void processData() {
        if (hasBitMapColumns) {
            processDataFromHiveTable();
        } else {
            processDataFromPathes();
        }
    }

    private void run() throws Exception {
        initSparkEnvironment();
        initConfig();
        checkConfig();
        processData();
    }

    public static void main(String[] args) {
        try {
            new SparkEtlJob().run();
        } catch (Exception e) {
            System.err.println("spark etl job run fail");
            e.printStackTrace();
            System.exit(-1);
        }
    }
}