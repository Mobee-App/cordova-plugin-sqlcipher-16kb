package io.sqlc;

import org.json.JSONArray;

interface SQLiteConnectorDatabase {
    void open(String dbPath) throws Exception;
    void closeDatabaseConnection();
    void beginTransaction() throws Exception;
    void endTransaction(boolean commit) throws Exception;
    void execSQL(String query) throws Exception;
    JSONArray executeSQLWithResults(String query, String[] bindArgs) throws Exception;
}
