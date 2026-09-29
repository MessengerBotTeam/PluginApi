# PluginApi

MessengerBotR의 플러그인 계약이다. 현재 개발 버전은 `0.1.0-SNAPSHOT`이고 프로토콜 버전은 `0`이다. 아직 공개 전이라 무엇이든 바뀔 수 있다.

앱에는 기본값만 들어 있다. JavaScript 엔진(Rhino), API2와 Legacy 프로필, 알림에서 메시지를 읽는 제공자다. 나머지는 누구나 이 계약으로 플러그인을 만들어 더한다. 새 언어(엔진), 그 언어나 기존 언어의 새 API(프로필), 새 수신·전송 방식과 기능(제공자)이 여기에 해당한다. 계약은 특정 플러그인의 언어나 API를 담지 않는다.

| 모듈 | 내용 |
|---|---|
| `contract` | 값, API 스키마, 표준 모듈, 엔진·제공자 SPI, 대칭 RPC. Android에 의존하지 않는다. |
| `android` | `PluginService`, Binder 세션, 공유 메모리 전송 |
| `tck` | 엔진과 제공자를 호스트 없이 돌려 보는 `EngineHarness`, `ProviderHarness`. JavaScript 엔진의 적합성 테스트(`JavaScriptEngineConformance`)와 제공자의 적합성 테스트(`ProviderConformance`) |

## 구조

```
                 API 스키마 (ModuleSpec)
  bot.reply(text: string, token: string?, room: string?, channelId: string?, ...) -> bool
  event bot.message {room: string, author: {...}, extra: map<any>?}
        ▲ 선언                                ▼ __api 데이터로 소비
  호스트 모듈 · 제공자(Provider) 여럿      엔진(언어 바인딩) → 프로필(키트 위의 얇은 층)
  (호스트가 멤버 단위로 조합하고, 인자·결과·이벤트를 검사)
```

- **API는 스키마다.** 모든 함수와 이벤트는 `ModuleSpec`에 이름, 매개변수, 타입, 버전과 함께 선언된다. 호스트는 이 스키마로 호출 인자, 반환 값, 이벤트 페이로드를 검사한다. 엔진은 스키마를 `__api` 데이터로 프로필에 넘긴다.
- **제공자는 한 종류다.** 메시지를 받는 쪽, 보내는 쪽, 새 기능을 더하는 쪽이 모두 `Provider`다. 제공자 하나가 여러 네임스페이스를 낼 수 있다. 표준 `bot`의 호환되는 부분을 구현하면서 동시에 자기 네임스페이스(예: `kakao`)를 낼 수 있다. 알림 대신 DB를 읽어 수신하는 제공자, Intent로 직접 전송하는 제공자도 같은 방식으로 만든다.
- **프로젝트는 제공자를 조합한다.** 프로젝트는 제공자를 여러 개 고르고, 호스트는 멤버(함수·이벤트) 단위로 API를 합친다. 두 제공자가 같은 멤버를 내면 프로젝트 설정의 `routes`로 하나를 고른다. 예를 들어 수신은 DB 제공자, `bot.send`는 Intent 제공자에게 맡길 수 있다.
- **표준은 추가로만 자란다.** 함수, 이벤트, 선택 매개변수, 선택 필드를 추가해도 버전은 그대로다. 호스트는 제공자를 자기 판의 표준에 맞춘다(`ModuleSpec.fit`). 그래서 호스트보다 오래된 PluginApi로 만든 제공자도, 더 새로운 것으로 만든 제공자도 그대로 쓰인다. 새 판에만 있는 것은 무시된다. 스크립트는 어느 제공자가 답하든 호스트 판의 시그니처를 보고, 제공자에게는 그 제공자가 선언한 매개변수만 전달된다(`fittedTo`). 호환되지 않는 변경만 버전을 올린다.
- **양쪽 모두 자기가 읽을 수 있는 만큼만 읽는다.** 스펙은 멤버 단위로 읽는다. 읽지 못하는 함수나 이벤트(나중 계약에서 추가된 타입을 쓰는 것 등)만 빠지고 나머지는 그대로 쓰인다. 프로토콜은 `hello`에서 양쪽이 말할 수 있는 범위 중 가장 새 버전으로 정한다.
- **`bot`은 어느 메신저에나 있는 것만 요구한다.** 메시지에서 꼭 있어야 하는 것은 방 이름, 내용, 보낸 사람 이름뿐이다. 방이나 메시지를 가리키는 값(토큰, 방 이름, 채널 ID)은 모두 선택이고 호출할 때 한꺼번에 넘긴다. 그래서 각 제공자는 자기가 아는 것을 쓰고, 한 제공자가 받은 메시지에 다른 제공자가 답할 수 있다. 제공자만 아는 데이터는 받을 때도 보낼 때도 `extra`에 담는다.
- **네임스페이스는 소문자 한 단어다.** `project`, `log`, `file`, `db`, `http`, `device`, `sys`는 호스트만 답한다.
- **호스트는 언어를 모른다.** 스크립트가 API를 보는 방식(값 변환, 오류, 비동기, 모듈 로딩)은 언어마다 바인딩이 정한다. JavaScript는 앱이 직접 실행하는 언어라서 바인딩이 계약에 들어 있다([명세](docs/bindings/javascript.md), 공유 로더와 키트, TCK). 다른 언어는 그 언어를 추가하는 플러그인이 정한다([새 언어 추가](docs/bindings/README.md)).
- **JavaScript 프로필 키트.** `require('msgbot')`가 모듈 생성, 인자 이름 붙이기, 이벤트 구독(`sys.listen`), 오류 보고를 대신한다. 그래서 다른 개발자가 새 JavaScript API를 만들 때도 자기 API의 모양만 쓰면 된다. 모든 JavaScript 엔진이 키트를 제공한다.
- **RPC는 하나다.** 엔진과 제공자는 같은 양방향 채널(`RpcPeer`) 위의 역할이다. 원격 엔진과 원격 제공자도 로컬 구현과 같은 인터페이스를 구현한다.
- **플러그인이 보낸 프레임은 믿지 않는다.** `ValueCodec`는 모든 길이와 중첩 깊이, 한 프레임이 만들 수 있는 값의 개수와 공유 메모리 크기를 검사한다. 타입 문자열도 길이와 깊이를 제한한다. 16KB가 넘는 바이트와 프레임은 공유 메모리로 옮기고, Binder가 받지 못한 프레임도 공유 메모리로 다시 보낸다. 그래서 긴 메시지도 프레임당 64MB까지 전달된다.
- **무엇도 조용히 사라지지 않는다.** 핸들러가 `Error`를 던져도, 답을 보낼 수 없어도 요청한 쪽은 곧바로 오류로 답을 받는다. 엔진 스레드는 받지 못한 작업을 예외로 알리고(`EngineThread`), 끝나지 않는 스크립트는 `ScriptEngine.interrupt()`로 멈춘다.

