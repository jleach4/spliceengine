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

package com.splicemachine.derby.impl.sql.execute.operations;

import com.splicemachine.test.SerialTest;
import org.junit.experimental.categories.Category;
import org.junit.After;
import org.junit.Before;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.spark_project.guava.collect.Lists;
import org.spark_project.guava.collect.Sets;
import com.splicemachine.derby.test.framework.SpliceSchemaWatcher;
import com.splicemachine.derby.test.framework.SpliceWatcher;
import com.splicemachine.derby.test.framework.TestConnection;
import com.splicemachine.homeless.TestUtils;
import com.splicemachine.test_tools.TableCreator;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static com.splicemachine.test_tools.Rows.row;
import static com.splicemachine.test_tools.Rows.rows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@Category(value = {SerialTest.class})
@RunWith(Parameterized.class)
public class RowCountOperationIT {

    private static final String SCHEMA = RowCountOperationIT.class.getSimpleName().toUpperCase();
    private static final SpliceWatcher spliceClassWatcher = new SpliceWatcher(SCHEMA);

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final int ROW_COUNT = 18;
    private static final long MIN_VALUE = 10;
    private static final long MAX_VALUE = 27;

    @ClassRule
    public static SpliceSchemaWatcher spliceSchemaWatcher = new SpliceSchemaWatcher(SCHEMA);


