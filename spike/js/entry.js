// Boot spike only: does pouchdb-core + an adapter run inside QuickJS via Zipline?
// Not production code, not the real @docstack/pouchdb-adapter-native.
//
// zipline-cli's own compile-time dependency-collection pass runs on a bare QuickJs
// instance with no `console` at all (confirmed - this was the very first thing it
// complained about, before any of the other shims below). Guarded the same way as
// the rest: at real runtime the actual Zipline instance already provides a working
// console wired to java.util.logging, so this stub never overrides it there.
if (typeof globalThis.console === 'undefined') {
  var noop = function () {};
  globalThis.console = { log: noop, info: noop, warn: noop, error: noop, debug: noop };
}
console.log('SPIKE_BOOT entry.js started; typeof setTimeout=' + typeof setTimeout +
  ' typeof globalThis.setTimeout=' + typeof globalThis.setTimeout);
//
// Shim preamble: pouchdb-core's browser build touches these globals at require()
// time even when the code path that needs them (fetch-based replication) is never
// exercised by this spike. Real fetch/TextEncoder support belongs to spec 04 task 3
// (OkHttp bridge) - these are throwaway stubs just to get past module load.
if (typeof globalThis.fetch === 'undefined') {
  globalThis.fetch = function () {
    return Promise.reject(new Error('fetch not implemented in this spike'));
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
// Buffer isn't available either (this is neither Node nor a browser) - pure JS
// base64, no Node/browser built-in relied on.
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
if (typeof globalThis.setTimeout === 'undefined') {
  // Confirmed: Zipline's real setTimeout/event-loop bridge (CoroutineEventLoop,
  // per zipline/src/hostMain/kotlin/app/cash/zipline/Zipline.kt) is wired through
  // JS-side glue code the Kotlin/JS compiler plugin generates - it is NOT free for
  // a hand-bundled raw JS module like this one, at compile time or at real
  // runtime. A true no-op broke nothing at compile time (nothing drains a queue
  // there) but left every deferred PouchDB callback (immediate()/nextTick chains)
  // never firing at real runtime, so put()/get() never settled.
  //
  // Minimal real implementation: a queue the host drains from Kotlin via
  // QuickJs.evaluate("globalThis.__drainTasks()") in a loop. This is a spike-only
  // polling shortcut - the real spec 04 implementation needs a proper
  // suspend-based bridge matching CoroutineEventLoop's model, not host-side
  // polling.
  var __taskQueue = [];
  globalThis.setTimeout = function (fn) {
    __taskQueue.push(fn);
    return __taskQueue.length;
  };
  globalThis.clearTimeout = function () {};
  globalThis.setInterval = function () { return 0; };
  globalThis.clearInterval = function () {};
  globalThis.__drainTasks = function () {
    var due = __taskQueue;
    __taskQueue = [];
    for (var i = 0; i < due.length; i++) {
      try { due[i](); } catch (e) { /* surfaced via __SPIKE_RESULT__, not here */ }
    }
    return due.length;
  };
}
if (typeof globalThis.process === 'undefined') {
  globalThis.process = { browser: true, env: {}, nextTick: function (fn) { setTimeout(fn, 0); } };
}

var PouchDBCore = require('pouchdb-core');
var MemoryAdapter = require('pouchdb-adapter-memory');

var PouchDB = PouchDBCore.plugin(MemoryAdapter);
var db = new PouchDB('spike', { adapter: 'memory' });

// console.log turned out to be a dead end for observability: it's not a native
// QuickJS/Zipline feature at all (Zipline.create()'s own source sets up no such
// thing) - it only ever "just works" for genuine Kotlin/JS-compiled Zipline apps
// because Kotlin/JS's stdlib ships its own console polyfill as part of *that*
// compiled output. For a hand-bundled raw JS module there's no console unless we
// supply one, and a host-side read-back is simpler and more reliable than wiring
// a Kotlin-bound console shim. Set a well-known global; the host reads it back via
// QuickJs.evaluate() after giving the async chain time to settle.
globalThis.__SPIKE_RESULT__ = 'SPIKE_PENDING';
globalThis.__SPIKE_TRACE__ = ['boot'];

try {
  var putPromise = db.put({ _id: 'doc1', hello: 'world' });
  globalThis.__SPIKE_TRACE__.push('put-called typeof=' + typeof putPromise +
    ' hasThen=' + (putPromise && typeof putPromise.then));
  putPromise
    .then(function () {
      globalThis.__SPIKE_TRACE__.push('put-resolved');
      return db.get('doc1');
    })
    .then(function (doc) {
      globalThis.__SPIKE_TRACE__.push('get-resolved');
      globalThis.__SPIKE_RESULT__ = 'SPIKE_OK ' + JSON.stringify(doc);
    })
    .catch(function (err) {
      globalThis.__SPIKE_TRACE__.push('rejected: ' + (err && err.message ? err.message : String(err)));
      globalThis.__SPIKE_RESULT__ = 'SPIKE_FAIL ' + (err && err.stack ? err.stack : String(err));
    });
} catch (e) {
  globalThis.__SPIKE_TRACE__.push('sync-throw: ' + (e && e.stack ? e.stack : String(e)));
  globalThis.__SPIKE_RESULT__ = 'SPIKE_FAIL sync ' + (e && e.stack ? e.stack : String(e));
}
