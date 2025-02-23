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

package com.splicemachine.stream;

import com.google.common.net.HostAndPort;
import com.splicemachine.EngineDriver;
import com.splicemachine.access.HConfiguration;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.iapi.sql.olap.OlapResult;
import com.splicemachine.derby.impl.sql.execute.operations.*;
import com.splicemachine.derby.stream.ActivationHolder;
import com.splicemachine.derby.stream.iapi.RemoteQueryClient;
import com.splicemachine.pipeline.Exceptions;
import io.netty.channel.ChannelHandler;
import org.apache.log4j.Logger;
import org.spark_project.guava.util.concurrent.ListenableFuture;
import org.spark_project.guava.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;



/**
 * Created by dgomezferro on 5/20/16.
 */
@ChannelHandler.Sharable
public class RemoteQueryClientImpl implements RemoteQueryClient {
    private static final Logger LOG = Logger.getLogger(RemoteQueryClientImpl.class);

    private static StreamListenerServer server;

    private final SpliceBaseOperation root;
    private ListenableFuture<OlapResult> olapFuture;
    private StreamListener streamListener;
    private long offset = 0;
    private long limit = -1;

    public RemoteQueryClientImpl(SpliceBaseOperation root) {
        this.root = root;
    }

    private StreamListenerServer getServer() throws StandardException {
        synchronized (RemoteQueryClientImpl.class) {
            if (server == null) {
                server = new StreamListenerServer(0);
                server.start();
            }
        }
        return server;
    }

    @Override
    public void submit() throws StandardException {
        Activation activation = root.getActivation();
        ActivationHolder ah = new ActivationHolder(activation, root);

        try {
            updateLimitOffset();
            int streamingBatches = HConfiguration.getConfiguration().getSparkResultStreamingBatches();
            int streamingBatchSize = HConfiguration.getConfiguration().getSparkResultStreamingBatchSize();
            streamListener = new StreamListener(limit, offset, streamingBatches, streamingBatchSize);
            StreamListenerServer server = getServer();
            server.register(streamListener);
            HostAndPort hostAndPort = server.getHostAndPort();
            String host = hostAndPort.getHostText();
            int port = hostAndPort.getPort();
            UUID uuid = streamListener.getUuid();

            String sql = activation.getPreparedStatement().getSource();
            sql = sql == null ? root.toString() : sql;
            String userId = activation.getLanguageConnectionContext().getCurrentUserId(activation);

            RemoteQueryJob jobRequest = new RemoteQueryJob(ah, root.getResultSetNumber(), uuid, host, port, userId, sql,
                    streamingBatches, streamingBatchSize);
            olapFuture = EngineDriver.driver().getOlapClient().submit(jobRequest);
            olapFuture.addListener(new Runnable() {
                @Override
                public void run() {
                    try {
                        OlapResult olapResult = olapFuture.get();
                        streamListener.completed(olapResult);
                    } catch (ExecutionException e) {
                        LOG.warn("Execution failed", e);
                        streamListener.failed(e.getCause());
                    } catch (InterruptedException e) {
                        // this shouldn't happen, the olapFuture already completed
                        Thread.currentThread().interrupt();
                        LOG.error("Unexpected exception, shouldn't happen", e);
                        streamListener.failed(e);
                    }
                }
            }, MoreExecutors.sameThreadExecutor());
        } catch (IOException e) {
            throw Exceptions.parseException(e);
        }
    }

    private void updateLimitOffset() throws StandardException {
        if (root instanceof ScrollInsensitiveOperation
                || root instanceof AnyOperation
                || root instanceof OnceOperation) {

            SpliceOperation source = root.getSubOperations().get(0);
            if (!(source instanceof RowCountOperation))
                return;

            RowCountOperation rco = (RowCountOperation) source;
            this.limit = rco.getFetchLimit();
            this.offset = rco.getTotalOffset();
            if (this.offset == -1) {
                offset = 0;
            }
            rco.setBypass(); // bypass this RowCountOperation
        }
    }

    @Override
    public Iterator<LocatedRow> getIterator() {
        return streamListener.getIterator();
    }

    @Override
    public void close() throws Exception {
        streamListener.stopAllStreams();
    }
}
