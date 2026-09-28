# PluginApi

MessengerBotR의 플러그인 계약이다. 현재 개발 버전은 `3.0.0-SNAPSHOT`이고 프로토콜 버전은 `4`다.

| 모듈 | 내용 |
|---|---|
| `contract` | 값, API 스키마, 표준 모듈, 엔진·제공자 SPI, 대칭 RPC. Android에 의존하지 않는다. |
| `android` | `PluginService`, Binder 세션, 공유 메모리 전송 |
| `tck` | 엔진이 자기 테스트에서 상속하는 적합성 테스트(`JavaScriptEngineConformance`) |

## 구조

```
                 API 스키마 (ModuleSpec)
  bot.reply(token: string, text: string) -> bool
  event bot.message {room: string, author: {...}, replyToken: string?}
        ▲ 선언                               ▼ 소비
  호스트 모듈 · 제공자(Provider)       엔진(언어 바인딩) → 프로필(__api로 API를 만드는 얇은 층)
  (호스트가 경계에서 인자와 결과를 검사)
```

- **API는 스키마다.** 모든 함수와 이벤트는 `ModuleSpec`에 이름, 매개변수, 타입, 버전과 함께 선언된다. 호스트는 호출 인자, 반환 값, 이벤트 페이로드를 이 스키마로 검사한다. 엔진은 스키마를 `__api` 데이터로 프로필에 넘기고, 도구는 같은 스키마로 타입 정의를 만든다.
- **네임스페이스 하나에는 주인이 하나다.** 네임스페이스는 소문자 한 단어라서 서로 겹칠 수 없다. `project`, `log`, `file`, `db`, `http`, `device`는 호스트가, `bot`은 프로젝트의 메시지 소스가, 나머지는 각 확장이 가진다.
- **인자는 이름으로 전달한다.** 선택 인자를 추가해도 기존 호출이 깨지지 않고, 키워드 인자가 있는 언어에서도 자연스럽게 쓸 수 있다.
- **언어별 규칙은 바인딩 명세에 한 번만 적는다.** `Value`와 언어 값 사이의 변환, 오류, 타이머, Promise, 모듈 로딩을 [docs/bindings/javascript.md](docs/bindings/javascript.md)에 정의한다. 같은 언어의 엔진은 모두 TCK로 이 명세를 검사하므로, 프로필은 특정 엔진이 아니라 언어에만 의존한다.
- **RPC는 하나다.** 엔진과 제공자는 같은 양방향 요청·응답·알림 채널(`RpcPeer`) 위의 역할이다. 원격 엔진(`RemoteScriptEngine`)과 원격 제공자(`RemoteProvider`)도 로컬 구현과 같은 인터페이스를 구현한다.
- **플러그인이 보낸 프레임은 믿지 않는다.** `ValueCodec`는 모든 길이와 중첩 깊이를 검사한다. 64KB가 넘는 바이트는 공유 메모리로 옮긴다.

## 플러그인 만들기

APK는 서비스 하나와 매니페스트 XML 하나로 자신이 제공하는 모든 것을 알린다. XML 형식은 `PluginManifestSchema`에 있다.

```kotlin
class MyPluginService : PluginService() {
    override val engines = mapOf("luaj" to ScriptEngineFactory(::LuaEngine))
    override val providers = mapOf("weather" to ::WeatherProvider)
}
```

제공자는 모듈을 선언하는 코드와 구현하는 코드를 한곳에 쓴다.

```kotlin
class WeatherProvider : Provider {
    override val module = provide("weather") {
        function("forecast", returns = Type.STRING) {
            param("city", Type.STRING)
            optional("days", Type.INT)
            handle { call -> "Sunny in ${call.args.string("city")}" }
        }
        event("alert") { field("text", Type.STRING) }
    }
}
```

메시지 소스는 `implement(StandardApi.Bot) { handle("reply") { ... }; emits("message") }`처럼 표준 `bot` 모듈 중 지원하는 부분만 구현한다.

자세한 작성 안내는 MessengerBotR의 `docs/plugin-authoring.md`에 있다.

```sh
./gradlew :contract:test :tck:build :android:testDebugUnitTest :android:assembleRelease
./gradlew publishToMavenLocal
```

MessengerBotR은 이 저장소를 Git 서브모듈로 고정하고 Gradle 복합 빌드로 소스를 직접 사용한다.
