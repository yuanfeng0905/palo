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

#ifndef BDG_PALO_BE_RUNTIME_EXEC_ENV_H
#define BDG_PALO_BE_RUNTIME_EXEC_ENV_H

#include <boost/scoped_ptr.hpp>
#include <boost/shared_ptr.hpp>
#include <boost/thread/thread.hpp>

#include "agent/cgroups_mgr.h"
#include "common/status.h"
#include "common/object_pool.h"
#include "exprs/timestamp_functions.h"
#include "runtime/client_cache.h"
#include "runtime/lib_cache.h"
#include "util/thread_pool.hpp"
#include "util/priority_thread_pool.hpp"
#include "util/thread_pool.hpp"

namespace palo {

class DataStreamMgr;
class ResultBufferMgr;
class TestExecEnv;
class EvHttpServer;
class WebPageHandler;
class MemTracker;
class PoolMemTrackerRegistry;
class ThreadResourceMgr;
class FragmentMgr;
class TMasterInfo;
class EtlJobMgr;
class LoadPathMgr;
class DiskIoMgr;
class TmpFileMgr;
class BfdParser;
class PullLoadTaskMgr;
class BrokerMgr;
class MetricRegistry;
class BufferPool;
class ReservationTracker;
class ConnectionManager;
class SnapshotLoader;
class BrpcStubCache;

// Execution environment for queries/plan fragments.
// Contains all required global structures, and handles to
// singleton services. Clients must call StartServices exactly
// once to properly initialise service state.
class ExecEnv {
public:
    ExecEnv();

    /// Returns the first created exec env instance. In a normal impalad, this is
    /// the only instance. In test setups with multiple ExecEnv's per process,
    /// we return the most recently created instance.
    static ExecEnv* GetInstance() { return _exec_env; }

    // Empty destructor because the compiler-generated one requires full
    // declarations for classes in scoped_ptrs.
    virtual ~ExecEnv();

    uint32_t cluster_id();

    const std::string& token() const;

    MetricRegistry* metrics() const;

    DataStreamMgr* stream_mgr() {
        return _stream_mgr.get();
    }
    ResultBufferMgr* result_mgr() {
        return _result_mgr.get();
    }
    BackendServiceClientCache* client_cache() {
        return _client_cache.get();
    }
    FrontendServiceClientCache* frontend_client_cache() {
        return _frontend_client_cache.get();
    }
    BrokerServiceClientCache* broker_client_cache() {
        return _broker_client_cache.get();
    }
    WebPageHandler* web_page_handler() {
        return _web_page_handler.get();
    }
    MemTracker* process_mem_tracker() {
        return _mem_tracker.get();
    }
    PoolMemTrackerRegistry* pool_mem_trackers() {
        return _pool_mem_trackers.get();
    }
    ThreadResourceMgr* thread_mgr() {
        return _thread_mgr.get();
    }
    PriorityThreadPool* thread_pool() {
        return _thread_pool.get();
    }
    ThreadPool* etl_thread_pool() {
        return _etl_thread_pool.get();
    }
    CgroupsMgr* cgroups_mgr() {
        return _cgroups_mgr.get();
    }
    FragmentMgr* fragment_mgr() {
        return _fragment_mgr.get();
    }
    TMasterInfo* master_info() {
        return _master_info.get();
    }
    EtlJobMgr* etl_job_mgr() {
        return _etl_job_mgr.get();
    }
    LoadPathMgr* load_path_mgr() {
        return _load_path_mgr.get();
    }
    DiskIoMgr* disk_io_mgr() {
        return _disk_io_mgr.get();
    }
    TmpFileMgr* tmp_file_mgr() {
        return _tmp_file_mgr.get();
    }

    BfdParser* bfd_parser() const {
        return _bfd_parser.get();
    }

    PullLoadTaskMgr* pull_load_task_mgr() const {
        return _pull_load_task_mgr.get();
    }

    BrokerMgr* broker_mgr() const {
        return _broker_mgr.get();
    }

    SnapshotLoader* snapshot_loader() const {
        return _snapshot_loader.get();
    }

    BrpcStubCache* brpc_stub_cache() const {
        return _brpc_stub_cache.get();
    }

    void set_enable_webserver(bool enable) {
        _enable_webserver = enable;
    }

    // Starts any dependent services in their correct order
    virtual Status start_services();

    // Initializes the exec env for running FE tests.
    Status init_for_tests();

    ReservationTracker* buffer_reservation() { 
        return _buffer_reservation.get(); 
    }
 
    BufferPool* buffer_pool() { 
        return _buffer_pool.get(); 
    }

private:
    Status start_webserver();
    // Leave protected so that subclasses can override
    boost::scoped_ptr<DataStreamMgr> _stream_mgr;
    boost::scoped_ptr<ResultBufferMgr> _result_mgr;
    boost::scoped_ptr<BackendServiceClientCache> _client_cache;
    boost::scoped_ptr<FrontendServiceClientCache> _frontend_client_cache;
    std::unique_ptr<BrokerServiceClientCache>_broker_client_cache;
    boost::scoped_ptr<EvHttpServer> _ev_http_server;
    boost::scoped_ptr<WebPageHandler> _web_page_handler;
    boost::scoped_ptr<MemTracker> _mem_tracker;
    boost::scoped_ptr<PoolMemTrackerRegistry> _pool_mem_trackers;
    boost::scoped_ptr<ThreadResourceMgr> _thread_mgr;
    boost::scoped_ptr<PriorityThreadPool> _thread_pool;
    boost::scoped_ptr<ThreadPool> _etl_thread_pool;
    boost::scoped_ptr<CgroupsMgr> _cgroups_mgr;
    boost::scoped_ptr<FragmentMgr> _fragment_mgr;
    boost::scoped_ptr<TMasterInfo> _master_info;
    boost::scoped_ptr<EtlJobMgr> _etl_job_mgr;
    boost::scoped_ptr<LoadPathMgr> _load_path_mgr;
    boost::scoped_ptr<DiskIoMgr> _disk_io_mgr;
    boost::scoped_ptr<TmpFileMgr> _tmp_file_mgr;

    std::unique_ptr<BfdParser> _bfd_parser;
    std::unique_ptr<PullLoadTaskMgr> _pull_load_task_mgr;
    std::unique_ptr<BrokerMgr> _broker_mgr;
    std::unique_ptr<SnapshotLoader> _snapshot_loader;
    std::unique_ptr<BrpcStubCache> _brpc_stub_cache;
    bool _enable_webserver;

    boost::scoped_ptr<ReservationTracker> _buffer_reservation;
    boost::scoped_ptr<BufferPool> _buffer_pool;

    ObjectPool _object_pool;
private:
    static ExecEnv* _exec_env;
    TimezoneDatabase _tz_database;

    /// Initialise 'buffer_pool_' and 'buffer_reservation_' with given capacity.
    void init_buffer_pool(int64_t min_page_len, int64_t capacity, int64_t clean_pages_limit);
};

}

#endif
