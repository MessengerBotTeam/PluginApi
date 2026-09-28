-- A contract fixture, not a complete API2: shows that a profile in any language can build its
-- facade from __api alone, with no knowledge of which providers exist.
local modules = {}
for _, spec in ipairs(__api) do
    local module = { events = {} }
    for _, fn in ipairs(spec.functions) do
        local qualified = spec.namespace .. '.' .. fn.name
        local params = fn.params
        -- Positional arguments take the schema's parameter names, in order.
        module[fn.name] = function(...)
            local values, args = { ... }, {}
            for i, param in ipairs(params) do args[param.name] = values[i] end
            return __host_call(qualified, args)
        end
    end
    for _, event in ipairs(spec.events) do module.events[event.name] = true end
    modules[spec.namespace] = module
end
Api = modules

local listeners = {}
local bot = {}
function bot.on(event, callback)
    local qualified = event:find('%.') and event or ('bot.' .. event)
    local namespace, name = qualified:match('^(%w+)%.(%w+)$')
    assert(modules[namespace] and modules[namespace].events[name], 'undeclared event: ' .. event)
    listeners[qualified] = callback
end
BotManager = { getCurrentBot = function() return bot end }

function __dispatch(name, payload)
    if name == 'bot.message' then
        payload.reply = function(text) return Api.bot.reply(payload.replyToken, text) end
    end
    local callback = listeners[name]
    if callback then callback(payload) end
end
