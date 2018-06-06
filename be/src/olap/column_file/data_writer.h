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

#ifndef BDG_PALO_BE_SRC_OLAP_COLUMN_FILE_DATA_WRITER_H
#define BDG_PALO_BE_SRC_OLAP_COLUMN_FILE_DATA_WRITER_H

#include "olap/row_block.h"
#include "olap/writer.h"

namespace palo {
class RowBlock;
namespace column_file {
class SegmentWriter;

// 列文件格式的Writer,接口参考IWriter中的定义
class ColumnDataWriter : public IWriter {
public:
    ColumnDataWriter(SmartOLAPTable table, OLAPIndex* index, bool is_push_write);
    virtual ~ColumnDataWriter();
    virtual OLAPStatus init();
    virtual OLAPStatus attached_by(RowCursor* row_cursor);
    virtual OLAPStatus finalize();
    virtual OLAPStatus write_row_block(RowBlock* row_block);
    virtual uint64_t written_bytes();
    virtual MemPool* mem_pool();
private:
    OLAPStatus _add_segment();
    OLAPStatus _finalize_segment();
    OLAPStatus _flush_row_block(RowBlock* row_block, bool is_finalized);
    OLAPStatus _flush_row_block(bool is_finalized);

    OLAPIndex* _index;
    RowBlock* _row_block;      // 使用RowBlcok缓存要写入的数据
    RowCursor _cursor;
    SegmentWriter* _segment_writer;
    int64_t _num_rows;
    uint32_t _block_id;        // 当前Segment内的block编号
    uint32_t _max_segment_size;
    uint32_t _segment;

    DISALLOW_COPY_AND_ASSIGN(ColumnDataWriter);
};

}  // namespace column_file
}  // namespace palo

#endif // BDG_PALO_BE_SRC_OLAP_COLUMN_FILE_DATA_WRITER_H
