# cordova-plugin-sqlcipher-16kb

A Cordova plugin for SQLCipher on Android with **16KB page size support** required by Google Play (May 2026 deadline).

Drop-in replacement for `cordova-sqlcipher-adapter`. Uses the maintained `net.zetetic:sqlcipher-android:4.15.0` library (free, BSD-licensed).

## Why this plugin?

The old `cordova-sqlcipher-adapter` uses the deprecated `android-database-sqlcipher` library which doesn't support 16KB page sizes. This plugin uses the modern `sqlcipher-android` library that does.

## Requirements

- Cordova Android platform
- minSdkVersion 23+

## Installation

```bash
cordova plugin add https://github.com/YOUR_USERNAME/cordova-plugin-sqlcipher-16kb
```

## Usage

Same API as `cordova-sqlcipher-adapter`:

```javascript
var db = window.sqlitePlugin.openDatabase({
    name: 'mydb.db',
    key: 'your-encryption-key',
    location: 'default'
});

db.transaction(function(tx) {
    tx.executeSql('CREATE TABLE IF NOT EXISTS DemoTable (id INTEGER PRIMARY KEY, name TEXT)');
});
```

## License

MIT
