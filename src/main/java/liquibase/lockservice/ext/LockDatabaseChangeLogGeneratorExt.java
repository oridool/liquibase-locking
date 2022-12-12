package liquibase.lockservice.ext;

import liquibase.Scope;
import liquibase.database.Database;
import liquibase.datatype.DataTypeFactory;
import liquibase.exception.DatabaseException;
import liquibase.executor.ExecutorService;
import liquibase.logging.Logger;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.sqlgenerator.core.LockDatabaseChangeLogGenerator;
import liquibase.statement.core.LockDatabaseChangeLogStatement;
import liquibase.statement.core.RawSqlStatement;
import liquibase.statement.core.UpdateStatement;


import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public class LockDatabaseChangeLogGeneratorExt extends LockDatabaseChangeLogGenerator {
    private final static Logger LOG = Scope.getCurrentScope().getLog(LockDatabaseChangeLogGeneratorExt.class);

    public static final String LOCKED_BY_SEPARATOR = "@@";

    @Override
    public int getPriority() {
        return super.getPriority()+1000;
    }

    @Override
    public boolean supports(LockDatabaseChangeLogStatement statement, Database database) {
        return true;
    }

    @Override
    public Sql[] generateSql(LockDatabaseChangeLogStatement statement, Database database, SqlGeneratorChain sqlGeneratorChain) {
        String lockedByValue = generateLockedBy(database);

        String liquibaseSchema = database.getLiquibaseSchemaName();
        String liquibaseCatalog = database.getLiquibaseCatalogName();

        UpdateStatement updateStatement = new UpdateStatement(liquibaseCatalog, liquibaseSchema, database.getDatabaseChangeLogLockTableName());
        updateStatement.addNewColumnValue("LOCKED", true);
        updateStatement.addNewColumnValue("LOCKGRANTED", new Timestamp(new java.util.Date().getTime()));
        updateStatement.addNewColumnValue("LOCKEDBY", lockedByValue);
        updateStatement.setWhereClause(database.escapeColumnName(liquibaseCatalog, liquibaseSchema, database.getDatabaseChangeLogTableName(), "ID") + " = 1 AND " + database.escapeColumnName(liquibaseCatalog, liquibaseSchema, database.getDatabaseChangeLogTableName(), "LOCKED") + " = " + DataTypeFactory.getInstance().fromDescription("boolean", database).objectToSql(false, database));

        return SqlGeneratorFactory.getInstance().generateSql(updateStatement, database);
    }

    private String generateLockedBy(Database database) {
        String dbPid = "0000";
        String dbPidStartTime = "";

        try {
            List<Map<String, ?>> rs = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database).queryForList(
                    new RawSqlStatement("select * from pg_stat_activity where pid=pg_backend_pid()"));
            if (rs.size() > 0) { // expected exactly one row
                Map<String, ?> row = rs.get(0);
                dbPid = Integer.toString ((Integer) row.get("PID"));
                dbPidStartTime = ((Timestamp)(row.get("BACKEND_START"))).toString();
            }
        } catch (DatabaseException e) {
            LOG.severe("Failed to read current Liquibase locking info", e);
        }

        String lockedByValue = dbPid + LOCKED_BY_SEPARATOR + dbPidStartTime;
        LOG.info("Setting LOCKEDBY value to " + lockedByValue);
        return lockedByValue;
    }
}
