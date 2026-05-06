package io.sqlc;

import android.database.Cursor;
import android.util.Log;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

class SQLiteAndroidDatabase implements SQLiteConnectorDatabase {

    private static final String TAG = "SQLiteAndroidDatabase";
    private SQLiteDatabase db;
    private String password;

    SQLiteAndroidDatabase(String password) {
        this.password = password;
    }

    static void initialize() {
        System.loadLibrary("sqlcipher");
    }

    public boolean isOpen() {
        return db != null && db.isOpen();
    }

    @Override
    public void open(String dbPath) throws Exception {
        db = SQLiteDatabase.openOrCreateDatabase(dbPath, password, null, null, null);
        Log.d(TAG, "SQLiteDatabase opened at: " + dbPath);
    }

    @Override
    public void closeDatabaseConnection() {
        if (db != null && db.isOpen()) db.close();
        db = null;
    }

    @Override
    public void beginTransaction() throws Exception {
        db.beginTransaction();
    }

    @Override
    public void endTransaction(boolean commit) throws Exception {
        if (commit) db.setTransactionSuccessful();
        db.endTransaction();
    }

    @Override
    public void execSQL(String query) throws Exception {
        db.execSQL(query);
    }

    public void execSQLWithBindArgs(String query, Object[] bindArgs) throws Exception {
        if (bindArgs == null || bindArgs.length == 0) {
            db.execSQL(query);
        } else {
            db.execSQL(query, bindArgs);
        }
    }

    @Override
    public JSONArray executeSQLWithResults(String query, String[] bindArgs) throws Exception {
        JSONArray results = new JSONArray();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(query, bindArgs);
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    String col = cursor.getColumnName(i);
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL:    row.put(col, JSONObject.NULL); break;
                        case Cursor.FIELD_TYPE_INTEGER: row.put(col, cursor.getLong(i)); break;
                        case Cursor.FIELD_TYPE_FLOAT:   row.put(col, cursor.getDouble(i)); break;
                        default:                        row.put(col, cursor.getString(i)); break;
                    }
                }
                results.put(row);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return results;
    }
}
