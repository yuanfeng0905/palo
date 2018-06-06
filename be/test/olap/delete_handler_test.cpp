// Copyright (c) 2017, Baidu.com, Inc. All Rights Reserved

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include <unistd.h>

#include <algorithm>
#include <iostream>
#include <string>
#include <vector>

#include <boost/assign.hpp>
#include <boost/regex.hpp>
#include <gtest/gtest.h>

#include "olap/command_executor.h"
#include "olap/delete_handler.h"
#include "olap/olap_define.h"
#include "olap/olap_engine.h"
#include "olap/olap_main.cpp"
#include "olap/push_handler.h"
#include "olap/utils.h"
#include "util/logging.h"

using namespace std;
using namespace palo;
using namespace boost::assign;
using google::protobuf::RepeatedPtrField;

namespace palo {

static const uint32_t MAX_PATH_LEN = 1024;

void set_up() {
    char buffer[MAX_PATH_LEN];
    getcwd(buffer, MAX_PATH_LEN);
    config::storage_root_path = string(buffer) + "/data_test";
    remove_all_dir(config::storage_root_path);
    remove_all_dir(string(getenv("PALO_HOME")) + UNUSED_PREFIX);
    create_dir(config::storage_root_path);
    touch_all_singleton();    
}

void tear_down() {
    char buffer[MAX_PATH_LEN];
    getcwd(buffer, MAX_PATH_LEN);
    config::storage_root_path = string(buffer) + "/data_test";
    remove_all_dir(config::storage_root_path);
    remove_all_dir(string(getenv("PALO_HOME")) + UNUSED_PREFIX);
}

void set_default_create_tablet_request(TCreateTabletReq* request) {
    request->tablet_id = 10003;
    request->__set_version(1);
    request->__set_version_hash(0);
    request->tablet_schema.schema_hash = 270068375;
    request->tablet_schema.short_key_column_count = 2;
    request->tablet_schema.keys_type = TKeysType::AGG_KEYS;
    request->tablet_schema.storage_type = TStorageType::ROW;

    TColumn k1;
    k1.column_name = "k1";
    k1.__set_is_key(true);
    k1.column_type.type = TPrimitiveType::TINYINT;
    request->tablet_schema.columns.push_back(k1);

    TColumn k2;
    k2.column_name = "k2";
    k2.__set_is_key(true);
    k2.column_type.type = TPrimitiveType::SMALLINT;
    request->tablet_schema.columns.push_back(k2);

    TColumn k3;
    k3.column_name = "k3";
    k3.__set_is_key(true);
    k3.column_type.type = TPrimitiveType::INT;
    request->tablet_schema.columns.push_back(k3);

    TColumn k4;
    k4.column_name = "k4";
    k4.__set_is_key(true);
    k4.column_type.type = TPrimitiveType::BIGINT;
    request->tablet_schema.columns.push_back(k4);

    TColumn k5;
    k5.column_name = "k5";
    k5.__set_is_key(true);
    k5.column_type.type = TPrimitiveType::LARGEINT;
    request->tablet_schema.columns.push_back(k5);

    TColumn k9;
    k9.column_name = "k9";
    k9.__set_is_key(true);
    k9.column_type.type = TPrimitiveType::DECIMAL;
    k9.column_type.__set_precision(6);
    k9.column_type.__set_scale(3);
    request->tablet_schema.columns.push_back(k9);

    TColumn k10;
    k10.column_name = "k10";
    k10.__set_is_key(true);
    k10.column_type.type = TPrimitiveType::DATE;
    request->tablet_schema.columns.push_back(k10);

    TColumn k11;
    k11.column_name = "k11";
    k11.__set_is_key(true);
    k11.column_type.type = TPrimitiveType::DATETIME;
    request->tablet_schema.columns.push_back(k11);

    TColumn k12;
    k12.column_name = "k12";
    k12.__set_is_key(true);
    k12.column_type.__set_len(64);
    k12.column_type.type = TPrimitiveType::CHAR;
    request->tablet_schema.columns.push_back(k12);

    TColumn k13;
    k13.column_name = "k13";
    k13.__set_is_key(true);
    k13.column_type.__set_len(64);
    k13.column_type.type = TPrimitiveType::VARCHAR;
    request->tablet_schema.columns.push_back(k13);

    TColumn v;
    v.column_name = "v";
    v.__set_is_key(false);
    v.column_type.type = TPrimitiveType::BIGINT;
    v.__set_aggregation_type(TAggregationType::SUM);
    request->tablet_schema.columns.push_back(v);
}

void set_default_push_request(TPushReq* request) {
    request->tablet_id = 10003;
    request->schema_hash = 270068375;
    request->__set_version(2);
    request->__set_version_hash(1);
    request->timeout = 86400;
    request->push_type = TPushType::LOAD;
}

class TestDeleteConditionHandler : public testing::Test {
protected:
    void SetUp() {
        // Create local data dir for OLAPEngine.
        char buffer[MAX_PATH_LEN];
        getcwd(buffer, MAX_PATH_LEN);
        config::storage_root_path = string(buffer) + "/data_delete_condition";
        remove_all_dir(config::storage_root_path);
        ASSERT_EQ(create_dir(config::storage_root_path), OLAP_SUCCESS);

        // Initialize all singleton object.
        OLAPRootPath::get_instance()->reload_root_paths(config::storage_root_path.c_str());

        _command_executor = new(nothrow) CommandExecutor();
        ASSERT_TRUE(_command_executor != NULL);

        // 1. Prepare for query split key.
        // create base tablet
        OLAPStatus res = OLAP_SUCCESS;
        set_default_create_tablet_request(&_create_tablet);
        res = _command_executor->create_table(_create_tablet);
        ASSERT_EQ(OLAP_SUCCESS, res);
        _olap_table = _command_executor->get_table(
                _create_tablet.tablet_id, _create_tablet.tablet_schema.schema_hash);
        ASSERT_TRUE(_olap_table.get() != NULL);
        _header_file_name = _olap_table->header_file_name();
    }

