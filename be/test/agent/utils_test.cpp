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

#include <algorithm>
#include "gtest/gtest.h"
#include "gmock/gmock.h"
#include "agent/utils.h"
#include "service/backend_options.h"
#include "util/logging.h"

using ::testing::_;
using ::testing::Return;
using ::testing::SetArgPointee;
using std::string;

namespace palo {

TEST(AgentUtilsTest, Test) {
    const char* host_name = BackendOptions::get_localhost().c_str();
    int cnt = std::count(host_name, host_name + 17, '.');
    EXPECT_EQ(3, cnt);
}

}  // namespace palo

int main(int argc, char **argv) {
    std::string conffile = std::string(getenv("PALO_HOME")) + "/conf/be.conf";
    if (!palo::config::init(conffile.c_str(), false)) {
        fprintf(stderr, "error read config file. \n");
        return -1;
    }
    
    palo::init_glog("be-test");
    palo::BackendOptions::init();
    ::testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
}
