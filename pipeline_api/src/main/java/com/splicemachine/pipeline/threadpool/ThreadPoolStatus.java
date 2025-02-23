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

package com.splicemachine.pipeline.threadpool;

import javax.management.MXBean;

/**
 * @author Scott Fines
 * Created on: 6/3/13
 */
@MXBean
public interface ThreadPoolStatus {

    int getPendingTaskCount();

    /**
     * @return the number of threads currently executing a task
     */
    int getActiveThreadCount();

    /**
     * @return the number of threads which have been allocated. This is
     * different from {@link #getActiveThreadCount()} in that it also records
     * threads which are alive, but are not currently executing tasks.
     */
    int getCurrentThreadCount();

    long getTotalSubmittedTasks();

    long getTotalFailedTasks();

    long getTotalSuccessfulTasks();

    /**
     * @return the total number of completed tasks. Includes failed and successful.
     */
    long getTotalCompletedTasks();

    /**
     * @return the maximum number of threads available in the pool.
     */
    int getMaxThreadCount();

    void setMaxThreadCount(int newMaxThreadCount);

    /**
     * @return the keepAliveTime for the writerpool, in milliseconds.
     */
    long getThreadKeepAliveTimeMs();

    /**
     * Set the thread keepAliveTime for the writer pool, in milliseconds.
     * @param timeMs the new keepAliveTime for the writer pool. in milliseconds.
     */
    void setThreadKeepAliveTimeMs(long timeMs);

    /**
     * @return the largest size this writer pool has ever had.
     */
    int getLargestThreadCount();

    long getTotalRejectedTasks();


}