    OLAPStatus push_empty_delta(int32_t version) {
        // push data
        TPushReq push_req;
        set_default_push_request(&push_req);
        push_req.version = version;
        push_req.version_hash = version;
        std::vector<TTabletInfo> tablets_info;
        return _command_executor->push(push_req, &tablets_info);
    }

    void TearDown() {
        // Remove all dir.
        _olap_table.reset();
        OLAPEngine::get_instance()->drop_table(
                _create_tablet.tablet_id, _create_tablet.tablet_schema.schema_hash);
        while (0 == access(_header_file_name.c_str(), F_OK)) {
            sleep(1);
        }
        ASSERT_EQ(OLAP_SUCCESS, remove_all_dir(config::storage_root_path));

        SAFE_DELETE(_command_executor);
    }

    typedef RepeatedPtrField<DeleteDataConditionMessage> del_cond_array;

    std::string _header_file_name;
    SmartOLAPTable _olap_table;
    TCreateTabletReq _create_tablet;
    CommandExecutor* _command_executor;
    DeleteConditionHandler _delete_condition_handler;
};

TEST_F(TestDeleteConditionHandler, StoreCondSucceed) {
    OLAPStatus success_res;
    std::vector<TCondition> conditions;

    TCondition condition;
    condition.column_name = "k1";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    condition.column_name = "k2";
    condition.condition_op = ">";
    condition.condition_values.clear();
    condition.condition_values.push_back("3");
    conditions.push_back(condition);

    condition.column_name = "k2";
    condition.condition_op = "<=";
    condition.condition_values.clear();
    condition.condition_values.push_back("5");
    conditions.push_back(condition);

    success_res = _delete_condition_handler.store_cond(_olap_table, 3, conditions);
    ASSERT_EQ(OLAP_SUCCESS, success_res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(3));

    // 验证存储在header中的过滤条件正确
    const del_cond_array& delete_conditions = _olap_table->delete_data_conditions();
    ASSERT_EQ(size_t(1), delete_conditions.size());
    EXPECT_EQ(3, delete_conditions.Get(0).version());
    ASSERT_EQ(size_t(3), delete_conditions.Get(0).sub_conditions_size());
    EXPECT_STREQ("k1=1", delete_conditions.Get(0).sub_conditions(0).c_str());
    EXPECT_STREQ("k2>>3", delete_conditions.Get(0).sub_conditions(1).c_str());
    EXPECT_STREQ("k2<=5", delete_conditions.Get(0).sub_conditions(2).c_str());

    // 再次存储相同版本号(版本号为3)的过滤条件
    conditions.clear();
    condition.column_name = "k1";
    condition.condition_op = "!=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    success_res = _delete_condition_handler.store_cond(_olap_table, 3, conditions);
    ASSERT_EQ(OLAP_SUCCESS, success_res);

    // 验证存储相同版本号的过滤条件情况下，新的过滤条件替换掉旧的过滤条件
    const del_cond_array& new_delete_conditions = _olap_table->delete_data_conditions();
    ASSERT_EQ(size_t(1), new_delete_conditions.size());
    EXPECT_EQ(3, new_delete_conditions.Get(0).version());
    ASSERT_EQ(size_t(1), new_delete_conditions.Get(0).sub_conditions_size());
    EXPECT_STREQ("k1!=1", new_delete_conditions.Get(0).sub_conditions(0).c_str());

    // 第三次存储不同版本号(版本号为4)的过滤条件
    conditions.clear();
    condition.column_name = "k1";
    condition.condition_op = "!=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    condition.column_name = "k1";
    condition.condition_op = "!=";
    condition.condition_values.clear();
    condition.condition_values.push_back("2");
    conditions.push_back(condition);

    success_res = _delete_condition_handler.store_cond(_olap_table, 4, conditions);
    ASSERT_EQ(OLAP_SUCCESS, success_res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(4));

    const del_cond_array& all_delete_conditions = _olap_table->delete_data_conditions();
    ASSERT_EQ(size_t(2), all_delete_conditions.size());
    EXPECT_EQ(3, all_delete_conditions.Get(0).version());
    ASSERT_EQ(size_t(1), all_delete_conditions.Get(0).sub_conditions_size());
    EXPECT_STREQ("k1!=1", all_delete_conditions.Get(0).sub_conditions(0).c_str());
    EXPECT_EQ(4, all_delete_conditions.Get(1).version());
    ASSERT_EQ(size_t(2), all_delete_conditions.Get(1).sub_conditions_size());
    EXPECT_STREQ("k1!=1", all_delete_conditions.Get(1).sub_conditions(0).c_str());
    EXPECT_STREQ("k1!=2", all_delete_conditions.Get(1).sub_conditions(1).c_str());
}

// 检测参数不正确的情况，包括：空的过滤条件字符串，以及负的版本号
TEST_F(TestDeleteConditionHandler, StoreCondInvalidParameters) {
    // 空的过滤条件
    std::vector<TCondition> conditions;
    OLAPStatus failed_res = _delete_condition_handler.store_cond(_olap_table, 3, conditions);
    ASSERT_EQ(OLAP_ERR_DELETE_INVALID_PARAMETERS, failed_res);

    // 负的版本号: -10
    TCondition condition;
    condition.column_name = "k1";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("2");
    conditions.push_back(condition);

    failed_res = _delete_condition_handler.store_cond(_olap_table, -10, conditions);
    ASSERT_EQ(OLAP_ERR_DELETE_INVALID_PARAMETERS, failed_res);
}

// 检测过滤条件中指定的列不存在,或者列不符合要求
TEST_F(TestDeleteConditionHandler, StoreCondNonexistentColumn) {
    // 'k100'是一个不存在的列
    std::vector<TCondition> conditions;
    TCondition condition;
    condition.column_name = "k100";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("2");
    conditions.push_back(condition);

    OLAPStatus failed_res = _delete_condition_handler.store_cond(_olap_table, 3, conditions);
    ASSERT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, failed_res);

