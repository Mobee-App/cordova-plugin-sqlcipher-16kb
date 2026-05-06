/*
 * SQLitePlugin.js
 * Drop-in replacement for cordova-sqlcipher-adapter
 * Uses net.zetetic:sqlcipher-android (16KB compatible)
 *
 * Critical: queues all operations until DB is actually open
 */

var exec = require('cordova/exec');

// Cache DB instances by name so getDataDb() called repeatedly returns same object
var _instances = {};

// ─── SQLitePlugin (DB object) ─────────────────────────────────────────────────

var SQLitePlugin = function(openargs, openSuccess, openError) {
    this.openargs = openargs;
    this.dbname   = openargs.name;
    this.txlock   = false;
    this.txqueue  = [];

    // Open state machine
    this.openState = 'opening';   // 'opening' | 'open' | 'failed'
    this.pendingCalls = [];       // operations queued until open completes

    this._openDB(openSuccess, openError);
};

SQLitePlugin.prototype._openDB = function(success, error) {
    var self = this;
    var args = {};
    for (var k in self.openargs) {
        if (Object.prototype.hasOwnProperty.call(self.openargs, k)) {
            args[k] = self.openargs[k];
        }
    }
    if (args.key && !args.password) args.password = args.key;

    exec(
        function() {
            self.openState = 'open';
            // Drain pending calls now that DB is open
            var pending = self.pendingCalls;
            self.pendingCalls = [];
            pending.forEach(function(fn) { fn(); });
            if (success) success();
        },
        function(err) {
            self.openState = 'failed';
            self.openError = err;
            // Reject all pending calls
            var pending = self.pendingCalls;
            self.pendingCalls = [];
            pending.forEach(function(fn) { fn(err); });
            if (error) error(err);
        },
        'SQLitePlugin', 'open', [args]
    );
};

// runs `fn()` immediately if open, queues it if still opening, errors if failed
SQLitePlugin.prototype._whenOpen = function(fn) {
    if (this.openState === 'open') {
        fn();
    } else if (this.openState === 'failed') {
        fn(this.openError);
    } else {
        this.pendingCalls.push(fn);
    }
};

SQLitePlugin.prototype.close = function(success, error) {
    var self = this;
    this._whenOpen(function() {
        delete _instances[self.dbname];
        exec(success, error, 'SQLitePlugin', 'close', [{ path: self.dbname }]);
    });
};

// ─── transaction ──────────────────────────────────────────────────────────────

SQLitePlugin.prototype.transaction = function(fn, error, success) {
    var self = this;
    this._whenOpen(function(err) {
        if (err) { if (error) error(err); return; }
        var t = new SQLitePluginTransaction(self, fn, error, success);
        self.txqueue.push(t);
        if (!self.txlock) self._nextTx();
    });
};

SQLitePlugin.prototype.readTransaction = SQLitePlugin.prototype.transaction;

SQLitePlugin.prototype._nextTx = function() {
    if (this.txqueue.length === 0) { this.txlock = false; return; }
    this.txlock = true;
    this.txqueue.shift()._run();
};

// ─── sqlBatch ─────────────────────────────────────────────────────────────────

SQLitePlugin.prototype.sqlBatch = function(sqlStatements, success, error) {
    var self = this;
    this._whenOpen(function(err) {
        if (err) { if (error) error(err); return; }
        var executes = sqlStatements.map(function(s) {
            if (Array.isArray(s)) return { sql: s[0], params: _normalizeParams(s[1]) };
            return { sql: s, params: [] };
        });
        exec(
            function(results) { if (success) success(); },
            function(err) { if (error) error(err); },
            'SQLitePlugin', 'backgroundExecuteSqlBatch',
            [{ dbname: self.dbname, executes: executes }]
        );
    });
};

// ─── Transaction object ───────────────────────────────────────────────────────

var SQLitePluginTransaction = function(db, fn, errorCb, successCb) {
    this.db        = db;
    this.fn        = fn;
    this.errorCb   = errorCb;
    this.successCb = successCb;
    this.executes  = [];
};

SQLitePluginTransaction.prototype.executeSql = function(sql, params, success, error) {
    this.executes.push({
        sql:     sql,
        params:  _normalizeParams(params),
        success: success || null,
        error:   error   || null
    });
};

SQLitePluginTransaction.prototype._run = function() {
    var t  = this;
    var db = t.db;

    try { t.fn(t); } catch(e) {
        db._nextTx();
        if (t.errorCb) t.errorCb(e);
        return;
    }

    if (t.executes.length === 0) {
        db._nextTx();
        if (t.successCb) t.successCb();
        return;
    }

    var batch     = t.executes.map(function(e) { return { sql: e.sql, params: e.params }; });
    var callbacks = t.executes;

    exec(
        function(results) {
            var hasError = false;
            for (var i = 0; i < results.length; i++) {
                (function(res, cb) {
                    if (res.error) {
                        hasError = true;
                        if (cb.error) cb.error({ message: res.error });
                    } else {
                        if (cb.success) cb.success(t, _makeResultSet(res));
                    }
                })(results[i], callbacks[i]);
            }
            db._nextTx();
            if (hasError) {
                if (t.errorCb) t.errorCb({ message: 'SQL error in transaction' });
            } else {
                if (t.successCb) t.successCb();
            }
        },
        function(err) {
            db._nextTx();
            if (t.errorCb) t.errorCb(err);
        },
        'SQLitePlugin', 'backgroundExecuteSqlBatch',
        [{ dbname: db.dbname, executes: batch }]
    );
};

// ─── Helpers ──────────────────────────────────────────────────────────────────

function _normalizeParams(params) {
    if (!params || !Array.isArray(params)) return [];
    return params.map(function(v) {
        if (v === null || v === undefined) return null;
        if (v instanceof Date) return v.toISOString();
        return v;
    });
}

function _makeResultSet(res) {
    var rows = res.rows || [];
    return {
        rows: {
            length: rows.length,
            item:   function(i) { return rows[i]; },
            _array: rows
        },
        rowsAffected: res.rowsAffected || 0,
        insertId:     res.insertId     || undefined
    };
}

// ─── Public API ───────────────────────────────────────────────────────────────

module.exports = {
    openDatabase: function(options, success, error) {
        if (!options || !options.name) throw new Error('openDatabase: name is required');
        // Reuse existing instance if already created (matches old plugin behavior)
        var existing = _instances[options.name];
        if (existing) {
            // If existing is still opening or open, just trigger success when ready
            existing._whenOpen(function(err) {
                if (err) { if (error) error(err); }
                else { if (success) success(); }
            });
            return existing;
        }
        var db = new SQLitePlugin(options, success, error);
        _instances[options.name] = db;
        return db;
    },
    deleteDatabase: function(options, success, error) {
        var name = options.name || options.path;
        delete _instances[name];
        exec(success, error, 'SQLitePlugin', 'delete', [{ path: name }]);
    }
};
