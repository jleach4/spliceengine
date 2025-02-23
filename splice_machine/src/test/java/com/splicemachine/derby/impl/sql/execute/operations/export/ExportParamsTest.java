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

package com.splicemachine.derby.impl.sql.execute.operations.export;

import org.spark_project.guava.base.Charsets;
import com.splicemachine.db.iapi.error.StandardException;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ExportParamsTest {

    @Test
    public void constructor() throws StandardException {

        ExportParams exportParams = new ExportParams("/dir", false, 42, "ascii", "F", "Q");

        assertEquals("/dir", exportParams.getDirectory());
        assertEquals(false, exportParams.isCompression());
        assertEquals(42, exportParams.getReplicationCount());
        assertEquals("ascii", exportParams.getCharacterEncoding());
        assertEquals('F', exportParams.getFieldDelimiter());
        assertEquals('Q', exportParams.getQuoteChar());
    }

    @Test
    public void constructor_testDefaults() throws StandardException {

        ExportParams exportParams = new ExportParams("/dir", true, -1, null, null, null);

        assertEquals("/dir", exportParams.getDirectory());
        assertEquals(true, exportParams.isCompression());
        assertEquals(1, exportParams.getReplicationCount());
        assertEquals(Charsets.UTF_8.name(), exportParams.getCharacterEncoding());
        assertEquals(',', exportParams.getFieldDelimiter());
        assertEquals('"', exportParams.getQuoteChar());
    }

    @Test
    public void constructor_whileSpaceDelimitersAreAllowed() throws StandardException {
        ExportParams exportParams = new ExportParams("/dir", false, -1, null, " ", " ");
        assertEquals(' ', exportParams.getFieldDelimiter());
        assertEquals(' ', exportParams.getQuoteChar());
    }

    @Test
    public void constructor_usingJavaEscapeSequencesToDesignateArbitraryUnicodeCharactersForDelimiters() throws StandardException {

        ExportParams params1 = new ExportParams("/dir", true, -1, null, "\\t", "\\n");
        assertEquals("\t".charAt(0), params1.getFieldDelimiter());
        assertEquals("\n".charAt(0), params1.getQuoteChar());

        ExportParams params2 = new ExportParams("/dir", false, -1, null, "\\u0300", "\\u0400");
        assertEquals('\u0300', params2.getFieldDelimiter());
        assertEquals('\u0400', params2.getQuoteChar());
    }

    @Test
    public void constructor_badExportDirectory() {
        try {
            new ExportParams(null, true, 1, "UTF-8", ",", null);
            fail();
        } catch (Exception e) {
            assertEquals("Invalid parameter 'export path'='null'.", e.getMessage());
        }
    }

    @Test
    public void constructor_badCharacterEncoding() {
        try {
            new ExportParams("/dir", true, 1, "NON_EXISTING_CHARSET", ",", null);
            fail();
        } catch (StandardException e) {
            assertEquals("Invalid parameter 'encoding'='NON_EXISTING_CHARSET'.", e.getMessage());
        }
    }

    @Test
    public void constructor_badFieldDelimiter() {
        try {
            new ExportParams("/dir", true, 1, "UTF-8", ",,,", null);
            fail();
        } catch (Exception e) {
            assertEquals("Invalid parameter 'field delimiter'=',,,'.", e.getMessage());
        }
    }


    @Test
    public void constructor_badQuoteCharacter() {
        try {
            new ExportParams("/dir", true, 1, "UTF-8", ",", "||||");
            fail();
        } catch (Exception e) {
            assertEquals("Invalid parameter 'quote character'='||||'.", e.getMessage());
        }
    }


}