    // 'v'是value列
    conditions.clear();
    condition.column_name = "v";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("5");
    conditions.push_back(condition);

    failed_res = _delete_condition_handler.store_cond(_olap_table, 3, conditions);
    ASSERT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, failed_res);
}

// 只删除特定版本的过滤条件
TEST_F(TestDeleteConditionHandler, DeleteCondRemoveOneCondition) {
    OLAPStatus res;
    std::vector<TCondition> conditions;
    TCondition condition;
    condition.column_name = "k1";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    condition.column_name = "k2";
    condition.condition_op = ">";
    condition.condition_values.clear();
    condition.condition_values.push_back("3");
    conditions.push_back(condition);

    condition.column_name = "k2";
    condition.condition_op = "<=";
    condition.condition_values.clear();
    condition.condition_values.push_back("5");
    conditions.push_back(condition);

    res = _delete_condition_handler.store_cond(_olap_table, 3, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(3));

    conditions.clear();
    condition.column_name = "k1";
    condition.condition_op = "!=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    res = _delete_condition_handler.store_cond(_olap_table, 4, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(4));

    conditions.clear();
    condition.column_name = "k2";
    condition.condition_op = ">=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    res = _delete_condition_handler.store_cond(_olap_table, 5, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(5));

    // 删除版本号为8的过滤条件
    res = _delete_condition_handler.delete_cond(_olap_table, 5, false);
    ASSERT_EQ(OLAP_SUCCESS, res);

    const del_cond_array& all_delete_conditions = _olap_table->delete_data_conditions();
    ASSERT_EQ(size_t(2), all_delete_conditions.size());

    EXPECT_EQ(3, all_delete_conditions.Get(0).version());
    ASSERT_EQ(size_t(3), all_delete_conditions.Get(0).sub_conditions_size());
    EXPECT_STREQ("k1=1", all_delete_conditions.Get(0).sub_conditions(0).c_str());
    EXPECT_STREQ("k2>>3", all_delete_conditions.Get(0).sub_conditions(1).c_str());
    EXPECT_STREQ("k2<=5", all_delete_conditions.Get(0).sub_conditions(2).c_str());

    EXPECT_EQ(4, all_delete_conditions.Get(1).version());
    ASSERT_EQ(size_t(1), all_delete_conditions.Get(1).sub_conditions_size());
    EXPECT_STREQ("k1!=1", all_delete_conditions.Get(1).sub_conditions(0).c_str());
}