## 플러그인 만들기

APK는 서비스 하나와 매니페스트 XML 하나로 자신이 제공하는 모든 것을 알린다. XML 형식은 `PluginManifestSchema`에 있다. 아래는 누군가 자기 JavaScript API(`api3`)와, DB로 받고 Intent로 보내는 제공자를 함께 내는 플러그인의 예다.

```xml
<msgbot-plugin protocol="0">
    <provider id="kakao-direct" label="@string/kakao_direct" provides="bot kakao" />
    <profile id="api3" language="javascript" label="@string/api3"
        shim="@raw/api3" template="@raw/api3_starter" requires="bot project log" />
</msgbot-plugin>
```

```kotlin
class MyPluginService : PluginService() {
    override val providers = mapOf("kakao-direct" to ::KakaoDirect)
}

class KakaoDirect : Provider {
    override val modules = listOf(
        implement(StandardApi.Bot) {
            handle("send") { call -> sendByIntent(call.args.stringOrNull("channelId"), call.args.string("text")) }
            emits("message")                        // DB를 읽어 context.emit("bot.message", ...)
        },
        provide("kakao") {
            function("members", returns = Type.list(Type.STRING)) {
                param("channelId", Type.STRING)
                // 네트워크나 DB를 기다리는 함수는 handleAsync로 제공자 스레드를 비워 둔다.
                handleAsync { call -> membersOf(call.args.string("channelId")) }   // CompletionStage
            }
        },
    )
}
```

`handle`은 제공자 스레드에서 바로 값을 돌려준다. `handleAsync`는 `CompletionStage`를 돌려주고, 답은 어느 스레드에서 완성해도 된다. 그동안 제공자 스레드는 다른 호출을 받는다. 제공자는 테스트에서 `ProviderConformance`를 상속해 호스트가 보는 방식 그대로 검사할 수 있다.

자세한 작성 안내는 MessengerBotR의 `docs/plugin-authoring.md`에 있다.

```sh
./gradlew :contract:test :tck:build :android:testDebugUnitTest :android:assembleRelease
./gradlew publishToMavenLocal
```

MessengerBotR은 이 저장소를 Git 서브모듈로 고정하고 Gradle 복합 빌드로 소스를 직접 사용한다.
