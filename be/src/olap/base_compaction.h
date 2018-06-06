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

#ifndef BDG_PALO_BE_SRC_OLAP_BASE_COMPACTION_H
#define BDG_PALO_BE_SRC_OLAP_BASE_COMPACTION_H

#include <map>
#include <string>

#include "olap/olap_common.h"
#include "olap/olap_define.h"
#include "olap/olap_index.h"
#include "olap/olap_table.h"

namespace palo {

class IData;

// @brief 实现对START_BASE_COMPACTION命令的处理逻辑，并返回处理结果
class BaseCompaction {
public:
    BaseCompaction() :
            _new_base_version(0, 0),
            _old_base_version(0, 0),
            _base_compaction_locked(false),
            _header_locked(false) {}

    virtual ~BaseCompaction() {
        _release_base_compaction_lock();
    }

    // 初始化BaseCompaction, 主要完成以下工作：
    // 1. 检查是否满足base compaction策略
    // 2. 如果满足，计算需要合并哪些版本
    //
    // 输入参数：
    // - table: 待执行BE的OLAPTable的智能指针
    // - is_manual_trigger
    //   - 如果为true，则是手动执行START_BASE_COMPACTION命令
    //   - 如果为false，则是根据BE策略来执行
    //
    // 返回值：
    // - 如果init执行成功，即可以执行BE，则返回OLAP_SUCCESS；
    // - 其它情况下，返回相应的错误码
    OLAPStatus init(SmartOLAPTable table, bool is_manual_trigger);

    // 执行BaseCompaction, 可能会持续很长时间
    //
    // 返回值：
    // - 如果执行成功，则返回OLAP_SUCCESS；
    // - 其它情况下，返回相应的错误码
    OLAPStatus run();

private:
    // 从need_merged_versions中剔除没过期的delete版本以及大于该delete版本的cumulative
    OLAPStatus _exclude_not_expired_delete(const std::vector<Version>& need_merged_versions,
                                           std::vector<Version>* candidate_versions);

    // 检验当前情况是否满足base compaction的触发策略
    //
    // 输入参数：
    // - is_manual_trigger: 是否是手动执行START_BASE_COMPACTION命令
    // 输出参数
    // - candidate_versions: BE可合并的cumulative文件
    //
    // 返回值：
    // - 如果满足触发策略，返回true
    // - 如果不满足，返回false
    bool _check_whether_satisfy_policy(bool is_manual_trigger,
                                       std::vector<Version>* candidate_versions);

    // 生成新的Base
    // 
    // 输入参数：
    // - new_base_version_hash: 新Base的VersionHash
    // - base_data_sources: 生成新Base需要的IData*
    // - selectivities: 生成Base过程中产生的selectivities
    // - row_count: 生成Base过程中产生的row_count
    //
    // 返回值：
    // - 如果执行成功，则返回OLAP_SUCCESS；
    // - 其它情况下，返回相应的错误码
    OLAPStatus _do_base_compaction(VersionHash new_base_version_hash,
                                  std::vector<IData*>* base_data_sources,
                                  std::vector<uint32_t>* selectivities,
                                  uint64_t* row_count);
   
    // 更新Header使得修改对外可见
    // 
    // 输入参数：
    // - selectivities: 生成Base过程中产生的selectivities
    // - row_count: 生成Base过程中产生的row_count
    // 
    // 输出参数：
    // - unused_olap_indices: 需要被物理删除的OLAPIndex*
    //
    // 返回值：
    // - 如果执行成功，则返回OLAP_SUCCESS；
    // - 其它情况下，返回相应的错误码
    OLAPStatus _update_header(const std::vector<uint32_t>& selectivities,
                              uint64_t row_count,
                              std::vector<OLAPIndex*>* unused_olap_indices);

    // 删除不再使用的OLAPIndex文件
    // 
    // 输入参数：
    // - unused_olap_indices: 需要被物理删除的OLAPIndex*
    //
    // 返回值：
    // - 如果执行成功，则返回OLAP_SUCCESS；
    // - 其它情况下，返回相应的错误码
    void _delete_old_files(std::vector<OLAPIndex*>* unused_indices);

    // 其它函数执行失败时，调用该函数进行清理工作
    void _cleanup();

    // 验证得到的candidate_versions是否正确
    //
    // 返回值：
    // - 如果错误，返回false
    // - 如果正确，返回true
    bool _validate_need_merged_versions(const std::vector<Version>& candidate_versions);

    // 验证删除文件操作是否正确
    // 
    // 返回值：
    // - 如果错误，返回OLAP_ERR_BE_ERROR_DELETE_ACTION
    // - 如果正确，返回OLAP_SUCCESS
    OLAPStatus _validate_delete_file_action();

    void _get_unused_versions(std::vector<Version>* unused_versions) {
        unused_versions->clear();

        std::vector<Version> all_versions;
        _table->list_versions(&all_versions);
        for (std::vector<Version>::const_iterator iter = all_versions.begin();
                iter != all_versions.end(); ++iter) {
            if (iter->first <= _new_base_version.second) {
                unused_versions->push_back(*iter);
            }
        }
    }

    // 根据version.second值，比较2个version的大小
    static bool _version_compare(const Version& left, const Version& right) {
        return left.second < right.second;
    }

    bool _try_base_compaction_lock() {
        if (_table->try_base_compaction_lock()) {
            _base_compaction_locked = true;
            return true;
        }

        return false;
    }

    void _release_base_compaction_lock() {
        if (_base_compaction_locked) {
            _table->release_base_compaction_lock();
            _base_compaction_locked = false;
        }
    }

    void _obtain_header_rdlock() {
        _table->obtain_header_rdlock();
        _header_locked = true;
    }

    void _obtain_header_wrlock() {
        _table->obtain_header_wrlock();
        _header_locked = true;
    }

    void _release_header_lock() {
        if (_header_locked) {
            _table->release_header_lock();
            _header_locked = false;
        }
    }

    // 需要进行操作的Table指针
    SmartOLAPTable _table;
    // 新base的version
    Version _new_base_version;
    // 现有base的version
    Version _old_base_version;
    // 现有的版本号最大的cumulative
    Version _latest_cumulative;
    // 在此次base compaction执行过程中，将被合并的cumulative文件版本
    std::vector<Version> _need_merged_versions;
    // 需要新增的版本对应的OLAPIndex
    std::vector<OLAPIndex*> _new_olap_indices;

    bool _base_compaction_locked;
    bool _header_locked;

    DISALLOW_COPY_AND_ASSIGN(BaseCompaction);
};

}  // namespace palo

#endif // BDG_PALO_BE_SRC_OLAP_BASE_COMPACTION_H
