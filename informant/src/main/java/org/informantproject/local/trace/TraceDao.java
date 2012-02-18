/**
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.informantproject.local.trace;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.informantproject.api.CharSequenceReader;
import org.informantproject.util.Clock;
import org.informantproject.util.JdbcUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Data access object for storing and reading trace data from the embedded H2 database.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceDao {

    private static final Logger logger = LoggerFactory.getLogger(TraceDao.class);

    private final Connection connection;
    private final Clock clock;

    private final PreparedStatement insertPreparedStatement;
    private final PreparedStatement selectByIdPreparedStatement;
    private final PreparedStatement selectPreparedStatement;
    private final PreparedStatement selectPreparedStatement2;
    private final PreparedStatement selectSummaryPreparedStatement;
    private final PreparedStatement deletePreparedStatement;
    private final PreparedStatement truncatePreparedStatement;
    private final PreparedStatement countPreparedStatement;

    private final boolean valid;

    @Inject
    TraceDao(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
        PreparedStatement insertPS = null;
        PreparedStatement selectByIdPS = null;
        PreparedStatement selectPS = null;
        PreparedStatement selectPS2 = null;
        PreparedStatement selectSummaryPS = null;
        PreparedStatement deletePS = null;
        PreparedStatement truncatePS = null;
        PreparedStatement countPS = null;
        boolean errorOnInit = false;
        try {
            if (!JdbcUtil.tableExists("trace", connection)) {
                createTable(connection);
            } else if (tableNeedsUpgrade(connection)) {
                // the upgrade at this point is just drop/re-create
                dropTable(connection);
                createTable(connection);
            }
            insertPS = connection.prepareStatement("insert into trace (id, capturedAt, startAt,"
                    + " stuck, duration, completed, threadNames, username, spans,"
                    + " mergedStackTree) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            selectByIdPS = connection.prepareStatement("select id, capturedAt, startAt, stuck,"
                    + " duration, completed, threadNames, username, spans, mergedStackTree from"
                    + " trace where id = ?");
            selectPS = connection.prepareStatement("select id, capturedAt, startAt, stuck,"
                    + " duration, completed, threadNames, username, spans, mergedStackTree from"
                    + " trace where capturedAt >= ? and capturedAt <= ?");
            selectPS2 = connection.prepareStatement("select id, capturedAt, startAt, stuck,"
                    + " duration, completed, threadNames, username, spans, mergedStackTree from"
                    + " trace where capturedAt >= ? and capturedAt <= ? and duration >= ? and"
                    + " duration <= ?");
            selectSummaryPS = connection.prepareStatement("select id, capturedAt, duration,"
                    + " completed from trace where capturedAt >= ? and capturedAt <= ?");
            deletePS = connection.prepareStatement("delete from trace where capturedAt >= ? and"
                    + " capturedAt <= ?");
            truncatePS = connection.prepareStatement("truncate table trace");
            countPS = connection.prepareStatement("select count(*) from trace");
        } catch (SQLException e) {
            errorOnInit = true;
            logger.error(e.getMessage(), e);
        }
        insertPreparedStatement = insertPS;
        selectByIdPreparedStatement = selectByIdPS;
        selectPreparedStatement = selectPS;
        selectPreparedStatement2 = selectPS2;
        selectSummaryPreparedStatement = selectSummaryPS;
        deletePreparedStatement = deletePS;
        truncatePreparedStatement = truncatePS;
        countPreparedStatement = countPS;
        this.valid = !errorOnInit;
    }

    void storeTrace(StoredTrace storedTrace) {
        logger.debug("storeTrace(): storedTrace={}", storedTrace);
        if (!valid) {
            return;
        }
        synchronized (connection) {
            try {
                int index = 1;
                insertPreparedStatement.setString(index++, storedTrace.getId());
                insertPreparedStatement.setLong(index++, clock.currentTimeMillis());
                insertPreparedStatement.setLong(index++, storedTrace.getStartAt());
                insertPreparedStatement.setBoolean(index++, storedTrace.isStuck());
                insertPreparedStatement.setLong(index++, storedTrace.getDuration());
                insertPreparedStatement.setBoolean(index++, storedTrace.isCompleted());
                insertPreparedStatement.setString(index++, storedTrace.getThreadNames());
                insertPreparedStatement.setString(index++, storedTrace.getUsername());
                insertPreparedStatement.setCharacterStream(index++, new CharSequenceReader(
                        storedTrace.getSpans()), storedTrace.getSpans().length());
                if (storedTrace.getMergedStackTree() == null) {
                    insertPreparedStatement.setClob(index++, (Clob) null);
                } else {
                    insertPreparedStatement.setCharacterStream(index++, new CharSequenceReader(
                            storedTrace.getMergedStackTree()), storedTrace.getMergedStackTree()
                            .length());
                }
                // TODO write metric data
                insertPreparedStatement.executeUpdate();
                // don't close prepared statement
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    public List<StoredTraceSummary> readStoredTraceSummaries(long capturedFrom, long capturedTo) {
        logger.debug("readStoredTraceSummaries(): capturedFrom={}, capturedTo={}", capturedFrom,
                capturedTo);
        if (!valid) {
            return Collections.emptyList();
        }
        synchronized (connection) {
            try {
                selectSummaryPreparedStatement.setLong(1, capturedFrom);
                selectSummaryPreparedStatement.setLong(2, capturedTo);
                ResultSet resultSet = selectSummaryPreparedStatement.executeQuery();
                try {
                    List<StoredTraceSummary> traceSummaries = new ArrayList<StoredTraceSummary>();
                    while (resultSet.next()) {
                        traceSummaries.add(buildStoredTraceSummaryFromResultSet(resultSet));
                    }
                    return traceSummaries;
                } finally {
                    resultSet.close();
                }
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
                return Collections.emptyList();
            }
        }
    }

    // multiple stored traces for the same id can exist in the case of stuck/unstuck trace records
    public List<StoredTrace> readStoredTraces(String id) {
        logger.debug("readStoredTraces(): id={}", id);
        if (!valid) {
            return Collections.emptyList();
        }
        synchronized (connection) {
            try {
                selectByIdPreparedStatement.setString(1, id);
                return readStoredTrace(selectByIdPreparedStatement);
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
                return Collections.emptyList();
            }
        }
    }

    public List<StoredTrace> readStoredTraces(long capturedFrom, long capturedTo) {
        logger.debug("readStoredTraces(): capturedFrom={}, capturedTo={}", capturedFrom,
                capturedTo);
        if (!valid) {
            return Collections.emptyList();
        }
        synchronized (connection) {
            try {
                selectPreparedStatement.setLong(1, capturedFrom);
                selectPreparedStatement.setLong(2, capturedTo);
                return readStoredTrace(selectPreparedStatement);
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
                return Collections.emptyList();
            }
        }
    }

    public List<StoredTrace> readStoredTraces(long capturedFrom, long capturedTo, long lowDuration,
            long highDuration) {

        logger.debug("readStoredTraces(): capturedFrom={}, capturedTo={}, lowDuration={},"
                + " highDuration={}", new long[] { capturedFrom, capturedTo, lowDuration,
                highDuration });
        if (!valid) {
            return Collections.emptyList();
        }
        synchronized (connection) {
            if (lowDuration <= 0 && highDuration == Long.MAX_VALUE) {
                return readStoredTraces(capturedFrom, capturedTo);
            }
            try {
                selectPreparedStatement2.setLong(1, capturedFrom);
                selectPreparedStatement2.setLong(2, capturedTo);
                selectPreparedStatement2.setLong(3, lowDuration);
                selectPreparedStatement2.setLong(4, highDuration);
                return readStoredTrace(selectPreparedStatement2);
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
                return Collections.emptyList();
            }
        }
    }

    public int deleteStoredTraces(long capturedFrom, long capturedTo) {
        logger.debug("deleteStoredTraces(): capturedFrom={}, capturedTo={}", capturedFrom,
                capturedTo);
        if (!valid) {
            return 0;
        }
        synchronized (connection) {
            try {
                deletePreparedStatement.setLong(1, capturedFrom);
                deletePreparedStatement.setLong(2, capturedTo);
                return deletePreparedStatement.executeUpdate();
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
                return 0;
            }
        }
    }

    public void deleteAllStoredTraces() {
        logger.debug("deleteAllStoredTraces()");
        if (!valid) {
            return;
        }
        synchronized (connection) {
            try {
                truncatePreparedStatement.execute();
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    long count() {
        if (!valid) {
            return 0;
        }
        synchronized (connection) {
            try {
                ResultSet resultSet = countPreparedStatement.executeQuery();
                try {
                    resultSet.next();
                    return resultSet.getLong(1);
                } finally {
                    resultSet.close();
                }
            } catch (SQLException e) {
                logger.error(e.getMessage(), e);
                return 0;
            }
        }
    }

    private static boolean tableNeedsUpgrade(Connection connection) throws SQLException {
        ResultSet resultSet = connection.getMetaData().getColumns(null, null, "TRACE", null);
        if (!resultSet.next() || !"ID".equals(resultSet.getString("COLUMN_NAME"))
                || Types.VARCHAR != resultSet.getInt("DATA_TYPE")) {
            return true;
        } else if (!resultSet.next() || !"CAPTUREDAT".equals(resultSet.getString("COLUMN_NAME"))
                || Types.BIGINT != resultSet.getInt("DATA_TYPE")) {
            return true;
        } else if (!resultSet.next() || !"STARTAT".equals(resultSet.getString("COLUMN_NAME"))
                || Types.BIGINT != resultSet.getInt("DATA_TYPE")) {
            return true;
        } else if (!resultSet.next() || !"STUCK".equals(resultSet.getString("COLUMN_NAME"))
                || Types.BOOLEAN != resultSet.getInt("DATA_TYPE")) {
            return true;
        } else if (!resultSet.next() || !"DURATION".equals(resultSet.getString("COLUMN_NAME"))
                || Types.BIGINT != resultSet.getInt("DATA_TYPE")) {
            return true;
        } else if (!resultSet.next() || !"COMPLETED".equals(resultSet.getString("COLUMN_NAME"))
                || Types.BOOLEAN != resultSet.getInt("DATA_TYPE")) {
            return true;
        } else if (!resultSet.next() || !"THREADNAMES".equals(resultSet.getString("COLUMN_NAME"))
                || Types.VARCHAR != resultSet.getInt("DATA_TYPE")) {
            return true;
        } else if (!resultSet.next() || !"USERNAME".equals(resultSet.getString("COLUMN_NAME"))
                || Types.VARCHAR != resultSet.getInt("DATA_TYPE")) {
            return true;
        } else if (!resultSet.next() || !"SPANS".equals(resultSet.getString("COLUMN_NAME"))
                || Types.CLOB != resultSet.getInt("DATA_TYPE")) {
            return true;
        } else if (!resultSet.next()
                || !"MERGEDSTACKTREE".equals(resultSet.getString("COLUMN_NAME"))
                || Types.CLOB != resultSet.getInt("DATA_TYPE")) {
            return true;
        }

        // at least for now, also check the index as part of the same check
        resultSet = connection.getMetaData().getIndexInfo(null, null, "TRACE", false, false);
        if (!resultSet.next() || !"TRACE_IDX".equals(resultSet.getString("INDEX_NAME"))
                || !"CAPTUREDAT".equals(resultSet.getString("COLUMN_NAME"))) {
            return true;
        } else if (!resultSet.next() || !"TRACE_IDX".equals(resultSet.getString("INDEX_NAME"))
                || !"DURATION".equals(resultSet.getString("COLUMN_NAME"))) {
            return true;
        }

        return false;
    }

    private static void dropTable(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.execute("drop table trace");
        } finally {
            statement.close();
        }
    }

    private static void createTable(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.execute("create table trace (id varchar, capturedAt bigint, startAt bigint,"
                    + " stuck boolean, duration bigint, completed boolean, threadnames varchar,"
                    + " username varchar, spans clob, mergedStackTree clob)");
            statement.execute("create index trace_idx on trace (capturedAt, duration)");
            if (tableNeedsUpgrade(connection)) {
                logger.error("the logic in tableNeedsUpgrade() needs fixing", new Throwable());
            }
        } finally {
            statement.close();
        }
    }

    private static List<StoredTrace> readStoredTrace(PreparedStatement preparedStatement)
            throws SQLException {

        ResultSet resultSet = preparedStatement.executeQuery();
        try {
            List<StoredTrace> traces = new ArrayList<StoredTrace>();
            while (resultSet.next()) {
                traces.add(buildStoredTraceFromResultSet(resultSet));
            }
            return traces;
        } finally {
            resultSet.close();
        }
    }

    private static StoredTrace buildStoredTraceFromResultSet(ResultSet resultSet)
            throws SQLException {

        StoredTrace storedTrace = new StoredTrace();
        int columnIndex = 1;
        storedTrace.setId(resultSet.getString(columnIndex++));
        columnIndex++; // TODO place holder for capturedAt
        storedTrace.setStartAt(resultSet.getLong(columnIndex++));
        storedTrace.setStuck(resultSet.getBoolean(columnIndex++));
        storedTrace.setDuration(resultSet.getLong(columnIndex++));
        storedTrace.setCompleted(resultSet.getBoolean(columnIndex++));
        storedTrace.setThreadNames(resultSet.getString(columnIndex++));
        storedTrace.setUsername(resultSet.getString(columnIndex++));
        storedTrace.setSpans(resultSet.getString(columnIndex++));
        storedTrace.setMergedStackTree(resultSet.getString(columnIndex++));
        return storedTrace;
    }

    private static StoredTraceSummary buildStoredTraceSummaryFromResultSet(ResultSet resultSet)
            throws SQLException {

        return new StoredTraceSummary(resultSet.getString(1), resultSet.getLong(2),
                resultSet.getLong(3), resultSet.getBoolean(4));
    }
}
