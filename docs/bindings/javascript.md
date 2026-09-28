# JavaScript 바인딩

JavaScript 엔진이 스크립트 전역에 만들어야 하는 것과, `Value`를 JavaScript 값으로 바꾸는 규칙이다. JavaScript는 앱이 직접 실행하는 언어라서 이 바인딩은 계약의 일부다. 엔진은 이 규칙을 한 번 구현하면 된다. 이 규칙을 지키는 엔진에서는 모든 JavaScript 프로필이 수정 없이 실행된다. 앱의 API2와 Legacy도, 플러그인이 내는 프로필도 마찬가지다. 다른 언어의 바인딩은 그 언어를 추가하는 플러그인이 정한다([새 언어 추가](README.md)).

규칙은 `PluginApi-tck`의 `JavaScriptEngineConformance`가 테스트로 검사한다. 엔진 저장소의 테스트 소스에서 이 클래스를 상속하면 된다.

```kotlin
class MyEngineConformanceTest : JavaScriptEngineConformance() {
    override fun createEngine(context: EngineContext) = MyEngine(context)
}
```

## 전역

| 이름 | 누가 만드나 | 뜻 |
|---|---|---|
| `__api` | 엔진 | 프로젝트가 쓸 수 있는 모듈 명세 배열. 프로필보다 먼저 바인딩한다. |
| `__host_call(name, args)` | 엔진 | 동기 호출. `args`는 이름 있는 인자를 담은 객체이며 결과 값을 반환한다. |
| `__host_call_async(name, args)` | 엔진 | 비동기 호출. `Promise`를 반환하고 `then` 콜백은 엔진 스레드에서 실행된다. |
| `__dispatch(name, payload)` | 프로필 | 이벤트 진입점. 정의하지 않았으면 이벤트는 무시된다. |
| `require(specifier)` | 엔진 | 프로젝트 소스를 CommonJS 모듈로 읽는다. `require('msgbot')`는 프로필 키트를 준다. |
| `globalThis` | 엔진 | 전역 객체. 키트와 프로필이 전역을 다룰 때 쓴다. |
| `setTimeout` / `setInterval` / `clearTimeout` / `clearInterval` | 엔진 | 엔진 스레드에서 실행하며, 엔진을 닫으면 멈춘다. |

`__api`의 각 원소는 `ModuleSpec.toValue()` 모양이다.

```js
{ namespace: 'weather', version: 1, doc: '...',
  functions: [{ name: 'forecast', params: [{ name: 'city', type: 'string', doc: '' }], returns: 'string', doc: '' }],
  events:    [{ name: 'alert', fields: [{ name: 'text', type: 'string', doc: '' }], doc: '' }] }
```

프로필은 이 데이터로 API를 만든다. 위치 인자는 `params` 순서대로 이름을 붙여 `__host_call('weather.forecast', { city: 'Seoul' })`로 보낸다. 새 제공자를 설치해도 엔진과 프로필을 고칠 필요가 없다. 이런 공통 작업은 아래의 프로필 키트가 대신한다.

## 프로필 키트: `require('msgbot')`

모든 JavaScript 프로필이 필요로 하지만 취향과는 상관없는 부분을 모았다. 프로필은 자기 API의 모양만 작성하면 된다. 공유 로더(`JavaScriptBinding.MODULE_LOADER`)가 이 키트를 기본 모듈로 제공하므로, 그 로더를 쓰는 엔진은 따로 할 일이 없다.

| 멤버 | 뜻 |
|---|---|
| `api.<ns>.<fn>(...)` | `__api`의 모든 함수. 위치 인자는 스키마 이름으로 전달한다. 평범한 객체 하나를 넘기면 이름 인자로 쓴다(첫 매개변수가 객체 타입이면 제외). |
| `api.<ns>.<fn>.async(...)` | 같은 호출을 `Promise`로 받는다. |
| `isAvailable(name)`, `spec(ns)` | 이 프로젝트에 해당 함수, 이벤트, 모듈이 있는지 확인한다. |
| `events.on(name, fn)` / `once` / `off` / `count` | 한정된 이름(`bot.message`)으로 이벤트를 듣는다. 전달될 수 없는 이벤트면 바로 오류가 난다. |
| `onError(error, event)` | 리스너가 실패했을 때 호출된다. 기본 동작은 프로젝트 로그에 기록하는 것이며, 교체할 수 있다. |
| `describe(error)`, `base64(bytes)` | 엔진마다 다른 오류 형식을 하나의 문자열로 만들고, 바이트를 base64로 바꾼다. |

