package com.splicemachine.procedures.external;

import com.splicemachine.EngineDriver;
import com.splicemachine.backup.BackupSystemProcedures;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.dictionary.DataDictionary;
import com.splicemachine.db.iapi.sql.dictionary.SchemaDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.TableDescriptor;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.db.impl.jdbc.EmbedConnection;
import com.splicemachine.derby.utils.SpliceAdmin;

import com.splicemachine.procedures.ProcedureUtils;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.log4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 *
 * Created by jfilali on 11/18/16.
 */
public class ExternalTableSystemProcedures {

    private static Logger LOG = Logger.getLogger(ExternalTableSystemProcedures.class);

    /**
     * This will refresh the schema of th external file. This is useful when some modify the file
     * outside of Splice.
     * It will grab the schema and the table descriptor, then get the file location and will create
     * a job for spark that will force the schema to refresh.
     *
     * @param schema
     * @param table
     * @param resultSets
     * @throws StandardException
     * @throws SQLException
     */

    public static void SYSCS_REFRESH_EXTERNAL_TABLE(String schema, String table, ResultSet[] resultSets) throws StandardException, SQLException {
        Connection conn = SpliceAdmin.getDefaultConn();
        LanguageConnectionContext lcc = conn.unwrap(EmbedConnection.class).getLanguageConnection();
        TransactionController tc=lcc.getTransactionExecute();
        try {
            DataDictionary data_dictionary=lcc.getDataDictionary();
            SchemaDescriptor sd=
                    data_dictionary.getSchemaDescriptor(schema,tc,true);
            TableDescriptor td=
                    data_dictionary.getTableDescriptor(table,sd,tc);

            if(!td.isExternal())
                throw StandardException.newException(SQLState.NOT_AN_EXTERNAL_TABLE, td.getName());

            String jobGroup = lcc.getSessionUserId() + " <" + tc.getTransactionIdString() +">";

            EngineDriver.driver().getOlapClient().execute(new DistributedRefreshExternalTableSchemaJob(jobGroup, td.getLocation()));
                    resultSets[0] = ProcedureUtils.generateResult("Success", String.format("%s.%s schema table refreshed",schema,table));

        }catch (Throwable t){
            resultSets[0] = ProcedureUtils.generateResult("Error", t.getLocalizedMessage());
            SpliceLogUtils.error(LOG, "Refresh external table Error", t);
            t.printStackTrace();
        }
    }

}
