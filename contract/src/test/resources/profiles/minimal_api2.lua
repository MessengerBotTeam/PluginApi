-- A contract fixture, not a complete API2: how short a Lua profile gets on the kit. It knows
-- nothing about which providers exist; everything in __api is reachable through Api.
local msgbot = require("msgbot")
Api = msgbot.api

local bot = {}
function bot.on(event, callback)
    local qualified = event:find("%.") and event or ("bot." .. event)
    msgbot.events.on(qualified, function(payload)
        if qualified == "bot.message" then
            payload.reply = function(text) return Api.bot.reply(payload.replyToken, text) end
        end
        callback(payload)
    end)
end
BotManager = { getCurrentBot = function() return bot end }
