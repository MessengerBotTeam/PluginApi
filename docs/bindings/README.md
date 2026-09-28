# 언어 바인딩

바인딩은 한 언어의 스크립트가 API를 보는 방식이다. 호스트는 어떤 바인딩도 모른다. 호스트와 엔진이 주고받는 것은 언어와 무관하다.

| 방향 | 무엇 |
|---|---|
| 호스트 → 엔진 | `LoadRequest`(API 스키마, 프로필 소스, 진입 파일, 프로젝트 소스, 옵션), `ScriptEvent`(한정된 이름, 스키마에 맞는 페이로드) |
| 엔진 → 호스트 | `HostBridge.call`/`callAsync`(한정된 함수 이름, 이름 있는 인자), `EngineContext.reportError` |

스크립트가 무엇을 보는지는 그 사이에서 바인딩이 정한다.

- **JavaScript**: 앱이 직접 실행하는 언어(Rhino)라서 바인딩이 계약에 들어 있다. 내장 엔진이든 플러그인 엔진이든 같은 프로필(앱의 API2와 Legacy, 플러그인이 내는 프로필)을 돌려야 하기 때문이다. [명세](javascript.md), `JavaScriptBinding`(공유 로더와 키트), `JavaScriptEngineConformance`(TCK)가 있다.
- **그 밖의 언어**: 그 언어를 추가하는 플러그인이 정한다. 계약에는 특정 언어가 들어 있지 않다.

## 새 언어를 추가하는 플러그인이 할 일

APK 하나에 엔진과 그 언어의 프로필을 최소 하나 넣는다. 프로필이 없으면 그 언어로 새 프로젝트를 만들 수 없다. 스크립트가 `__api`를 직접 쓰게 하려면 shim이 빈 프로필을 넣으면 된다.

```xml
<msgbot-plugin protocol="4">
    <engine id="luaj" label="@string/luaj">
        <language name="lua" label="@string/lua" extension="lua" />
    </engine>
    <profile id="lua-api" language="lua" label="@string/lua_api"
        shim="@raw/lua_api" template="@raw/lua_starter" requires="bot log" />
</msgbot-plugin>
```

```kotlin
class LuaEngine(private val context: EngineContext) : ScriptEngine {
    override fun load(request: LoadRequest) { /* 바인딩 → request.profile 실행 → request.entry 실행 */ }
    override fun dispatch(event: ScriptEvent) { /* 스크립트의 이벤트 진입점 호출 */ }
    override fun eval(source: String): Value = TODO()
    override fun close() {}
}

class LuaPluginService : PluginService() {
    override val engines = mapOf("luaj" to ScriptEngineFactory(::LuaEngine))
}
```

그리고 그 언어의 바인딩을 정해 문서로 공개한다. 그 언어로 새 API(프로필)를 만드는 다른 개발자는 이 문서를 보고 만든다.

1. **값**: `Value`의 여덟 가지 모양(`null`, 불리언, 64비트 정수, 실수, 문자열, 바이트, 배열, 객체)을 그 언어의 무엇으로 바꾸는지. 넘길 수 없는 값을 스크립트가 넘기면 어떻게 되는지.
2. **호출**: 스크립트가 호스트 함수를 부르는 방법. 관례로 `__api`, `__host_call`, `__host_call_async`, `__dispatch`라는 이름을 쓴다(`Binding`). 호스트에는 항상 이름 있는 인자로 보낸다. 위치 인자에는 `__api`의 매개변수 순서대로 이름을 붙인다.
3. **오류**: 호스트의 `CallResult.Err`(코드 `unknown_function`, `bad_args`, `unavailable`, `failed`와 메시지)가 스크립트에서 어떻게 보이는지. 로드 실패는 파일 이름을 담은 `EngineException`으로 알린다.
4. **비동기**: `callAsync`의 결과를 그 언어다운 방식(콜백, 코루틴, Future)으로 돌려주는 방법.
5. **이벤트**: 엔진이 `dispatch`를 받으면 스크립트의 진입점을 부른다. 스크립트가 `sys.listen`을 부르면 그 뒤로는 들은 이벤트만 온다. 한 번도 부르지 않으면 모든 이벤트가 온다.
6. **실행 순서와 모듈 로딩**: 바인딩 → 프로필 → 진입 파일 순서로 실행한다. `LoadRequest.sources`에 프로젝트 폴더의 그 언어 확장자 파일과 `.json` 파일이 있다.
7. **키트(선택)**: 프로필 작성자가 매번 반복할 일(모듈 만들기, 인자 이름 붙이기, 이벤트 구독, 오류 보고)을 모은 모듈. JavaScript 키트(`javascript/kit.js`)를 참고한다.

같은 언어의 두 번째 엔진은 먼저 공개된 바인딩을 따라야 기존 프로필이 그대로 돈다. 여러 엔진이 한 바인딩을 공유하게 되면 이 폴더에 명세를 올려 계약의 일부로 삼을 수 있다.

## 호스트가 이미 해 주는 것

- **검사**: 호출 인자, 반환 값, 이벤트 페이로드를 스키마로 검사한다. 스크립트의 실수는 `bad_args`로 돌아오므로 엔진은 검사하지 않아도 된다.
- **조합**: 어떤 제공자가 무엇을 내는지 엔진은 모른다. `__api`를 그대로 넘기면 나중에 설치된 제공자의 모듈도 스크립트에 보인다.
- **IPC**: `PluginService.engines`에 팩토리를 적으면 세션, 스레드, Binder, 큰 바이트 전송이 처리된다.
- **스레드**: 엔진의 모든 메서드, 타이머(`context.scheduler`), 비동기 응답이 엔진 스레드 하나에서 실행된다.
- **테스트**: `PluginApi-tck`의 `EngineHarness`로 호스트 없이 엔진을 호스트와 같은 방식으로 돌리고, 호스트 함수를 흉내 낸다. `LoopbackTransport.pair()`로 원격 경계까지 확인할 수 있다.
