/*
 * MessengerBotR JavaScript binding: CommonJS modules over a project's sources.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Evaluates to function (sources, compile, fallback) -> makeRequire(fromPath).
 * Written in ES5 so every JavaScript engine can run it unchanged.
 */
(function (sources, compile, fallback) {
    'use strict';
    var has = Object.prototype.hasOwnProperty;
    var cache = Object.create(null);

    function normalize(path) {
        var out = [];
        var parts = path.split('/');
        for (var i = 0; i < parts.length; i++) {
            var part = parts[i];
            if (part === '' || part === '.') continue;
            if (part === '..') {
                if (out.length === 0) return null;
                out.pop();
            } else {
                out.push(part);
            }
        }
        return out.join('/');
    }

    function dirname(path) {
        var slash = path.lastIndexOf('/');
        return slash < 0 ? '' : path.substring(0, slash);
    }

    // null: not a project path, so the runtime's own require may know it.
    function resolve(specifier, from) {
        var relative = specifier.indexOf('./') === 0 || specifier.indexOf('../') === 0;
        if (!relative && specifier.charAt(0) !== '/') return null;
        var base = normalize(relative ? dirname(from) + '/' + specifier : specifier);
        if (base === null) throw new Error("Cannot find module '" + specifier + "': it is outside the project");
        var candidates = [base, base + '.js', base + '.json', base + '/index.js'];
        for (var i = 0; i < candidates.length; i++) {
            if (has.call(sources, candidates[i])) return candidates[i];
        }
        throw new Error("Cannot find module '" + specifier + "' from '" + from + "'");
    }

    function load(path) {
        var cached = cache[path];
        if (cached) return cached.exports;
        var module = { id: path, filename: path, exports: {}, loaded: false };
        cache[path] = module;
        try {
            if (/\.json$/.test(path)) {
                module.exports = JSON.parse(sources[path]);
            } else {
                compile(path, sources[path]).call(module.exports, module.exports, makeRequire(path), module, path, dirname(path));
            }
        } catch (e) {
            delete cache[path];
            throw e;
        }
        module.loaded = true;
        return module.exports;
    }

    function makeRequire(from) {
        var require = function (specifier) {
            specifier = String(specifier);
            var path = resolve(specifier, from);
            if (path !== null) return load(path);
            if (fallback) return fallback(specifier);
            throw new Error("Cannot find module '" + specifier + "'");
        };
        require.cache = cache;
        return require;
    }

    return makeRequire;
})
