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

package com.baidu.palo.analysis;

import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.InternalException;
import com.baidu.palo.thrift.TExprNode;
import com.baidu.palo.thrift.TExprNodeType;
import com.baidu.palo.thrift.TTupleIsNullPredicate;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * Internal expr that returns true if all of the given tuples are NULL, otherwise false.
 * Used to make exprs originating from an inline view nullable in an outer join.
 * The given tupleIds must be materialized and nullable at the appropriate PlanNode.
 */
public class TupleIsNullPredicate extends Predicate {

    private final List<TupleId> tupleIds = Lists.newArrayList();

    public TupleIsNullPredicate(List<TupleId> tupleIds) {
        Preconditions.checkState(tupleIds != null && !tupleIds.isEmpty());
        this.tupleIds.addAll(tupleIds);
    }

    protected TupleIsNullPredicate(TupleIsNullPredicate other) {
        super(other);
        tupleIds.addAll(other.tupleIds);
   }

    @Override
    protected void analyzeImpl(Analyzer analyzer) throws AnalysisException {
        super.analyzeImpl(analyzer);
        // analyzer = analyzer;
        // evalCost_ = tupleIds_.size() * IS_NULL_COST;
    }

    @Override
    protected boolean isConstantImpl() { return false; }

    @Override
    public Expr clone() {
        return new TupleIsNullPredicate(this);
    }

    @Override
    protected void toThrift(TExprNode msg) {
        msg.node_type = TExprNodeType.TUPLE_IS_NULL_PRED;
        msg.tuple_is_null_pred = new TTupleIsNullPredicate();
        for (TupleId tid : tupleIds) {
            msg.tuple_is_null_pred.addToTuple_ids(tid.asInt());
        }
    }

    public List<TupleId> getTupleIds() {
        return tupleIds;
    }


    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }
        if (!(o instanceof TupleIsNullPredicate)) {
            return false;
        }
        TupleIsNullPredicate other = (TupleIsNullPredicate) o;
        return other.tupleIds.containsAll(tupleIds)
            && tupleIds.containsAll(other.tupleIds);
    }

    /**
     * Makes each input expr nullable, if necessary, by wrapping it as follows:
     * IF(TupleIsNull(tids), NULL, expr)
     *
     * The given tids must be materialized. The given inputExprs are expected to be bound
     * by tids once fully substituted against base tables. However, inputExprs may not yet
     * be fully substituted at this point.
     *
     * Returns a new list with the nullable exprs.
     */
    public static List<Expr> wrapExprs(List<Expr> inputExprs,
        List<TupleId> tids, Analyzer analyzer) throws InternalException {
        // Assert that all tids are materialized.
        for (TupleId tid: tids) {
            TupleDescriptor tupleDesc = analyzer.getTupleDesc(tid);
            Preconditions.checkState(tupleDesc.getIsMaterialized());
        }
        // Perform the wrapping.
        List<Expr> result = Lists.newArrayListWithCapacity(inputExprs.size());
        for (Expr e: inputExprs) {
            result.add(wrapExpr(e, tids, analyzer));
        }
        return result;
    }

    /**
     * Returns a new analyzed conditional expr 'IF(TupleIsNull(tids), NULL, expr)',
     * if required to make expr nullable. Otherwise, returns expr.
     */
    public static Expr wrapExpr(Expr expr, List<TupleId> tids, Analyzer analyzer)
        throws InternalException {
    if (!requiresNullWrapping(expr, analyzer)) {
        return expr;
    }
        List<Expr> params = Lists.newArrayList();
        params.add(new TupleIsNullPredicate(tids));
        params.add(new NullLiteral());
        params.add(expr);
        Expr ifExpr = new FunctionCallExpr("if", params);
        ifExpr.analyzeNoThrow(analyzer);
        return ifExpr;
    }

    /**
     * Returns true if the given expr evaluates to a non-NULL value if all its contained
     * SlotRefs evaluate to NULL, false otherwise.
     * Throws an InternalException if expr evaluation in the BE failed.
     */
    private static boolean requiresNullWrapping(Expr expr, Analyzer analyzer)
        throws InternalException {
        if (expr.isConstant()) {
            return false;
        }
        return true;
    }

    @Override
    public String toSql() {
        return "";
    }
}
