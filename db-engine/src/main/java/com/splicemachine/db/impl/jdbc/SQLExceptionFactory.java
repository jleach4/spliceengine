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

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.jdbc.ExceptionFactory;
import com.splicemachine.db.iapi.services.i18n.MessageService;

import java.sql.SQLException;

/**
 *Class to create SQLException
 *
 */
public class SQLExceptionFactory implements ExceptionFactory {
    /**
     * method to construct SQLException
     * version specific drivers can overload this method to create
     * version specific exceptions
     */
    public SQLException getSQLException(String message, String messageId,
            SQLException next, int severity, Throwable t, Object[] args) {
        return new EmbedSQLException(message, messageId, next, severity,
                t, args);
    }

    /**
     * Construct an SQLException whose message and severity are derived from
     * the message id.
     */
    public final SQLException getSQLException(String messageId,
            SQLException next, Throwable cause, Object[] args) {
        String message = MessageService.getCompleteMessage(messageId, args);
        int severity = StandardException.getSeverityFromIdentifier(messageId);
        return getSQLException(message, messageId, next, severity, cause, args);
    }

	/**
	 * Unpack a SQL exception, looking for an EmbedSQLException which carries
	 * the Derby messageID and args which we will serialize across DRDA so
	 * that the client can reconstitute a SQLException with appropriate text.
	 * If we are running JDBC3 or JDBC2, then we hope that the passed-in
	 * exception is already an EmbedSQLException, which carries all the
	 * information we need.
	 */
	public	SQLException	getArgumentFerry(SQLException se)
	{
		return StandardException.getArgumentFerry(se);
	}

}
