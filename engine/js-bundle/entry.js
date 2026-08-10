// Real pouchdb-core + @docstack/pouchdb-adapter-native, bundled for QuickJS/Zipline.
// Not the memory-adapter stand-in the original spec-04 boot spike used.
//
// Loaded as one more module in the combined manifest (see SPIKE-NOTES.md's
// manifest-merge technique) - this module's own top-level code (the require() calls
// below) runs BEFORE the Kotlin/JS `engine` module's mainFunction (launchZipline) is
// invoked (confirmed empirically during the boot spike continuation: every module's
// top-level code runs first, mainFunction runs last). globalThis.console/setTimeout
// are still these temporary pre-GlobalBridge placeholders at require() time; real ones
// take over once launchZipline() calls Zipline.get(), before it defines
// globalThis.__docstackHost/calls globalThis.__createPouchDB - so nothing here may run
// PouchDB operations at module-load time, only define entry points for later.
if (typeof globalThis.console === 'undefined') {
  var noop = function () {};
  globalThis.console = { log: noop, info: noop, warn: noop, error: noop, debug: noop };
}
if (typeof globalThis.setTimeout === 'undefined') {
  globalThis.setTimeout = function () { return 0; };
  globalThis.clearTimeout = function () {};
  globalThis.setInterval = function () { return 0; };
  globalThis.clearInterval = function () {};
}

// pouchdb-core requires pouchdb-fetch unconditionally at load time even when nothing
// on that path is exercised yet (real fetch support is spec 04 task 3, not this task).
if (typeof globalThis.fetch === 'undefined') {
  globalThis.fetch = function () {
    return Promise.reject(new Error('fetch not implemented - spec 04 task 3'));
  };
}
if (typeof globalThis.Headers === 'undefined') {
  globalThis.Headers = function () { this._h = {}; };
  globalThis.Headers.prototype.get = function (k) { return this._h[k] || null; };
  globalThis.Headers.prototype.set = function (k, v) { this._h[k] = v; };
}
if (typeof globalThis.TextEncoder === 'undefined') {
  globalThis.TextEncoder = function () {};
  globalThis.TextEncoder.prototype.encode = function (str) {
    var bytes = [];
    for (var i = 0; i < str.length; i++) bytes.push(str.charCodeAt(i));
    return new Uint8Array(bytes);
  };
}
if (typeof globalThis.TextDecoder === 'undefined') {
  globalThis.TextDecoder = function () {};
  globalThis.TextDecoder.prototype.decode = function (bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return s;
  };
}
// No Buffer either (this is neither Node nor a browser) - pure JS base64.
var B64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
if (typeof globalThis.btoa === 'undefined') {
  globalThis.btoa = function (str) {
    var out = '';
    for (var i = 0; i < str.length; i += 3) {
      var a = str.charCodeAt(i);
      var b = str.charCodeAt(i + 1);
      var c = str.charCodeAt(i + 2);
      out += B64_CHARS[a >> 2];
      out += B64_CHARS[((a & 3) << 4) | (isNaN(b) ? 0 : b >> 4)];
      out += isNaN(b) ? '=' : B64_CHARS[((b & 15) << 2) | (isNaN(c) ? 0 : c >> 6)];
      out += isNaN(c) ? '=' : B64_CHARS[c & 63];
    }
    return out;
  };
}
if (typeof globalThis.atob === 'undefined') {
  globalThis.atob = function (b64) {
    b64 = b64.replace(/=+$/, '');
    var out = '';
    var bits = 0, value = 0;
    for (var i = 0; i < b64.length; i++) {
      value = (value << 6) | B64_CHARS.indexOf(b64[i]);
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        out += String.fromCharCode((value >> bits) & 0xff);
      }
    }
    return out;
  };
}
if (typeof globalThis.process === 'undefined') {
  // kotlinx-coroutines-core's JS Dispatchers.Default picks NodeDispatcher whenever
  // `process.nextTick` exists, and NodeDispatcher's ScheduledMessageQueue expects
  // nextTick to behave like a real microtask (fast, high-frequency, drains before any
  // macrotask) - not like setTimeout, which here is Zipline's real cross-boundary
  // GlobalBridge setTimeout (a full host round trip per tick). Routing nextTick through
  // setTimeout is what caused coroutine dispatch to hang after the first continuation:
  // use QuickJS's native Promise microtask queue instead, which every Promise chain
  // already relies on successfully.
  globalThis.process = { browser: true, env: {}, nextTick: function (fn) { Promise.resolve().then(fn); } };
}

var PouchDBCore = require('pouchdb-core');
var NativeAdapterModule = require('@docstack/pouchdb-adapter-native');
var NativeAdapter = NativeAdapterModule.NativeAdapter;

// Defined now, called later (from launchZipline(), after globalThis.__docstackHost is
// the real carrier) - registering the adapter and opening a db before that would bind
// it to whatever placeholder carrier exists at require() time, which is none.
globalThis.__createPouchDB = function (carrier) {
  return PouchDBCore.plugin(NativeAdapter({ carrier: carrier }));
};