// 删除特定版本以及版本比它小的过滤条件
TEST_F(TestDeleteConditionHandler, DeleteCondRemovBelowCondition) {
    OLAPStatus res;
    std::vector<TCondition> conditions;
    TCondition condition;
    condition.column_name = "k1";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    condition.column_name = "k2";
    condition.condition_op = ">";
    condition.condition_values.clear();
    condition.condition_values.push_back("3");
    conditions.push_back(condition);

    condition.column_name = "k2";
    condition.condition_op = "<=";
    condition.condition_values.clear();
    condition.condition_values.push_back("5");
    conditions.push_back(condition);

    res = _delete_condition_handler.store_cond(_olap_table, 3, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(3));

    conditions.clear();
    condition.column_name = "k1";
    condition.condition_op = "!=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    res = _delete_condition_handler.store_cond(_olap_table, 4, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(4));

    conditions.clear();
    condition.column_name = "k2";
    condition.condition_op = ">=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    res = _delete_condition_handler.store_cond(_olap_table, 5, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(5));

    // 删除版本号为7以及版本号小于7的过滤条件
    res = _delete_condition_handler.delete_cond(_olap_table, 4, true);
    ASSERT_EQ(OLAP_SUCCESS, res);

    const del_cond_array& all_delete_conditions = _olap_table->delete_data_conditions();
    ASSERT_EQ(size_t(1), all_delete_conditions.size());

    EXPECT_EQ(5, all_delete_conditions.Get(0).version());
    ASSERT_EQ(size_t(1), all_delete_conditions.Get(0).sub_conditions_size());
    EXPECT_STREQ("k2>=1", all_delete_conditions.Get(0).sub_conditions(0).c_str());
}

// 测试删除条件值不符合类型要求
class TestDeleteConditionHandler2 : public testing::Test {
protected:
    void SetUp() {
        // Create local data dir for OLAPEngine.
        char buffer[MAX_PATH_LEN];
        getcwd(buffer, MAX_PATH_LEN);
        config::storage_root_path = string(buffer) + "/data_delete_condition";
        remove_all_dir(config::storage_root_path);
        ASSERT_EQ(create_dir(config::storage_root_path), OLAP_SUCCESS);

        // Initialize all singleton object.
        OLAPRootPath::get_instance()->reload_root_paths(config::storage_root_path.c_str());

        _command_executor = new(nothrow) CommandExecutor();
        ASSERT_TRUE(_command_executor != NULL);

        // 1. Prepare for query split key.
        // create base tablet
        OLAPStatus res = OLAP_SUCCESS;
        set_default_create_tablet_request(&_create_tablet);
        res = _command_executor->create_table(_create_tablet);
        ASSERT_EQ(OLAP_SUCCESS, res);
        _olap_table = _command_executor->get_table(
                _create_tablet.tablet_id, _create_tablet.tablet_schema.schema_hash);
        ASSERT_TRUE(_olap_table.get() != NULL);
        _header_file_name = _olap_table->header_file_name();
    }

