-- Executable contract fixture, not a complete API2 implementation.
local caps = {}
for _, capability in ipairs(__caps) do caps[capability] = true end
local listeners = {}
local bot = {}
function bot.on(event, callback)
    assert(caps['event.' .. event], 'undeclared event: ' .. event)
    listeners[event] = callback
end
BotManager = { getCurrentBot = function() return bot end }

local modules = {}
for _, capability in ipairs(__caps) do
    local namespace, method = capability:match('^(.*)%.([^%.]+)$')
    if namespace and namespace ~= 'event' then
        modules[namespace] = modules[namespace] or {}
        local qualified = capability
        modules[namespace][method] = function(...) return __host_call(qualified, {...}) end
    end
end
ExtensionApi = { module = function(namespace) return modules[namespace] end }

function __dispatch(event)
    if event.type == 'message' then
        event.reply = function(text) return __host_call('bot.reply', {event.replyToken, text}) end
    end
    local callback = listeners[event.type]
    if callback then callback(event) end
end
