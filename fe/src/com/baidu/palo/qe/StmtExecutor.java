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

package com.baidu.palo.qe;

import com.baidu.palo.analysis.Analyzer;
import com.baidu.palo.analysis.CreateTableAsSelectStmt;
import com.baidu.palo.analysis.DdlStmt;
import com.baidu.palo.analysis.EnterStmt;
import com.baidu.palo.analysis.ExportStmt;
import com.baidu.palo.analysis.Expr;
import com.baidu.palo.analysis.InsertStmt;
import com.baidu.palo.analysis.KillStmt;
import com.baidu.palo.analysis.QueryStmt;
import com.baidu.palo.analysis.RedirectStatus;
import com.baidu.palo.analysis.SelectStmt;
import com.baidu.palo.analysis.SetStmt;
import com.baidu.palo.analysis.ShowStmt;
import com.baidu.palo.analysis.SqlParser;
import com.baidu.palo.analysis.SqlScanner;
import com.baidu.palo.analysis.StatementBase;
import com.baidu.palo.analysis.StmtRewriter;
import com.baidu.palo.analysis.UnsupportedStmt;
import com.baidu.palo.analysis.UseStmt;
import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.catalog.Column;
import com.baidu.palo.catalog.ColumnType;
import com.baidu.palo.catalog.Database;
import com.baidu.palo.catalog.Table.TableType;
import com.baidu.palo.catalog.Type;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.DdlException;
import com.baidu.palo.common.ErrorCode;
import com.baidu.palo.common.ErrorReport;
import com.baidu.palo.common.InternalException;
import com.baidu.palo.common.NotImplementedException;
import com.baidu.palo.common.util.DebugUtil;
import com.baidu.palo.common.util.ProfileManager;
import com.baidu.palo.common.util.RuntimeProfile;
import com.baidu.palo.common.util.TimeUtils;
import com.baidu.palo.mysql.MysqlChannel;
import com.baidu.palo.mysql.MysqlEofPacket;
import com.baidu.palo.mysql.MysqlSerializer;
import com.baidu.palo.mysql.privilege.PrivPredicate;
import com.baidu.palo.planner.Planner;
import com.baidu.palo.rewrite.ExprRewriter;
import com.baidu.palo.rpc.RpcException;
import com.baidu.palo.thrift.TExplainLevel;
import com.baidu.palo.thrift.TQueryOptions;
import com.baidu.palo.thrift.TResultBatch;
import com.baidu.palo.thrift.TUniqueId;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Do one COM_QEURY process.
// first: Parse receive byte array to statement struct.
// second: Do handle function for statement.
public class StmtExecutor {
    private static final Logger LOG = LogManager.getLogger(StmtExecutor.class);

    private ConnectContext context;
    private MysqlSerializer serializer;
    private String originStmt;
    private StatementBase parsedStmt;
    private Analyzer analyzer;
    private boolean isRegisterQuery = false;
    private RuntimeProfile profile;
    private RuntimeProfile summaryProfile;
    private volatile Coordinator coord = null;
    private MasterOpExecutor masterOpExecutor = null;
    private RedirectStatus redirectStatus = null;
    private Planner planner;
    private boolean isProxy;
    private ShowResultSet proxyResultSet = null;

    public StmtExecutor(ConnectContext context, String stmt, boolean isProxy) {
        this.context = context;
        this.originStmt = stmt;
        this.serializer = context.getSerializer();
        this.isProxy = isProxy;
    }

    public StmtExecutor(ConnectContext context, String stmt) {
        this(context, stmt, false);
    }

