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

namespace cpp palo
namespace java com.baidu.palo.thrift

enum TStatusCode {
  OK,
  CANCELLED,
  ANALYSIS_ERROR,
  NOT_IMPLEMENTED_ERROR,
  RUNTIME_ERROR,
  MEM_LIMIT_EXCEEDED,
  INTERNAL_ERROR,
  THRIFT_RPC_ERROR,
  TIMEOUT,
  KUDU_NOT_ENABLED,
  KUDU_NOT_SUPPORTED_ON_OS,
  MEM_ALLOC_FAILED,
  BUFFER_ALLOCATION_FAILED,
  MINIMUM_RESERVATION_UNAVAILABLE
}

struct TStatus {
  1: required TStatusCode status_code
  2: optional list<string> error_msgs
}
