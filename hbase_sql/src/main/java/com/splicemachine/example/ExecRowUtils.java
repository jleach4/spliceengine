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

package com.splicemachine.example;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import org.apache.spark.mllib.linalg.DenseVector;
import org.apache.spark.mllib.linalg.Vector;

/**
 * Created by jleach on 12/15/15.
 */
public class ExecRowUtils {

    /**
     *
     * ExecRow is one-based as far as the elements
     *
     * @param execRow
     * @param fieldsToConvert
     * @return
     */
    public static Vector convertExecRowToVector(ExecRow execRow,int[] fieldsToConvert) throws StandardException {
        double[] vectorValues = new double[fieldsToConvert.length];
        for (int i=0;i<fieldsToConvert.length;i++) {
            vectorValues[i] = execRow.getColumn(fieldsToConvert[i]).getDouble();
        }
        return new DenseVector(vectorValues);
    }

}