    // At the end of query execution, we begin to add up profile
    public void initProfile(long beginTimeInNanoSecond) {
        profile = new RuntimeProfile("Query");
        summaryProfile = new RuntimeProfile("Summary");
        summaryProfile.addInfoString(ProfileManager.QUERY_ID, DebugUtil.printId(context.queryId()));
        summaryProfile.addInfoString(ProfileManager.START_TIME, TimeUtils.longToTimeString(context.getStartTime()));

        long currentTimestamp = System.currentTimeMillis();
        long totalTimeMs = currentTimestamp - context.getStartTime();
        summaryProfile.addInfoString(ProfileManager.END_TIME, TimeUtils.longToTimeString(currentTimestamp));
        summaryProfile.addInfoString(ProfileManager.TOTAL_TIME, DebugUtil.getPrettyStringMs(totalTimeMs));

        summaryProfile.addInfoString(ProfileManager.QUERY_TYPE, "Query");
        summaryProfile.addInfoString(ProfileManager.QUERY_STATE, context.getState().toString());
        summaryProfile.addInfoString("Palo Version", "Palo version 2.0");
        summaryProfile.addInfoString(ProfileManager.USER, context.getQualifiedUser());
        summaryProfile.addInfoString(ProfileManager.DEFAULT_DB, context.getDatabase());
        summaryProfile.addInfoString(ProfileManager.SQL_STATEMENT, originStmt);
        profile.addChild(summaryProfile);
        if (coord != null) {
            coord.getQueryProfile().getCounterTotalTime().setValue(TimeUtils.getEstimatedTime(beginTimeInNanoSecond));
            coord.endProfile();
            profile.addChild(coord.getQueryProfile());
            coord = null;
        }
    }

    public boolean isForwardToMaster() {
        if (Catalog.getInstance().isMaster()) {
            return false;
        }

        if (redirectStatus == null) {
            return false;
        } else {
            return redirectStatus.isForwardToMaster();
        }
    }

    public ByteBuffer getOutputPacket() {
        if (masterOpExecutor == null) {
            return null;
        } else {
            return masterOpExecutor.getOutputPacket();
        }
    }

    public ShowResultSet getProxyResultSet() {
        return proxyResultSet;
    }

    public ShowResultSet getShowResultSet() {
        if (masterOpExecutor == null) {
            return null;
        } else {
            return masterOpExecutor.getProxyResultSet();
        }
    }

    public boolean isQueryStmt() {
        if (parsedStmt != null && parsedStmt instanceof QueryStmt) {
            return true;
        }
        return false;
    }

    // Execute one statement.
    // Exception:
    //  IOException: talk with client failed.
    public void execute() throws Exception {
        long beginTimeInNanoSecond = TimeUtils.getStartTime();
        try {
            // analyze this query
            analyze();

            if (isForwardToMaster()) {
                forwardToMaster();
                return;
            }  else {
                LOG.debug("no need to transfer to Master originStmt={}", originStmt);
            }

            if (parsedStmt instanceof QueryStmt) {
                if (!Catalog.getInstance().isMaster() && !Catalog.getInstance().canRead()) {
                    LOG.info("cannot read. forward to master");
                    forwardToMaster();
                    return;
                }
                LOG.debug("parsedStmt instanceof QueryStmt");
                int retryTime = 3;
                for (int i = 0; i < retryTime; i ++) {
                    try {
                        handleQueryStmt();
                        if (context.getSessionVariable().isReportSucc()) {
                            writeProfile(beginTimeInNanoSecond);
                        }
                        break;
                    } catch (RpcException e) {
                        if (i == retryTime - 1) {
                            throw e;
                        }
                        if (!context.getMysqlChannel().isSend()) {
                            LOG.warn("retry " + (i + 1) + " times, sql=" + originStmt);
                            continue;
                        } else {
                            throw e;
                        }
                    } finally {
                        QeProcessor.unregisterQuery(context.queryId());
                    }
                }
            } else if (parsedStmt instanceof SetStmt) {
                handleSetStmt();
            } else if (parsedStmt instanceof EnterStmt) {
                handleEnterStmt();
            } else if (parsedStmt instanceof UseStmt) {
                handleUseStmt();
            } else if (parsedStmt instanceof CreateTableAsSelectStmt) {
                handleInsertStmt();
            } else if (parsedStmt instanceof InsertStmt) { // Must ahead of DdlStmt because InserStmt is its subclass
                handleInsertStmt();
                if (context.getSessionVariable().isReportSucc()) {
                    writeProfile(beginTimeInNanoSecond);
                }
            } else if (parsedStmt instanceof DdlStmt) {
                handleDdlStmt();
            } else if (parsedStmt instanceof ShowStmt) {
                handleShow();
            } else if (parsedStmt instanceof KillStmt) {
                handleKill();
            } else if (parsedStmt instanceof ExportStmt) {
                handleExportStmt();
            } else if (parsedStmt instanceof UnsupportedStmt) {
                handleUnsupportedStmt();
            } else {
                context.getState().setError("Do not support this query.");
            }
        } catch (IOException e) {
            LOG.warn("execute IOException ", e);
            // the excetion happens when interact with client
            // this exception shows the connection is gone
            context.getState().setError(e.getMessage());
            throw e;
        } catch (AnalysisException e) {
            // analysis exception only print message, not print the stack
            LOG.warn("execute Exception", e);
            context.getState().setError(e.getMessage());
            context.getState().setErrType(QueryState.ErrType.ANALYSIS_ERR);
        } catch (Exception e) {
            LOG.warn("execute Exception", e);
            context.getState().setError(e.getMessage());
            if (parsedStmt instanceof KillStmt) {
                // ignore kill stmt execute err(not monitor it)
                context.getState().setErrType(QueryState.ErrType.ANALYSIS_ERR);
            }
        } finally {
            if (isRegisterQuery) {
                QeProcessor.unregisterQuery(context.queryId());
            }
        }
    }

