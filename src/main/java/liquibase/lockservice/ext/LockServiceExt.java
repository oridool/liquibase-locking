package liquibase.lockservice.ext;

import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.exception.LockException;
import liquibase.executor.ExecutorService;
import liquibase.lockservice.StandardLockService;
import liquibase.statement.core.RawSqlStatement;
import liquibase.statement.core.SelectFromDatabaseChangeLogLockStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import static liquibase.lockservice.ext.LockDatabaseChangeLogGeneratorExt.LOCKED_BY_SEPARATOR;

public class LockServiceExt extends StandardLockService {

    private final static Logger LOG = LoggerFactory.getLogger(LockServiceExt.class);

    @Override
    public int getPriority() {
        return 1000;
    }

    @Override
    public boolean supports(Database database) {
        return true;
    }

    @Override
    public void waitForLock() throws LockException {
        try {
            Boolean locked = ExecutorService.getInstance().getExecutor(database).queryForObject(
                    new SelectFromDatabaseChangeLogLockStatement("LOCKED"), Boolean.class);
            if (locked != null && locked == Boolean.TRUE) {
                try {
                    String lockedBy = ExecutorService.getInstance().getExecutor(database).queryForObject(new SelectFromDatabaseChangeLogLockStatement("LOCKEDBY"), String.class);
                    if (lockedBy != null && !lockedBy.isBlank()) {
                        LOG.warn("Database is locked by: {} ", lockedBy);
                        StringTokenizer tok = new StringTokenizer(lockedBy, LOCKED_BY_SEPARATOR);
                        if (tok.countTokens() >= 2) {
                            String dbPid = tok.nextToken();
                            String dbPidStart = tok.nextToken();
                            boolean lockHolderActive = isPidActive(dbPid, dbPidStart);
                            if (!lockHolderActive) {
                                LOG.warn("Database Lock was created by an inactive client (pid={}, startTime={}). Releasing lock!", dbPid, dbPidStart);
                                releaseLock();
                            } else {
                                LOG.warn("Database Lock was created by a still active client (pid={}, startTime={}). NOT Releasing lock!", dbPid, dbPidStart);
                            }
                        } else {
                            LOG.error("Databased is locked, cannot parse LOCKEDBY value: '{}' in table {}", lockedBy, database.getDatabaseChangeLogLockTableName());
                        }
                    } else {
                        LOG.error("Databased is locked but LOCKEDBY information is missing");
                    }
                } catch (DatabaseException e) {
                    LOG.error("Can't read the LOCKEDBY field from {}", database.getDatabaseChangeLogLockTableName(), e);
                }
            } else {
                LOG.warn("Databased is not locked");
            }
        } catch (DatabaseException e) {
            LOG.error("Can't read the LOCKED field from " + database.getDatabaseChangeLogLockTableName(), e);
        }

        super.waitForLock();

    }

    private boolean isPidActive(String dbPid, String dbPidStart) {
        try {
            String sql = String.format("select * from pg_stat_activity where PID=%s and BACKEND_START='%s'", dbPid, dbPidStart);
            List<Map<String, ?>> rs = ExecutorService.getInstance().getExecutor(database).queryForList(new RawSqlStatement(sql));
            if (rs.size() > 0) {
                return true;
            }
        } catch (DatabaseException e) {
            LOG.error(e.getMessage(), e);
        }

        return false;
    }
}



