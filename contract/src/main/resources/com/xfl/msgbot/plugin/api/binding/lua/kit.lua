--[[
MessengerBotR Lua binding: the profile kit, require("msgbot").
SPDX-License-Identifier: AGPL-3.0-or-later

What every Lua profile needs that is not a matter of taste, so a profile keeps only the shape of
its own API:

  api        every module in __api. api.weather.forecast("Seoul") passes positional arguments
             under the schema's names; one plain table passes them by name; .async(callback, ...)
             answers later with callback(value, err)
  events     listeners by qualified name; the host is told what to deliver (sys.listen)
  on_error   where a failing listener goes; the project log by default
  list(t)    marks a table as a list, so an empty one is not taken for a map

Requiring it installs __dispatch. Plain Lua 5.1+, nothing engine-specific.
]]

local specs = {}
for _, spec in ipairs(__api) do specs[spec.namespace] = spec end

local function find(items, name)
    for _, item in ipairs(items) do
        if item.name == name then return item end
    end
end

local function split(qualified)
    return tostring(qualified):match("^([^.]+)%.(.+)$")
end

local function function_spec(qualified)
    local namespace, name = split(qualified)
    local spec = namespace and specs[namespace]
    return spec and find(spec.functions, name)
end

local function event_spec(qualified)
    local namespace, name = split(qualified)
    local spec = namespace and specs[namespace]
    return spec and find(spec.events, name)
end

-- Whether the first parameter itself takes a table, so a lone table is that value, not the names.
local function takes_table(fn)
    local first = fn.params[1] and fn.params[1].type:gsub("%?$", "")
    return first == "any" or (first and (first:find("^list<") or first:find("^map<") or first:find("^{"))) ~= nil
end

local function args_for(qualified, fn, ...)
    local count = select("#", ...)
    local first = ...
    if count == 1 and type(first) == "table" and getmetatable(first) == nil and #fn.params > 0 and not takes_table(fn) then
        return first
    end
    if count > #fn.params then
        error(qualified .. " takes at most " .. #fn.params .. " argument(s), got " .. count, 3)
    end
    local args = {}
    for i = 1, count do
        local value = select(i, ...)
        if value ~= nil then args[fn.params[i].name] = value end
    end
    return args
end

local function make_module(spec)
    local module = {}
    for _, fn in ipairs(spec.functions) do
        local qualified = spec.namespace .. "." .. fn.name
        module[fn.name] = setmetatable({
            async = function(callback, ...)
                return __host_call_async(qualified, args_for(qualified, fn, ...), callback)
            end,
        }, {
            __call = function(_, ...)
                return __host_call(qualified, args_for(qualified, fn, ...))
            end,
        })
    end
    return module
end

local api = {}
for namespace, spec in pairs(specs) do api[namespace] = make_module(spec) end

local listeners = {}
local can_listen = function_spec("sys.listen") ~= nil
local LIST = { __msgbot_list = true } -- engines read this marker, so an empty list stays a list

local kit = { api = api }

function kit.list(t)
    return setmetatable(t or {}, LIST)
end

local function tell_host()
    if not can_listen then return end
    local events = kit.list()
    for name, fns in pairs(listeners) do
        if #fns > 0 then events[#events + 1] = name end
    end
    table.sort(events)
    __host_call("sys.listen", { events = events })
end

function kit.is_available(qualified)
    return function_spec(qualified) ~= nil or event_spec(qualified) ~= nil
end

function kit.spec(namespace)
    return specs[namespace]
end

kit.events = {}

function kit.events.on(name, fn)
    if not event_spec(name) then
        error("Event '" .. tostring(name) .. "' is never delivered to this project; check its providers.", 2)
    end
    local fns = listeners[name] or {}
    listeners[name] = fns
    fns[#fns + 1] = fn
    if #fns == 1 then tell_host() end
    return function() kit.events.off(name, fn) end
end

function kit.events.off(name, fn)
    local fns = listeners[name]
    if not fns or #fns == 0 then return end
    for i = #fns, 1, -1 do
        if fn == nil or fns[i] == fn then table.remove(fns, i) end
    end
    if #fns == 0 then tell_host() end
end

function kit.events.count(name)
    return #(listeners[name] or {})
end

function kit.on_error(err, event)
    local message = "listener of " .. event .. " failed: " .. tostring(err)
    if function_spec("log.write") then
        __host_call("log.write", { level = "error", message = message, tag = "profile" })
    else
        error(err, 0)
    end
end

function kit.dispatch(name, payload)
    local fns = {}
    for i, fn in ipairs(listeners[name] or {}) do fns[i] = fn end
    for _, fn in ipairs(fns) do
        local ok, err = pcall(fn, payload)
        if not ok then kit.on_error(err, name) end
    end
end

-- Nothing is delivered until someone listens; a profile that never requires the kit gets everything.
tell_host()
function __dispatch(name, payload) kit.dispatch(name, payload) end

return kit
