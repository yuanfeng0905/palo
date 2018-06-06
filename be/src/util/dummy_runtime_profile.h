// Modifications copyright (C) 2017, Baidu.com, Inc.
// Copyright 2017 The Apache Software Foundation

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

#ifndef BDG_PALO_BE_SRC_UTIL_DEBUG_RUNTIME_PROFILE_H
#define BDG_PALO_BE_SRC_UTIL_DEBUG_RUNTIME_PROFILE_H

#include "common/object_pool.h"
#include "util/runtime_profile.h"

namespace palo {
class DummyProfile {
public:
    DummyProfile() : _pool(), _profile(new RuntimeProfile(&_pool, "dummy", false)) {}
    RuntimeProfile* profile() { return _profile; }
    virtual ~DummyProfile() {
        delete _profile;
    }

private:
    ObjectPool _pool;
    RuntimeProfile* const _profile;
};
}
#endif
