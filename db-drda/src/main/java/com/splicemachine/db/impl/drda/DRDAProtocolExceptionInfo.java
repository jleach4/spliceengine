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

package com.splicemachine.db.impl.drda;

class DRDAProtocolExceptionInfo {
    
    /*
      Holds static information about the protocol error
      to put in the Hash Table
    */
    
    /**
     * errorCodePoint specifies the code point of the error reply message, (e.g.
     * CodePoint.SYNTAXRM) whereas errCdCodePoint specifies the code point of an
     * extra required field in that reply message. Most error reply messages
     * have one or two required fields that are quite common, like SVRCOD
     * (severity code) or RDBNAM (database name). Some error reply messages
     * additionally have required fields that are specific to them.
     * errCdCodePoint is used to specify these. For instance, SYNTAXRM has a
     * required field called SYNERRCD, and PRCCNVRM has a required field called
     * PRCCNVCD.
     */
    protected int errorCodePoint;	   
    
    // Severity Code
    protected int svrcod;
    
    /**
     * The CodePoint describing the error condition for the errorCodePoint.
     * (e.g. CodePoint.SYNERRCD, when errorCodePoint is CodePoint.SYNTAXRM)
     */
    protected int errCdCodePoint ;
    
    // Sends an originating Codepoint
    protected boolean sendsCodpntArg;
	
    DRDAProtocolExceptionInfo(int errorCodePoint, int svrcod,  
					int errCdCodePoint,
					boolean sendsCodpntArg)
    {
	this.errorCodePoint = errorCodePoint;
	this.svrcod = svrcod;
	this.errCdCodePoint = errCdCodePoint;
	this.sendsCodpntArg = sendsCodpntArg;
    }
    
    
}

















