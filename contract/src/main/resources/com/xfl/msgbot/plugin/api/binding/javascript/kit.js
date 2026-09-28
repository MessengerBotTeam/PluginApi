/*
 * MessengerBotR JavaScript binding: the profile kit, require('msgbot').
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Everything a JavaScript profile needs that is not a matter of taste, so a profile keeps only
 * the shape of its own API:
 *
 *   api        every module in __api, as functions; positional arguments take the schema's
 *              names, one plain object passes them by name, fn.async(...) returns a Promise
 *   events     listeners by qualified name; the host is told what to deliver (sys.listen)
 *   onError    where a failing listener goes; the project log by default
 *   base64     bytes (Uint8Array) as base64
 *
 * Requiring it installs __dispatch, so a profile that only uses events.on needs nothing else.
 * Written in ES5 so every JavaScript engine runs it unchanged.
 */
'use strict';

var specs = Object.create(null);
__api.forEach(function (spec) { specs[spec.namespace] = spec; });

function find(list, name) {
    for (var i = 0; i < list.length; i++) if (list[i].name === name) return list[i];
    return null;
}

function split(qualified) {
    var dot = String(qualified).indexOf('.');
    return dot < 0 ? null : [qualified.substring(0, dot), qualified.substring(dot + 1)];
}

function functionSpec(qualified) {
    var parts = split(qualified);
    var spec = parts && specs[parts[0]];
    return spec ? find(spec.functions, parts[1]) : null;
}

function eventSpec(qualified) {
    var parts = split(qualified);
    var spec = parts && specs[parts[0]];
    return spec ? find(spec.events, parts[1]) : null;
}

function isPlainObject(v) {
    if (v === null || typeof v !== 'object' || Array.isArray(v) || v instanceof Uint8Array) return false;
    var proto = Object.getPrototypeOf(v);
    return proto === Object.prototype || proto === null;
}

// Positional arguments take the schema's names in order. A single plain object passes them by
// name, unless the first parameter itself takes an object.
function argsFor(qualified, fn, values) {
    var first = fn.params.length ? fn.params[0].type.replace(/\?$/, '') : '';
    var takesObject = first === 'any' || first.indexOf('map<') === 0 || first.charAt(0) === '{';
    if (values.length === 1 && isPlainObject(values[0]) && fn.params.length > 0 && !takesObject) return values[0];
    if (values.length > fn.params.length) {
        throw new TypeError(qualified + ' takes at most ' + fn.params.length + ' argument(s), got ' + values.length);
    }
    var args = {};
    for (var i = 0; i < values.length; i++) if (values[i] !== undefined) args[fn.params[i].name] = values[i];
    return args;
}

function makeModule(spec) {
    var module = Object.create(null);
    spec.functions.forEach(function (fn) {
        var qualified = spec.namespace + '.' + fn.name;
        var call = function () { return __host_call(qualified, argsFor(qualified, fn, Array.prototype.slice.call(arguments))); };
        call.async = function () { return __host_call_async(qualified, argsFor(qualified, fn, Array.prototype.slice.call(arguments))); };
        module[fn.name] = call;
    });
    return Object.freeze(module);
}

var api = Object.create(null);
Object.keys(specs).forEach(function (namespace) { api[namespace] = makeModule(specs[namespace]); });
Object.freeze(api);

// Listeners by qualified event name. The host delivers only what someone listens to.
var listeners = Object.create(null);
var canListen = functionSpec('sys.listen') !== null;

function tellHost() {
    if (!canListen) return;
    __host_call('sys.listen', { events: Object.keys(listeners).filter(function (name) { return listeners[name].length > 0; }) });
}

var kit = {
    api: api,

    /** Whether the project has this function or event ('weather.forecast', 'bot.message'). */
    isAvailable: function (qualified) { return functionSpec(qualified) !== null || eventSpec(qualified) !== null; },

    /** The module spec for a namespace, as __api has it, or null. */
    spec: function (namespace) { return specs[namespace] || null; },

    events: {
        /** Listens to a qualified event; throws when the project can never deliver it. */
        on: function (name, fn) {
            name = String(name);
            if (!eventSpec(name)) throw new Error("Event '" + name + "' is never delivered to this project; check its providers.");
            if (typeof fn !== 'function') throw new TypeError('A listener must be a function');
            var first = !listeners[name] || listeners[name].length === 0;
            (listeners[name] = listeners[name] || []).push(fn);
            if (first) tellHost();
            return function () { kit.events.off(name, fn); };
        },
        once: function (name, fn) {
            var off = kit.events.on(name, function () {
                off();
                return fn.apply(null, arguments);
            });
            return off;
        },
        off: function (name, fn) {
            var fns = listeners[name];
            if (!fns || fns.length === 0) return;
            if (fn === undefined) fns.length = 0;
            else for (var i = fns.length - 1; i >= 0; i--) if (fns[i] === fn) fns.splice(i, 1);
            if (fns.length === 0) tellHost();
        },
        count: function (name) { return (listeners[name] || []).length; }
    },

    /** Calls every listener of an event; one that throws goes to onError and the rest still run. */
    dispatch: function (name, payload) {
        var fns = (listeners[name] || []).slice();
        for (var i = 0; i < fns.length; i++) {
            try {
                var result = fns[i](payload);
                if (result && typeof result.then === 'function') {
                    result.then(null, function (e) { kit.onError(e, name); });
                }
            } catch (e) {
                kit.onError(e, name);
            }
        }
    },

    /** Replace to route listener failures elsewhere. */
    onError: function (error, eventName) {
        var message = 'listener of ' + eventName + ' failed: ' + kit.describe(error);
        if (functionSpec('log.write')) __host_call('log.write', { level: 'error', message: message, tag: 'profile' });
        else throw error;
    },

    /** An error as one string, message and stack once each, whichever engine made it. */
    describe: function (error) {
        var text = String(error);
        var stack = error && error.stack;
        return stack && stack.indexOf(text) < 0 ? text + '\n' + stack : stack || text;
    },

    base64: function (bytes) {
        var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
        var out = '';
        for (var i = 0; i < bytes.length; i += 3) {
            var n = (bytes[i] << 16) | ((bytes[i + 1] || 0) << 8) | (bytes[i + 2] || 0);
            out += chars.charAt(n >> 18 & 63) + chars.charAt(n >> 12 & 63) +
                (i + 1 < bytes.length ? chars.charAt(n >> 6 & 63) : '=') +
                (i + 2 < bytes.length ? chars.charAt(n & 63) : '=');
        }
        return out;
    }
};

// Nothing is delivered until someone listens; a profile that never requires the kit gets everything.
tellHost();
globalThis.__dispatch = function (name, payload) { kit.dispatch(name, payload); };

module.exports = kit;
