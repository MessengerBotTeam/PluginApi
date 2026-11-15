/*
 * MessengerBotR JavaScript binding: CommonJS and ES modules over a project's sources.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Evaluates to function (sources, compile, fallback, java, esm) -> makeRequire(fromPath). Also serves
 * the builtin modules such as require('msgbot'), `java:` modules and the `#!support` directive.
 * makeRequire.esm holds what an engine that runs ES modules itself needs. ES5 so every JavaScript
 * engine can run it.
 */
(function (sources, compile, fallback, java, esm) {
    'use strict';
    var has = Object.prototype.hasOwnProperty;
    var cache = Object.create(null);
    var builtins = /*BUILTINS*/null;
    var BUILTIN = 'builtin:';
    var JAVA = 'java:';
    var IDENTIFIER = /^[A-Za-z_$][A-Za-z0-9_$]*$/;
    var JAVA_NAME = /^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$/;
    var KEYWORDS_BEFORE_REGEX = /^(return|typeof|instanceof|in|of|new|delete|void|throw|case|do|else|yield|await)$/;

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

    function isRelative(specifier) {
        return specifier.indexOf('./') === 0 || specifier.indexOf('../') === 0 || specifier.charAt(0) === '/';
    }

    // The project path a relative specifier names, before any extension is guessed.
    function projectPath(specifier, from) {
        var base = normalize(specifier.charAt(0) === '/' ? specifier : dirname(from) + '/' + specifier);
        if (base === null) throw new Error("Cannot find module '" + specifier + "': it is outside the project");
        return base;
    }

    // ---- Scanner: tokens of a source with strings, comments, templates and regular expressions skipped.

    // Each token is { t: text, k: 'id' | 'str' | 'punct', s: start, e: end, d: bracket depth }.
    function tokenize(source) {
        var tokens = [];
        var depth = 0;
        var templates = []; // brace depths at which a template's ${ } closes
        var i = 0;
        var n = source.length;
        var last = null;

        function regexAllowed() {
            if (last === null) return true;
            if (last.k === 'id') return KEYWORDS_BEFORE_REGEX.test(last.t);
            if (last.k === 'str') return false;
            return !(last.t === ')' || last.t === ']' || last.t === '}');
        }

        function skipTemplate() {
            // At the character after ` or after a } that closes ${.
            while (i < n) {
                var c = source.charAt(i);
                if (c === '\\') {
                    i += 2;
                } else if (c === '`') {
                    i++;
                    return;
                } else if (c === '$' && source.charAt(i + 1) === '{') {
                    i += 2;
                    templates.push(depth);
                    depth++;
                    return;
                } else {
                    i++;
                }
            }
        }

        while (i < n) {
            var c = source.charAt(i);
            var start = i;
            if (c === '/' && source.charAt(i + 1) === '/') {
                while (i < n && source.charAt(i) !== '\n') i++;
            } else if (c === '/' && source.charAt(i + 1) === '*') {
                var close = source.indexOf('*/', i + 2);
                i = close < 0 ? n : close + 2;
            } else if (c === '"' || c === "'") {
                i++;
                while (i < n && source.charAt(i) !== c && source.charAt(i) !== '\n') i += source.charAt(i) === '\\' ? 2 : 1;
                i++;
                last = { t: source.substring(start + 1, i - 1), k: 'str', s: start, e: i, d: depth };
                tokens.push(last);
            } else if (c === '`') {
                i++;
                skipTemplate();
                last = { t: '`', k: 'str', s: start, e: i, d: depth };
                tokens.push(last);
            } else if (c === '/' && regexAllowed()) {
                i++;
                var inClass = false;
                while (i < n) {
                    var r = source.charAt(i);
                    if (r === '\\') {
                        i += 2;
                        continue;
                    }
                    if (r === '\n') break;
                    if (r === '[') inClass = true;
                    else if (r === ']') inClass = false;
                    else if (r === '/' && !inClass) break;
                    i++;
                }
                i++;
                while (i < n && /[A-Za-z]/.test(source.charAt(i))) i++;
                last = { t: '/re/', k: 'str', s: start, e: i, d: depth };
                tokens.push(last);
            } else if (/[A-Za-z_$]/.test(c)) {
                while (i < n && /[A-Za-z0-9_$]/.test(source.charAt(i))) i++;
                last = { t: source.substring(start, i), k: 'id', s: start, e: i, d: depth };
                tokens.push(last);
            } else if (/[0-9]/.test(c)) {
                while (i < n && /[0-9A-Za-z_.]/.test(source.charAt(i))) i++;
                last = { t: '0', k: 'str', s: start, e: i, d: depth };
                tokens.push(last);
            } else if (/\s/.test(c)) {
                i++;
            } else {
                i++;
                if (c === '}' && templates.length > 0 && templates[templates.length - 1] === depth - 1) {
                    templates.pop();
                    depth--;
                    skipTemplate();
                    last = { t: '`', k: 'str', s: start, e: i, d: depth };
                    tokens.push(last);
                    continue;
                }
                if (c === '{' || c === '(' || c === '[') {
                    last = { t: c, k: 'punct', s: start, e: i, d: depth };
                    depth++;
                } else if (c === '}' || c === ')' || c === ']') {
                    depth--;
                    last = { t: c, k: 'punct', s: start, e: i, d: depth };
                } else {
                    last = { t: c, k: 'punct', s: start, e: i, d: depth };
                }
                tokens.push(last);
            }
        }
        return tokens;
    }

    function isModuleKeyword(tokens, i) {
        var token = tokens[i];
        if (token.k !== 'id' || token.d !== 0) return false;
        var before = tokens[i - 1];
        if (before && before.t === '.') return false;
        var after = tokens[i + 1];
        if (token.t === 'export') return true;
        return token.t === 'import' && !(after && (after.t === '(' || after.t === '.'));
    }

    function hasModuleSyntax(source) {
        var tokens = tokenize(source);
        for (var i = 0; i < tokens.length; i++) if (isModuleKeyword(tokens, i)) return true;
        return false;
    }

    // Whether a new line between [before] and [after] ends a statement without a semicolon.
    function endsAtNewLine(source, before, after) {
        if (source.substring(before.e, after.s).indexOf('\n') < 0) return false;
        var closesValue = before.k !== 'punct' || before.t === ')' || before.t === ']' || before.t === '}';
        return closesValue && (after.k === 'id' || after.t === '(' || after.t === '[');
    }

    // The static import and export statements: for each, the keyword, the tokens after it up to the
    // module name or the end of the statement, and the module name token if there is one.
    function statements(source) {
        var tokens = tokenize(source);
        var found = [];
        for (var i = 0; i < tokens.length; i++) {
            if (!isModuleKeyword(tokens, i)) continue;
            var keyword = tokens[i];
            var j = i + 1;
            var from = null;
            while (j < tokens.length) {
                var tok = tokens[j];
                var previous = tokens[j - 1];
                if (tok.k === 'str' && tok.t !== '`' && tok.t !== '/re/' && tok.t !== '0' &&
                    (previous.t === 'from' || (keyword.t === 'import' && j === i + 1))) {
                    from = tok;
                    break;
                }
                if (tok.d === 0 && tok.t === ';') break;
                if (j > i + 1 && tok.d === 0 && isModuleKeyword(tokens, j)) break;
                if (j > i + 1 && tok.d === 0 && previous.d === 0 && endsAtNewLine(source, previous, tok) && tok.t !== 'from') break;
                j++;
            }
            found.push({ keyword: keyword, tokens: tokens.slice(i + 1, from ? j : j), from: from, last: from || tokens[j - 1] || keyword });
            if (from) i = j;
            else i = j - 1;
        }
        return found;
    }

    // `{ a, b as c, default as d }`: the imported names, a, b and default.
    function braceNames(tokens) {
        var names = [];
        var inside = false;
        for (var i = 0; i < tokens.length; i++) {
            var t = tokens[i];
            if (t.t === '{') inside = true;
            else if (t.t === '}') inside = false;
            else if (inside && (t.k === 'id' || t.k === 'str') && t.t !== 'as' && !(tokens[i - 1] && tokens[i - 1].t === 'as')) names.push(t.t);
        }
        return names;
    }

    // Names asked of every `java:` module across the project, so its ES module can export them.
    var javaNames = null;

    function javaImportNames(specifier) {
        if (javaNames === null) {
            javaNames = Object.create(null);
            for (var path in sources) {
                if (!has.call(sources, path) || !/\.(m?js|cjs)$/.test(path)) continue;
                var found = statements(sources[path]);
                for (var i = 0; i < found.length; i++) {
                    var statement = found[i];
                    if (!statement.from || statement.from.t.indexOf(JAVA) !== 0) continue;
                    var list = javaNames[statement.from.t] || (javaNames[statement.from.t] = []);
                    var names = braceNames(statement.tokens);
                    for (var k = 0; k < names.length; k++) if (list.indexOf(names[k]) < 0) list.push(names[k]);
                }
            }
        }
        return javaNames[specifier] || [];
    }

    // ---- The `#!support` directive.

    function directives(path, source) {
        var result = { esm: false, cjs: false, line: false };
        if (source.indexOf('#!') !== 0) return result;
        var end = source.indexOf('\n');
        var line = (end < 0 ? source : source.substring(0, end)).replace(/\r$/, '');
        result.line = true;
        var match = /^#!support(\s.*)?$/.exec(line);
        if (!match) return result;
        var words = (match[1] || '').trim().split(/\s+/);
        for (var i = 0; i < words.length; i++) {
            if (words[i] === 'esm') result.esm = true;
            else if (words[i] === 'cjs') result.cjs = true;
            else throw new Error(path + ":1: unknown #!support value '" + words[i] + "'; use esm or cjs");
        }
        return result;
    }

    // Puts [text] in place of the first line, so every later line keeps its number.
    function replaceFirstLine(source, text) {
        var end = source.indexOf('\n');
        return text + (end < 0 ? '' : source.substring(end));
    }

    function kindOf(path, source) {
        if (/\.json$/.test(path)) return 'json';
        if (/\.cjs$/.test(path)) return 'cjs';
        if (/\.mjs$/.test(path)) return 'esm';
        if (directives(path, source).esm) return 'esm';
        return hasModuleSyntax(source) ? 'esm' : 'cjs';
    }

    // ---- ES module syntax as CommonJS, for engines that only run CommonJS (`#!support esm`).

    // Java classes and packages throw for a member they lack (Rhino), so they are never converted modules.
    function isConverted(module) {
        try {
            return !!(module && module.__esModule === true);
        } catch (e) {
            return false;
        }
    }

    var interop = {
        // The value `import x from` gives: a converted module's default, or the whole CommonJS exports.
        def: function (module) { return isConverted(module) ? module['default'] : module; },
        ns: function (module) {
            if (isConverted(module)) return module;
            var ns = { 'default': module };
            if (module !== null && (typeof module === 'object' || typeof module === 'function')) {
                try {
                    for (var key in module) if (has.call(module, key) && key !== 'default') ns[key] = module[key];
                } catch (ignored) {
                    // A host object that cannot list its members: only default.
                }
            }
            return ns;
        },
        live: function (exports, getters) {
            Object.defineProperty(exports, '__esModule', { value: true });
            for (var name in getters) {
                if (has.call(getters, name)) Object.defineProperty(exports, name, { enumerable: true, configurable: true, get: getters[name] });
            }
        },
        star: function (exports, module) {
            for (var key in module) {
                if (has.call(module, key) && key !== 'default' && key !== '__esModule' && !has.call(exports, key)) {
                    (function (k) { Object.defineProperty(exports, k, { enumerable: true, configurable: true, get: function () { return module[k]; } }); })(key);
                }
            }
        }
    };

    function quote(text) {
        return JSON.stringify(text);
    }

    // [asScript] when the result runs as a global script (the entry), which has no exports or module of its own.
    function toCommonJs(path, source, asScript) {
        var found = statements(source);
        var edits = [];
        var getters = [];
        var temp = 0;
        for (var i = 0; i < found.length; i++) {
            var st = found[i];
            var kw = st.keyword;
            var toks = st.tokens;
            var endPos = st.last.e;
            var tail = source.substring(endPos).match(/^\s*;/);
            if (tail) endPos += tail[0].length;
            if (kw.t === 'import') {
                var spec = st.from ? quote(st.from.t) : null;
                if (!st.from) throw new Error(path + ': cannot convert an import without a module name');
                var parts = [];
                var name = '__esm' + (temp++);
                parts.push('var ' + name + ' = require(' + spec + ')');
                var idx = 0;
                if (toks.length && toks[0].k === 'id' && toks[0].t !== 'from') {
                    parts.push(toks[0].t + ' = __esm_interop.def(' + name + ')');
                    idx = 1;
                    if (toks[idx] && toks[idx].t === ',') idx++;
                }
                if (toks[idx] && toks[idx].t === '*') {
                    parts.push(toks[idx + 2].t + ' = __esm_interop.ns(' + name + ')');
                } else if (toks[idx] && toks[idx].t === '{') {
                    for (var k = idx + 1; k < toks.length && toks[k].t !== '}'; k++) {
                        if (toks[k].t === ',') continue;
                        var imported = toks[k].t;
                        var local = imported;
                        if (toks[k + 1] && toks[k + 1].t === 'as') {
                            local = toks[k + 2].t;
                            k += 2;
                        }
                        parts.push(local + ' = ' + (imported === 'default' ? '__esm_interop.def(' + name + ')' : name + '[' + quote(imported) + ']'));
                    }
                }
                edits.push({ s: kw.s, e: endPos, text: parts.join(', ') + ';' });
            } else {
                // export ...
                var first = toks[0];
                if (!first) continue;
                if (first.t === 'default') {
                    var second = toks[1];
                    if (second && (second.t === 'function' || second.t === 'class' || second.t === 'async') && toks[2] && toks[2].k === 'id' && toks[2].t !== 'function') {
                        var declared = second.t === 'async' ? toks[3] && toks[3].t : toks[2].t;
                        edits.push({ s: kw.s, e: first.e, text: '' });
                        getters.push(['default', declared]);
                    } else {
                        edits.push({ s: kw.s, e: first.e, text: 'exports["default"] =' });
                        getters.push(['default', null]);
                    }
                } else if (first.t === 'const' || first.t === 'let' || first.t === 'var') {
                    edits.push({ s: kw.s, e: first.s, text: '' });
                    // Top-level names declared after the keyword, up to the first `=` of each declarator.
                    var level = first.d;
                    var expectName = true;
                    for (var m = 1; m < toks.length; m++) {
                        var tk = toks[m];
                        if (tk.d === level && tk.t === ',') expectName = true;
                        else if (expectName && tk.k === 'id' && tk.d === level) {
                            getters.push([tk.t, tk.t]);
                            expectName = false;
                        } else if (tk.d === level && tk.t === '=') expectName = false;
                    }
                } else if (first.t === 'function' || first.t === 'class' || first.t === 'async') {
                    var fname = first.t === 'async' ? toks[2] && toks[2].t : (toks[1] && toks[1].t === '*' ? toks[2].t : toks[1].t);
                    edits.push({ s: kw.s, e: first.s, text: '' });
                    getters.push([fname, fname]);
                } else if (first.t === '*') {
                    if (!st.from) throw new Error(path + ': cannot convert export * without a module name');
                    var star = '__esm' + (temp++);
                    if (toks[1] && toks[1].t === 'as') {
                        edits.push({ s: kw.s, e: endPos, text: 'var ' + star + ' = __esm_interop.ns(require(' + quote(st.from.t) + '));' });
                        getters.push([toks[2].t, star]);
                    } else {
                        edits.push({ s: kw.s, e: endPos, text: '__esm_interop.star(exports, require(' + quote(st.from.t) + '));' });
                    }
                } else if (first.t === '{') {
                    var source2 = null;
                    if (st.from) {
                        source2 = '__esm' + (temp++);
                        edits.push({ s: kw.s, e: endPos, text: 'var ' + source2 + ' = require(' + quote(st.from.t) + ');' });
                    } else {
                        edits.push({ s: kw.s, e: endPos, text: '' });
                    }
                    for (var q = 1; q < toks.length && toks[q].t !== '}'; q++) {
                        if (toks[q].t === ',') continue;
                        var inner = toks[q].t;
                        var outer = inner;
                        if (toks[q + 1] && toks[q + 1].t === 'as') {
                            outer = toks[q + 2].t;
                            q += 2;
                        }
                        getters.push([outer, source2 ? (inner === 'default' ? '__esm_interop.def(' + source2 + ')' : source2 + '[' + quote(inner) + ']') : inner]);
                    }
                }
            }
        }
        // Dynamic import() and import.meta, anywhere in the file.
        var tokens = tokenize(source);
        for (var d = 0; d < tokens.length; d++) {
            var tok = tokens[d];
            if (tok.k !== 'id' || tok.t !== 'import' || (tokens[d - 1] && tokens[d - 1].t === '.')) continue;
            if (tokens[d + 1] && tokens[d + 1].t === '(') edits.push({ s: tok.s, e: tok.e, text: '__esm_import' });
            else if (tokens[d + 1] && tokens[d + 1].t === '.' && tokens[d + 2] && tokens[d + 2].t === 'meta') edits.push({ s: tok.s, e: tokens[d + 2].e, text: '__esm_meta' });
        }
        for (var a = 0; a < tokens.length; a++) {
            if (tokens[a].k === 'id' && tokens[a].t === 'await' && tokens[a].d === 0 && !(tokens[a - 1] && tokens[a - 1].t === '.')) {
                throw new Error(path + ':' + lineOf(source, tokens[a].s) + ': top-level await needs an engine that runs ES modules');
            }
        }
        edits.sort(function (x, y) { return y.s - x.s; });
        var out = source;
        for (var z = 0; z < edits.length; z++) out = out.substring(0, edits[z].s) + edits[z].text + out.substring(edits[z].e);
        var defaults = [];
        var liveParts = [];
        for (var g = 0; g < getters.length; g++) {
            if (getters[g][1] === null) continue;
            liveParts.push(quote(getters[g][0]) + ': function () { return ' + getters[g][1] + '; }');
        }
        var prelude = (asScript ? 'var exports = {}, module = { exports: exports }, __filename = ' + quote(path) + '; ' : '') +
            "var __esm_interop = require.esm; var __esm_import = function (s) { return new Promise(function (ok) { ok(__esm_interop.ns(require(s))); }); }; " +
            'var __esm_meta = { url: "file:///" + __filename }; __esm_interop.live(exports, {' + liveParts.join(', ') + '});';
        return replaceFirstLine(out, prelude);
    }

    function lineOf(source, position) {
        return source.substring(0, position).split('\n').length;
    }

    // ---- Dynamic import().

    // Set by an engine whose own import() cannot reach the project: importer(specifier, from) gives
    // [the module's evaluation promise or undefined, its namespace].
    var importer = null;

    function dynamicImport(specifier, from) {
        return new Promise(function (resolved) {
            if (!importer) {
                resolved(interop.ns(makeRequire(from)(specifier)));
                return;
            }
            var answer = importer(String(specifier), from);
            resolved(answer[0] ? answer[0].then(function () { return answer[1]; }) : answer[1]);
        });
    }

    // Replaces every import( call with [replacement]( when the engine needs it.
    function rewriteDynamicImports(source, replacement) {
        if (!importer) return source;
        var tokens = tokenize(source);
        var out = source;
        for (var i = tokens.length - 1; i >= 0; i--) {
            var token = tokens[i];
            if (token.k !== 'id' || token.t !== 'import' || !tokens[i + 1] || tokens[i + 1].t !== '(') continue;
            if (tokens[i - 1] && tokens[i - 1].t === '.') continue;
            out = out.substring(0, token.s) + replacement + out.substring(token.e);
        }
        return out;
    }

    // ---- CommonJS loading.

    // null means neither a project path, a builtin nor a Java name; defer to fallback.
    function resolve(specifier, from) {
        if (specifier.indexOf(JAVA) === 0) return specifier;
        if (!isRelative(specifier)) return has.call(builtins, specifier) ? BUILTIN + specifier : null;
        var base = projectPath(specifier, from);
        var candidates = [base, base + '.js', base + '.json', base + '/index.js'];
        for (var i = 0; i < candidates.length; i++) {
            if (has.call(sources, candidates[i])) return candidates[i];
        }
        throw new Error("Cannot find module '" + specifier + "' from '" + from + "'");
    }

    function javaValue(specifier) {
        var name = specifier.substring(JAVA.length);
        if (!JAVA_NAME.test(name)) throw new Error("Cannot find module '" + specifier + "': not a Java package or class name");
        if (!java) throw new Error("Cannot find module '" + specifier + "': this engine does not run Java");
        var last = name.substring(name.lastIndexOf('.') + 1);
        var value;
        try {
            value = java(name, /^[A-Z]/.test(last));
        } catch (e) {
            throw new Error("Cannot find module '" + specifier + "': " + (e && e.message ? e.message : e));
        }
        if (value === null) throw new Error("Cannot find module '" + specifier + "': Java classes are off for this project");
        if (value === undefined) throw new Error("Cannot find module '" + specifier + "': no such Java package or class");
        return value;
    }

    function load(path) {
        var cached = cache[path];
        if (cached) return cached.exports;
        var module = { id: path, filename: path, exports: {}, loaded: false };
        cache[path] = module;
        try {
            if (path.indexOf(JAVA) === 0) {
                module.exports = javaValue(path);
            } else if (path.indexOf(BUILTIN) === 0) {
                compile(path, builtins[path.substring(BUILTIN.length)]).call(module.exports, module.exports, makeRequire(path), module, path, '');
            } else if (/\.json$/.test(path)) {
                module.exports = JSON.parse(sources[path]);
            } else {
                var source = sources[path];
                var directive = directives(path, source);
                if (kindOf(path, source) === 'esm') {
                    if (esm) throw new Error("'" + path + "' is an ES module: load it with import, not require");
                    if (!directive.esm) {
                        throw new Error(path + ': this engine runs CommonJS only. Use require instead of import, or put #!support esm on the first line to convert the file');
                    }
                    source = toCommonJs(path, source);
                } else if (directive.line) {
                    source = replaceFirstLine(source, '');
                }
                source = rewriteDynamicImports(source, 'require.dynamicImport');
                var require = makeRequire(path);
                compile(path, source).call(module.exports, module.exports, require, module, path, dirname(path));
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
        require.esm = interop;
        require.dynamicImport = function (specifier) { return dynamicImport(specifier, from); };
        return require;
    }

    // ---- ES modules, for engines that run them: each import becomes a module source the engine compiles.

    var REGISTRY = '__msgbot_modules';
    var registry = Object.create(null);
    if (esm) {
        Object.defineProperty(globalThis, REGISTRY, { value: registry, configurable: true });
        Object.defineProperty(globalThis, '__msgbot_require', { value: makeRequire, configurable: true });
        Object.defineProperty(globalThis, '__msgbot_import_from', {
            value: function (from) { return function (specifier) { return dynamicImport(specifier, from); }; },
            configurable: true
        });
    }

    // A module that hands over a value: default is the value, named exports are its own keys and [extra].
    function valueModule(name, value, extra, ownKeys) {
        registry[name] = value;
        var names = [];
        if (ownKeys && value !== null && (typeof value === 'object' || typeof value === 'function')) {
            if (isConverted(value)) {
                for (var own in value) if (has.call(value, own)) names.push(own);
            } else {
                try {
                    var keys = Object.keys(value);
                    for (var i = 0; i < keys.length; i++) names.push(keys[i]);
                } catch (ignored) {
                    // A Java package or other host object without keys: only the names asked for.
                }
            }
        }
        for (var e = 0; e < extra.length; e++) if (names.indexOf(extra[e]) < 0) names.push(extra[e]);
        var lines = ['const m = globalThis[' + quote(REGISTRY) + '][' + quote(name) + '];'];
        lines.push('export default ' + (isConverted(value) ? 'm["default"]' : 'm') + ';');
        for (var n = 0; n < names.length; n++) {
            var key = names[n];
            if (key === 'default' || key === '__esModule' || !IDENTIFIER.test(key)) continue;
            lines.push('export const ' + key + ' = m[' + quote(key) + '];');
        }
        return lines.join('\n');
    }

    // The engine's resolver calls this for every import: [specifier] as written, [from] the project path
    // of the importing module. Returns { name, source, esm } where name identifies the module.
    function moduleFor(specifier, from) {
        specifier = String(specifier);
        if (specifier.indexOf(JAVA) === 0) {
            var javaName = '/' + specifier;
            return { name: javaName, source: valueModule(javaName, javaValue(specifier), javaImportNames(specifier), false), esm: false };
        }
        if (!isRelative(specifier)) {
            var other = '/@' + specifier;
            var value;
            if (has.call(builtins, specifier)) value = load(BUILTIN + specifier);
            else if (fallback) value = fallback(specifier);
            else throw new Error("Cannot find module '" + specifier + "' imported from '" + from + "'");
            return { name: other, source: valueModule(other, value, [], true), esm: false };
        }
        // Like Node, an import names the file exactly: no extension is guessed.
        var path = projectPath(specifier, from);
        if (!has.call(sources, path)) throw new Error("Cannot find module '" + specifier + "' imported from '" + from + "'");
        return sourceModule(path);
    }

    function sourceModule(path) {
        if (!has.call(sources, path)) throw new Error("Cannot find module '" + path + "'");
        var source = sources[path];
        var kind = kindOf(path, source);
        if (kind === 'esm') {
            var directive = directives(path, source);
            if (directive.cjs) source = replaceFirstLine(source, 'const require = globalThis.__msgbot_require(' + quote(path) + ');');
            else if (directive.line) source = replaceFirstLine(source, '');
            source = rewriteDynamicImports(source, 'globalThis.__msgbot_import_from(' + quote(path) + ')');
            return { name: '/' + path, source: source, esm: true };
        }
        return { name: '/' + path, source: valueModule('/' + path, load(path), [], true), esm: false };
    }

    // The entry file as a global script, for an engine that runs it that way (not as an ES module).
    function script(path) {
        var source = sources[path];
        var directive = directives(path, source);
        if (kindOf(path, source) === 'esm') {
            if (esm) throw new Error("'" + path + "' is an ES module: run it through makeRequire.esm.entry");
            if (!directive.esm) {
                throw new Error(path + ': this engine runs CommonJS only. Use require instead of import, or put #!support esm on the first line to convert the file');
            }
            return toCommonJs(path, source, true);
        }
        return rewriteDynamicImports(directive.line ? replaceFirstLine(source, '') : source, 'require.dynamicImport');
    }

    // The name moduleFor would give, without loading anything: what an engine caches modules by.
    function nameOf(specifier, from) {
        specifier = String(specifier);
        if (specifier.indexOf(JAVA) === 0) return '/' + specifier;
        if (!isRelative(specifier)) return '/@' + specifier;
        return '/' + projectPath(specifier, from);
    }

    makeRequire.script = script;
    makeRequire.esm = {
        kindOf: function (path) { return kindOf(path, sources[path]); },
        nameOf: nameOf,
        setImporter: function (f) { importer = f; },
        moduleFor: moduleFor,
        entry: sourceModule
    };
    return makeRequire;
})
