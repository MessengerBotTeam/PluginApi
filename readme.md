# PluginApi

MessengerBotR의 플러그인 계약이다. `contract`는 Android에 의존하지 않는 값·이벤트·기능·스크립트 엔진 계약이고, `android`는 프로젝트별 바인더 세션과 대용량 전송을 제공한다.

엔진은 언어 실행만 맡고, 스크립트 프로필은 언어별 shim과 시작 템플릿을 제공한다. 메시지 소스와 일반 확장 기능은 `CapabilityProvider`를 구현해 이벤트와 이름 있는 기능을 제공한다. 서로의 APK나 구현 타입을 참조하지 않는다. `ProviderDescriptor.namespace`가 기능과 이벤트 이름의 충돌을 막는다. 현재 프로토콜은 2, 개발 아티팩트 버전은 `2.0.0-SNAPSHOT`이다.

제공자 호출은 `ProviderCall.projectId`로 프로젝트별 상태를 구분한다. 이벤트는 `ProviderEvent.projectId`를 지정해 한 프로젝트에 보내거나 `null`로 구독한 모든 프로젝트에 보낸다.

```sh
./gradlew :contract:test :android:assembleRelease
./gradlew :contract:publishGprPublicationToMavenLocal :android:publishGprPublicationToMavenLocal
```

MessengerBotR은 이 저장소를 Git 서브모듈로 고정하고 Gradle 복합 빌드로 소스를 직접 사용한다. 플러그인 작성 예시는 MessengerBotR의 `docs/plugin-authoring.md`에 있다.
