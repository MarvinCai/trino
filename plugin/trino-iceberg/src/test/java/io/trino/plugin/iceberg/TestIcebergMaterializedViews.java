/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.iceberg;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.metadata.QualifiedObjectName;
import io.trino.spi.connector.ConnectorMaterializedViewDefinition;
import io.trino.spi.connector.SchemaTableName;
import io.trino.sql.tree.ExplainType;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.MaterializedResult;
import io.trino.testing.MaterializedRow;
import io.trino.transaction.TransactionId;
import io.trino.transaction.TransactionManager;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.trino.testing.MaterializedResult.DEFAULT_PRECISION;
import static io.trino.testing.TestingAccessControlManager.TestingPrivilegeType.DELETE_TABLE;
import static io.trino.testing.TestingAccessControlManager.TestingPrivilegeType.DROP_MATERIALIZED_VIEW;
import static io.trino.testing.TestingAccessControlManager.TestingPrivilegeType.INSERT_TABLE;
import static io.trino.testing.TestingAccessControlManager.TestingPrivilegeType.REFRESH_MATERIALIZED_VIEW;
import static io.trino.testing.TestingAccessControlManager.TestingPrivilegeType.SELECT_COLUMN;
import static io.trino.testing.TestingAccessControlManager.TestingPrivilegeType.UPDATE_TABLE;
import static io.trino.testing.TestingAccessControlManager.privilege;
import static io.trino.testing.assertions.Assert.assertEquals;
import static io.trino.testing.assertions.Assert.assertFalse;
import static io.trino.testing.assertions.Assert.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestIcebergMaterializedViews
        extends AbstractTestQueryFramework
{
    @Override
    protected DistributedQueryRunner createQueryRunner()
            throws Exception
    {
        return IcebergQueryRunner.createIcebergQueryRunner(ImmutableMap.of(), new IcebergConfig().getFileFormat(), ImmutableList.of());
    }

    @BeforeClass
    public void setUp()
    {
        assertUpdate("CREATE TABLE base_table1(_bigint BIGINT, _date DATE) WITH (partitioning = ARRAY['_date'])");
        assertUpdate("INSERT INTO base_table1 VALUES (0, DATE '2019-09-08'), (1, DATE '2019-09-09'), (2, DATE '2019-09-09')", 3);
        assertUpdate("INSERT INTO base_table1 VALUES (3, DATE '2019-09-09'), (4, DATE '2019-09-10'), (5, DATE '2019-09-10')", 3);
        assertQuery("SELECT count(*) FROM base_table1", "VALUES 6");

        assertUpdate("CREATE TABLE base_table2 (_varchar VARCHAR, _bigint BIGINT, _date DATE) WITH (partitioning = ARRAY['_bigint', '_date'])");
        assertUpdate("INSERT INTO base_table2 VALUES ('a', 0, DATE '2019-09-08'), ('a', 1, DATE '2019-09-08'), ('a', 0, DATE '2019-09-09')", 3);
        assertQuery("SELECT count(*) FROM base_table2", "VALUES 3");
    }

    @Test
    public void testCreateWithInvalidPropertyFails()
    {
        assertThatThrownBy(() -> computeActual("CREATE MATERIALIZED VIEW materialized_view_with_property " +
                "WITH (invalid_property = ARRAY['_date']) AS " +
                "SELECT _bigint, _date FROM base_table1"))
                .hasMessage("Catalog 'iceberg' does not support materialized view property 'invalid_property'");
    }

    @Test
    public void testShowCreate()
    {
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_with_property " +
                "WITH (partitioning = ARRAY['_date']) AS " +
                "SELECT _bigint, _date FROM base_table1");
        assertQuery("SELECT COUNT(*) FROM materialized_view_with_property", "VALUES 6");
        assertThat(computeActual("SHOW CREATE MATERIALIZED VIEW materialized_view_with_property").getOnlyValue())
                .isEqualTo(
                        "CREATE MATERIALIZED VIEW iceberg.tpch.materialized_view_with_property\n" +
                                "WITH (\n" +
                                "   format = 'ORC',\n" +
                                "   partitioning = ARRAY['_date']\n" +
                                ") AS\n" +
                                "SELECT\n" +
                                "  _bigint\n" +
                                ", _date\n" +
                                "FROM\n" +
                                "  base_table1");
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_with_property");
    }

    @Test
    public void testSystemMaterializedViewProperties()
    {
        assertThat(computeActual("SELECT * FROM system.metadata.materialized_view_properties WHERE catalog_name = 'iceberg'"))
                .contains(new MaterializedRow(DEFAULT_PRECISION, "iceberg", "partitioning", "[]", "array(varchar)", "Partition transforms"));
    }

    @Test
    public void testSessionCatalogSchema()
    {
        Session session = Session.builder(getSession())
                .setCatalog("tpch")
                .setSchema("tiny")
                .build();
        assertUpdate(session, "CREATE MATERIALIZED VIEW iceberg.tpch.materialized_view_session_test AS SELECT * FROM nation");
        assertQuery(session, "SELECT COUNT(*) FROM iceberg.tpch.materialized_view_session_test", "VALUES 25");
        assertUpdate(session, "DROP MATERIALIZED VIEW iceberg.tpch.materialized_view_session_test");

        session = Session.builder(getSession())
                .setCatalog(null)
                .setSchema(null)
                .build();
        assertUpdate(session, "CREATE MATERIALIZED VIEW iceberg.tpch.materialized_view_session_test AS SELECT * FROM iceberg.tpch.base_table1");
        assertQuery(session, "SELECT COUNT(*) FROM iceberg.tpch.materialized_view_session_test", "VALUES 6");
        assertUpdate(session, "DROP MATERIALIZED VIEW iceberg.tpch.materialized_view_session_test");
    }

    @Test
    public void testDropDenyPermission()
    {
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_drop_deny AS SELECT * FROM base_table1");
        assertAccessDenied(
                "DROP MATERIALIZED VIEW materialized_view_drop_deny",
                "Cannot drop materialized view .*.materialized_view_drop_deny.*",
                privilege("materialized_view_drop_deny", DROP_MATERIALIZED_VIEW));
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_drop_deny");
    }

    @Test
    public void testRefreshDenyPermission()
    {
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_refresh_deny AS SELECT * FROM base_table1");
        assertAccessDenied(
                "REFRESH MATERIALIZED VIEW materialized_view_refresh_deny",
                "Cannot refresh materialized view .*.materialized_view_refresh_deny.*",
                privilege("materialized_view_refresh_deny", REFRESH_MATERIALIZED_VIEW));

        assertUpdate("DROP MATERIALIZED VIEW materialized_view_refresh_deny");
    }

    @Test
    public void testRefreshAllowedWithRestrictedStorageTable()
    {
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_refresh AS SELECT * FROM base_table1");
        SchemaTableName storageTable = getStorageTable(
            new QualifiedObjectName("iceberg", "tpch", "materialized_view_refresh"));

        assertAccessAllowed(
                "REFRESH MATERIALIZED VIEW materialized_view_refresh",
                privilege(storageTable.getTableName(), INSERT_TABLE),
                privilege(storageTable.getTableName(), DELETE_TABLE),
                privilege(storageTable.getTableName(), UPDATE_TABLE),
                privilege(storageTable.getTableName(), SELECT_COLUMN));

        assertUpdate("DROP MATERIALIZED VIEW materialized_view_refresh");
    }

    @Test(enabled = false) // TODO https://github.com/trinodb/trino/issues/5892
    public void testCreateRefreshSelect()
    {
        Session session = getSession();

        // A very simple non-partitioned materialized view
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_no_part as select * from base_table1");
        // A non-partitioned materialized view with grouping and aggregation
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_agg as select _date, count(_date) as num_dates from base_table1 group by 1");
        // A partitioned materialized view with grouping and aggregation
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_part WITH (partitioning = ARRAY['_date']) as select _date, count(_date) as num_dates from base_table1 group by 1");
        // A non-partitioned join materialized view
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_join as " +
                "select t2._bigint, _varchar, t1._date from base_table1 t1, base_table2 t2 where t1._date = t2._date");
        // A partitioned join materialized view
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_join_part WITH (partitioning = ARRAY['_date', '_bigint']) as " +
                "select t1._bigint, _varchar, t2._date, sum(1) as my_sum from base_table1 t1, base_table2 t2 where t1._date = t2._date group by 1, 2, 3 order by 1, 2");

        // The tests here follow the pattern:
        // 1. Select the data from unrefreshed materialized view, verify the number of rows in the result
        // 2. Ensure that plan uses base tables and not the storage table
        // 3. Refresh the materialized view
        // 4. Select the data from refreshed materialized view, verify the number of rows in the result
        // 5. Ensure that the plan uses the storage table
        // 6. In some cases validate the result data
        MaterializedResult baseResult = computeActual("SELECT * from materialized_view_no_part");
        assertEquals(baseResult.getRowCount(), 6);
        String plan = getExplainPlan("SELECT * from materialized_view_no_part", ExplainType.Type.IO);
        assertTrue(plan.contains("base_table1"));
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_no_part", 6);
        MaterializedResult viewResult = computeActual("SELECT * from materialized_view_no_part");
        assertEquals(viewResult.getRowCount(), 6);
        plan = getExplainPlan("SELECT * from materialized_view_no_part", ExplainType.Type.IO);
        assertFalse(plan.contains("base_table1"));

        baseResult = computeActual("SELECT * from materialized_view_agg");
        assertEquals(baseResult.getRowCount(), 3);
        plan = getExplainPlan("SELECT * from materialized_view_agg", ExplainType.Type.IO);
        assertTrue(plan.contains("base_table1"));
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_agg", 3);
        viewResult = computeActual("SELECT * from materialized_view_agg");
        assertEquals(viewResult.getRowCount(), 3);
        plan = getExplainPlan("SELECT * from materialized_view_agg", ExplainType.Type.IO);
        assertFalse(plan.contains("base_table1"));
        assertQuery(session, "SELECT * from materialized_view_agg", "VALUES (DATE '2019-09-10', 2)," +
                "(DATE '2019-09-08', 1), (DATE '2019-09-09', 3)");

        baseResult = computeActual("SELECT * from materialized_view_part");
        assertEquals(baseResult.getRowCount(), 3);
        plan = getExplainPlan("SELECT * from materialized_view_part", ExplainType.Type.IO);
        assertTrue(plan.contains("base_table1"));
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_part", 3);
        viewResult = computeActual("SELECT * from materialized_view_part");
        assertEquals(viewResult.getRowCount(), 3);
        plan = getExplainPlan("SELECT * from materialized_view_part", ExplainType.Type.IO);
        assertFalse(plan.contains("base_table1"));

        baseResult = computeActual("SELECT * from materialized_view_join");
        assertEquals(baseResult.getRowCount(), 5);
        plan = getExplainPlan("SELECT * from materialized_view_join", ExplainType.Type.IO);
        assertTrue(plan.contains("base_table1") && plan.contains("base_table2"));
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_join", 5);
        viewResult = computeActual("SELECT * from materialized_view_join");
        assertEquals(viewResult.getRowCount(), 5);
        plan = getExplainPlan("SELECT * from materialized_view_join", ExplainType.Type.IO);
        assertFalse(plan.contains("base_table1") || plan.contains("base_table2"));

        baseResult = computeActual("SELECT * from materialized_view_join_part");
        assertEquals(baseResult.getRowCount(), 4);
        plan = getExplainPlan("SELECT * from materialized_view_join_part", ExplainType.Type.IO);
        assertTrue(plan.contains("base_table1") && plan.contains("base_table2"));
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_join_part", 4);
        viewResult = computeActual("SELECT * from materialized_view_join_part");
        assertEquals(viewResult.getRowCount(), 4);
        plan = getExplainPlan("SELECT * from materialized_view_join_part", ExplainType.Type.IO);
        assertFalse(plan.contains("base_table1") || plan.contains("base_table2"));
        assertQuery(session, "SELECT * from materialized_view_join_part", "VALUES (2, 'a', DATE '2019-09-09', 1), " +
                "(0, 'a', DATE '2019-09-08', 2), (3, 'a', DATE '2019-09-09', 1), (1, 'a', DATE '2019-09-09', 1)");

        assertUpdate("DROP MATERIALIZED VIEW materialized_view_no_part");
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_agg");
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_part");
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_join");
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_join_part");
    }

    @Test(enabled = false) // TODO https://github.com/trinodb/trino/issues/5892
    public void testDetectStaleness()
    {
        // Base tables and materialized views for staleness check
        assertUpdate("CREATE TABLE base_table3(_bigint BIGINT, _date DATE) WITH (partitioning = ARRAY['_date'])");
        assertUpdate("INSERT INTO base_table3 VALUES (0, DATE '2019-09-08'), (1, DATE '2019-09-09'), (2, DATE '2019-09-09')", 3);

        assertUpdate("CREATE TABLE base_table4 (_varchar VARCHAR, _bigint BIGINT, _date DATE) WITH (partitioning = ARRAY['_bigint', '_date'])");
        assertUpdate("INSERT INTO base_table4 VALUES ('a', 0, DATE '2019-09-08'), ('a', 1, DATE '2019-09-08'), ('a', 0, DATE '2019-09-09')", 3);

        // A partitioned materialized view with grouping and aggregation
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_part_stale WITH (partitioning = ARRAY['_date']) as select _date, count(_date) as num_dates from base_table3 group by 1");
        // A non-partitioned join materialized view
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_join_stale as " +
                "select t2._bigint, _varchar, t1._date from base_table3 t1, base_table4 t2 where t1._date = t2._date");
        // A partitioned join materialized view
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_join_part_stale WITH (partitioning = ARRAY['_date', '_bigint']) as " +
                "select t1._bigint, _varchar, t2._date, sum(1) as my_sum from base_table3 t1, base_table4 t2 where t1._date = t2._date group by 1, 2, 3 order by 1, 2");

        // Ensure that when data is inserted into base table, materialized view is rendered stale. Note that, currently updates and deletes to/from iceberg tables is not supported.
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_part_stale", 2);
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_join_stale", 4);
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_join_part_stale", 3);

        assertUpdate("INSERT INTO base_table3 VALUES (3, DATE '2019-09-09'), (4, DATE '2019-09-10'), (5, DATE '2019-09-10')", 3);
        String plan = getExplainPlan("SELECT * from materialized_view_part_stale", ExplainType.Type.IO);
        assertTrue(plan.contains("base_table3"));

        plan = getExplainPlan("SELECT * from materialized_view_join_stale", ExplainType.Type.IO);
        assertTrue(plan.contains("base_table3") || plan.contains("base_table4"));

        plan = getExplainPlan("SELECT * from materialized_view_join_part_stale", ExplainType.Type.IO);
        assertTrue(plan.contains("base_table3") || plan.contains("base_table4"));

        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_part_stale", 3);
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_join_stale", 5);
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_join_part_stale", 4);

        plan = getExplainPlan("SELECT * from materialized_view_part_stale", ExplainType.Type.IO);
        assertFalse(plan.contains("base_table3"));

        plan = getExplainPlan("SELECT * from materialized_view_join_stale", ExplainType.Type.IO);
        assertFalse(plan.contains("base_table3") || plan.contains("base_table4"));

        plan = getExplainPlan("SELECT * from materialized_view_join_part_stale", ExplainType.Type.IO);
        assertFalse(plan.contains("base_table3") || plan.contains("base_table4"));

        assertUpdate("DROP TABLE IF EXISTS base_table3");
        assertUpdate("DROP TABLE IF EXISTS base_table4");
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_part_stale");
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_join_stale");
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_join_part_stale");
    }

    @Test(enabled = false) // TODO https://github.com/trinodb/trino/issues/5892
    public void testSqlFeatures()
    {
        Session session = getSession();

        // Materialized views to test SQL features
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_window WITH (partitioning = ARRAY['_date']) as select _date, " +
                "sum(_bigint) OVER (partition by _date order by _date) as sum_ints from base_table1");
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_window", 6);
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_union WITH (partitioning = ARRAY['_date']) as " +
                "select _date, count(_date) as num_dates from base_table1 group by 1 union " +
                "select _date, count(_date) as num_dates from base_table2 group by 1");
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_union", 5);
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_subquery WITH (partitioning = ARRAY['_date']) as " +
                "select _date, count(_date) as num_dates from base_table1 where _date = (select max(_date) from base_table2) group by 1");
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_subquery", 1);

        // This set of tests intend to test various SQL features in the context of materialized views. It also tests commands pertaining to materialized views.
        String plan = getExplainPlan("SELECT * from materialized_view_window", ExplainType.Type.IO);
        assertFalse(plan.contains("base_table1"));
        plan = getExplainPlan("SELECT * from materialized_view_union", ExplainType.Type.IO);
        assertFalse(plan.contains("base_table1"));
        plan = getExplainPlan("SELECT * from materialized_view_subquery", ExplainType.Type.IO);
        assertFalse(plan.contains("base_table1"));

        assertQueryFails("show create view  materialized_view_window",
                "line 1:1: Relation 'iceberg.tpch.materialized_view_window' is a materialized view, not a view");

        assertQuery(session, "show create materialized view  materialized_view_window",
                "VALUES ('CREATE MATERIALIZED VIEW iceberg.tpch.materialized_view_window AS\n" +
                        "SELECT\n" +
                        "  _date\n" +
                        ", sum(_bigint) OVER (PARTITION BY _date ORDER BY _date ASC) sum_ints\n" +
                        "FROM\n" +
                        "  base_table1')");

        assertQueryFails("INSERT INTO materialized_view_window VALUES (0, '2019-09-08'), (1, DATE '2019-09-09'), (2, DATE '2019-09-09')",
                "Inserting into materialized views is not supported");

        MaterializedResult result = computeActual("explain (type logical) refresh materialized view materialized_view_window");
        assertEquals(result.getRowCount(), 1);
        result = computeActual("explain (type distributed) refresh materialized view materialized_view_window");
        assertEquals(result.getRowCount(), 1);
        result = computeActual("explain (type validate) refresh materialized view materialized_view_window");
        assertEquals(result.getRowCount(), 1);
        result = computeActual("explain (type io) refresh materialized view materialized_view_window");
        assertEquals(result.getRowCount(), 1);
        result = computeActual("explain analyze refresh materialized view materialized_view_window");
        assertEquals(result.getRowCount(), 1);

        assertUpdate("DROP MATERIALIZED VIEW materialized_view_window");
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_union");
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_subquery");
    }

    @Test(enabled = false) // TODO https://github.com/trinodb/trino/issues/5892
    public void testReplace()
    {
        // Materialized view to test 'replace' feature
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_replace WITH (partitioning = ARRAY['_date']) as select _date, count(_date) as num_dates from base_table1 group by 1");
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_replace", 3);

        assertUpdate("CREATE OR REPLACE MATERIALIZED VIEW materialized_view_replace as select sum(1) as num_rows from base_table2");
        String plan = getExplainPlan("SELECT * from materialized_view_replace", ExplainType.Type.IO);
        assertTrue(plan.contains("base_table2"));
        assertUpdate("REFRESH MATERIALIZED VIEW materialized_view_replace", 1);
        MaterializedResult viewResult = computeActual("SELECT * from materialized_view_replace");
        assertEquals(viewResult.getRowCount(), 1);

        assertUpdate("DROP MATERIALIZED VIEW materialized_view_replace");
    }

    @Test(enabled = false) // TODO https://github.com/trinodb/trino/issues/5892
    public void testNestedMaterializedViews()
    {
        // Base table and materialized views for nested materialized view testing
        assertUpdate("CREATE TABLE base_table5(_bigint BIGINT, _date DATE) WITH (partitioning = ARRAY['_date'])");
        assertUpdate("INSERT INTO base_table5 VALUES (0, DATE '2019-09-08'), (1, DATE '2019-09-09'), (2, DATE '2019-09-09')", 3);
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_level1 WITH (partitioning = ARRAY['_date']) as select _date, count(_date) as num_dates from base_table5 group by 1");
        assertUpdate("CREATE MATERIALIZED VIEW materialized_view_level2 WITH (partitioning = ARRAY['_date']) as select _date, num_dates from materialized_view_level1");

        // Unrefreshed 2nd level materialized view .. resolves to base table
        String plan = getExplainPlan("select * from materialized_view_level2", ExplainType.Type.IO);
        assertTrue(plan.contains("base_table5"));
        assertUpdate("refresh materialized view materialized_view_level2", 2);
        plan = getExplainPlan("select * from materialized_view_level2", ExplainType.Type.IO);

        // Refreshed 2nd level materialized view .. resolves to storage table
        assertFalse(plan.contains("base_table5"));

        // Re-refreshing 2nd level materialized view is a no-op
        assertUpdate("refresh materialized view materialized_view_level2", 0);

        // Insert into the base table
        assertUpdate("INSERT INTO base_table5 VALUES (3, DATE '2019-09-09'), (4, DATE '2019-09-10'), (5, DATE '2019-09-10')", 3);
        assertUpdate("refresh materialized view materialized_view_level2", 3);

        // Refreshing the 2nd level (outer-most) materialized view does not refresh the 1st level (inner) materialized view.
        plan = getExplainPlan("select * from materialized_view_level1", ExplainType.Type.IO);
        assertTrue(plan.contains("base_table5"));

        assertUpdate("DROP TABLE IF EXISTS base_table5");
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_level1");
        assertUpdate("DROP MATERIALIZED VIEW materialized_view_level2");
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        assertUpdate("DROP TABLE IF EXISTS base_table1");
        assertUpdate("DROP TABLE IF EXISTS base_table2");
        // Drop storage tables
        MaterializedResult baseResult = computeActual("show tables in tpch");
        for (MaterializedRow row : baseResult.getMaterializedRows()) {
            assertUpdate("DROP TABLE IF EXISTS " + row.getField(0).toString());
        }
    }

    private SchemaTableName getStorageTable(QualifiedObjectName materializedViewName)
    {
        TransactionManager transactionManager = getQueryRunner().getTransactionManager();
        TransactionId transactionId = transactionManager.beginTransaction(false);
        Session session = getSession().beginTransactionId(transactionId, transactionManager, getQueryRunner().getAccessControl());
        Optional<ConnectorMaterializedViewDefinition> materializedView = getQueryRunner().getMetadata()
                .getMaterializedView(session, materializedViewName);
        assertThat(materializedView).isPresent();
        return materializedView.get().getStorageTable().get().getSchemaTableName();
    }
}
