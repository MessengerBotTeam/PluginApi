# PluginApi

MessengerBotR의 플러그인 계약이다. 현재 개발 버전은 `0.1.0-SNAPSHOT`이고 프로토콜 버전은 `0`이다. 아직 공개 전이라 무엇이든 바뀔 수 있다.

앱에는 기본값만 들어 있다. JavaScript 엔진(Rhino), API2와 Legacy 프로필, 알림에서 메시지를 읽는 제공자다. 나머지는 누구나 이 계약으로 플러그인을 만들어 더한다. 새 언어(엔진), 그 언어나 기존 언어의 새 API(프로필), 새 수신·전송 방식과 기능(제공자)이 여기에 해당한다. 계약은 특정 플러그인의 언어나 API를 담지 않는다.

| 모듈 | 내용 |
|---|---|
| `contract` | 값, API 스키마, 표준 모듈, 엔진·제공자·도구 SPI, 대칭 RPC. Android에 의존하지 않는다. |
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
- **프로젝트는 제공자를 조합한다.** 프로젝트는 제공자를 여러 개 고르고, 호스트는 멤버(함수·이벤트) 단위로 API를 합친다.
  - 함수는 제공자 하나가 맡는다. 두 제공자가 같은 함수를 내면 목록에서 뒤에 있는 쪽이 맡고, 프로젝트 설정의 `routes`로 바꿀 수 있다. 예를 들어 `bot.send`는 Intent 제공자에게 맡길 수 있다.
  - 이벤트는 선택한 모든 제공자에게서 받는다. 알림, DB, 서버에서 오는 `bot.message`를 한 스크립트가 함께 받는다. 호스트는 각 메시지에 보낸 제공자의 ID(`sourceProviderId`)를 붙이며, 제공자가 넣은 값은 지운다.
  - 메시지의 토큰(`replyToken`, `readToken`, 이미지 토큰)으로 하는 호출은 그 토큰을 발급한 제공자에게 간다. 그래서 여러 수신원의 메시지에 각자 답할 수 있다. 토큰 없이 주소로 보내는 호출은 `routes`와 제공자 순서를 따른다.
  - 표준 `bot.reply`, `bot.markRead`, `bot.image`에 넘기는 토큰은 호스트가 메시지에 담아 전달한 참조만 받는다. 제공자가 발급한 원래 문자열을 스크립트가 직접 넘기면 `unavailable`로 실패한다. 원래 문자열은 여러 제공자에서 겹칠 수 있어 발급자를 안전하게 고를 수 없기 때문이다. 스크립트는 참조를 해석하거나 재구성하지 않고 그대로 사용한다. 제공자의 핸들러에는 호스트가 원래 토큰으로 복원해 전달한다.
  - 참조는 전달 대상 프로젝트와 제공자 실행 세대에 묶인다. 제공자가 옵션 변경으로 `stop()` 후 다시 `start()`되는 경우도 옛 참조를 무효화한다. 사라지거나 만료된 참조는 다른 제공자나 주소 호출로 자동 재전송하지 않는다. 토큰 없이 방 주소로 보내려면 별도로 `bot.send`를 호출한다.
  - 이 규칙은 표준 메시징 함수의 토큰에 적용한다. 제공자 전용 네임스페이스의 API는 자기 계약으로 원래 토큰이나 핸들을 받을 수 있다. 그 전용 API의 문자열을 표준 `bot.*` 토큰으로 사용할 수 있다는 뜻은 아니다.
