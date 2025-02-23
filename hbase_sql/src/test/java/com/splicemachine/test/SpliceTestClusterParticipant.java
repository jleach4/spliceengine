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

package com.splicemachine.test;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.log4j.Logger;

import com.splicemachine.access.configuration.SQLConfiguration;

/**
 * Add an additional member to the cluster started with SpliceTestPlatform.
 * <p/>
 * Although MiniHBaseCluster can be used to create a multi region-server cluster all within a single JVM our system
 * currently requires each member to be in a separate JVM (because of static-state/singletons, SpliceDriver, for instance).
 * <p/>
 * Running: mvn exec:exec -PspliceClusterMember
 */
public class SpliceTestClusterParticipant {

    private static final Logger LOG = Logger.getLogger(SpliceTestClusterParticipant.class);

    private static final int REGION_SERVER_PORT = 60020;
    private static final int REGION_SERVER_WEB_PORT = 60030;

    private final String hbaseTargetDirectory;
    private final int memberNumber;

    /**
     * MAIN:
     * <p/>
     * arg-1: hbase dir
     * arg-2: cluster member number
     */
    public static void main(String[] args) throws Exception {
        SpliceTestClusterParticipant spliceTestPlatform;
        if (args.length == 2) {
            spliceTestPlatform = new SpliceTestClusterParticipant(args[0], Integer.parseInt(args[1]));
            spliceTestPlatform.start();
        } else {
            System.out.println("usage: SpliceTestClusterParticipant [hbase dir] [member number]");
            System.exit(1);
        }
    }

    public SpliceTestClusterParticipant(String hbaseTargetDirectory, int memberNumber) {
        this.hbaseTargetDirectory = hbaseTargetDirectory;
        this.memberNumber = memberNumber;
    }

    private void start() throws Exception {
        int regionServerPort = REGION_SERVER_PORT + memberNumber;
        int regionServerInfoPort = REGION_SERVER_WEB_PORT + memberNumber;
        int derbyPort = SQLConfiguration.DEFAULT_NETWORK_BIND_PORT + memberNumber;

        Configuration config = SpliceTestPlatformConfig.create(
                hbaseTargetDirectory,
                0,
                0,
                regionServerPort,
                regionServerInfoPort,
                derbyPort,
                false
        );

        MiniHBaseCluster miniHBaseCluster = new MiniHBaseCluster(config, 0, 1);
        miniHBaseCluster.startRegionServer();
    }

}
