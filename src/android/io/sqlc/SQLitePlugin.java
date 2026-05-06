package io.sqlc;

import android.content.Context;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SQLitePlugin extends CordovaPlugin {

    private static final String TAG = "SQLitePlugin";
    private final Map<String, SQLiteAndroidDatabase> dbMap = new HashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @Override
    public void pluginInitialize() {
        SQLiteAndroidDatabase.initialize();
        Log.d(TAG, "SQLitePlugin initialized");
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext cb) throws JSONException {
        if      (action.equals("open"))                      { openDatabase(args, cb); }
        else if (action.equals("close"))                     { closeDatabase(args, cb); }
        else if (action.equals("delete"))                    { deleteDatabase(args, cb); }
        else if (action.equals("backgroundExecuteSqlBatch")
              || action.equals("executeSqlBatch"))           { executeSqlBatch(args, cb); }
        else { return false; }
        return true;
    }

    // ── open ──────────────────────────────────────────────────────────────────

    private void openDatabase(final JSONArray args, final CallbackContext cb) {
        pool.execute(new Runnable() { @Override public void run() {
            try {
                JSONObject opts = args.getJSONObject(0);
                String name     = opts.getString("name");
                String password = opts.optString("password", opts.optString("key", ""));

                synchronized (dbMap) {
                    SQLiteAndroidDatabase existing = dbMap.get(name);
                    // Return existing only if it is actually open
                    if (existing != null && existing.isOpen()) {
                        Log.d(TAG, "DB already open: " + name);
                        cb.success();
                        return;
                    }
                    // Remove stale entry if closed
                    if (existing != null) dbMap.remove(name);
                }

                String path = dbPath(name);
                Log.d(TAG, "Opening DB: " + name + " path=" + path);
                SQLiteAndroidDatabase db = new SQLiteAndroidDatabase(password);
                db.open(path);

                synchronized (dbMap) { dbMap.put(name, db); }
                Log.d(TAG, "DB opened successfully: " + name);
                cb.success();
            } catch (Exception e) {
                Log.e(TAG, "open error", e);
                cb.error(e.getMessage());
            }
        }});
    }

    // ── close ─────────────────────────────────────────────────────────────────

    private void closeDatabase(final JSONArray args, final CallbackContext cb) {
        pool.execute(new Runnable() { @Override public void run() {
            try {
                String name = args.getJSONObject(0).getString("path");
                SQLiteAndroidDatabase db;
                synchronized (dbMap) { db = dbMap.remove(name); }
                if (db != null) db.closeDatabaseConnection();
                cb.success();
            } catch (Exception e) { cb.error(e.getMessage()); }
        }});
    }

    // ── delete ────────────────────────────────────────────────────────────────

    private void deleteDatabase(final JSONArray args, final CallbackContext cb) {
        pool.execute(new Runnable() { @Override public void run() {
            try {
                String name = args.getJSONObject(0).getString("path");
                synchronized (dbMap) {
                    SQLiteAndroidDatabase db = dbMap.remove(name);
                    if (db != null) db.closeDatabaseConnection();
                }
                File f = new File(dbPath(name));
                if (f.exists()) f.delete();
                cb.success();
            } catch (Exception e) { cb.error(e.getMessage()); }
        }});
    }

    // ── executeSqlBatch ───────────────────────────────────────────────────────

    private void executeSqlBatch(final JSONArray args, final CallbackContext cb) {
        pool.execute(new Runnable() { @Override public void run() {
            try {
                JSONObject opts    = args.getJSONObject(0);
                String dbName      = opts.getString("dbname");
                JSONArray executes = opts.getJSONArray("executes");

                SQLiteAndroidDatabase db;
                synchronized (dbMap) { db = dbMap.get(dbName); }

                if (db == null || !db.isOpen()) {
                    Log.e(TAG, "DB not open: " + dbName);
                    cb.error("Database not open: " + dbName);
                    return;
                }

                JSONArray results = new JSONArray();

                for (int i = 0; i < executes.length(); i++) {
                    JSONObject item    = executes.getJSONObject(i);
                    String sql         = item.getString("sql");
                    JSONArray paramArr = item.optJSONArray("params");

                    Object[] bindArgs = null;
                    if (paramArr != null && paramArr.length() > 0) {
                        bindArgs = new Object[paramArr.length()];
                        for (int j = 0; j < paramArr.length(); j++) {
                            Object v = paramArr.get(j);
                            bindArgs[j] = (v == JSONObject.NULL) ? null : v;
                        }
                    }

                    // Replace ?1, ?2... → ? (named positional params used in app)
                    sql = replaceNamedParams(sql, bindArgs);

                    JSONObject res = new JSONObject();
                    try {
                        String upper = sql.trim().toUpperCase();
                        if (upper.startsWith("SELECT") || upper.startsWith("PRAGMA") || upper.startsWith("WITH")) {
                            JSONArray rows = db.executeSQLWithResults(sql, toStringArray(bindArgs));
                            res.put("rows", rows);
                            res.put("rowsAffected", 0);
                        } else {
                            db.execSQLWithBindArgs(sql, bindArgs);
                            res.put("rows", new JSONArray());
                            res.put("rowsAffected", 1);
                        }
                        Log.d(TAG, "OK: " + sql.substring(0, Math.min(80, sql.length())));
                    } catch (Exception e) {
                        Log.e(TAG, "SQL error: " + sql + " → " + e.getMessage());
                        res.put("error", e.getMessage());
                        res.put("rows", new JSONArray());
                        res.put("rowsAffected", 0);
                    }
                    results.put(res);
                }

                cb.success(results);

            } catch (Exception e) {
                Log.e(TAG, "executeSqlBatch error", e);
                cb.error(e.getMessage());
            }
        }});
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String replaceNamedParams(String sql, Object[] bindArgs) {
        if (bindArgs == null || bindArgs.length == 0) return sql;
        if (sql.contains("?1") || sql.contains("?2") || sql.contains("?3")) {
            for (int i = bindArgs.length; i >= 1; i--) {
                sql = sql.replace("?" + i, "?");
            }
        }
        return sql;
    }

    private String[] toStringArray(Object[] args) {
        if (args == null) return null;
        String[] r = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            r[i] = (args[i] == null) ? null : args[i].toString();
        }
        return r;
    }

    private String dbPath(String name) {
        Context ctx = cordova.getActivity().getApplicationContext();
        File dir = ctx.getDatabasePath(name).getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        return ctx.getDatabasePath(name).getAbsolutePath();
    }
}