- **표준은 추가로만 자란다.** 함수, 이벤트, 선택 매개변수, 선택 필드를 추가해도 버전은 그대로다. 호스트는 제공자를 자기 판의 표준에 맞춘다(`ModuleSpec.fit`). 그래서 호스트보다 오래된 PluginApi로 만든 제공자도, 더 새로운 것으로 만든 제공자도 그대로 쓰인다. 새 판에만 있는 것은 무시된다. 스크립트는 어느 제공자가 답하든 호스트 판의 시그니처를 보고, 제공자에게는 그 제공자가 선언한 매개변수만 전달된다(`fittedTo`). 호환되지 않는 변경만 버전을 올린다.
- **양쪽 모두 자기가 읽을 수 있는 만큼만 읽는다.** 스펙은 멤버 단위로 읽는다. 읽지 못하는 함수나 이벤트(나중 계약에서 추가된 타입을 쓰는 것 등)만 빠지고 나머지는 그대로 쓰인다. 프로토콜은 `hello`에서 양쪽이 말할 수 있는 범위 중 가장 새 버전으로 정한다.
- **`bot`은 어느 메신저에나 있는 것만 요구한다.** 메시지에서 꼭 있어야 하는 것은 방 이름, 내용, 보낸 사람 이름뿐이다. 방이나 메시지를 가리키는 값(토큰, 방 이름, 채널 ID)은 모두 선택이고 호출할 때 한꺼번에 넘긴다. 그래서 각 제공자는 자기가 아는 것을 쓰고, 한 제공자가 받은 메시지에 다른 제공자가 답할 수 있다. 제공자만 아는 데이터는 받을 때도 보낼 때도 `extra`에 담는다.
- **네임스페이스는 소문자 한 단어다.** `project`, `log`, `file`, `db`, `http`, `device`, `sys`는 호스트만 답한다.
- **호스트는 언어를 모른다.** 스크립트가 API를 보는 방식(값 변환, 오류, 비동기, 모듈 로딩)은 언어마다 바인딩이 정한다. JavaScript는 앱이 직접 실행하는 언어라서 바인딩이 계약에 들어 있다([명세](docs/bindings/javascript.md), 공유 로더와 키트, TCK). 다른 언어는 그 언어를 추가하는 플러그인이 정한다([새 언어 추가](docs/bindings/README.md)).
- **JavaScript 프로필 키트.** `require('msgbot')`가 모듈 생성, 인자 이름 붙이기, 이벤트 구독(`sys.listen`), 오류 보고를 대신한다. 그래서 다른 개발자가 새 JavaScript API를 만들 때도 자기 API의 모양만 쓰면 된다. 모든 JavaScript 엔진이 키트를 제공한다.
- **RPC는 하나다.** 엔진과 제공자는 같은 양방향 채널(`RpcPeer`) 위의 역할이다. 원격 엔진과 원격 제공자도 로컬 구현과 같은 인터페이스를 구현한다.
- **플러그인이 보낸 프레임은 믿지 않는다.** `ValueCodec`는 모든 길이와 중첩 깊이, 한 프레임이 만들 수 있는 값의 개수와 공유 메모리 크기를 검사한다. 컬렉션이 선언한 개수도 미리 예산에서 뺀다. 타입 문자열도 길이와 깊이를 제한한다. 16KB가 넘는 바이트와 프레임은 공유 메모리로 옮기고, Binder가 받지 못한 프레임도 공유 메모리로 다시 보낸다. 그래서 긴 메시지도 프레임당 64MB까지 전달된다. 한 프레임이 따로 옮기는 바이트는 16개까지이고, 나머지는 프레임과 함께 간다.
- **보내는 쪽도 같은 한도를 지킨다.** 받는 쪽이 거부할 값은 보내기 전에 오류가 되어, 요청한 쪽이 타임아웃까지 기다리지 않는다.
- **무엇도 조용히 사라지지 않는다.** 핸들러가 `Error`를 던져도, 답을 보낼 수 없어도 요청한 쪽은 곧바로 오류로 답을 받는다. 엔진 스레드는 받지 못한 작업을 예외로 알리고(`EngineThread`), 끝나지 않는 스크립트는 `ScriptEngine.interrupt()`로 멈춘다. 호스트 호출을 기다리는 스크립트도 interrupt로 멈춘다. 보내지 못한 답은 `PluginService`가 로그로 남긴다.
- **상대가 사라지면 기다리지 않는다.** 전송이 상대 프로세스가 죽었다고 알리면(`TransportClosedException`) `RpcPeer`가 바로 닫히고, 기다리던 요청은 모두 그 자리에서 실패한다. Binder 버퍼가 잠시 찬 것과는 구별한다.
- **포기한 일은 늦게 하지 않는다.** 요청에는 기한이 실린다. 호스트가 기다리기를 그만둔 이벤트와 제공자 호출, 스크립트가 그만둔 호스트 호출은 차례가 와도 실행하지 않고 `unavailable`로 답한다. 늦은 메시지에 답하거나 같은 메시지를 두 번 보내지 않기 위해서다.
- **재시작 전의 이벤트는 새 시작에 섞이지 않는다.** `provider.start`마다 세대 번호가 붙고, 이전 세대가 보낸 emit은 호스트가 버린다. 멈춘 제공자가 emit하면 버리고 오류로 알리며, 멈출 때 아직 답하지 않은 비동기 호출은 `unavailable`로 끝낸다. 멈춘 제공자로 온 호출도 실행하지 않는다.
- **세션은 서로 막지 않는다.** 한 플러그인의 세션은 모두 Binder 객체 하나를 쓰지만, 프레임은 세션마다 자기 스레드에서 처리한다. 세션은 연 앱만 쓰고 닫을 수 있다.
- **멈춘 스레드는 쌓이지 않는다.** 네이티브 코드에서 막힌 스크립트는 interrupt가 닿지 않아, 세션을 닫아도 그 스레드와 메모리가 남는다. `PluginService`는 닫은 세션이 10초 안에 멈추지 않으면 기록하고, 열린 세션이 없거나 그런 세션이 3개가 되면 플러그인 프로세스를 끝낸다. 앱은 플러그인이 죽은 것을 보고 기다리던 요청을 바로 실패시킨 뒤, 아직 쓰는 프로젝트를 다시 연결한다.

