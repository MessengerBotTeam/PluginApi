# Lua 바인딩

Lua 엔진이 스크립트 전역에 만들어야 하는 것과, `Value`를 Lua 값으로 바꾸는 규칙이다. [JavaScript 바인딩](javascript.md)과 구조가 같으며, 차이는 Lua에 맞춘 부분뿐이다. Lua 프로필은 이 명세에만 의존하므로 이를 지키는 Lua 엔진이라면 어느 것에서나 실행된다.

## 전역

| 이름 | 누가 만드나 | 뜻 |
|---|---|---|
| `__api` | 엔진 | 모듈 명세 배열. 프로필보다 먼저 바인딩한다. 모양은 JavaScript와 같다. |
| `__host_call(name, args)` | 엔진 | 동기 호출. `args`는 이름 있는 인자를 담은 테이블이며 결과 값을 반환한다. |
| `__host_call_async(name, args, callback)` | 엔진 | 비동기 호출. 엔진 스레드에서 `callback(value, err)`를 호출한다. |
| `__dispatch(name, payload)` | 프로필 | 이벤트 진입점. |
| `require(name)` | 엔진 | `require("lib.util")`은 프로젝트의 `lib/util.lua`를 읽는다. `require("msgbot")`은 키트다. |
| `setTimeout` / `setInterval` / `clearTimeout` / `clearInterval` | 엔진 | 엔진 스레드에서 실행하며, 엔진을 닫으면 멈춘다. |

키트 소스는 `LuaBinding.KIT`에 있다. 엔진은 이를 `package.preload["msgbot"]`로 제공한다.

## 값

| `Value` | Lua → 호스트 | 호스트 → Lua |
|---|---|---|
| `VNull` | `nil` | `nil` |
| `VBool` | `boolean` | `boolean` |
| `VInt` | 정수 값인 `number` | 64비트 정수가 있는 Lua면 정수. 없으면 ±(2^53−1) 안에서는 `number`, 그 밖은 문자열 |
| `VDouble` | 정수가 아닌 `number` | `number` |
| `VString` | `string` | `string` |
| `VBytes` | 없음(바이트는 문자열로 받는다) | 바이트 문자열 |
| `VArray` | 1부터 빈틈없는 정수 키만 가진 테이블, 또는 `msgbot.list(t)`로 표시한 테이블 | 시퀀스 테이블 |
| `VObject` | 그 밖의 테이블(키는 문자열) | 문자열 키 테이블 |

빈 테이블은 `VObject`다. 빈 목록을 보내려면 `msgbot.list()`를 쓴다. 이 함수는 메타테이블에 `__msgbot_list = true`를 붙이며, 엔진은 이 표시를 읽는다. 함수, userdata, 코루틴은 넘길 수 없고, 넘기면 호출한 자리에서 오류가 난다.

## 오류

호스트가 `CallResult.Err`로 답하면 `__host_call`은 테이블 `{ code = "bad_args", message = "weather.forecast: ..." }`을 `error`로 던진다. 이 테이블은 `tostring` 결과가 `"code: message"`가 되도록 메타테이블을 가진다. 그래서 `pcall`로 받은 오류에서 `err.code`로 분기할 수 있다. `__host_call_async`는 같은 테이블을 `callback`의 두 번째 인자로 넘긴다.

스크립트를 로드하지 못하면 파일 이름을 담은 `EngineException`을 던진다. 아무도 기다리지 않는 작업(타이머 콜백 등)에서 난 오류는 `EngineContext.reportError`로 보고한다.

## 프로필 키트: `require("msgbot")`

JavaScript 키트와 같은 역할이며, 이름만 Lua 관례를 따른다.

```lua
local msgbot = require("msgbot")
local api, events = msgbot.api, msgbot.events

local forecast = api.weather.forecast("Seoul")                    -- 위치 인자
api.weather.forecast.async(function(value, err) end, { city = "Busan" })  -- 이름 인자, 콜백
events.on("bot.message", function(m) api.bot.reply(m.replyToken, "hi") end)
```

`msgbot.is_available(name)`, `msgbot.spec(ns)`, `msgbot.events.on/off/count`, `msgbot.on_error`, `msgbot.list(t)`를 제공한다. 키트를 불러오면 `__dispatch`가 설치되고, 리스너가 있는 이벤트만 전달되도록 `sys.listen`이 호출된다.

`PluginApi/contract/src/test/resources/profiles/minimal_api2.lua`는 이 키트로 만든 20줄짜리 API2 프로필이다. `LuaProfileContractTest`는 이 프로필을 실제 LuaJ에서 IPC를 거쳐 실행해 검증한다.
