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

package com.splicemachine.derby.stream.control;

import java.io.Closeable;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.apache.log4j.Logger;
import org.spark_project.guava.io.Closeables;

import com.splicemachine.access.api.DistributedFileSystem;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.derby.impl.load.ImportUtils;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.utils.SpliceLogUtils;

/**
 * A component used during bulk import to write "bad" records to a file so that errors with the record
 * can be addressed and the record can be re-imported.<br/>
 * This can be used as part of a spark accumulator or as a stand-alone recorder (control side).
 * <p/>
 * A bad record is one that has cause some error during import and the general form in this file will be:
 * <pre>
 *     Some error msg with an error code [this,1,record that caused the error]
 * </pre>
 * Note that, when running import as a series of spark tasks, each task will have an instance of this class.
 */
public class BadRecordsRecorder implements Externalizable, Closeable {
    private static final Logger LOG = Logger.getLogger(BadRecordsRecorder.class);
    private static final String BAD_EXTENSION = ".bad";

    private long badRecordTolerance;
    private long numberOfBadRecords = 0L;
    private Path badRecordMasterPath;
    private transient OutputStream fileOut;

    public BadRecordsRecorder() {/*Externalizable*/}

    /**
     * Create an instance.
     * @param statusDirectory the directory in which the final bad record file should reside. If
     *                        null or empty, we'll use the parent directory of <code>filePath</code>
     * @param inputFilePath the input record file path (VTI file name). The bad record file will use
     *                 this to name the bad record file
     * @param badRecordTolerance the number of bad records we'll tolerate before failing the import.
     *                           <code>-1</code> (or any number less that zero) means tolerate all
     *                           bad records.
     */
    public BadRecordsRecorder(String statusDirectory, String inputFilePath, long badRecordTolerance) {
        this.badRecordTolerance = badRecordTolerance;
        try {
            this.badRecordMasterPath = generateWritableFilePath(statusDirectory, inputFilePath, BAD_EXTENSION);
        } catch (StandardException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Record a bad record found in this import.<br/>
     * Note that, when running in spark, these will be only from the spark task
     * in which the accumulator is running.
     * @param badRecord error msg + the import record that caused the problem.
     * @return <code>true</code> when we've hit "too many bad records". This return value cannot
     * be accessed in a spark task setting because we're running withing an accumulator and its
     * value cannot be accessed within a task.
     */
    public boolean recordBadRecord(String badRecord) {
        ++numberOfBadRecords;
        writeToFile(badRecord);
        if (reachedTooManyBadRecords()) {
            // close file when we've surpassed our limit of bad records
            close();
            return true;
        }
        return false;
    }

    /**
     * Get the name of the master bad record file.<br/>
     * @return the path of the final bad record file for this import.
     */
    public String getBadRecordFileName() {
        return badRecordMasterPath.toString();
    }

    /**
     * Get the number of bad records this spark task has seen.
     * @return bad records recorded.
     */
    public long getNumberOfBadRecords() {
        return numberOfBadRecords;
    }

    /**
     * Have we reached our tolerance of bad records?<br/>
     * <emph>NOTE</emph>: this may not be the overall tolerance of the import, just
     * this spark task.
     * @return <code>true</code> if this instance has reached tolerance.
     */
    public boolean reachedTooManyBadRecords() {
        // if tolerance < 0, we accept all bad records
        return (badRecordTolerance >= 0 && numberOfBadRecords > badRecordTolerance);
    }

    /**
     * Called from accumulator when merging them together.<br/>
     * <code>numberOfBadRecords</code> is accumulated.
     * @param r2 merge from <code>r2</code> into <code>this</code>.
     * @return <code>this</code>
     */
    public BadRecordsRecorder merge(BadRecordsRecorder r2) {
        // called by spark as result of accumulator.addInPlace()
        if (r2 != null) {
            this.numberOfBadRecords += r2.numberOfBadRecords;
        }

        return this;
    }

    @Override
    public void close() {
        if (fileOut !=null) {
            try {
                Closeables.close(fileOut, true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                fileOut = null;
            }
        }
    }

    /**
     * The unique name for a <code>BadRecordRecorder</code> is the file path for "bad" file -
     * its VTI file name.
     * @return name unique to this import
     */
    public String getUniqueName() {
        return this.badRecordMasterPath.toString();
    }

    /**
     * Write a record to the temp file
     * @param record record to write
     */
    private void writeToFile(String record) {
        // lazily init when we don't have a stream to which write
        if (fileOut == null) {
            try {
                DistributedFileSystem dfs = SIDriver.driver().fileSystem();
                String filePath = badRecordMasterPath.toString() + "_" + this.hashCode();
                fileOut = dfs.newOutputStream(filePath, StandardOpenOption.CREATE);
            } catch (Exception e) {
                close();
                throw new RuntimeException(e);
            }
        }
        try {
            fileOut.write(Bytes.toBytes(record));
        } catch (Exception e) {
            close();
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a file path to which to write and ensure it's writable. If a file exists at this location,
     * given these naming conventions, we'll discriminate by appending <code>_n</code>, where 'n' is an
     * increasing digit.
     * @param badDirectory the directory into which to create the file.  If null or empty, we'll
     *                     use the parent directory of <code>vtiFilePath</code>
     * @param vtiFilePath the complete path to the input file.  If <code>badDirectory</code> is
     *                    null or empty, we'll only use the file name, else we'll use this parent
     *                    directory for the bad file location.
     * @param extension the file extension. Used for initial name discrimination.
     * @return a writable file path to which to write.
     * @throws StandardException if an error occurs checking file writablility.
     */
    private static Path generateWritableFilePath(String badDirectory,
                                                 String vtiFilePath,
                                                 String extension) throws StandardException {
        DistributedFileSystem fileSystem = SIDriver.driver().fileSystem();
        Path inputFilePath = fileSystem.getPath(vtiFilePath);
        if (LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG, "BadRecordsRecorder: badDirectory=%s, filePath=%s", badDirectory, inputFilePath);
        assert inputFilePath != null;

        if (badDirectory == null || badDirectory.isEmpty() || badDirectory.toUpperCase().equals("NULL")) {
            badDirectory = inputFilePath.getParent().toString();
        }

        ImportUtils.validateWritable(badDirectory, true);
        int i = 0;
        while (true) {
            String fileName = badDirectory + "/" + inputFilePath.getFileName();
            fileName = fileName + (i == 0 ? extension : "_" + i + extension);
            Path fileSystemPathForWrites = fileSystem.getPath(fileName);
            if (! Files.exists(fileSystemPathForWrites)) {
                return fileSystemPathForWrites;
            }
            i++;
        }
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        // flush/close the stream if we opened it in this process
        // because we lose it in serde
        close();
        out.writeLong(badRecordTolerance);
        out.writeLong(numberOfBadRecords);
        out.writeUTF(badRecordMasterPath.toString());
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        DistributedFileSystem fileSystem = SIDriver.driver().fileSystem();
        badRecordTolerance = in.readLong();
        numberOfBadRecords = in.readLong();
        badRecordMasterPath = fileSystem.getPath(in.readUTF());
    }

    @Override
    public String toString(){
        return Long.toString(numberOfBadRecords);
    }
}