    void TearDown() {
        // Remove all dir.
        _olap_table.reset();
        OLAPEngine::get_instance()->drop_table(
                _create_tablet.tablet_id, _create_tablet.tablet_schema.schema_hash);
        while (0 == access(_header_file_name.c_str(), F_OK)) {
            sleep(1);
        }
        ASSERT_EQ(OLAP_SUCCESS, remove_all_dir(config::storage_root_path));

        SAFE_DELETE(_command_executor);
    }

    typedef RepeatedPtrField<DeleteDataConditionMessage> del_cond_array;

    std::string _header_file_name;
    SmartOLAPTable _olap_table;
    TCreateTabletReq _create_tablet;
    CommandExecutor* _command_executor;
};

TEST_F(TestDeleteConditionHandler2, ValidConditionValue) {
    OLAPStatus res;
    DeleteConditionHandler cond_handler;
    std::vector<TCondition> conditions;

    // 测试数据中, k1,k2,k3,k4类型分别为int8, int16, int32, int64
    TCondition condition;
    condition.column_name = "k1";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("-1");
    conditions.push_back(condition);

    condition.column_name = "k2";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("-1");
    conditions.push_back(condition);

    condition.column_name = "k3";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("-1");
    conditions.push_back(condition);

    condition.column_name = "k4";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("-1");
    conditions.push_back(condition);

    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_SUCCESS, res);

    // k5类型为int128
    conditions.clear();
    condition.column_name = "k5";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_SUCCESS, res);

    // k9类型为decimal, precision=6, frac=3
    conditions.clear();
    condition.column_name = "k9";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("2.3");
    conditions.push_back(condition);

    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_SUCCESS, res);

    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("2");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_SUCCESS, res);

    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("-2");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_SUCCESS, res);

    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("-2.3");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_SUCCESS, res);

    // k10,k11类型分别为date, datetime
    conditions.clear();
    condition.column_name = "k10";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("2014-01-01");
    conditions.push_back(condition);

    condition.column_name = "k10";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("2014-01-01 00:00:00");
    conditions.push_back(condition);

    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_SUCCESS, res);

    // k12,k13类型分别为string(64), varchar(64)
    conditions.clear();
    condition.column_name = "k12";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("YWFh");
    conditions.push_back(condition);

    condition.column_name = "k13";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("YWFhYQ==");
    conditions.push_back(condition);

    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_SUCCESS, res);
}