키트를 불러오면 `__dispatch`가 설치되고 `sys.listen`이 호출된다. 그 뒤로는 리스너가 있는 이벤트만 엔진에 전달되므로, 듣지 않는 이벤트 때문에 원격 엔진과 IPC가 오가지 않는다.

```js
// 다른 개발자가 플러그인으로 내는 새 API의 전부: 이벤트마다 함수 하나, Promise 중심
const { api, events } = require('msgbot');
globalThis.onMessage = (handler) => events.on('bot.message', (m) => handler({
  text: m.content, room: m.room, sender: m.author.name,
  reply: (text) => api.bot.reply.async({ text, token: m.replyToken, room: m.room, channelId: m.channelId }),   // Promise 중심
}));
```

## 값

| `Value` | JavaScript → 호스트 | 호스트 → JavaScript |
|---|---|---|
| `VNull` | `null`, `undefined` | `null` |
| `VBool` | `boolean` | `boolean` |
| `VInt` | ±(2^53−1) 안의 정수 `number`, 64비트 안의 `BigInt` | ±(2^53−1) 안이면 `number`, 밖이면 `BigInt` |
| `VDouble` | 그 밖의 `number` (소수, `NaN`, `Infinity`, 안전 범위 밖의 정수) | `number` |
| `VString` | `string` | `string` |
| `VBytes` | `Uint8Array` | `Uint8Array` |
| `VArray` | `Array` (`undefined` 원소는 `null`) | `Array` |
| `VObject` | 평범한 객체 (`undefined`인 속성은 뺀다) | 평범한 객체 |

함수, 심볼, 클래스 인스턴스 같은 다른 값은 넘길 수 없다. 이런 값을 넘기면 `__host_call`과 `__host_call_async` 모두 호출한 자리에서 `TypeError`를 던지며, 호스트에는 아무것도 전달되지 않는다.

## 오류

호스트가 `CallResult.Err`로 답하면 `__host_call`은 `Error`를 던진다. `__host_call_async`는 같은 `Error`로 reject한다. 이 `Error`의 `code`에는 `bad_args`나 `unknown_function` 같은 오류 코드가, `message`에는 호스트의 설명이 들어간다.

스크립트를 로드하지 못하면 `EngineException`을 던진다. 이때 메시지에 파일 이름을 넣는다(예: `main.js:3: Unexpected token`). 아무도 기다리지 않는 작업에서 난 오류는 `EngineContext.reportError`로 보고한다. 타이머 콜백이나 처리되지 않은 Promise 거부가 여기에 해당한다.

## 이벤트 구독

호스트는 `sys.listen({events: [...]})`로 받은 이벤트만 엔진에 전달한다. 한 번도 호출하지 않았다면 모든 이벤트를 전달한다. 키트를 쓰면 리스너를 등록하거나 해제할 때 알아서 호출된다.

## 실행 순서

1. `__api`, 호스트 호출 함수, 타이머, `require`, `globalThis`를 둔다.
2. 프로필(`LoadRequest.profile`)을 실행한다. 프로필은 `require('msgbot')`를 쓸 수 있다.
3. 진입 파일(`LoadRequest.entry`)을 전역 스크립트로 실행한다. 여기서 선언한 `function response`는 전역에 남는다.

`require`는 `./`, `../`, `/`로 시작하는 경로를 프로젝트 소스에서 찾는다. 찾는 순서는 경로 그대로, `.js`, `.json`, `/index.js`다. `msgbot`은 키트다. 그 밖의 이름은 런타임 고유의 `require`에 넘긴다. Node의 내장 모듈이 그런 예이며, 고유 `require`가 없는 런타임에서는 오류가 난다. `JavaScriptBinding.MODULE_LOADER`에 이 규칙을 구현한 로더가 있으므로 엔진은 파일을 함수로 컴파일하는 방법만 제공하면 된다.

모든 JavaScript 코드는 엔진 스레드 하나에서 실행한다. 로드, 이벤트, 타이머, 비동기 응답 같은 작업이 하나 끝날 때마다 Promise 마이크로태스크를 처리한다.
