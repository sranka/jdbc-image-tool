package pz.tool.jdbcimage.main;

import org.apache.commons.dbcp2.BasicDataSource;
import pz.tool.jdbcimage.LoggedUtils;
import pz.tool.jdbcimage.db.SqlExecuteCommand;
import pz.tool.jdbcimage.db.TableGroupedCommands;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DB facade for MSSQL database.
 */
public class Mssql extends DBFacade {
    private MainToolBase mainToolBase;

    public Mssql(MainToolBase mainToolBase) {
        this.mainToolBase = mainToolBase;
    }

    @Override
    public void setupDataSource(BasicDataSource bds) {
        bds.setDefaultTransactionIsolation(Connection.TRANSACTION_NONE);
    }

    @Override
    public ResultSet getUserTables(Connection con) throws SQLException {
        return con.getMetaData().getTables(con.getCatalog(), "dbo", "%", new String[]{"TABLE"});
    }

    @Override
    public String escapeColumnName(String s) {
        return "[" + s + "]";
    }

    @Override
    public String escapeTableName(String s) {
        return "[" + s + "]";
    }

    @Override
    public Duration modifyConstraints(boolean enable) throws SQLException {
        long time = System.currentTimeMillis();
        List<String> queries = new ArrayList<>();
        // table name, foreign key name
        queries.add("SELECT t.Name, dc.Name "
                + "FROM sys.tables t INNER JOIN sys.foreign_keys dc ON t.object_id = dc.parent_object_id "
                + "ORDER BY t.Name");
        TableGroupedCommands commands = new TableGroupedCommands();
        for (String query : queries) {
            mainToolBase.executeQuery(
                    query,
                    row -> {
                        try {
                            String tableName = row.getString(1);
                            String constraint = row.getString(2);
                            if (mainToolBase.containsTable(tableName)) {
                                if (enable) {
                                    String desc = "Enable constraint " + constraint + " on table " + tableName;
                                    String sql = "ALTER TABLE [" + tableName + "] CHECK CONSTRAINT [" + constraint + "]";
                                    commands.add(tableName, desc, sql);
                                } else {
                                    String desc = "Disable constraint " + constraint + " on table " + tableName;
                                    String sql = "ALTER TABLE [" + tableName + "] NOCHECK CONSTRAINT [" + constraint + "]";
                                    commands.add(tableName, desc, sql);
                                }
                                return null;
                            } else {
                                return null;
                            }
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    }
            );
            // there are DEADLOCK problems when running in parallel
            mainToolBase.runSerial(commands.tableGroups
                    .stream()
                    .map(x -> SqlExecuteCommand.toSqlExecuteTask(
                            mainToolBase.getWriteConnectionSupplier(),
                            mainToolBase.out,
                            x.toArray(new SqlExecuteCommand[x.size()]))
                    )
                    .collect(Collectors.toList()));
        }
        return Duration.ofMillis(System.currentTimeMillis() - time);

    }

    @Override
    public Duration modifyIndexes(boolean enable) throws SQLException {
        long time = System.currentTimeMillis();
        mainToolBase.out.println("Index " + (enable ? "enable" : "disable") + " not supported on MSSQL!");
        return Duration.ofMillis(System.currentTimeMillis() - time);
    }

    @Override
    public String getTruncateTableSql(String tableName) {
        // unable to use TRUNCATE TABLE on MSSQL server even with CONSTRAINTS DISABLED!
        return "DELETE FROM " + escapeTableName(tableName);
    }

    @Override
    public void afterImportTable(Connection con, String table, boolean hasIdentityColumn) throws SQLException {
        if (hasIdentityColumn) {
            try (Statement stmt = con.createStatement()) {
                stmt.execute("SET IDENTITY_INSERT [" + table + "] OFF");
            }
        }
    }

    @Override
    public void beforeImportTable(Connection con, String table, boolean hasIdentityColumn) throws SQLException {
        if (hasIdentityColumn) {
            try (Statement stmt = con.createStatement()) {
                stmt.execute("SET IDENTITY_INSERT [" + table + "] ON");
            }
        }
    }

    /**
     * Returns tables that have no identity columns.
     *
     * @return set of tables that contain identity columns
     */
    public Set<String> getTablesWithIdentityColumns() {
        Set<String> retVal = new HashSet<>();
        try (Connection con = mainToolBase.getReadOnlyConnection()) {
            try (Statement stmt = con.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("select name from sys.objects where type = 'U' and OBJECTPROPERTY(object_id, 'TableHasIdentity')=1")) {
                    while (rs.next()) {
                        retVal.add(rs.getString(1));
                    }
                }
            } finally {
                try {
                    con.rollback(); // nothing to commit
                } catch (SQLException e) {
                    LoggedUtils.ignore("Unable to rollback!", e);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return retVal;
    }
}
