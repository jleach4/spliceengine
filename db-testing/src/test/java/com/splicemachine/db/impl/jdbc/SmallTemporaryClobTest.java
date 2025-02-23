/*
 * Apache Derby is a subproject of the Apache DB project, and is licensed under
 * the Apache License, Version 2.0 (the "License"); you may not use these files
 * except in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Splice Machine, Inc. has modified this file.
 *
 * All Splice Machine modifications are Copyright 2012 - 2016 Splice Machine, Inc.,
 * and are licensed to you under the License; you may not use this file except in
 * compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */
package com.splicemachine.db.impl.jdbc;

import com.splicemachine.dbTesting.functionTests.util.streams.CharAlphabet;
import com.splicemachine.dbTesting.functionTests.util.streams.LoopingAlphabetReader;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Test basic operations on a small temporary Clob.
 * <p>
 * The test is intended to use sizes that makes the Clob stay in memory (i.e.
 * it is not being pushed to disk due to size).
 */
public class SmallTemporaryClobTest extends InternalClobTest{

    private static final long CLOBLENGTH = 1027;
    private static final long BYTES_PER_CHAR = 3;

    public SmallTemporaryClobTest(String name) {
        super(name);
    }

    /**
     * Creates a small read-write Clob that is kept in memory.
     */
    public void setUp()
            throws Exception {
        super.initialCharLength = CLOBLENGTH;
        super.headerLength = 2 + 3;
       // All tamil letters. Also add the header bytes.
        super.initialByteLength = CLOBLENGTH *3 + headerLength;
        super.bytesPerChar = BYTES_PER_CHAR;
        EmbedStatement embStmt = (EmbedStatement)createStatement();
        iClob = new TemporaryClob(embStmt);
        transferData(
            new LoopingAlphabetReader(CLOBLENGTH, CharAlphabet.tamil()),
            iClob.getWriter(1L),
            CLOBLENGTH);
        assertEquals(CLOBLENGTH, iClob.getCharLength());
    }

    public void tearDown()
            throws Exception {
        this.iClob.release();
        this.iClob = null;
        super.tearDown();
    }

    public static Test suite()
            throws Exception {
        Class<? extends TestCase> theClass = SmallTemporaryClobTest.class;
        TestSuite suite = new TestSuite(theClass, "SmallTemporaryClobTest suite");
        suite.addTest(addModifyingTests(theClass));
        return suite;
    }

} // End class SmallTemporaryClobTest