TEST_F(TestDeleteConditionHandler2, InvalidConditionValue) {
    OLAPStatus res;
    DeleteConditionHandler cond_handler;
    std::vector<TCondition> conditions;

    // 测试k1的值越上界，k1类型为int8
    TCondition condition;
    condition.column_name = "k1";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1000");
    conditions.push_back(condition);

    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k1的值越下界，k1类型为int8
    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("-1000");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k2的值越上界，k2类型为int16
    conditions[0].condition_values.clear();
    conditions[0].column_name = "k2";
    conditions[0].condition_values.push_back("32768");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k2的值越下界，k2类型为int16
    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("-32769");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k3的值越上界，k3类型为int32
    conditions[0].condition_values.clear();
    conditions[0].column_name = "k3";
    conditions[0].condition_values.push_back("2147483648");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k3的值越下界，k3类型为int32
    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("-2147483649");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k4的值越上界，k2类型为int64
    conditions[0].condition_values.clear();
    conditions[0].column_name = "k4";
    conditions[0].condition_values.push_back("9223372036854775808");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k4的值越下界，k1类型为int64
    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("-9223372036854775809");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k5的值越上界，k5类型为int128
    conditions[0].condition_values.clear();
    conditions[0].column_name = "k5";
    conditions[0].condition_values.push_back("170141183460469231731687303715884105728");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k5的值越下界，k5类型为int128
    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("-170141183460469231731687303715884105729");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k9整数部分长度过长，k9类型为decimal, precision=6, frac=3
    conditions[0].condition_values.clear();
    conditions[0].column_name = "k9";
    conditions[0].condition_values.push_back("1234.5");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k9小数部分长度过长，k9类型为decimal, precision=6, frac=3
    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("1.2345");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k9没有小数部分，但包含小数点
    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("1.");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k10类型的过滤值不符合对应格式，k10为date
    conditions[0].condition_values.clear();
    conditions[0].column_name = "k10";
    conditions[0].condition_values.push_back("20130101");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("2013-64-01");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("2013-01-40");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k11类型的过滤值不符合对应格式，k11为datetime
    conditions[0].condition_values.clear();
    conditions[0].column_name = "k11";
    conditions[0].condition_values.push_back("20130101 00:00:00");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("2013-64-01 00:00:00");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("2013-01-40 00:00:00");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("2013-01-01 24:00:00");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("2013-01-01 00:60:00");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    conditions[0].condition_values.clear();
    conditions[0].condition_values.push_back("2013-01-01 00:00:60");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);

    // 测试k12和k13类型的过滤值过长，k12,k13类型分别为string(64), varchar(64)
    conditions[0].condition_values.clear();
    conditions[0].column_name = "k12";
    conditions[0].condition_values.push_back("YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYW"
                                    "FhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYW"
                                    "FhYWFhYWFhYWFhYWFhYWFhYWFhYWE=;k13=YWFhYQ==");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);
    
    conditions[0].condition_values.clear();
    conditions[0].column_name = "k13";
    conditions[0].condition_values.push_back("YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYW"
                                    "FhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYW"
                                    "FhYWFhYWFhYWFhYWFhYWFhYWFhYWE=;k13=YWFhYQ==");
    res = cond_handler.store_cond(_olap_table, 2, conditions);
    EXPECT_EQ(OLAP_ERR_DELETE_INVALID_CONDITION, res);
}

class TestDeleteHandler : public testing::Test {
protected:
    void SetUp() {
        // Create local data dir for OLAPEngine.
        char buffer[MAX_PATH_LEN];
        getcwd(buffer, MAX_PATH_LEN);
        config::storage_root_path = string(buffer) + "/data_delete_condition";
        remove_all_dir(config::storage_root_path);
        ASSERT_EQ(create_dir(config::storage_root_path), OLAP_SUCCESS);

        // Initialize all singleton object.
        OLAPRootPath::get_instance()->reload_root_paths(config::storage_root_path.c_str());

        _command_executor = new(nothrow) CommandExecutor();
        ASSERT_TRUE(_command_executor != NULL);

        // 1. Prepare for query split key.
        // create base tablet
        OLAPStatus res = OLAP_SUCCESS;
        set_default_create_tablet_request(&_create_tablet);
        res = _command_executor->create_table(_create_tablet);
        ASSERT_EQ(OLAP_SUCCESS, res);
        _olap_table = _command_executor->get_table(
                _create_tablet.tablet_id, _create_tablet.tablet_schema.schema_hash);
        ASSERT_TRUE(_olap_table.get() != NULL);
        _header_file_name = _olap_table->header_file_name();

        _data_row_cursor.init(_olap_table->tablet_schema());
        _data_row_cursor.allocate_memory_for_string_type(_olap_table->tablet_schema());
    }

    OLAPStatus push_empty_delta(int32_t version) {
        // push data
        TPushReq push_req;
        set_default_push_request(&push_req);
        push_req.version = version;
        push_req.version_hash = version;
        std::vector<TTabletInfo> tablets_info;
        return _command_executor->push(push_req, &tablets_info);
    }

    void TearDown() {
        // Remove all dir.
        _olap_table.reset();
        _delete_handler.finalize();
        OLAPEngine::get_instance()->drop_table(
                _create_tablet.tablet_id, _create_tablet.tablet_schema.schema_hash);
        while (0 == access(_header_file_name.c_str(), F_OK)) {
            sleep(1);
        }
        ASSERT_EQ(OLAP_SUCCESS, remove_all_dir(config::storage_root_path));

        SAFE_DELETE(_command_executor);
    }

    typedef RepeatedPtrField<DeleteDataConditionMessage> del_cond_array;

    std::string _header_file_name;
    RowCursor _data_row_cursor;
    SmartOLAPTable _olap_table;
    TCreateTabletReq _create_tablet;
    DeleteHandler _delete_handler;
    CommandExecutor* _command_executor;
};

