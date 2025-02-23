/*
 * Copyright 2012 - 2016 Splice Machine, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.splicemachine.si.impl;

import org.spark_project.guava.base.Function;
import com.splicemachine.si.api.txn.TransactionStatus;

/**
 * Provides hooks for tests to provide callbacks. Mainly used to provide thread coordination in tests. It allows tests
 * to "trace" the internals of the SI execution.
 */
public class Tracer {
    private static transient Function<byte[],byte[]> fRowRollForward = null;
    private static transient Function<Long, Object> fTransactionRollForward = null;
    private static transient Function<Object[], Object> fStatus = null;
    private static transient Runnable fCompact = null;
    private static transient Function<Long, Object> fCommitting = null;
    private static transient Function<Long, Object> fWaiting = null;
    private static transient Function<Object[], Object> fRegion = null;
    private static transient Function<Object, String> bestAccess = null; 

    public static Integer rollForwardDelayOverride = null;

    public static void registerRowRollForward(Function<byte[],byte[]> f) {
        Tracer.fRowRollForward = f;
    }
    
    public static boolean isTracingRowRollForward() {
    	return Tracer.fRowRollForward != null;
    }

    public static void registerTransactionRollForward(Function<Long, Object> f) {
        Tracer.fTransactionRollForward = f;
    }

    public static boolean isTracingTransactionRollForward() {
    	return Tracer.fTransactionRollForward != null;
    }
    
    public static void registerStatus(Function<Object[], Object> f) {
        Tracer.fStatus = f;
    }

    public static void registerCompact(Runnable f) {
        Tracer.fCompact = f;
    }

    public static void registerCommitting(Function<Long, Object> f) {
        Tracer.fCommitting = f;
    }

    public static void registerBestAccess(Function<Object, String> f) {
        Tracer.bestAccess = f;
    }

    public static void registerWaiting(Function<Long, Object> f) {
        Tracer.fWaiting = f;
    }

    public static void registerRegion(Function<Object[], Object> f) {
        Tracer.fRegion = f;
    }

    public static void traceRowRollForward(byte[] key) {
        if (fRowRollForward != null) {
            fRowRollForward.apply(key);
        }
    }

    public static void traceTransactionRollForward(long transactionId) {
        if (fTransactionRollForward != null) {
            fTransactionRollForward.apply(transactionId);
        }
    }

    public static void traceStatus(long transactionId, TransactionStatus newStatus, boolean beforeChange) {
        if (fStatus != null) {
            fStatus.apply(new Object[] {transactionId, newStatus, beforeChange});
        }
    }

    public static void compact() {
        if (fCompact != null) {
            fCompact.run();
        }
    }

    public static void traceCommitting(long transactionId) {
        if (fCommitting != null) {
            fCommitting.apply(transactionId);
        }
    }

    public static void traceWaiting(long transactionId) {
        if (fWaiting != null) {
            fWaiting.apply(transactionId);
        }
    }

    public static void traceRegion(String tableName, Object region) {
        if (fRegion != null) {
            fRegion.apply(new Object[] {tableName, region});
        }
    }

    public static void traceBestAccess(Object objectParam) {
        if (bestAccess != null) {
        	bestAccess.apply(objectParam);
        }
    }

}