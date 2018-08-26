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

#ifndef BDG_PALO_BE_SRC_EXEC_BROKER_WRITER_H
#define BDG_PALO_BE_SRC_EXEC_BROKER_WRITER_H

#include <stdint.h>

#include <string>
#include <map>

#include "common/status.h"
#include "exec/file_writer.h"
#include "gen_cpp/Types_types.h"
#include "gen_cpp/PaloBrokerService_types.h"

namespace palo {

class ExecEnv;
class TBrokerRangeDesc;
class TNetworkAddress;

// Reader of broker file
class BrokerWriter : public FileWriter {
public:
    BrokerWriter(ExecEnv* env,
                  const std::vector<TNetworkAddress>& broker_addresses,
                  const std::map<std::string, std::string>& properties,
                  const std::string& dir,
                  int64_t start_offset);
    virtual ~BrokerWriter();

    virtual Status open() override;

    virtual Status write(const uint8_t* buf, size_t buf_len, size_t* written_len) override;

    virtual void close() override;

private:
    ExecEnv* _env;
    const std::vector<TNetworkAddress>& _addresses;
    const std::map<std::string, std::string>& _properties;
    std::string _path;
    int64_t _cur_offset;

    bool _is_closed;
    TBrokerFD _fd;

    // TODO: use for retry if one broker down
    int _addr_idx;
};

} // end namespace palo

#endif // BDG_PALO_BE_SRC_EXEC_BROKER_WRITER_H