TEST_F(TestDeleteHandler, InitSuccess) {
    OLAPStatus res;
    std::vector<TCondition> conditions;
    DeleteConditionHandler delete_condition_handler;

    // Header中还没有删除条件
    res = _delete_handler.init(_olap_table, 2);
    ASSERT_EQ(OLAP_SUCCESS, res);

    // 往头文件中添加过滤条件
    TCondition condition;
    condition.column_name = "k1";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    condition.column_name = "k2";
    condition.condition_op = ">";
    condition.condition_values.clear();
    condition.condition_values.push_back("3");
    conditions.push_back(condition);

    condition.column_name = "k2";
    condition.condition_op = "<=";
    condition.condition_values.clear();
    condition.condition_values.push_back("5");
    conditions.push_back(condition);

    res = delete_condition_handler.store_cond(_olap_table, 3, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(3));

    conditions.clear();
    condition.column_name = "k1";
    condition.condition_op = "!=";
    condition.condition_values.clear();
    condition.condition_values.push_back("3");
    conditions.push_back(condition);

    res = delete_condition_handler.store_cond(_olap_table, 4, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(4));

    conditions.clear();
    condition.column_name = "k2";
    condition.condition_op = ">=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    res = delete_condition_handler.store_cond(_olap_table, 5, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(5));

    conditions.clear();
    condition.column_name = "k2";
    condition.condition_op = "!=";
    condition.condition_values.clear();
    condition.condition_values.push_back("3");
    conditions.push_back(condition);

    res = delete_condition_handler.store_cond(_olap_table, 6, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(6));

    // 从header文件中取出版本号小于等于7的过滤条件
    _delete_handler.finalize();
    res = _delete_handler.init(_olap_table, 4);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(2, _delete_handler.conditions_num());
    vector<int32_t> conds_version = _delete_handler.get_conds_version();
    sort(conds_version.begin(), conds_version.end());
    EXPECT_EQ(3, conds_version[0]);
    EXPECT_EQ(4, conds_version[1]);

    _delete_handler.finalize();
}

// 测试一个过滤条件包含的子条件之间是and关系,
// 即只有满足一条过滤条件包含的所有子条件，这条数据才会被过滤
TEST_F(TestDeleteHandler, FilterDataSubconditions) {
    OLAPStatus res;
    DeleteConditionHandler cond_handler;
    std::vector<TCondition> conditions;

    // 往Header中添加过滤条件
    // 过滤条件1
    TCondition condition;
    condition.column_name = "k1";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    condition.column_name = "k2";
    condition.condition_op = "!=";
    condition.condition_values.clear();
    condition.condition_values.push_back("4");
    conditions.push_back(condition);

    res = cond_handler.store_cond(_olap_table, 3, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(3));

    // 指定版本号为10以载入Header中的所有过滤条件(在这个case中，只有过滤条件1)
    _delete_handler.init(_olap_table, 10);

    // 构造一行测试数据
    vector<string> data_str;
    data_str.push_back("1");
    data_str.push_back("6");
    data_str.push_back("8");
    data_str.push_back("-1");
    data_str.push_back("16");
    data_str.push_back("1.2");
    data_str.push_back("2014-01-01");
    data_str.push_back("2014-01-01 00:00:00");
    data_str.push_back("YWFH");
    data_str.push_back("YWFH==");
    data_str.push_back("1");
    res = _data_row_cursor.from_string(data_str);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_TRUE(_delete_handler.is_filter_data(1, _data_row_cursor));

    // 构造一行测试数据
    data_str[1] = "4";
    res = _data_row_cursor.from_string(data_str);
    ASSERT_EQ(OLAP_SUCCESS, res);
    // 不满足子条件：k2!=4
    ASSERT_FALSE(_delete_handler.is_filter_data(1, _data_row_cursor));

    _delete_handler.finalize();
}