    private TestConnection conn;
    private String connectionString;

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        Collection<Object[]> params = Lists.newArrayListWithCapacity(2);
        params.add(new Object[]{"jdbc:splice://localhost:1527/splicedb;create=true;user=splice;password=admin"});
        params.add(new Object[]{"jdbc:splice://localhost:1527/splicedb;create=true;user=splice;password=admin;useSpark=true"});
        return params;
    }

    public RowCountOperationIT(String connectionString) throws Exception {
        this.connectionString = connectionString;
    }

    @Before
    public void setUp() throws Exception{
        conn = new TestConnection(DriverManager.getConnection(connectionString, new Properties()));
        conn.setAutoCommit(false);
        conn.setSchema(SCHEMA);
    }

    @After
    public void tearDown() throws Exception{
        conn.rollback();
        conn.reset();
    }

    @BeforeClass
    public static void createdSharedTables() throws Exception {
        TestConnection conn = spliceClassWatcher.getOrCreateConnection();

        List<Iterable<Object>> tableARows = Lists.newArrayList(
                row(10), row(11), row(12), row(13), row(14), row(15),
                row(16), row(17), row(18), row(19), row(20), row(21),
                row(22), row(23), row(24), row(25), row(26), row(27)
        );

        List<Iterable<Object>> tableBRows = Lists.newArrayList(
                row(10), row(11), row(12), row(13), row(14), row(15),
                row(16), row(17), row(18), row(19), row(20), row(21));

        // shuffle rows in test tables so tests do no depend on order
        Collections.shuffle(tableARows);
        Collections.shuffle(tableBRows);

        new TableCreator(conn)
                .withCreate("create table A (a bigint)")
                .withInsert("insert into A values(?)")
                .withRows(rows(tableARows)).create();

        new TableCreator(conn)
                .withCreate("create table B (a bigint)")
                .withInsert("insert into B values(?)")
                .withRows(rows(tableBRows)).create();


        conn.collectStats(spliceSchemaWatcher.schemaName,"A");

    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // first row only
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void firstRowOnly_ordered() throws Exception {
        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "10 |", "select * from A order by a fetch first row only");
    }

    @Test
    public void firstRowOnly_offset_ordered() throws Exception {
        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "20 |", "select * from A order by a offset 10 rows fetch first row only");
    }

    @Test
    public void topOneRow_offset_ordered() throws Exception {
        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "20 |", "select top * from A order by a offset 10 rows");
    }

    @Test
    public void firstRowOnly_unordered() throws Exception {
        validateUnOrdered(1, "select * from A fetch first row only");
    }

    @Test
    public void topOneRowOnly_unordered() throws Exception {
        validateUnOrdered(1, "select top * from A");
    }

    @Test
    public void firstRowOnly_overGroupBy() throws Exception {
        validateOrdered("" +
                "avg |\n" +
                "------\n" +
                " 18  |", "select avg(distinct a) as \"avg\" from A fetch first row only");
    }

    @Test
    public void topOneRowOnly_overGroupBy() throws Exception {
        validateOrdered("" +
                "avg |\n" +
                "------\n" +
                " 18  |", "select top 1 avg(distinct a) as \"avg\" from A");
    }

    @Test
    public void offset10Row_overGroupBy() throws Exception {
        validateOrdered("" +
                "sum |\n" +
                "------\n" +
                " 20  |\n" +
                " 21  |\n" +
                " 22  |\n" +
                " 23  |\n" +
                " 24  |\n" +
                " 25  |\n" +
                " 26  |\n" +
                " 27  |", "select sum(a) as \"sum\" from A group by a order by a offset 10 rows");
    }
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // first x rows only
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void firstXRowsOnly_ordered() throws Exception {
        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "10 |\n" +
                "11 |\n" +
                "12 |\n" +
                "13 |\n" +
                "14 |\n" +
                "15 |\n" +
                "16 |\n" +
                "17 |\n" +
                "18 |\n" +
                "19 |\n" +
                "20 |\n" +
                "21 |\n" +
                "22 |\n" +
                "23 |\n" +
                "24 |", "select * from A order by a fetch first 15 rows only");
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // top x
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void topXRowsOnly_ordered() throws Exception {
        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "10 |\n" +
                "11 |\n" +
                "12 |\n" +
                "13 |\n" +
                "14 |\n" +
                "15 |\n" +
                "16 |\n" +
                "17 |\n" +
                "18 |\n" +
                "19 |\n" +
                "20 |\n" +
                "21 |\n" +
                "22 |\n" +
                "23 |\n" +
                "24 |", "select top 15 * from A order by a");
    }

    @Test
    public void firstXRowsOnly_unordered() throws Exception {
        validateUnOrdered(15, "select * from A fetch first 15 rows only");
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // { limit x }
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void limit_ordered() throws Exception {
        validateOrdered("" +
                            "A |\n" +
                            "----\n" +
                            "10 |\n" +
                            "11 |\n" +
                            "12 |\n" +
                            "13 |\n" +
                            "14 |\n" +
                            "15 |\n" +
                            "16 |\n" +
                            "17 |\n" +
                            "18 |\n" +
                            "19 |", "select * from A order by a { limit 10 }");
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // limit x (without braces)
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Ignore("DB-2750 - Remove requirement for curly braces around limit query.")
    @Test
    public void limit_WO_braces_ordered() throws Exception {
        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "10 |\n" +
                "11 |\n" +
                "12 |\n" +
                "13 |\n" +
                "14 |\n" +
                "15 |\n" +
                "16 |\n" +
                "17 |\n" +
                "18 |\n" +
                "19 |", "select * from A order by a limit 10");
    }

    @Test
    public void limit_unordered() throws Exception {
        validateUnOrdered(10, "select * from A  { limit 10 }");
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // first x rows only ( where x > table row count )
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void firstXRowsOnly_largeX_ordered() throws Exception {
        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "10 |\n" +
                "11 |\n" +
                "12 |\n" +
                "13 |\n" +
                "14 |\n" +
                "15 |\n" +
                "16 |\n" +
                "17 |\n" +
                "18 |\n" +
                "19 |\n" +
                "20 |\n" +
                "21 |\n" +
                "22 |\n" +
                "23 |\n" +
                "24 |\n" +
                "25 |\n" +
                "26 |\n" +
                "27 |", "select * from A order by a fetch first 1000 rows only");
    }

    @Test
    public void firstXRowsOnly_largeX_unordered() throws Exception {
        validateUnOrdered(ROW_COUNT, "select * from A fetch first 1000 rows only");
    }


    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // top x rows ( where x > table row count )
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void topXRows_largeX_ordered() throws Exception {
        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "10 |\n" +
                "11 |\n" +
                "12 |\n" +
                "13 |\n" +
                "14 |\n" +
                "15 |\n" +
                "16 |\n" +
                "17 |\n" +
                "18 |\n" +
                "19 |\n" +
                "20 |\n" +
                "21 |\n" +
                "22 |\n" +
                "23 |\n" +
                "24 |\n" +
                "25 |\n" +
                "26 |\n" +
                "27 |", "select top 1000 * from A order by a");
    }

    @Test
    public void topXRows_largeX_unordered() throws Exception {
        validateUnOrdered(ROW_COUNT, "select top 1000 * from A");
    }


    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // offset (small offset compared to number of rows returned)
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void offsetSmall_ordered() throws Exception {
        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "15 |\n" +
                "16 |\n" +
                "17 |\n" +
                "18 |\n" +
                "19 |\n" +
                "20 |\n" +
                "21 |\n" +
                "22 |\n" +
                "23 |\n" +
                "24 |\n" +
                "25 |\n" +
                "26 |\n" +
                "27 |", "select * from A order by a offset 5 rows");
    }

    @Test
    public void offsetSmall_unordered() throws Exception {
        validateUnOrdered(ROW_COUNT - 5, "select * from A offset 5 rows");
    }

    @Test
    public void offsetZero_unordered() throws Exception {
        validateUnOrdered(ROW_COUNT, "select * from A offset 0 rows");
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // offset (large offset compared to number of rows returned)
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void offsetLarge_ordered() throws Exception {
        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "26 |\n" +
                "27 |", "select * from A order by a offset 16 rows");
    }

    @Test
    public void offsetLarge_unordered() throws Exception {
        validateUnOrdered(ROW_COUNT - 16, "select * from A offset 16 rows");
    }


    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // offset + next X rows
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void offset_fetchNextXRows_ordered() throws Exception {
        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "13 |\n" +
                "14 |\n" +
                "15 |\n" +
                "16 |", "select * from A order by a offset 3 rows fetch next 4 rows only");
    }

    @Test
    public void offset_fetchNextXRows_unordered() throws Exception {
        validateUnOrdered(4, "select * from A offset 3 rows fetch next 4 rows only");
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // RowCountOperation over joins (intentionally leaving out order by clause in all of these
    // tests so that we hit the case where join is directly below RowCountOperation).
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void overJoin_firstRowOnly() throws Exception {
        validateUnOrdered(1, "select A.a from A join B on A.a=B.a fetch first row only");
    }

    @Test
    public void testRepeatedOverJoin_offset() throws Exception {
        for(int i=0;i<10;i++){
            overJoin_offset();
        }
    }

    @Test
    public void overJoin_nextXRowsOnly() throws Exception {
        validateUnOrdered(3, "select A.a from A join B on A.a=B.a fetch next 3 rows only");
    }

    @Test
    public void overJoin_offset() throws Exception {
        validateUnOrdered(8, "select A.a from A join B on A.a=B.a offset 4 rows");
    }

    @Test
    public void overJoin_offset_and_limit() throws Exception {
        validateUnOrdered(7, "select A.a from A join B on A.a=B.a offset 4 rows fetch next 7 row only");
    }

    @Test
    public void overJoin_offset_and_limit_MergeSort() throws Exception {
        validateUnOrdered(7, "select A.a from A join B --SPLICE-PROPERTIES joinStrategy=SORTMERGE \n" +
                "on A.a=B.a offset 4 rows fetch next 7 row only");
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // over distinct -- at one point RowOperation over DistinctScanOperation did not work.
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void overDistinct_offset() throws Exception {
        validateUnOrdered(16, "select distinct * from A offset 2 rows");
    }

    @Test
    public void overDistinct_offsetLarge() throws Exception {
        validateUnOrdered(0, "select distinct * from A offset 100 rows");
    }

    @Test
    public void overDistinct_offsetZero() throws Exception {
        validateUnOrdered(18, "select distinct * from A offset 0 rows");
    }

    @Test
    public void overDistinct_offset_and_limit() throws Exception {
        validateUnOrdered(4, "select distinct * from A offset 2 rows fetch first 4 rows only");
    }

    @Test
    public void overDistinct_limit() throws Exception {
        validateUnOrdered(15, "select distinct * from A fetch first 15 rows only");
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // subselect
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    @Test
    public void subSelect_offset() throws Exception {
        String query = "" +
                "select a from A where a in " +
                "(select B.a from B order by a desc offset 2 rows) " +
                "order by a offset 3 rows";

        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "13 |\n" +
                "14 |\n" +
                "15 |\n" +
                "16 |\n" +
                "17 |\n" +
                "18 |\n" +
                "19 |", query);

        validateUnOrdered(7, query);
    }

    @Test
    public void subSelect_limit() throws Exception {
        String query = "" +
                "select a from A where a in " +
                "(select B.a from B order by a desc { limit 6 })" +
                "order by a { limit 3 }";

        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "16 |\n" +
                "17 |\n" +
                "18 |", query);

        validateUnOrdered(3, query);

    }

    @Test
    public void subSelect_limit_offset() throws Exception {
        String query = "" +
                "select a from A where a in " +
                "(select B.a from B order by a desc { limit 6 }) " +
                "order by a offset 2 rows fetch first 3 rows only";

        validateOrdered("" +
                "A |\n" +
                "----\n" +
                "18 |\n" +
                "19 |\n" +
                "20 |", query);

        validateUnOrdered(3, query);
    }

    @Test
    public void createTableAsTopN() throws Exception {
        int updates = conn.createStatement().executeUpdate("create table topn as select top 10 a from A with data");
        assertEquals("Row count does not match expectation", 10, updates);
    }

    @Test
    public void createTableAsLimit() throws Exception {
        int updates = conn.createStatement().executeUpdate("create table tablelim as select a from A {limit 10} with data");
        assertEquals("Row count does not match expectation", 10, updates);
    }

    @Test
    public void exportWithLimit() throws Exception {
        final int limit = 10;
        String exportPath = temporaryFolder.getRoot().getAbsolutePath();
        String exportQuery = String.format("EXPORT('%s', false,null,null,null,null) select * from A {limit %d}", exportPath, limit);
        ResultSet rs = conn.createStatement().executeQuery(exportQuery);
        assertTrue(rs.next());
        long exportedRowCount = rs.getLong(1);
        assertEquals("Exported rows don't match limit", limit, exportedRowCount);
    }


    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // test utils
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    private void validateUnOrdered(int expectedRowCount, String query) throws Exception {
        Statement statement = conn.createStatement();
        ResultSet resultSet = statement.executeQuery(query);

        Set<Long> uniqueValues = Sets.newHashSet();
        long rowCount = 0;
        while (resultSet.next()) {
            long value = resultSet.getLong(1);
            uniqueValues.add(value);
            assertTrue("unexpected value in result set = " + value, value <= MAX_VALUE && value >= MIN_VALUE);
            rowCount++;
        }
        assertEquals("Row count does not match expectation", expectedRowCount, rowCount);
        assertEquals("Did not expect resultset to contain duplicates", rowCount, uniqueValues.size());
    }

    private void validateOrdered(String expectedResult, String query) throws Exception {
        Statement statement = conn.createStatement();
        ResultSet rs = statement.executeQuery(query);
        String queryResultAsString = TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
        assertEquals(expectedResult, queryResultAsString);
    }

}