## 에디터 도구(Tooling)

엔진, 프로필, 제공자 외에 네 번째 컴포넌트가 하나 더 있다. 에디터가 언어를 이해하도록 돕는 **도구**다. 도구는 스크립트를 실행하지 않는다. 호스트가 편집 중인 텍스트를 보내면 진단(오류와 경고), 자동완성, 호버, 시그니처 도움말로 답한다.

```xml
<msgbot-plugin protocol="0">
    <tooling id="ts-tools" label="@string/ts_tools" languages="javascript typescript" />
</msgbot-plugin>
```

```kotlin
class MyPluginService : PluginService() {
    override val tooling = mapOf("ts-tools" to ToolingFactory(::TypeScriptTools))
}

class TypeScriptTools(private val context: ToolingContext) : LanguageTools {
    override val capabilities = setOf(ToolingCapability.DIAGNOSTICS, ToolingCapability.COMPLETION)
    override fun configure(workspace: ToolingWorkspace) { /* 선언 파일(workspace.libs)로 새로 시작 */ }
    override fun sync(change: ToolingChange) { /* 바뀐 파일 반영 */ }
    override fun diagnostics(path: String): List<ToolDiagnostic> = analyze(path)
    override fun complete(path: String, offset: Int): List<ToolCompletion> = completionsAt(path, offset)
}
```

- **서버가 아니다.** 호스트가 요청하고 도구가 답하는 함수 호출이다. JSON-RPC나 별도 프로세스가 없다. 언어 서버를 감싸서 구현해도 되고, 라이브러리를 직접 불러도 된다.
- **텍스트를 호스트가 보낸다.** `configure`가 작업 공간(언어, 옵션, 프로젝트가 쓰는 API 선언 같은 `libs`)을 정하고, `sync`가 편집한 파일을 알린다. 이어서 `diagnostics`, `complete`, `hover`, `signatureHelp`로 묻는다. 위치는 파일 처음부터 센 UTF-16 코드 단위다.
- **할 수 있는 것만 구현한다.** 필요한 함수만 재정의하고 `capabilities`에 적는다. 호스트는 적지 않은 것을 묻지 않는다.
- **파일은 읽기만 한다.** `ToolingContext.host.read`와 `list`로 호스트가 허락한 파일만 읽는다. 가져오는 모듈(`node_modules` 같은 것)을 따라갈 때 쓴다. 호출은 답이 올 때까지 막히므로 도구 스레드에서 부르고 읽은 것은 캐시한다.
- **도구의 호출은 한 스레드에서 차례로 온다.** 호스트가 기다리기를 그만둔 질문은 차례가 와도 실행하지 않는다. 입력이 빠를 때 오래된 분석이 쌓이지 않게 하기 위해서다. `configure`와 `sync`는 건너뛰지 않는다.
- **호스트가 같은 계약으로 쓴다.** `RemoteLanguageTools`가 호스트 쪽 프록시이고 `LanguageTools`를 구현한다. 도구가 시작하지 못하거나 질문이 실패하면 호출한 쪽이 `CallException`을 받는다.

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

## Binder 인터페이스 호환성

AIDL 호출 번호는 명시적으로 고정하며, 기존 번호는 바꾸거나 재사용하지 않는다. 메서드는 새 번호로 추가만 한다. 세션을 열기 전에 앱과 플러그인은 서로의 `binderVersion()`을 확인하고, 상대가 `BinderContract.MIN_VERSION`보다 오래됐으면 다시 빌드하라는 오류로 알린다. 이 메서드가 없는 옛 버전은 0을 돌려주므로 같은 오류가 난다. 메서드를 추가하면 `BinderContract.VERSION`을 올린다. `MIN_VERSION`은 새로 추가한 메서드를 반드시 호출해야 할 때만 올린다. AIDL을 바꾸고 버전을 올리지 않으면 호환성 테스트가 실패한다. 이 검사는 RPC의 `hello`와 프로토콜 버전 협상과는 별개다.