// 测试多个过滤条件之间是or关系，
// 即如果存在多个过滤条件，会一次检查数据是否符合这些过滤条件；只要有一个过滤条件符合，则过滤数据
TEST_F(TestDeleteHandler, FilterDataConditions) {
    OLAPStatus res;
    DeleteConditionHandler cond_handler;
    std::vector<TCondition> conditions;

    // 往Header中添加过滤条件
    // 过滤条件1
    TCondition condition;
    condition.column_name = "k1";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    condition.column_name = "k2";
    condition.condition_op = "!=";
    condition.condition_values.clear();
    condition.condition_values.push_back("4");
    conditions.push_back(condition);

    res = cond_handler.store_cond(_olap_table, 3, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(3));

    // 过滤条件2
    conditions.clear();
    condition.column_name = "k1";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("3");
    conditions.push_back(condition);

    res = cond_handler.store_cond(_olap_table, 4, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(4));

    // 过滤条件3
    conditions.clear();
    condition.column_name = "k2";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("5");
    conditions.push_back(condition);

    res = cond_handler.store_cond(_olap_table, 5, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(5));

    // 指定版本号为10以载入Header中的所有三条过滤条件
    _delete_handler.init(_olap_table, 10);

    vector<string> data_str;
    data_str.push_back("4");
    data_str.push_back("5");
    data_str.push_back("8");
    data_str.push_back("-1");
    data_str.push_back("16");
    data_str.push_back("1.2");
    data_str.push_back("2014-01-01");
    data_str.push_back("2014-01-01 00:00:00");
    data_str.push_back("YWFH");
    data_str.push_back("YWFH==");
    data_str.push_back("1");
    res = _data_row_cursor.from_string(data_str);
    ASSERT_EQ(OLAP_SUCCESS, res);
    // 这行数据会因为过滤条件3而被过滤
    ASSERT_TRUE(_delete_handler.is_filter_data(1, _data_row_cursor));

    _delete_handler.finalize();
}

// 测试在过滤时，版本号小于数据版本的过滤条件将不起作用
TEST_F(TestDeleteHandler, FilterDataVersion) {
    OLAPStatus res;
    DeleteConditionHandler cond_handler;
    std::vector<TCondition> conditions;

    // 往Header中添加过滤条件
    // 过滤条件1
    TCondition condition;
    condition.column_name = "k1";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("1");
    conditions.push_back(condition);

    condition.column_name = "k2";
    condition.condition_op = "!=";
    condition.condition_values.clear();
    condition.condition_values.push_back("4");
    conditions.push_back(condition);

    res = cond_handler.store_cond(_olap_table, 3, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(3));

    // 过滤条件2
    conditions.clear();
    condition.column_name = "k1";
    condition.condition_op = "=";
    condition.condition_values.clear();
    condition.condition_values.push_back("3");
    conditions.push_back(condition);

    res = cond_handler.store_cond(_olap_table, 4, conditions);
    ASSERT_EQ(OLAP_SUCCESS, res);
    ASSERT_EQ(OLAP_SUCCESS, push_empty_delta(4));

    // 指定版本号为10以载入Header中的所有过滤条件(过滤条件1，过滤条件2)
    _delete_handler.init(_olap_table, 10);

    // 构造一行测试数据
    vector<string> data_str;
    data_str.push_back("1");
    data_str.push_back("6");
    data_str.push_back("8");
    data_str.push_back("-1");
    data_str.push_back("16");
    data_str.push_back("1.2");
    data_str.push_back("2014-01-01");
    data_str.push_back("2014-01-01 00:00:00");
    data_str.push_back("YWFH");
    data_str.push_back("YWFH==");
    data_str.push_back("1");
    res = _data_row_cursor.from_string(data_str);
    ASSERT_EQ(OLAP_SUCCESS, res);
    // 如果数据版本小于6，则过滤条件1生效，这条数据被过滤
    ASSERT_TRUE(_delete_handler.is_filter_data(1, _data_row_cursor));
    // 如果数据版本大于6，则过滤条件1会被跳过
    ASSERT_FALSE(_delete_handler.is_filter_data(4, _data_row_cursor));

    _delete_handler.finalize();
}

}  // namespace palo

int main(int argc, char** argv) {
    std::string conffile = std::string(getenv("PALO_HOME")) + "/conf/be.conf";
    if (!palo::config::init(conffile.c_str(), false)) {
        fprintf(stderr, "error read config file. \n");
        return -1;
    }
    palo::init_glog("be-test");
    int ret = palo::OLAP_SUCCESS;
    testing::InitGoogleTest(&argc, argv);

    palo::set_up();
    ret = RUN_ALL_TESTS();
    palo::tear_down();

    google::protobuf::ShutdownProtobufLibrary();
    return ret;
}