    private void forwardToMaster() throws Exception {
        masterOpExecutor = new MasterOpExecutor(originStmt, context, redirectStatus);
        LOG.debug("need to transfer to Master originStmt={}", originStmt);
        masterOpExecutor.execute();
    }

    private void writeProfile(long beginTimeInNanoSecond) {
        initProfile(beginTimeInNanoSecond);
        StringBuilder builder = new StringBuilder();
        profile.prettyPrint(builder, "");
        System.out.println(builder.toString());
        ProfileManager.getInstance().pushProfile(profile);
    }

    // Lock all database before analyze
    private void lock(Map<String, Database> dbs) {
        if (dbs == null) {
            return;
        }
        for (Database db : dbs.values()) {
            db.readLock();
        }
    }

    // unLock all database after analyze
    private void unLock(Map<String, Database> dbs) {
        if (dbs == null) {
            return;
        }
        for (Database db : dbs.values()) {
            db.readUnlock();
        }
    }

    // Analyze one statement to structure in memory.
    private void analyze() throws AnalysisException, InternalException, 
                                               NotImplementedException {
        LOG.info("the originStmt is ={}", originStmt);
        // Parse statement with parser generated by CUP&FLEX
        SqlScanner input = new SqlScanner(new StringReader(originStmt));
        SqlParser parser = new SqlParser(input);
        try {
            parsedStmt = (StatementBase) parser.parse().value;
            redirectStatus = parsedStmt.getRedirectStatus();
        } catch (Error e) {
            LOG.warn("error happens when parsing sql: {}", e);
            throw new AnalysisException("sql parsing error, please check your sql");
        } catch (AnalysisException e) {
            LOG.warn("origin_stmt: " + originStmt + "; Analyze error message: " + parser.getErrorMsg(originStmt), e);
            String errorMessage = parser.getErrorMsg(originStmt);
            if (errorMessage == null) {
                throw  e;
            } else {
                throw new AnalysisException(errorMessage, e);
            }
        } catch (Exception e) {
            // TODO(lingbin): we catch 'Exception' to prevent unexpected error,
            // should be removed this try-catch clause future.
            LOG.warn("Analyze failed because " + parser.getErrorMsg(originStmt), e);
            throw new AnalysisException("Internal Error, maybe this is a bug, please contact with Palo RD.");
        }

        analyzer = new Analyzer(context.getCatalog(), context);
        // Convert show statement to select statement here
        if (parsedStmt instanceof ShowStmt) {
            SelectStmt selectStmt = ((ShowStmt) parsedStmt).toSelectStmt(analyzer);
            if (selectStmt != null) {
                parsedStmt = selectStmt;
            }
        }

        if (parsedStmt instanceof QueryStmt
                || parsedStmt instanceof InsertStmt
                || parsedStmt instanceof CreateTableAsSelectStmt) {
            Map<String, Database> dbs = Maps.newTreeMap();
            QueryStmt queryStmt;
            if (parsedStmt instanceof QueryStmt) {
                queryStmt = (QueryStmt) parsedStmt;
                queryStmt.getDbs(analyzer, dbs);
            } else {
                InsertStmt insertStmt;
                if (parsedStmt instanceof InsertStmt) {
                    insertStmt = (InsertStmt) parsedStmt;
                } else {
                    insertStmt = ((CreateTableAsSelectStmt) parsedStmt).getInsertStmt();
                }
                insertStmt.getDbs(analyzer, dbs);
            }

            lock(dbs);
            try {
                parsedStmt.analyze(analyzer);
                // TODO chenhao16, InsertStmt's QueryStmt rewrite
                StatementBase originStmt = null;
                if (parsedStmt instanceof InsertStmt) {
                    originStmt = parsedStmt;
                    parsedStmt = ((InsertStmt) parsedStmt).getQueryStmt();
                }
                if (parsedStmt instanceof QueryStmt) {
                    QueryStmt queryStmt1 = (QueryStmt)parsedStmt;
                    boolean isExplain = ((QueryStmt) parsedStmt).isExplain();
                    // Apply expr and subquery rewrites.
                    boolean reAnalyze = false;

                    ExprRewriter rewriter = analyzer.getExprRewriter();
                    rewriter.reset();
                    queryStmt1.rewriteExprs(rewriter);
                    reAnalyze = rewriter.changed();
                    if (analyzer.containSubquery()) {
                        StmtRewriter.rewrite(analyzer, parsedStmt);
                        reAnalyze = true;
                    }

                    if (reAnalyze) {
                        // The rewrites should have no user-visible effect. Remember the original result
                        // types and column labels to restore them after the rewritten stmt has been
                        // reset() and re-analyzed.
                        List<Type> origResultTypes = Lists.newArrayList();
                        for (Expr e: queryStmt1.getResultExprs()) {
                            origResultTypes.add(e.getType());
                        }
                        List<String> origColLabels =
                                Lists.newArrayList(queryStmt1.getColLabels());

                        // Re-analyze the stmt with a new analyzer.
                        analyzer = new Analyzer(context.getCatalog(), context);
                        // TODO chenhao16 , merge Impala
                        // insert re-analyze
                        if (originStmt != null) {
                            originStmt.reset();
                            originStmt.analyze(analyzer);
                        } else {
                            // query re-analyze
                            parsedStmt.reset();
                            parsedStmt.analyze(analyzer);
                        }
                        // Restore the original result types and column labels.
                        queryStmt1.castResultExprs(origResultTypes);
                        queryStmt1.setColLabels(origColLabels);
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("rewrittenStmt: " + parsedStmt.toSql());
                        }
                        if (isExplain) parsedStmt.setIsExplain(isExplain);
                    }
                }

                if (originStmt != null && originStmt instanceof InsertStmt) {
                    parsedStmt = originStmt;
                }
                // create plan
                planner = new Planner();
                if (parsedStmt instanceof QueryStmt || parsedStmt instanceof InsertStmt) {
                    planner.plan(parsedStmt, analyzer, context.getSessionVariable().toThrift());
                } else {
                    planner.plan(((CreateTableAsSelectStmt) parsedStmt).getInsertStmt(),
                            analyzer, new TQueryOptions());
                }
                // TODO(zc):
                // Preconditions.checkState(!analyzer.hasUnassignedConjuncts());
            } catch (AnalysisException e) {
                throw e;
            } catch (InternalException e) {
                throw e;
            }  catch (NotImplementedException e) {
                throw e;
            } catch (Exception e) {
                LOG.warn("Analyze failed because ", e);
                throw new AnalysisException("Internal Error, maybe this is a bug, please contact with Palo RD.");
            } finally {
                unLock(dbs);
            }
        } else {
            try {
                parsedStmt.analyze(analyzer);
            } catch (AnalysisException e) {
                throw e;
            } catch (Exception e) {
                LOG.warn("Analyze failed because ", e);
                throw new AnalysisException("Internal Error, maybe this is a bug, please contact with Palo RD.");
            }
        }
    }

    // Because this is called by other thread
    public void cancel() {
        Coordinator coordRef = coord;
        if (coordRef != null) {
            coordRef.cancel();
        }
    }

    // Handle kill statement.
    private void handleKill() throws DdlException {
        KillStmt killStmt = (KillStmt) parsedStmt;
        long id = killStmt.getConnectionId();
        ConnectContext killCtx = context.getConnectScheduler().getContext(id);
        if (killCtx == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_NO_SUCH_THREAD, id);
        }
        if (context == killCtx) {
            // Suicide
            context.setKilled();
        } else {
            // Check auth
            if (!Catalog.getCurrentCatalog().getAuth().checkGlobalPriv(ConnectContext.get(), PrivPredicate.ADMIN)) {
                ErrorReport.reportDdlException(ErrorCode.ERR_KILL_DENIED_ERROR, id);
            }

            killCtx.kill(killStmt.isConnectionKill());
        }
        context.getState().setOk();
    }

    // Process set statement.
    private void handleSetStmt() {
        try {
            SetStmt setStmt = (SetStmt) parsedStmt;
            SetExecutor executor = new SetExecutor(context, setStmt);
            executor.execute();
        } catch (DdlException e) {
            // Return error message to client.
            context.getState().setError(e.getMessage());
            return;
        }
        context.getState().setOk();
    }

    // Process a select statement.
    private void handleQueryStmt() throws Exception {
        // Every time set no send flag and clean all data in buffer
        context.getMysqlChannel().reset();
        QueryStmt queryStmt = (QueryStmt) parsedStmt;

        // assign request_id
        UUID uuid = UUID.randomUUID();
        context.setQueryId(new TUniqueId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()));

        if (queryStmt.isExplain()) {
            String explainString = planner.getExplainString(planner.getFragments(), TExplainLevel.VERBOSE);
            handleExplainStmt(explainString);
            return;
        }
        coord = new Coordinator(context, analyzer, planner);

        QeProcessor.registerQuery(context.queryId(), coord);
        isRegisterQuery = true;

        coord.exec();
        // if python's MysqlDb get error after sendfields, it can't catch the excpetion
        // so We need to send fields after first batch arrived

        // send result
        TResultBatch batch;
        MysqlChannel channel = context.getMysqlChannel();
        boolean isSendFields = false;
        while ((batch = coord.getNext()) != null) {
            if (!isSendFields) {
                sendFields(queryStmt.getColLabels(), queryStmt.getResultExprs());
            }
            isSendFields = true;

            for (ByteBuffer row : batch.getRows()) {
                channel.sendOnePacket(row);
            }
            context.updateReturnRows(batch.getRows().size());
        }

        if (!isSendFields) {
            sendFields(queryStmt.getColLabels(), queryStmt.getResultExprs());
        }
        context.getState().setEof();
    }

    // Process a select statement.
    private void handleInsertStmt() throws Exception {
        // Every time set no send flag and clean all data in buffer
        context.getMysqlChannel().reset();
        // create plan
        InsertStmt insertStmt = null;
        if (parsedStmt instanceof CreateTableAsSelectStmt) {
            // Create table here
            ((CreateTableAsSelectStmt) parsedStmt).createTable(analyzer);
            insertStmt = ((CreateTableAsSelectStmt) parsedStmt).getInsertStmt();
        } else {
            insertStmt = (InsertStmt) parsedStmt;
        }
        if (insertStmt.getQueryStmt().isExplain()) {
            String explainString = planner.getExplainString(planner.getFragments(), TExplainLevel.VERBOSE);
            handleExplainStmt(explainString);
            return;
        }

        // assign request_id
        UUID uuid = UUID.randomUUID();
        context.setQueryId(new TUniqueId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()));

        coord = new Coordinator(context, analyzer, planner);

        QeProcessor.registerQuery(context.queryId(), coord);
        isRegisterQuery = true;

        coord.exec();

        coord.join(context.getSessionVariable().getQueryTimeoutS());
        if (!coord.isDone()) {
            coord.cancel();
            ErrorReport.reportDdlException(ErrorCode.ERR_EXECUTE_TIMEOUT);
        }

        if (!coord.getExecStatus().ok()) {
            String errMsg = coord.getExecStatus().getErrorMsg();
            LOG.warn("insert failed: {}", errMsg);

            // hide host info
            int hostIndex = errMsg.indexOf("host");
            if (hostIndex != -1) {
                errMsg = errMsg.substring(0, hostIndex);
            }
            ErrorReport.reportDdlException(errMsg, ErrorCode.ERR_FAILED_WHEN_INSERT);
        }

        LOG.info("delta files is {}", coord.getDeltaUrls());

        if (insertStmt.getTargetTable().getType() != TableType.OLAP) {
            // no need to add load job.
            // mysql table is already being inserted.
            context.getState().setOk();
            return;
        }

        context.getCatalog().getLoadInstance().addLoadJob(
                uuid.toString(),
                insertStmt.getDb(),
                insertStmt.getTargetTable().getId(),
                coord.getDeltaUrls(),
                System.currentTimeMillis()
        );

        context.getState().setOk("{'label':'" + uuid.toString() + "'}");
    }

    private void handleUnsupportedStmt() {
        context.getMysqlChannel().reset();
        // do nothing
        context.getState().setOk();
    }

    // Process use statement.
    private void handleUseStmt() throws AnalysisException {
        UseStmt useStmt = (UseStmt) parsedStmt;
        try {
            if (Strings.isNullOrEmpty(useStmt.getClusterName())) {
                ErrorReport.reportAnalysisException(ErrorCode.ERR_CLUSTER_NO_SELECT_CLUSTER);
            }
            context.getCatalog().changeDb(context, useStmt.getDatabase());
        } catch (DdlException e) {
            context.getState().setError(e.getMessage());
            return;
        }
        context.getState().setOk();
    }

    private void sendMetaData(ShowResultSetMetaData metaData) throws IOException {
        // sends how many columns
        serializer.reset();
        serializer.writeVInt(metaData.getColumnCount());
        context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
        // send field one by one
        for (Column col : metaData.getColumns()) {
            serializer.reset();
            // TODO(zhaochun): only support varchar type
            serializer.writeField(col.getName(), col.getColumnType().getType());
            context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
        }
        // send EOF
        serializer.reset();
        MysqlEofPacket eofPacket = new MysqlEofPacket(context.getState());
        eofPacket.writeTo(serializer);
        context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
    }

    private void sendFields(List<String> colNames, List<Expr> exprs) throws IOException {
        // sends how many columns
        serializer.reset();
        serializer.writeVInt(colNames.size());
        context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
        // send field one by one
        for (int i = 0; i < colNames.size(); ++i) {
            serializer.reset();
            serializer.writeField(colNames.get(i), exprs.get(i).getType().getPrimitiveType());
            context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
        }
        // send EOF
        serializer.reset();
        MysqlEofPacket eofPacket = new MysqlEofPacket(context.getState());
        eofPacket.writeTo(serializer);
        context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
    }

    public void sendShowResult(ShowResultSet resultSet) throws IOException {
        context.updateReturnRows(resultSet.getResultRows().size());
        // Send meta data.
        sendMetaData(resultSet.getMetaData());

        // Send result set.
        for (List<String> row : resultSet.getResultRows()) {
            serializer.reset();
            for (String item : row) {
                if (item == null) {
                    serializer.writeNull();
                } else {
                    serializer.writeLenEncodedString(item);
                }
            }
            context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
        }

        context.getState().setEof();
    }
    // Process show statement
    private void handleShow() throws IOException, AnalysisException, DdlException {
        ShowExecutor executor = new ShowExecutor(context, (ShowStmt) parsedStmt);
        ShowResultSet resultSet = executor.execute();
        if (resultSet == null) {
            // state changed in execute
            return;
        }
        if (isProxy) {
            proxyResultSet = resultSet;
            return;
        }

        sendShowResult(resultSet);
    }

    private void handleExplainStmt(String result) throws IOException {
        ShowResultSetMetaData metaData =
                ShowResultSetMetaData.builder()
                        .addColumn(new Column("Explain String", ColumnType.createVarchar(20)))
                        .build();
        sendMetaData(metaData);

        // Send result set.
        for (String item : result.split("\n")) {
            serializer.reset();
            serializer.writeLenEncodedString(item);
            context.getMysqlChannel().sendOnePacket(serializer.toByteBuffer());
        }
        context.getState().setEof();
    }

    private void handleDdlStmt() {
        try {
            DdlExecutor.execute(context.getCatalog(), (DdlStmt) parsedStmt);
            context.getState().setOk();
        } catch (DdlException e) {
            // Return message to info client what happened.
            context.getState().setError(e.getMessage());
        } catch (Exception e) {
            // Maybe our bug
            LOG.warn("DDL statement(" + originStmt + ") process failed.", e);
            context.getState().setError("Unexpected exception: " + e.getMessage());
        }
    }

    // process enter cluster
    private void handleEnterStmt() {
        final EnterStmt enterStmt = (EnterStmt) parsedStmt;
        try {
            context.getCatalog().changeCluster(context, enterStmt.getClusterName());
            context.setDatabase("");
        } catch (DdlException e) {
            context.getState().setError(e.getMessage());
            return;
        }
        context.getState().setOk();
    }

    private void handleExportStmt() throws Exception {
        ExportStmt exportStmt = (ExportStmt) parsedStmt;
        context.getCatalog().getExportMgr().addExportJob(exportStmt);
    }
}
