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

#pragma once

#include <stdint.h>

#include <string>
#include <map>

#include "common/status.h"
#include "exec/file_reader.h"
#include "gen_cpp/Types_types.h"
#include "gen_cpp/PaloBrokerService_types.h"

namespace palo {

class ExecEnv;
class TBrokerRangeDesc;
class TNetworkAddress;
class RuntimeState;

// Reader of broker file
class BrokerReader : public FileReader {
public:
    BrokerReader(ExecEnv* env,
                 const std::vector<TNetworkAddress>& broker_addresses,
                 const std::map<std::string, std::string>& properties,
                 const std::string& path,
                 int64_t start_offset);
    virtual ~BrokerReader();

    Status open();

    // Read 
    virtual Status read(uint8_t* buf, size_t* buf_len, bool* eof) override;

    virtual void close() override;
private:
    ExecEnv* _env;
    const std::vector<TNetworkAddress>& _addresses;
    const std::map<std::string, std::string>& _properties;
    const std::string& _path;

    int64_t _cur_offset;

    bool _is_fd_valid;
    TBrokerFD _fd;
    bool _eof;

    int _addr_idx;
};

}

