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
import org.apache.commons.lang3.StringEscapeUtils;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isEmpty;

/**
 * Represents the user provided parameters of a given export.
 */
public class ExportParams implements Serializable {

    private static final String DEFAULT_ENCODING = Charsets.UTF_8.name();
    private static final short DEFAULT_REPLICATION_COUNT = 1;
    private static final char DEFAULT_FIELD_DELIMITER = ',';
    private static final char DEFAULT_QUOTE_CHAR = '"';
    private static final String DEFAULT_RECORD_DELIMITER = "\n";

    private String directory;
    private short replicationCount = DEFAULT_REPLICATION_COUNT;
    private boolean compression;
    private String characterEncoding = DEFAULT_ENCODING;

    private char fieldDelimiter = DEFAULT_FIELD_DELIMITER;
    private char quoteChar = DEFAULT_QUOTE_CHAR;

    // for serialization
    public ExportParams() {
    }

    public ExportParams(String directory, boolean compression, int replicationCount, String characterEncoding,
                        String fieldDelimiter, String quoteChar) throws StandardException {
        setDirectory(directory);
        setCompression(compression);
        setReplicationCount((short) replicationCount);
        setCharacterEncoding(characterEncoding);
        setDefaultFieldDelimiter(StringEscapeUtils.unescapeJava(fieldDelimiter));
        setQuoteChar(StringEscapeUtils.unescapeJava(quoteChar));
    }

    /**
     * Create params with all default options and the specified directory.
     */
    public static ExportParams withDirectory(String directory) {
        ExportParams params = new ExportParams();
        params.directory = directory;
        return params;
    }

    public String getDirectory() {
        return directory;
    }

    public char getFieldDelimiter() {
        return fieldDelimiter;
    }

    public char getQuoteChar() {
        return quoteChar;
    }

    public String getRecordDelimiter() {
        return DEFAULT_RECORD_DELIMITER;
    }

    public String getCharacterEncoding() {
        return characterEncoding;
    }

    public boolean isCompression() {
        return compression;
    }

    public short getReplicationCount() {
        return replicationCount;
    }

    // - - - - - - - - - - -
    // private setters
    // - - - - - - - - - - -

    private void setDirectory(String directory) throws StandardException {
        checkArgument(!isBlank(directory), "export path", directory);
        this.directory = directory;
    }

    private void setCompression(Boolean compression) throws StandardException {
        this.compression = compression;
    }

    private void setReplicationCount(short replicationCount) {
        if (replicationCount > 0) {
            this.replicationCount = replicationCount;
        }
    }

    public void setCharacterEncoding(String characterEncoding) throws StandardException {
        if (!isBlank(characterEncoding)) {
            checkArgument(isValidCharacterSet(characterEncoding), "encoding", characterEncoding);
            this.characterEncoding = characterEncoding;
        }
    }

    public void setDefaultFieldDelimiter(String fieldDelimiter) throws StandardException {
        if (!isEmpty(fieldDelimiter)) {
            checkArgument(fieldDelimiter.length() == 1, "field delimiter", fieldDelimiter);
            this.fieldDelimiter = fieldDelimiter.charAt(0);
        }
    }

    public void setQuoteChar(String quoteChar) throws StandardException {
        if (!isEmpty(quoteChar)) {
            checkArgument(quoteChar.length() == 1, "quote character", quoteChar);
            this.quoteChar = quoteChar.charAt(0);
        }
    }

    private static void checkArgument(boolean isOk, String parameter, String value) throws StandardException {
        if (!isOk) {
            throw StandardException.newException(SQLState.UU_INVALID_PARAMETER, parameter, value);
        }
    }

    public static boolean isValidCharacterSet(String charSet) {
        try {
            Charset.forName(charSet);
        } catch (UnsupportedCharsetException e) {
            return false;
        }
        return true;
    }

}
