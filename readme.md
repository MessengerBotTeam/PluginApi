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
  event bot.message {room: string, author: {...}, extra: map<any>?}
        ▲ 선언                                ▼ __api 데이터로 소비
  호스트 모듈 · 제공자(Provider) 여럿      엔진(언어 바인딩) → 프로필(키트 위의 얇은 층)
  (호스트가 멤버 단위로 조합하고, 인자·결과·이벤트를 검사)
```

- **API는 스키마다.** 모든 함수와 이벤트는 `ModuleSpec`에 이름, 매개변수, 타입, 버전과 함께 선언된다. 호스트는 이 스키마로 호출 인자, 반환 값, 이벤트 페이로드를 검사한다. 엔진은 스키마를 `__api` 데이터로 프로필에 넘긴다.
- **제공자는 한 종류다.** 메시지를 받는 쪽, 보내는 쪽, 새 기능을 더하는 쪽이 모두 `Provider`다. 제공자 하나가 여러 네임스페이스를 낼 수 있다. 표준 `bot`의 호환되는 부분을 구현하면서 동시에 자기 네임스페이스(예: `kakao`)를 낼 수 있다. 알림 대신 DB를 읽어 수신하는 제공자, Intent로 직접 전송하는 제공자도 같은 방식으로 만든다.
- **프로젝트는 제공자를 조합한다.** 프로젝트는 제공자를 여러 개 고르고, 호스트는 멤버(함수·이벤트) 단위로 API를 합친다. 두 제공자가 같은 멤버를 내면 프로젝트 설정의 `routes`로 하나를 고른다. 예를 들어 수신은 DB 제공자, `bot.send`는 Intent 제공자에게 맡길 수 있다.
- **표준은 추가로만 자란다.** 함수, 이벤트, 선택 매개변수, 선택 필드를 추가해도 버전은 그대로다. 그래서 예전에 만든 제공자도 계속 받아들여진다(`ModuleSpec.accepts`). 호환되지 않는 변경만 버전을 올린다. 메시지의 `extra`에는 제공자만 아는 데이터(첨부, 메시지 종류 등)를 담는다.
- **네임스페이스는 소문자 한 단어다.** `project`, `log`, `file`, `db`, `http`, `device`, `sys`는 호스트만 답한다.
- **언어별 규칙은 바인딩 명세에 한 번만 적는다.** 값 변환, 오류, 타이머, 비동기, 모듈 로딩을 [JavaScript](docs/bindings/javascript.md)와 [Lua](docs/bindings/lua.md) 명세에 정의했다. 같은 언어의 엔진은 TCK로 검사한다.
- **프로필 키트.** `require('msgbot')`가 모듈 생성, 인자 이름 붙이기, 이벤트 구독(`sys.listen`), 오류 보고를 대신한다. API3 같은 새 API도 자기 모양만 쓰면 된다.
- **RPC는 하나다.** 엔진과 제공자는 같은 양방향 채널(`RpcPeer`) 위의 역할이다. 원격 엔진과 원격 제공자도 로컬 구현과 같은 인터페이스를 구현한다.
- **플러그인이 보낸 프레임은 믿지 않는다.** `ValueCodec`는 모든 길이와 중첩 깊이를 검사한다. 64KB가 넘는 바이트는 공유 메모리로 옮긴다.

## 플러그인 만들기

APK는 서비스 하나와 매니페스트 XML 하나로 자신이 제공하는 모든 것을 알린다. XML 형식은 `PluginManifestSchema`에 있다.

```xml
<msgbot-plugin protocol="4">
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
                handle { call -> membersOf(call.args.string("channelId")) }
            }
        },
    )
}
```

자세한 작성 안내는 MessengerBotR의 `docs/plugin-authoring.md`에 있다.

```sh
./gradlew :contract:test :tck:build :android:testDebugUnitTest :android:assembleRelease
./gradlew publishToMavenLocal
```

MessengerBotR은 이 저장소를 Git 서브모듈로 고정하고 Gradle 복합 빌드로 소스를 직접 사용한다.
