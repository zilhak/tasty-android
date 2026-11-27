# Android 네트워크 접근 기능 계획

## 1. 목적과 상태

- 상태: **초안**. Codex 계획 검증 3회차까지 끝났다(1회차 24건, 2회차 17건, 3회차 9건을 모두 반영. 규칙에 따라 3회차 반영본은 다시 검증하지 않았다).
- 요청: R-20261127-3(2026-11-27). 이 문서는 그 요청의 범위 2(Rust 연결 기반의 구조), 3(attach 등 네트워크 접근 기능의 계획), 4(CI 계획)를 맡는다. 화면 설계는 하지 않는다. 흐름과 필요한 API만 적는다.
- 기준 커밋:
  - 이 레포 develop `84278b0`.
  - tasty-network develop `b103e9073b9e0e33ac5f586d8cb3b3825f54fa1b`(짧게 `b103e90`). 이 문서에서 `@b103e90`을 붙인 인용은 그 커밋의 파일이다.
  - tasty-network의 모바일 설계 절은 develop에 아직 없다. 진행 중 초안(`bcbcfba`, 그 뒤 수정본 `6bad6ff`)을 인용할 때는 **진행 중**이라고 적고 b103e90의 사실과 나눈다. 이 두 커밋은 아직 공개 원격에 없으므로, 이 문서는 인용하는 계약을 본문에 요약해 적는다. 이름과 값은 그 설계가 확정될 때 바뀔 수 있고, 공개된 뒤 확정 커밋을 이 문서에 다시 적는다.
  - 사용자 작업 브랜치 `work/android-terminal` `4415132f9e35247b98f328d2d5546b302c5dd8d6`(기점 `ab57f98`, 커밋 9개, 공개 원격에는 아직 없다). 로컬 터미널 화면과 Rust 연결 구조가 들어 있다. 사용자 결정 U1에 따라 이 브랜치를 develop에 반영하는 작업이 따로 있고, 이 문서는 그 구조를 이어 쓴다고 전제한다.
- 쓰는 방법: 각 절은 **정해진 것 / 제안 / 결정 대기**로 나눈다. "정해진 것"은 아래 결정 표나 레포 지침에 출처가 있는 것이다. "제안"은 이 계획이 고른 설계이며 Codex 검증과 구현 리뷰에서 바뀔 수 있다. "결정 대기"는 사용자나 다른 레포가 정할 것이다.

### 결정 표

| 번호 | 내용 | 출처 |
|---|---|---|
| 전제 1~6 | 레포 지침 [`CLAUDE.md`](../../CLAUDE.md) "역할": 모바일은 데스크톱과 다른 형태로 만들고 원칙을 따로 정한다, 플러그인 시스템 없음(공식 플러그인 기능은 내장), Explorer 내장, 원격 파일은 SSH(파일은 SFTP, Git 정보는 원격 `git` 실행)이고 이를 위해 본체에 전용 채널을 더하지 않는다, 기능이 없으면 시각적 피드백, Kotlin + Compose와 UniFFI로 공유하는 Rust 크레이트. 전용 채널 금지는 파일·Git 경로의 규칙이다. 5.4의 요청 채널은 원격 Tasty 연동을 위해 tasty-network가 주는 채널이며 파일·Git에는 쓰지 않는다 | 레포 지침 |
| A1 | 앱을 열면 로컬 터미널 하나, 상단 탭바에 탭 하나, 탭바 오른쪽 위 메뉴 버튼 | 사용자 결정 2026-11-11 |
| SSH 백엔드 | 모바일 SSH는 tasty-network의 내장(russh) 백엔드를 쓴다. 내장 백엔드는 OpenSSH 설정·키·known_hosts 형식을 같은 뜻으로 읽고, exec 채널과 SFTP도 지원한다 | 요청 R-20261127-3 "이미 정해진 것" |
| handshake v2 | v2는 v1 상대를 거절한다. 마이그레이션은 지원하지 않는다. Android attach는 tasty-network로 전환한 데스크톱 Tasty에만 붙고, tasty 전환과 Android attach는 같은 tasty-network 태그를 기준으로 함께 간다 | 같은 요청 |
| U1 | `work/android-terminal`을 develop 위로 올려 반영한다 | 요청 답 2026-11-27 |
| U2 | 원격 Tasty는 목록·상태를 먼저 하고, 화면 attach는 다음 단계에서 한다(C) | 같은 답 |
| U3 | 앱에서 Ed25519 키를 만들어 Android Keystore의 AES 키로 감싸 저장한다. 기존 개인 키 가져오기와 공개키 복사 화면을 둔다. 비밀번호는 저장하지 않고 매번 묻는다(B) | 같은 답 |
| U4 | 접속 프로필은 앱의 `ssh/config`(OpenSSH 형식, UI 편집과 붙여넣기 가져오기)이고, Tasty 전용 값은 앱 고유 파일에 둔다(A) | 같은 답 |
| U5 | 연결은 앱이 앞에 있을 때만 유지한다(A)로 시작한다. 백그라운드 지속 연결을 실제로 제공하는 단계에서 다시 묻는다 | 같은 답 |
| R1 | 바인딩 크레이트 `tasty-net-ffi`는 tasty-network에 두고, 앱 cdylib에 rlib으로 링크해 ABI별 `.so` 하나로 싣는다. Kotlin 생성은 앱 Gradle 작업이 라이브러리 모드로 한다. UniFFI 판은 tasty-network가 고정하는 최신 안정판을 두 레포가 함께 쓴다 | 같은 답 |
| R2 | 6절 요구 표 #1~#11(6b·7b 포함)을 tasty-network에 전달했다. 6절 #2(메모리 바이트 키)는 U3 때문에 조건부가 아니라 요구다. 6절 #12~#15와 #7+ 행(#7에 이 문서가 더한 확인 항목)은 이 문서가 더한 것이고 2026-11-27에 전달됐다. tasty-network 쪽 위치는 6절에 적는다 | 같은 답 |
| R3 | tasty에 두 작업을 만든다. (B) attach 도메인 메시지·descriptor의 serde 타입만 담은 작은 크레이트(Android에서 빌드 가능), (A) 읽기 전용 원자 메서드 `workspace.overview`. 크레이트 이름과 sha는 tasty에 반영된 뒤 받는다 | tasty 쪽 의견(tasty develop `a8c4d26f4d` 기준), 2026-11-27 |
| SSH 파일 계약 | 앱의 `ssh/` 폴더를 `~/.ssh`로 본다. OpenSSH도 모르는 지시어는 거절한다. `@cert-authority` 줄은 1단계에서 믿지 않는다. 호스트 키 확인 콜백은 `StrictHostKeyChecking ask`일 때 부르고, `accept-new`는 묻지 않고 기록한다 | tasty-network 후속 결정(2026-11-27). 사용자에게 묻지 않는다 |
| iOS | 구조만 iOS가 가능하게 둔다. 이 레포는 iOS 앱을 만들지 않는다 | 사용자 결정 |

## 2. 층 구조

### 정해진 것

- 위에서 아래로 다섯 층이다.

  | 층 | 내용 | 자리 |
  |---|---|---|
  | 화면 | Compose 화면(A1 탭바, 프로필·Explorer·Git·원격 Tasty·연결 상태 화면) | `app/` |
  | 상태 | Kotlin 저장소(repository)와 ViewModel. 막히는 Rust 호출을 화면 스레드 밖에서 부른다 | `app/` |
  | 생성 바인딩 | UniFFI가 만든 Kotlin. 앱 크레이트의 패키지는 `dev.tasty.android.core`(`work/android-terminal`의 `rust/tasty-mobile-core/uniffi.toml`). Rust 라이브러리는 JNA로 싣는다 | 빌드 산출물(커밋하지 않는다) |
  | 앱 Rust | 앱 cdylib `tasty-mobile-core`. ABI마다 **Rust cdylib 하나**(`libtasty_mobile_core.so`)를 싣는다. JNA는 자기 네이티브 라이브러리를 따로 싣는다 | `rust/tasty-mobile-core` |
  | 공유 Rust | rlib으로 링크하는 크레이트: `tasty-terminal`(로컬 터미널), `tasty-net-ffi`와 그 아래 tasty-network 코어(transport·identity·topology, 나중에 p2p), R3 (B)의 tasty 도메인 타입 크레이트 | 다른 레포, git 의존 |

- 한 cdylib으로 묶는 근거: UniFFI 라이브러리 모드는 한 cdylib에 링크된 UniFFI 크레이트 전부의 바인딩을 한 번에 만든다. 그래서 앱 export와 tasty-network export를 `.so` 하나에서 함께 생성하고 부를 수 있다. Rust 표준 라이브러리와 전역 상태도 한 벌만 실린다. (런타임이 두 벌 실린다는 근거는 쓰지 않는다. `tasty-terminal`@`e1e705bc`는 tokio에 의존하지 않는다.)
- 계약(R1 판정 기준):
  1. 앱과 tasty-network 양쪽의 export가 최종 `.so`에 남아 있고, 생성된 Kotlin에서 호출된다. 양쪽 생성 Kotlin이 싣는 네이티브 라이브러리 이름이 `tasty_mobile_core` 하나다.
  2. 같은 UniFFI 판을 쓴다. 앱은 `work/android-terminal`의 `uniffi = "0.32"`를 tasty-network가 고정하는 판으로 올린다.
  3. 모든 tasty-network 의존이 같은 rev를 가리킨다.
  4. 최종 Android 배포 빌드의 앱 전체 일반 의존 그래프에서 tasty-network의 `system-ssh` feature가 꺼져 있고, tasty-network 진행 중 초안의 의존 쪽 검사가 금지하는 나머지 항목도 없다(7절).
- 다른 프로그램 실행 금지는 tasty-network 경계의 규칙이다. 앱의 로컬 터미널 PTY(`tasty-terminal`이 쓰는 `portable-pty`)는 그 규칙의 대상이 아니다.

### 제안

- 스레드: tasty-network 진행 중 초안의 바인딩 API는 동기식이고, 이벤트는 앱이 `EventStream::next(timeout)`으로 끌어온다. 앱은 막히는 호출을 `Dispatchers.IO`에서 부르고, 이벤트 끌어오기는 연결 관리 객체 하나가 전용 코루틴 하나에서 돌린다.
- 앱이 구현하는 콜백은 둘이다. 모바일 초안은 `SshPrompt` 하나만 적지만, ffi 작업에는 `PeerKeyStore`(6절 #7b)도 들어갔다.
  - `SshPrompt`: 초안은 연결을 연 앱 스레드가 막혀 있는 동안 불린다고 적는다. 앱은 그 스레드를 막은 채 UI에 질문을 띄우고 답을 돌려준다. 대기 상한은 tasty-network 설정값(`embedded.prompt_timeout`)이다.
  - `PeerKeyStore`: handshake 도중 불린다. 어느 스레드에서 불리는지, 같은 저장소에 겹쳐 불릴 수 있는지는 `SshPrompt`의 규칙을 그대로 가정하지 않고 ffi 작업이 정한 계약을 따른다. 앱 구현은 UI를 부르지 않고 파일만 읽고 쓰며, 여러 스레드에서 동시에 불려도 안전하게 만든다.
- 앱 크레이트의 역할: `tasty-mobile-core`는 tasty-network의 export를 다시 감싸지 않는다. 앱 고유 로직(프로필 파일 읽기·쓰기 보조, R3 도메인 타입의 해석, Git 출력 해석이 Rust로 갈 경우)만 자기 export로 둔다. tasty-network 쪽 API는 `tasty-net-ffi`의 생성 Kotlin을 그대로 쓴다.
- 심벌 보존: 라이브러리 모드는 최종 `.so`에 남은 UniFFI 메타데이터와 export 심벌을 읽는다. 앱이 tasty-network export를 다시 감싸지 않으면 rlib 의존의 심벌이 링크 단계에서 빠질 수 있다. 고정한 UniFFI 판에서 의존 크레이트의 scaffolding을 다시 내보내는 수단(0.33.0 소스의 `uniffi_reexport_scaffolding!`)으로 앱 cdylib에 연결한다. 이름과 사용법은 고정한 판의 문서로 다시 확인한다.
- 확인할 것: 라이브러리 모드에서 `tasty-net-ffi`의 Kotlin 패키지 이름은 그 크레이트의 `uniffi.toml`(git 의존이므로 tasty-network 레포 안)이 정한다. 앱 쪽 덮어쓰기가 필요한지와, 두 크레이트의 생성 Kotlin이 모두 같은 라이브러리 `tasty_mobile_core`를 싣는지는 `tasty-net-ffi`가 생긴 뒤 생성 결과로 확인한다(6절 #12).

### 결정 대기

- 없음. UniFFI 판의 실제 값은 tasty-network가 정한다(3절).

## 3. 바인딩 크레이트 위치

### 정해진 것 (R1)

- `tasty-net-ffi`는 tasty-network 레포에 둔다. 앱은 자기 cdylib `tasty-mobile-core`에 그 크레이트를 rlib으로 링크해 ABI별 `.so` 하나로 싣는다.
- Kotlin 생성은 앱 Gradle 작업이 라이브러리 모드로 한다. tasty-network는 Gradle, AAR 묶기, 앱 서명을 갖지 않는다(진행 중 초안 "바인딩 층").
- UniFFI 판은 tasty-network가 고정하는 최신 안정판이다. 앱은 0.32에서 그 판으로 올린다.
- Android용 태그가 나오기 전에는 git 의존 `rev = "b103e9073b9e0e33ac5f586d8cb3b3825f54fa1b"` 고정으로 시작한다. 태그로 올리는 시점은 tasty 전환과 같은 태그다.

### 제안

- 새로 합의한 것: 진행 중 초안 `bcbcfba`의 "바인딩 층"은 `crate-type = ["lib", "cdylib", "staticlib"]`의 `lib`를 "생성과 시험용"으로만 적었다. R1은 다른 cdylib에 rlib으로 링크돼도 동작하는 것(`uniffi::setup_scaffolding!` 네임스페이스 충돌 없음)을 요구로 더한다. 이 요구는 tasty-network ffi 작업에 전달됐다.
- ②단계의 임시 브리지: b103e90의 `tasty-net-identity`는 UniFFI에 의존하지 않고 UniFFI export가 없다(`crates/tasty-net-identity/Cargo.toml`@b103e90). 그래서 ②에서는 `tasty-mobile-core`가 기기 키 만들기·읽기와 기기 ID 문자열 반환만 자기 export로 감싼다. ③에서 `tasty-net-ffi`가 같은 기능을 내면 이 임시 export를 지우고 ffi 쪽 API로 바꾼다. 두 단계가 같은 키 폴더를 넘겨야 기기 ID가 바뀌지 않는다(5.4). 임시 export는 앱 안에서만 쓰고 다른 Rust 타입을 FFI로 노출하지 않아, 바꿀 때 Kotlin 호출 자리만 고치면 되게 한다.
- 판을 올릴 때 생성 명령을 다시 본다. 진행 중 초안 `6bad6ff`는 UniFFI 0.33.0의 `uniffi-bindgen`이 라이브러리 모드를 기본으로 하고 `--library` 플래그를 deprecated로 둔다고 적는다. `work/android-terminal`의 `uniffiBindgen` 작업은 `--library`를 넘긴다. 판을 올리는 커밋에서 그 판의 문서로 인자를 고친다.
- rev를 올리는 단위: tasty-network rev(또는 태그)를 올리는 커밋은 `rust/Cargo.toml`과 `rust/Cargo.lock`을 함께 바꾸고, 그 커밋 하나가 앱 빌드와 생성 Kotlin을 통과해야 한다. tasty 도메인 타입 크레이트(R3 B)가 같은 tasty-network 판을 따로 의존하면 2절 계약 3(같은 rev)이 깨질 수 있으므로, rev를 올릴 때 tasty 쪽 rev도 같은 태그 기준인지 함께 본다.

### 결정 대기

- tasty-network: UniFFI 판 고정값, `tasty-net-ffi`가 생기는 커밋, Android용 태그 시점.
- tasty: R3 (B) 크레이트 이름과 sha.

## 4. 빌드 연결

### 정해진 것 (U1, `work/android-terminal`에서 이어 쓰는 구조)

- `app/build.gradle.kts`에 `ndkVersion = "30.0.16248370"`.
- Gradle 작업 `cargoNdkBuild`: `rust/`에서 `cargo ndk -t <abi>... -P 29 -o <출력> build --release --locked -p tasty-mobile-core`를 실행한다. 기본 ABI는 `arm64-v8a`·`x86_64`이고, `-Ptasty.rustAbis=x86_64`처럼 줄일 수 있다. 출력은 `app/build/rust/jniLibs`.
- Gradle 작업 `uniffiBindgen`: 첫 ABI의 `libtasty_mobile_core.so`로 `cargo run --release --locked -q -p uniffi-bindgen -- generate --library <so> --language kotlin --no-format --out-dir <출력>`을 실행한다. 출력은 `app/build/generated/source/uniffi`. bindgen은 workspace 안의 `uniffi-bindgen` 크레이트라 따로 설치하지 않는다.
- `androidComponents.onVariants`가 두 출력을 각 변형의 `jniLibs`와 `kotlin` 생성 폴더로 연결한다(`addGeneratedSourceDirectory`).
- JNA는 `aar` 형식으로 의존한다(`libs.versions.toml` `jna = "5.19.1"`).
- Rust 쪽: `rust/Cargo.toml` workspace(`tasty-mobile-core`, `uniffi-bindgen`), `rust/Cargo.lock`, `rust/rust-toolchain.toml`(`1.96.0`, targets `aarch64-linux-android`·`x86_64-linux-android`). release profile `lto = "thin"`, `codegen-units = 1`, `strip = "debuginfo"`. Rust는 늘 release로 빌드한다(터미널의 debug 빌드가 너무 느리다는 이유, 같은 브랜치 `app/build.gradle.kts` 주석).
- 도구 판: cargo-ndk `4.1.2`(같은 브랜치의 CI가 고정한 판). ABI는 64비트 둘만 쓴다(32비트 제외). tasty-network 진행 중 초안의 Android target(`aarch64-linux-android`·`x86_64-linux-android`, NDK API 수준 29)과 같다. 앱 `minSdk = 29`와 `-P 29`도 같다.

### 제안

- `cargoNdkBuild` 입력 보강: 지금 입력(정본: `app/build.gradle.kts`의 `cargoNdkBuild` 등록과 `CargoNdkBuild` 선언)에는 `rust/rust-toolchain.toml`이 없고 NDK 폴더는 `@Internal`이다. 그래서 `rust/rust-toolchain.toml`이나 `ndkVersion`만 바꾸면 작업이 다시 돌지 않는다. 두 값을 입력에 넣는다(툴체인 파일은 입력 파일로, NDK 판은 `@Input` 문자열로).
- `uniffiBindgen` 입력 보강: 지금 선언한 입력은 첫 ABI의 `.so` 하나다(정본: `UniffiBindgen` 선언). 그래서 `.so` 내용이 같으면 앞 작업이 다시 돌아도 생성을 건너뛰고, 반대로 `uniffi.toml`이나 `uniffi-bindgen` 크레이트(소스·판)만 바뀌어 Kotlin 출력이 달라져야 할 때도 건너뛸 수 있다. 생성기 크레이트 소스, 생성에 쓰는 `uniffi.toml`, `Cargo.lock`, 툴체인 파일을 이 작업의 입력으로도 선언한다. NDK 판이나 ABI 목록은 `.so`를 바꾸는 경로로만 생성에 영향을 준다.
- APK 패키징 ABI: `tasty.rustAbis`는 cargo-ndk 출력만 줄인다. `work/android-terminal`의 Gradle에는 `abiFilters`가 없고, JNA `5.19.1` AAR에는 `armeabi-v7a`·`x86` 등의 `libjnidispatch.so`도 들어 있다. 그래서 지금 APK에는 Rust `.so`가 없는 ABI 폴더가 생길 수 있다. 64비트 두 ABI 정책을 `defaultConfig.ndk.abiFilters`(또는 같은 뜻의 패키징 설정)에도 적용해 두 네이티브 라이브러리가 ABI마다 짝을 이루게 한다. 한 ABI만 빌드하는 개발용 `-Ptasty.rustAbis`는 패키징 ABI도 같은 값으로 줄인다.
- git 의존: tasty-network·tasty rev를 바꾸면 `rust/Cargo.toml`과 `rust/Cargo.lock`이 바뀌므로 지금 입력으로도 다시 돈다. git 의존의 소스는 `CARGO_HOME`의 git 캐시에 받는다. `--locked`는 lock 파일 변경만 막고 네트워크 받기는 막지 않는다. 오프라인 빌드는 목표로 두지 않는다. 캐시가 비어 있으면 받고, 있으면 다시 받지 않는 것이 기대 동작이다.
- Gradle 빌드 캐시: 두 작업은 `@CacheableTask`가 아니므로 빌드 캐시에 저장되지 않고 최신 여부 확인(up-to-date)만 받는다. `.so` 두 벌은 크고, Rust 쪽은 `rust/target`이 따로 증분을 맡으므로 이대로 둔다. 구성 캐시(`gradle.properties`의 `org.gradle.configuration-cache=true`)와는 지금 구조(`ExecOperations` 주입, provider 연결)가 맞는지 4절 검증 항목으로 본다.
- 16 KB 페이지: NDK r28 이상은 기본으로 16 KB ELF 정렬을 적용한다. JNA `5.19.1` AAR의 두 대상 ABI `libjnidispatch.so`도 `PT_LOAD` 정렬이 `0x4000`이다(Codex 검증 1회차 확인). 최종 산출물에서는 두 가지를 따로 본다: APK 안 모든 네이티브 라이브러리의 ELF 정렬, 압축하지 않은 `.so` 엔트리의 ZIP 정렬. NDK 기본값은 ZIP 정렬까지 보장하지 않는다.
- 증거 요구(무엇으로 입증할지):
  - 깨끗한 칸에서 `assembleDebug` 한 번으로 APK 안에 두 ABI의 `libtasty_mobile_core.so`와 `libjnidispatch.so`가 들어가고, 다른 ABI 폴더는 없다.
  - APK의 모든 `.so`가 16 KB ELF 정렬이고 ZIP 정렬도 맞다.
  - 아무것도 바꾸지 않고 다시 돌리면 `cargoNdkBuild`·`uniffiBindgen`이 최신으로 건너뛰어진다.
  - `rust-toolchain.toml`, `ndkVersion`, `Cargo.lock`을 하나씩 바꾸면 그때마다 `cargoNdkBuild`가 다시 돈다. `uniffi.toml`이나 `uniffi-bindgen` 크레이트만 바꾸면 `uniffiBindgen`이 다시 돈다.
  - 생성 Kotlin에서 앱 export와 tasty-network export를 각각 하나 이상 부르는 단위·기기 시험이 통과한다.

### 결정 대기

- 없음.

## 5. 네트워크 기능 흐름

화면은 설계하지 않는다. 기능마다 흐름, 필요한 API, 실패 사유만 적는다. 괄호 안 번호는 6절 요구 목록 번호다.

### 5.1 SSH 연결 프로필

정해진 것:

- 프로필 형식(U4): 앱 저장소의 `ssh/config`(OpenSSH 형식). 앱 UI로 편집하고, 데스크톱 `~/.ssh/config`를 붙여 넣어 가져올 수 있다. Tasty 전용 값(원격 `tasty` 실행 파일 경로 등)은 앱 고유 파일에 둔다.
- 키와 비밀(U3): 앱에서 Ed25519 키를 만들고 개인 키를 Keystore의 AES 키로 감싸 저장한다. 기존 개인 키를 가져올 수 있다. 공개키를 복사하는 화면을 둔다. 비밀번호는 저장하지 않고 연결할 때마다 묻는다. 감싼 키는 앱이 풀어 메모리 바이트로 라이브러리에 넘긴다(#2).
- SSH 파일 계약(결정 표 "SSH 파일 계약"): 앱의 `ssh/`를 `~/.ssh`로 보고, 모르는 지시어는 거절하고, `@cert-authority`는 1단계에서 믿지 않고, 호스트 키 확인은 `ask`일 때만 묻는다. 바뀐 키·폐기된 키는 `accept-new`·`ask`에서도 거절한다.
- 내장 백엔드가 읽는 자리: 앱 내부 저장소의 `noBackupFilesDir`(5.6) 아래 OpenSSH 형식 파일. tasty-network 진행 중 초안의 설정값은 판마다 다르다. `bcbcfba`는 `embedded.home` 하나로 `~`를 펼쳤다. `6bad6ff`(`ssh-backends.md` "내장 백엔드가 읽는 파일")는 두 값을 함께 둔다: `embedded.ssh_dir`(ssh 폴더. 설정 파일 안의 `~/.ssh/`로 시작하는 경로가 이 폴더로 펼쳐진다. 모바일은 바인딩 층이 앱의 `ssh/`를 넣는다)과 `embedded.home`(그 밖의 `~/`). 앱은 두 역할을 각각 준다: `embedded.ssh_dir`에는 `noBackupFilesDir/ssh`, `embedded.home`에는 앱 홈으로 쓸 `noBackupFilesDir` 아래 폴더. 이름은 확정된 설계를 따른다.

제안:

- 호스트 키 정책 보존: tasty-network 진행 중 설계(`6bad6ff` `ssh-backends.md`)의 `with_verify`는 `StrictHostKeyChecking=accept-new`를 사용자 config보다 먼저 적용하는 옵션이지만, `system-ssh` 쪽 런처(`SshConnector::with_launcher`)로만 받는다. 모바일 빌드는 `system-ssh`를 끄므로(2절 계약 4) 앱에는 이 옵션이 없다. ffi나 내장 백엔드가 같은 강제값을 둘 경우에도 앱은 쓰지 않고, 가져온 config의 `ask`를 포함해 프로필의 유효 호스트 키 정책을 그대로 둔다. config에 값이 없을 때의 기본값은 ④에서 정하되 config 값보다 앞에 두지 않는다. 진행 중 설계라 확정판에서 다시 본다.
- 저장 배치(따로 적지 않은 것은 `filesDir` 아래. 비밀과 신뢰 기록은 모두 `noBackupFilesDir` 아래다):

  | 자리 | 내용 |
  |---|---|
  | `ssh/config`, `ssh/known_hosts` | OpenSSH 형식. 내장 백엔드가 그대로 읽는다. `ssh/` 폴더 전체가 `noBackupFilesDir` 아래(5.6) |
  | `ssh/<이름>.pub` | 공개키(복사 화면이 읽는다) |
  | `keys/<이름>.wrapped` | Keystore AES-GCM으로 감싼 개인 키. 평문 개인 키 파일은 만들지 않는다. `noBackupFilesDir` 아래(5.6) |
  | `tasty/profiles.toml`(가칭) | Host 별명마다 Tasty 전용 값. 원격 `tasty` 경로, 데이터 폴더 이름, 포트 파일 경로처럼 포트 발견 설정(`PortDiscoveryConfig`, `repo-boundary.md` "포트 발견의 tasty 관례"@b103e90)에 넣을 값 |
  | `net/` | tasty-network 기기 키 폴더(5.4)와 원격 Tasty 신뢰 저장소(5.4). `noBackupFilesDir` 아래(5.6) |

- 가져온 키가 passphrase로 잠겨 있으면 잠긴 채 감싸 두고, 연결할 때 `SshPrompt`로 passphrase를 묻는다. 비밀번호를 저장하지 않는다는 U3의 원칙을 passphrase에도 같게 적용한다.
- 메모리 키와 프로필의 연결: 감싼 키를 메모리로 넘길 때는 "어느 키를 어느 Host에 써도 되는가"가 정해져야 한다. 시도 순서만으로는 모자란다. 제안하는 규칙: 감싼 키마다 앱 안 이름이 있고, config의 `IdentityFile ~/.ssh/<이름>`이 그 이름을 가리키면 그 감싼 키를 그 자리의 키로 쓴다(파일 대신 메모리 바이트로). 기준은 유효 설정이 고른 키 목록이다: OpenSSH는 `IdentityFile`이 하나도 없으면 기본 키 목록(`id_rsa`, `id_ecdsa`, `id_ed25519` 등)을 채우고, `IdentitiesOnly yes`에서도 그 목록을 쓴다(`IdentitiesOnly`는 agent의 다른 키를 빼는 것이다). 그래서 감싼 키는 그 목록의 자리 이름(`IdentityFile`로 지정됐든 기본 키 자리든)과 이름이 같을 때 그 자리의 키가 된다. 목록에 없는 감싼 키는 그 Host에 쓰지 않는다. 이 규칙이 tasty-network의 메모리 키 API와 맞는지, 고른 메모리 자격이 세션 재사용 키(5.3)에 들어가는지는 #2·#13의 확인 항목이다.
- 사용자 이름: 붙여 넣은 config에 `User`가 없고 tasty-network의 로컬 사용자 기본값(진행 중 설계의 `embedded.local_user`)도 없으면 연결이 `UserRequired`로 끝난다(`6bad6ff` `ssh-backends.md`). Android에는 쓸 만한 로컬 사용자 이름이 없으므로, 최종 대상이나 `ProxyJump`의 어느 홉에서 유효 사용자가 비면 연결 전에 사용자에게 받고 그 Host의 `User`로 `ssh/config`에 적는다. config에 이미 있는 값은 그대로 쓴다.
- 붙여 넣은 config의 거절·미지원 사유는 tasty-network 진행 중 설계가 주는 설정 점검 결과(`SshConnector::inspect`, `6bad6ff`)를 그대로 화면에 보인다.

결정 대기: 없음.

### 5.2 내장 SSH exec

정해진 것:

- Git 정보(전제 4)는 원격에서 `git` 명령을 exec 채널로 실행해 읽는다.
- 원격 Tasty 포트 발견도 exec 채널로 같은 원격 명령을 돌린다(진행 중 초안 `bcbcfba` `mobile.md` "내장 백엔드의 경계"). 포트 발견은 transport 안에서 일어나고 앱은 설정값만 준다.

제안:

- 앱이 직접 쓰는 exec(Git용)는 tasty-network가 주는 exec API(#3)를 쓴다. 출력 상한, 시간 제한, 취소가 API에 있어야 한다. 포트 발견이 이미 쓰는 상한·마감 규칙(정본: `tasty-net-ssh::discover_remote_port`와 `repo-boundary.md` "포트 발견의 tasty 관례"@b103e90)과 같은 모양을 기대한다.
- Git 출력 해석은 Kotlin에 둔다. 본체는 git2로 로컬 저장소를 읽으므로(기획 초안 5절) CLI 출력 파서는 새로 필요하다. 다만 본체 `tasty-git-core`(`89efb5602`)에는 상태·로그·diff 결과 타입이 있다. 기획 3.2의 범위가 정해질 때, 같은 뜻의 필드는 그 결과 모델의 이름과 의미에 맞춘다(기획 초안 5절도 결과 타입 공유를 후보로 적는다).
- 성공 출력은 Git의 기계 판독 형식(`status --porcelain=v2 -z`, `log`의 지정 형식 등)으로 받아 로캘과 특수문자 경로에 덜 기대게 한다. 읽기 전용 조회가 원격 인덱스를 고쳐 쓰지 않게 `git --no-optional-locks`를 붙인다(`status`는 기본으로 인덱스의 상태 캐시를 갱신해 쓸 수 있다).
- 실패 분류: exec 결과는 종료 코드 말고도 종료 신호, 세션 손실, 종료 상태 미수신이 있다(`6bad6ff` `ssh-backends.md` "터널·exec·SFTP"). 그래서 "`git` 없음"은 종료 코드 하나로 정하지 않는다. 지원하는 원격 셸(POSIX sh, Windows PowerShell·cmd)마다 명령과 경로를 넘기는 방식과 `git` 설치 여부를 확인하는 명령을 따로 정한다. 포트 발견의 `detect_port_mode`는 원격에 도달한 발견 방식(성공한 방식, 그리고 원격 인스턴스 종료 확정 `RemoteInstanceExited`를 보고한 방식도 포함. `detect_first_success`와 시험 `detect_port_mode_returns_the_mode_that_reported_an_exited_instance`)만 돌려주고 셸 자체는 판별하지 않으므로(`crates/tasty-net-ssh/src/discovery.rs`@b103e90), Git용 원격 셸 판정은 따로 둔다(프로필 값 또는 앱의 판정 명령). 저장소 판정은 한 번의 실패로 정하지 않고 셋을 따로 본다: (1) 저장소 발견 여부(대상 경로 접근 실패와 "저장소를 찾지 못함"을 나눈다), (2) 작업 트리가 필요한 기능(상태, 작업 트리·인덱스와의 미커밋 변경 비교)의 사용 가능 여부(bare 저장소나 `.git` 폴더 안에서는 `--is-inside-work-tree`가 `false`라 저장소는 있어도 그 기능만 비활성. 커밋 사이 비교와 기록은 bare에서도 된다), (3) Git 실행 거부(`safe.directory` 등)와 일반 실행 실패. 판정 결과는 5.8의 시각적 피드백으로 보인다.

결정 대기: Git 정보로 보여 줄 범위(기획 초안 3.2, 사용자 몫).

### 5.3 내장 SSH SFTP

정해진 것: Explorer(전제 3)는 SFTP로 읽는다.

제안:

- 1단계는 읽기만 한다: 목록(이름·종류·크기·mtime·권한), 링크 대상 읽기(진행 중 설계는 목록 `read_dir`와 링크 대상 `read_link`를 따로 둔다), `stat`·`lstat`, `realpath`, 범위 읽기(offset·len). 실패 사유를 권한 없음·없음으로 나눠 받는다(#4).
- subsystem 실패: SSH의 subsystem 요청 실패 응답은 서버가 SFTP를 껐는지까지 알려 주지 않는다(RFC 4254 6.5). 그래서 "SFTP 꺼짐"이라고 단정할 근거가 있는 경우와 "서버가 SFTP 요청을 거절함(사유 모름)"을 나눠 보인다.
- 세션 공유(#6): 진행 중 설계는 용도(터널·포트 발견·exec·SFTP)마다 config를 해석하고, 해석 결과의 연결 값 묶음(세션 키)이 같을 때만 세션 하나를 나눠 쓴다. `Match command`·`Match sessiontype`으로 용도마다 대상이나 키가 달라지면 같은 Host 별명이라도 세션이 둘이다. 세션은 그 위 핸들(터널·exec·SFTP)이 하나라도 살아 있는 동안 유지되고 마지막 핸들이 닫히면 닫힌다. 앱은 같은 Host의 Explorer와 Git 화면이 대개 한 세션을 쓴다고 기대하되, 화면 수명과 핸들 수명을 맞춰 쓰지 않는 핸들을 닫는다.
- 경로 문법: Explorer의 경로 잇기와 부모 구하기는 원격 Tasty가 descriptor에 알리는 `path_syntax`(`Posix`·`Windows`, 5.4)를 따른다. 경로 모양(드라이브 문자, 구분자)으로 원격 OS를 추정하지 않는다.

결정 대기: 쓰기(쓰기·이름 바꾸기·삭제, #5)는 기획 초안 4.4(편집 범위, 사용자 몫)가 정해진 뒤.

### 5.4 원격 Tasty attach

정해진 것:

- 범위(U2): 1단계는 원격 Tasty의 워크스페이스 목록과 상태다. 화면 attach는 2단계다.
- 경로(`identity.md` "토큰 전달"@b103e90, `transport.md` "채널 대응"·"요청 채널"@b103e90):
  1. exec로 포트 발견. 원격의 연결 레코드(포트·토큰·인스턴스 ID)를 한 번에 읽는다. 기존 발견기와 레코드 파서, 그 우선순위와 폴백 규칙을 그대로 쓴다(정본: `tasty-net-ssh::discover_remote_port`, `repo-boundary.md` "포트 발견의 tasty 관례"@b103e90). 숫자 포트만 오면(전환 전 Tasty) `SshTransport::connect`가 `ConnectError::PeerTooOld`(`evidence: PortOnlyDiscovery`)로 끝내고 다시 걸지 않는다. 연결한 뒤 v1 거절 ACK를 읽은 경우는 handshake의 `HandshakeError::PeerTooOld`(`evidence: V1Ack`)다(`crates/tasty-net-transport/src/ssh.rs`·`handshake.rs`@b103e90). FFI는 두 경로의 `evidence`를 보존한다.
  2. 로컬 루프백 포워딩 종단으로 `direct-tcpip` 터널.
  3. 채널마다 handshake v2. 첫 메시지의 `purpose`가 `attach`·`bulk`·`request` 중 하나다. `attach`·`request`는 토큰과 기기 서명으로 인증한다. `bulk`는 토큰과 부모 attach 링크가 발급한 자격을 내고 ACK를 받으며, 기기 서명을 다시 주고받지 않는다(`handshake.md` "상태 표"·"bulk 자격"@b103e90). 요청 채널은 attach 링크 없이 혼자 열 수 있다.
- 토큰은 로그·UI에 내보내지 않는다.
- 원격 Tasty 기기 키 신뢰 저장소(`PeerKeyStore`)는 소비자인 앱이 준다. 첫 등록은 `pin_if_absent` 한 번으로 원자적으로 정하고, 기록을 바꾸는 길은 사용자의 명시적 재등록(`reregister`) 하나다(`identity.md` "TOFU"@b103e90, `handshake.md` "검사 순서"@b103e90). 앱이 dialer로 키 바뀜을 발견하면 연결 시도는 `HandshakeError::PeerKeyMismatch { expected, got }`로 끝난다(`crates/tasty-net-transport/src/handshake.rs`@b103e90). `peer_key_mismatch`는 받는 쪽이 보내는 거절 사유 코드다.
- 원격 쪽이 v2 연결 레코드를 게시하는 일은 tasty 몫이다. Android attach는 그 게시가 있는 tasty에만 붙는다.
- 메서드와 응답은 tasty가 정한다(R3). 요청 채널은 운반만 한다(`transport.md` "요청 채널"@b103e90).
- 1단계 메서드(R3 A): 읽기 전용 원자 메서드 `workspace.overview`. 응답 모양은 워크스페이스마다 `{id, name, subtitle, busy_count, mirror, ws_holder?, surfaces: [{id, kind, title, busy, holder?, granted_seq?}]}`와 capabilities다. 단일 서피스 점유는 `attach.list`의 `attached[]{surface_id, holder, granted_seq}`에 이미 있다. 크레이트 이름·sha를 받기 전에도 이 모양으로 Kotlin 모델을 가정해도 된다.
- 도메인 타입(R3 B): attach 도메인 메시지와 descriptor의 serde 타입만 담은 tasty 크레이트. Android에서 빌드할 수 있다. 기존 크레이트를 쓰지 않고 새로 만드는 근거(tasty `a8c4d26f4d` 기준): 지금 그 타입이 있는 `tasty-ipc`는 rusqlite bundled·memory·telemetry·agent 의존을 함께 끌고 오고, `tasty-remote`는 ssh 전제다. 2단계(화면 attach)가 이 크레이트를 쓴다.
- 경로 문법(R3 B): 원격 Tasty는 descriptor에 `path_syntax`(`Posix`·`Windows`)를 알린다. 앱은 원격 경로를 잇거나 부모를 구할 때 이 값을 쓰고, 경로 모양으로 OS를 추정하지 않는다.
- 미지원 메서드 코드: 원격 Tasty가 모르는 메서드에는 JSON-RPC 오류 코드 `-32601`(method not found)로 답한다. tasty는 이 값을 R3 (B) 크레이트의 공개 상수 `METHOD_NOT_FOUND`로 둔다(값은 바뀌지 않는다).

제안:

- 발견과 터널의 대상 일치: 용도마다 config를 해석하므로(5.3) 포트 발견 exec와 터널의 실제 대상이 `Match`로 달라질 수 있다. v2는 상대 기기 서명을 확인하기 전 첫 메시지에 토큰을 싣는다. 그래서 발견한 레코드(토큰 포함)는 발견과 같은 유효 대상(호스트·포트·사용자·점프 홉)으로 가는 터널에만 쓴다. 두 해석의 유효 대상이 다르면 토큰을 보내기 전에 구성 오류로 끝낸다. 이 확인은 발견 결과로 터널을 만들거나 다시 쓰고 레코드의 토큰을 넣는 transport 경로(`crates/tasty-net-transport/src/ssh.rs`@b103e90)에서, 포트 발견과 터널 연결에 실제로 쓴 유효 대상을 비교하는 방식으로 하자고 요구한다. 연결 전 설정 점검(`inspect`)의 결과로는 실제로 쓴 대상을 보증하지 못한다. FFI는 불일치 오류를 그대로 넘긴다(#7).
- 기기 키: tasty-network identity의 `DeviceKey::load_or_create`를 `noBackupFilesDir/net/`(5.6) 아래 키 폴더로 부른다. 같은 폴더의 `device_key`를 다시 쓰므로 폴더가 바뀌면 새 기기 ID가 생긴다. 그래서 ②의 임시 export부터 이 최종 경로를 쓰고, ③에서 ffi의 `TastyNet` 생성 인자에도 같은 폴더를 넘긴다. 기기 키와 연결 레코드의 `File::lock` 동작은 다른 경로이므로, 기기 키 시험이 레코드 쪽 잠금을 증명하지 않는다(#9).
- `PeerKeyStore` 구현: `net/peers/` 아래 상대마다 파일 하나. 저장 키(`PeerRef`)는 편집할 수 있는 Host 별명이 아니라 앱이 프로필마다 붙이는 바뀌지 않는 ID다(별명을 바꿔도 기록이 빠지지 않게). `pin_if_absent`는 기기 키 코드(`crates/tasty-net-identity/src/device_key.rs`@b103e90)와 같은 방식으로, 내용을 다 쓴 임시 파일을 `hard_link`로 게시한다. 경쟁에서 진 호출은 이미 확정된 ID를 읽어 `Existing`으로 돌려준다. 그래서 같은 상대에게 겹친 첫 연결에서도 `Inserted`는 하나다.
- 키 바뀜 화면: 연결 상태 요약(`ConnectionTable`의 마지막 원인)이 아니라 연결 시도가 돌려준 구조화된 오류(`PeerKeyMismatch { expected, got }`)에서 두 키를 받아 보여 주고, 사용자가 확인하면 `reregister`를 부른다. b103e90의 연결 목록 요약은 이 오류를 문자열로 줄이므로 화면 근거로 쓰지 않는다(`connection_table/info.rs`@b103e90). 전환 전 Tasty 판정(`PortOnlyDiscovery`·`V1Ack`)도 같은 시도 오류에서 읽는다.
- 요청 채널: transport의 `dial_request`는 이미 `RequestClient`를 돌려준다(`handshake.rs`·`request.rs`@b103e90). 요청 ID, 응답 연결, 시간 제한, 채널 닫기를 그 객체가 맡으므로, 1단계는 ffi가 `RequestClient`를 감싸 주고 앱은 `workspace.overview`를 부르기만 한다(#7). 1단계는 attach 링크나 descriptor에 기대지 않는다. 소유: `RequestClient` 핸들은 Kotlin의 원격 Tasty 저장소 객체 하나가 갖고 닫는다. 호출 결과(JSON 바이트 또는 문자열)는 Kotlin이 해석하지 않고 `tasty-mobile-core`의 serde 해석 함수로 넘겨 UniFFI 레코드로 받는다. b103e90의 `dial_request`는 상대가 `net.request`를 광고했는지를 열기 조건으로 보지 않으므로, handshake 뒤 첫 호출 전에 상대 `caps`를 확인하는 일은 ffi가 그 값을 내주면 앱이 한다(#7 확인 항목).
- 두 capability를 나눈다: handshake의 `net.request`(전송 계층 capability, `handshake.md` "capability"@b103e90)는 요청 채널을 열 수 있는지이고, `workspace.overview` 응답의 capabilities는 R3의 도메인 계약이다. 세 근거를 따로 다룬다: `net.request` 미광고는 "원격 Tasty가 요청 채널을 광고하지 않음"(구버전 판정은 `PortOnlyDiscovery`·`V1Ack`로만 한다), 원격 메서드 미지원과 응답의 도메인 capability 부족은 각각 "원격 Tasty가 이 기능을 지원하지 않음"의 다른 사유다. `RequestClient`는 원격이 돌려준 메서드 오류를 `RequestError::Remote(Value)`로 넘기고, 원격의 error 객체를 통째로 보존한다(`crates/tasty-net-transport/src/request.rs`의 `Remote` 변형, tasty-network develop `130c5d4`). 메서드 미지원은 문자열 비교가 아니라 그 객체의 `code`로 판정한다: `error.code`가 숫자 `-32601`(`METHOD_NOT_FOUND`)일 때만 미지원으로 본다. `code`에는 숫자도 문자열도 올 수 있으므로, 문자열 `"-32601"`이나 다른 값은 미지원이 아니라 일반 원격 오류로 다룬다. 미지원이면 시각적 피드백만 보인다(전제 5, 5.8).
- 다시 읽는 조건: `workspace.overview`는 호출 시점의 원자 응답이고, 요청 채널은 호출마다 응답을 돌려줄 뿐 바뀜을 알리지 않는다. `ConnectionTable` 이벤트도 연결 전이만 알린다. 그래서 1단계는 첫 조회, 사용자의 새로고침, 재연결 뒤, 앱이 앞으로 돌아온 뒤에 다시 읽는다. 링크가 살아 있는 동안의 점유 바뀜을 자동으로 받는 일은 2단계(attach 도메인 메시지) 이후로 둔다.
- 응답 크기: b103e90의 요청 채널은 직렬화된 응답을 프레임 하나(`MAX_FRAME_LEN`, 1 MiB)로 제한하고, 넘으면 `RequestError::ResponseTooLarge`(원격 응답 코드 `net.response_too_large`)를 돌려준다(`crates/tasty-net-transport/src/request.rs`@b103e90). `workspace.overview`는 워크스페이스와 서피스를 한 응답에 담으므로, 앱은 이 오류를 FFI에서 그대로 받아 "응답이 너무 큼"으로 보이고 빈 목록이나 기능 미지원으로 읽지 않는다. R3의 원자 응답 계약은 바꾸지 않는다.
- 응답 해석: Kotlin에 JSON 해석 의존을 따로 들이지 않고 `tasty-mobile-core`의 serde 타입으로 해석한다. R3 답은 (B) 크레이트가 attach 도메인 메시지와 descriptor 타입을 담는다고 적고, `workspace.overview` 응답 타입까지 담는지는 적지 않았다. 그래서 우선 `tasty-mobile-core` 안에 R3 답의 응답 모양과 같은 serde 타입을 두고, (B)나 (A)의 반영이 그 응답 타입을 공개하면 그것으로 바꾼다.
- 2단계(화면 attach): A1 탭바에서 원격 서피스를 탭 하나로 연다. 렌더러는 `work/android-terminal`의 터미널 화면을 다시 쓴다. 이 단계에는 tasty의 mirror 계약(descriptor, snapshot·delta, 리사이즈, 입력 확인. `transport.md` "순서 계약과 채널" O1~O15@b103e90)과 R3 (B)의 도메인 메시지가 필요하다. 2단계의 자세한 계획은 1단계가 끝난 뒤 따로 쓴다.

결정 대기:

- tasty: R3 (A)·(B)의 반영과 크레이트 이름·sha(`METHOD_NOT_FOUND`·`path_syntax`가 들어 있는 판), 전환한 Tasty의 v2 레코드 게시.
- 사용자: 대화형 원격 SSH 터미널(기획 초안 4.2 "대화형 SSH 터미널을 넣는다" 행)은 U2가 정하지 않았다. 기획 초안 4.2의 열린 선택지로 남는다.

### 5.5 권한

정해진 것(84278b0 사실): `targetSdk = 37`(`app/build.gradle.kts`)이고 매니페스트(`app/src/main/AndroidManifest.xml`)에 권한 선언이 없다.

제안:

- `INTERNET`: 모든 원격 연결에 필요하다.
- `ACCESS_NETWORK_STATE`: 망 상태를 구독해 `AppEvent::NetworkChanged`·`NetworkLost`(5.7)를 만들 때 필요하다.
- LAN 직접 접속 권한: Android 17을 대상으로 하는 앱은 같은 망 기기에 직접 접속할 때 런타임 권한(`ACCESS_LOCAL_NETWORK`)이 필요하고, Rust 소켓에도 적용된다고 알려져 있다. 권한 이름과 조건은 작업 시점 Android 문서로 다시 확인한다. 이 권한은 Android 17 이상에서 SDK 37 이상을 대상으로 하는 앱의 직접 LAN 소켓 연결에 적용된다. 첫 원격 연결이 인터넷 대상일 수 있으므로, 요청은 직접 LAN 연결을 할 때로 좁힌다. 판정은 config의 문자열이 아니라 실제 첫 SSH 홉(`ProxyJump`가 있으면 첫 점프 호스트)의 이름 풀이 결과로 한다. 풀이 결과가 사설·링크 로컬 범위면 LAN 연결이다. 다만 Android 17에서는 `.local` 이름 풀이 자체에 LAN 권한이 필요할 수 있어, 권한 없이 풀이부터 하면 주소를 얻기 전에 막히고 소켓 FD로 진단할 수도 없다. 그래서 유효 설정의 첫 SSH 홉이 `.local`이면 이름 풀이 전에 권한을 확인하고 요청한다. 이 판정은 Rust 안(이름 풀이 뒤)에서 일어나므로 그 결과와 차단 사유를 FFI 오류로 받는 길이 필요하다(6절 #14). 거절했거나 회수됐으면 소켓을 열지 않고 그 사유로 끝내며(5.8, #14 (a)) 설정으로 가는 길을 준다.
- 차단 진단: 권한이 없다는 상태와 TCP 시간 초과만으로는 LAN 차단이라고 확정할 수 없다. Android 문서가 실패한 TCP 소켓의 FD로 차단 사유를 읽는 NDK API를 준다. FFI 오류로 바뀐 뒤에는 그 FD가 남아 있다는 보장이 없으므로, 진단은 소켓을 가진 Rust 계층이 소켓을 닫기 전에 하고 결과를 오류에 싣는다. 그 API는 `minSdk 29`보다 새 Android에만 있으므로, OS 판과 심벌 가용성을 실행 중에 확인하는 호출 경계(약한 심벌 또는 동적 조회)로 부르고 API 29 빌드를 유지한다. 이 진단을 tasty-network 소켓 안에서 할지, 앱이 소켓 생성 훅을 줄지는 #14의 확인 항목이다.

결정 대기: 없음.

### 5.6 저장과 백업

정해진 것(84278b0 사실): `android:allowBackup="false"`이고, `backup_rules.xml`과 `data_extraction_rules.xml`이 다섯 도메인(root, file, database, sharedpref, external)의 루트(`data_extraction_rules.xml`에서 `path`를 생략한 것은 그 도메인 루트를 뜻한다)를 클라우드 백업과 기기 이전에서 뺀다. 기기 보호 저장소의 `device_*` 도메인은 두 파일에 없다.

제안:

- 비밀(감싼 키 `keys/`, 기기 키, `PeerKeyStore`)은 `filesDir`이 아니라 앱 전용 `noBackupFilesDir` 아래에 둔다. 그러면 백업 규칙 파일이 빠뜨린 도메인이 있거나, 일부 기기에서 `allowBackup=false`만으로 기기 이전이 막히지 않는 경우에도 비밀이 백업·이전 대상이 되지 않는다. 5.1 표의 `keys/`·`net/`·`ssh/`가 이 폴더 아래다.
- `ssh/` 폴더 전체(`config`, `known_hosts`, 공개키)도 `noBackupFilesDir` 아래에 둔다. 내장 백엔드는 `embedded.ssh_dir`로 받은 폴더를 기준으로 읽으므로 폴더 안의 상대 경로와 파일 형식은 그대로다. 그러면 나중에 일반 파일의 백업 범위를 넓혀도 SSH 신뢰 기록(`known_hosts`)은 따로 보호된다. `filesDir`에는 비밀이 아닌 앱 설정(`tasty/profiles.toml` 등)만 남는다.
- 그래서 새 기기에서는 기기 ID가 새로 생기고, 개인 키는 다시 만들거나 가져온다. 백업 범위를 넓히는 일은 비밀을 뺀다는 확인과 함께 따로 정한다. 두 XML에 `device_*` 도메인을 더할지는 그 도메인을 쓰는 단계에서 정한다.

결정 대기: 없음.

### 5.7 연결 상태 보기

정해진 것:

- 백그라운드(U5): 앱이 앞에 있을 때만 연결을 유지한다. 진행 중 초안의 수명 설정 기본값(`pause`)을 그대로 쓴다. 포그라운드 서비스는 두지 않는다. 백그라운드 지속 연결을 실제로 제공하는 단계에서 다시 묻는다.
- 진행 중 초안(`6bad6ff` "앱 수명")의 기본 `pause`는 대기 중인 SSH 재연결을 멈추고 열린 터널은 그대로 둔다. P2P 등록은 유예(`lifecycle.background_grace`) 뒤 닫는다. 일반 SSH 세션은 마지막 핸들이 닫힐 때 닫힌다(5.3). 그래서 앱이 아무것도 하지 않으면, OS가 앱 실행을 멈췄을 때 모든 연결이 곧 끊기고 원인이 남는다고 단정할 수 없다.

제안:

- 두 층을 나눠 보인다.
  - (a) Tasty 전송 링크: tasty-network의 `ConnectionTable`(포트 발견을 거치는 터널 단위의 항목, `transport.md` "연결 목록"@b103e90). 스냅샷 `connections()`, 전이 이벤트, 상태(`connecting`·`connected`·`reconnecting`·`disconnected`), 마지막 원인.
  - (b) attach 없이 쓰는 일반 SSH 세션(Explorer·Git용). b103e90의 `ConnectionTable`에는 이 단위가 없다. 같은 상태 API로 볼 수 있게 해 달라는 것이 새 요구다(#6b).
- 화면이 쓰는 값: 연결 목록, 실패 사유, 다시 시도할 수 있는지(`is_retryable()`), 재시도 대기 표시. 이벤트는 `EventStream::next(timeout)`으로 끌어오고, 이벤트가 유실되면(`Lagged`) 스냅샷을 다시 읽는다.
- 앱 수명 알림: 앱의 앞·뒤 전환과 망 바뀜·끊김을 `AppEvent`(`Foreground`·`Background`·`NetworkChanged`·`NetworkLost`)로 라이브러리에 넘긴다. 앱이 앞으로 돌아오면 상태 화면이 재연결 상태를 보인다.
- U5(앞에 있을 때만 유지)를 앱이 지키는 방법은 한 가지다: 앱이 `Background`를 받으면 앱이 관리하는 연결을 모두 바로 닫고, `Foreground`를 받으면 앱이 기억한 대상을 앱이 다시 연다. 백그라운드 동안 열어 두는 연결은 없다.
  - 닫을 때: 저장소 객체는 새 연결·호출을 받지 않는다. 진행 중인 연결 시도는 취소하고, 떠 있는 `SshPrompt`는 거절로 답한다. 열어 둔 exec·SFTP 핸들, 터널·링크, 요청 채널(`RequestClient`)을 닫는다. b103e90의 `RequestClient`는 따로 닫는 핸들을 주고, 그냥 drop하면 종료 응답을 기다릴 수 있으므로(`request.rs`@b103e90) 닫는 핸들로 닫는다. 취소한 뒤 늦게 성공해 돌아온 핸들은 받는 즉시 닫고 화면에 넘기지 않는다.
  - 다시 열 때: 앱이 모두 닫았으므로 라이브러리에는 그 대상의 재연결 예약이 남지 않는다(진행 중 설계의 `Foreground`는 라이브러리가 멈춰 둔 재연결만 다시 시작한다). 앱의 저장소 객체가 닫기 전에 화면에 필요했던 대상(열린 Explorer·Git 화면의 Host, 원격 Tasty 목록 화면의 대상)을 기억해 두었다가 다시 열고, 원격 Tasty 목록은 다시 읽는다(5.4).
- 앱 수명 이벤트를 라이브러리에 넘기는 일과 이 정리는 ⑥ 상태 화면을 기다리지 않고 핸들을 처음 여는 ④·⑤에서 만든다. ⑥은 그 결과를 모아 보이는 화면이다(8절).

결정 대기: tasty-network가 #6b를 받을지와 그 모양.

### 5.8 기능이 없을 때의 시각적 피드백

전제 5와 기획 초안 3.4의 경우를 각 흐름의 실패 사유와 짝짓는다. 모양(배너, 빈 화면 안내, 비활성 표시)은 정하지 않는다.

| 경우(기획 3.4) | 흐름 | 받는 실패 사유 |
|---|---|---|
| 원격에 `git`이 없다 | 5.2 | 원격 셸별 설치 확인 명령의 결과(종료 코드 하나로 정하지 않는다) |
| 대상 폴더가 git 저장소가 아니다 | 5.2 | 저장소를 찾지 못함. 경로 접근 실패, `safe.directory` 거부, 일반 실패, 종료 신호, 세션 손실과 나눈다 |
| 저장소는 있지만 작업 트리가 없다(bare 등) | 5.2 | 작업 트리 판정 `false`. 상태와 미커밋 변경 비교만 비활성으로 보이고, 기록과 커밋 사이 비교는 그대로 쓴다 |
| SFTP 서브시스템이 꺼져 있거나 서버가 거절했다 | 5.3 | 꺼짐을 단정할 근거가 있는 경우와 subsystem 요청 거절(사유 모름)을 나눈다(#4) |
| 권한이 없다 | 5.3 | SFTP 실패 사유 "권한 없음"(#4) |
| 원격 Tasty가 없거나 실행 중이 아니다 | 5.4 | 포트 발견 실패(원격 인스턴스 없음·종료), 원격 리스너 없음 |
| 원격 Tasty가 전환 전 판이다 | 5.4 | `PeerTooOld { evidence: PortOnlyDiscovery }` 또는 v1 거절 ACK(`evidence: V1Ack`) |
| 원격 Tasty가 요청 채널을 광고하지 않는다 | 5.4 | handshake `caps`에 `net.request` 없음(구버전 판정과 별개) |
| 원격 Tasty가 기능을 지원하지 않는다 | 5.4 | `RequestError::Remote(Value)`의 `error.code`가 숫자 `-32601`(`METHOD_NOT_FOUND`, 숫자일 때만), `workspace.overview` capabilities에 값 없음. 시각적 피드백만 보인다 |
| 원격 목록이 너무 크다 | 5.4 | `RequestError::ResponseTooLarge`. "응답이 너무 큼"으로 보이고 빈 목록·미지원으로 읽지 않는다 |
| 원격 Tasty 기기 키가 바뀌었다 | 5.4 | 연결 시도 오류 `HandshakeError::PeerKeyMismatch { expected, got }` |
| LAN 권한을 거절했거나 회수됐다(연결 전) | 5.5 | 앱의 권한 요청·확인 결과. 소켓을 열기 전이라 차단 사유를 읽을 소켓이 없다 |
| LAN 연결이 권한 때문에 막혔다(연결 중) | 5.5 | 소켓을 닫기 전에 읽은 차단 사유(6절 #14) |
| 망이 없다 | 5.7 | `NetworkLost` |

## 6. tasty-network 요구 목록 (NEEDS-REPO R2)

R2 처리 결과: 아래 첫 표 #1~#11(6b·7b 포함)을 tasty-network에 전달했다(2026-11-27). 이 문서가 더한 것(#7+ 행, 아래 둘째 표 #12~#15)도 전달됐다(2026-11-27). 그 항목들이 tasty-network 설계 문서의 어디에 들어갔는지는 각 행에 적는다(tasty-network develop `130c5d4` 기준). "원래 상태"는 R2 전에 tasty-network 쪽에 무엇이 있었는지다. R2 전의 요구는 exec(표준 입출력·종료 상태)와 SFTP 지원, 같은 세션 위에서 함께 쓰는 경계까지였고, 그 위의 세부 API(출력 상한·시간 제한·취소·env, 범위 읽기, 실패 분류 등)는 R2가 구체화했다. "후속 작업 문서" 열은 tasty-network의 내장 SSH 작업·ffi 작업 문서에 그 항목이 직접 적혀 있는지(있음), 전달 기록으로만 확인되는지(전달 기록)다.

| # | 항목 | 앱이 쓰는 곳 | 원래 상태 / 후속 작업 문서 |
|---|---|---|---|
| 1 | 내장 SSH 연결: 앱이 준 폴더(앱의 `ssh/`를 `~/.ssh`로 봄)의 OpenSSH 형식 config·키·known_hosts로 접속. 비밀번호·passphrase·keyboard-interactive·호스트 키 확인(`ask`일 때. `accept-new`는 자동 기록)을 `SshPrompt` 콜백으로. 모르는 지시어 거절, `@cert-authority` 1단계 미신뢰 | 5.1 | 진행 중 초안과 후속 결정에 있음 / 있음 |
| 2 | 키를 파일 대신 메모리 바이트로 줄 수 있는 길. 메모리 키의 수명과 지우기, 설정 조회에 키 바이트가 나오지 않는 것. **U3=B라 요구다** | 5.1 | 새 / 있음 |
| 3 | exec: 명령 실행, stdout·stderr 스트림 또는 상한 있는 수집, 종료 상태, 시간 제한·취소, env | 5.2 | exec 실행·표준 입출력·종료 상태는 이미 요청됨, 상한·시간 제한·취소·env는 R2가 구체화 / 있음 |
| 4 | SFTP: 같은 SSH 세션 위 subsystem. 목록(이름·종류·크기·mtime·권한), 링크 대상 읽기(별도 연산), `stat`·`lstat`, `realpath`, 범위 읽기(offset·len), 실패 사유 구분(권한 없음, 없음, subsystem 요청 거절. 꺼짐은 근거가 있을 때만) | 5.3, 5.8 | SFTP 지원은 이미 요청됨, 연산 목록·범위 읽기·실패 사유 구분은 R2가 구체화 / 있음. 전달 당시 문구는 `readdir`에 링크 대상을 넣고 "subsystem 꺼짐"을 구분하는 것이었다. 진행 중 설계(`6bad6ff`)는 링크 대상을 `read_link`로 따로 두고, 이 문서는 subsystem 요청 거절(사유 모름)을 꺼짐과 나눈다(5.3) |
| 5 | SFTP 쓰기(쓰기·이름 바꾸기·삭제) | 기획 4.4 결정 뒤 | 보류 / 보류로 있음 |
| 6 | 한 SSH 세션을 터널·exec·SFTP가 함께 쓰는 경계 | 5.3, 5.7 | 이미 요청됨 / 있음 |
| 6b | attach 없이 쓰는 일반 SSH 세션의 수명·끊김 이벤트를 `ConnectionTable`과 같은 상태 API로 보기 | 5.7 | 새 / 있음 |
| 7 | 원격 Tasty attach 경로: exec 포트 발견 → `direct-tcpip` → handshake v2 → `attach`·`request`·`bulk` 채널. 채널 위 프레임 읽기·쓰기를 FFI로 노출(도메인 해석은 앱·tasty 쪽). 토큰은 FFI 밖 로그·UI·오류·Debug 출력에 내지 않는다 | 5.4 | 경로는 설계에 있음. FFI 노출 범위는 새 / 있음 (전달됨) |
| 7+ | #7에 이 문서가 더한 확인 항목: 1단계용으로 `RequestClient`(호출·닫는 핸들)를 감싸 노출, 상대 `caps`(`net.request`)를 앱이 볼 수 있게, `RequestError::ResponseTooLarge`·두 `PeerTooOld` 경로의 `evidence` 보존, 발견과 터널의 유효 대상 일치를 transport 경로에서 확인하고 불일치를 오류로 넘기기 | 5.4 | 새 / 전달됨(2026-11-27). `RequestClient`·`caps`·`evidence`는 `docs/design/mobile.md` "바인딩 API (요구를 받을 자리)"의 요청 채널 행과 "android 요구의 자리" #7+ 행. 발견과 터널의 대상 일치는 `docs/design/ssh-backends.md` "포트 발견과 터널의 대상 일치"(불일치는 `ConnectError::TargetMismatch`) |
| 7b | `PeerKeyStore`(앱이 구현하는 신뢰 저장소)의 FFI 경계: 앱이 구현하는 콜백 인터페이스, 최초 등록 원자성, 사용자 재등록 | 5.4 | 새 / 있음 |
| 8 | 연결 목록·사유·`is_retryable()`·`EventStream::next(timeout)`·`AppEvent` | 5.7 | 진행 중 초안에 있음 / 있음 |
| 9 | identity: 앱이 준 폴더의 기기 키 만들기·읽기. `File::lock`이 Android에서 잠그는지는 연결 레코드 경로의 문제라 따로 확인한다 | 5.4 | 기기 키는 있음. `File::lock` 확인은 새 / 있음 |
| 10 | Android용 태그: 모바일 빌드에서 `system-ssh` 꺼짐, `aarch64-linux-android`·`x86_64-linux-android`, NDK API 29. 앱은 그 태그로 rev를 올린다. 태그 시점을 알린다 | 3절 | 진행 중 초안에 있음. 태그 시점 통지는 새 / 전달 기록 |
| 11 | 내장 백엔드에서 기존 PowerShell·cmd 포트 발견 계약(`repo-boundary.md` "포트 발견의 tasty 관례"@b103e90)이 그대로 도는지 검증 | 5.4(데스크톱이 Windows일 때) | 새(검증 요구) / 있음 |

R1에서 함께 요구한 것(전달됨): `tasty-net-ffi`가 `lib`로 다른 cdylib에 링크돼도 동작할 것, UniFFI 판을 앱과 맞출 것.

이 문서를 쓰며 새로 찾은 확인 항목(모두 전달됨, 2026-11-27). "tasty-network 쪽 위치"는 tasty-network develop `130c5d4`의 설계 문서다:

| # | 항목 | 앱이 쓰는 곳 | tasty-network 쪽 위치 |
|---|---|---|---|
| 12 | 라이브러리 모드에서 `tasty-net-ffi` 생성 Kotlin의 패키지 이름과, 앱이 그것을 덮어쓸 필요가 있는지 | 2절 | `docs/design/mobile.md` "크레이트"의 Kotlin 패키지 이름 항목과 "android 요구의 자리" #12 행 |
| 13 | #2의 메모리 키를 config의 `IdentityFile` 참조와 잇는 규칙(5.1 제안), `IdentitiesOnly`와 함께 쓸 때의 조건, 고른 메모리 자격이 세션 재사용 키에 들어가는지 | 5.1, 5.3 | `docs/design/ssh-backends.md` "android 요구의 자리" #2 행의 #13 문단, 세션 키는 같은 문서 "용도마다 해석하고, 같을 때만 세션을 나눈다". 5.1의 이름 잇기 제안은 같은 문서 "후속 작업과 미정"의 "메모리 키와 `IdentityFile` 이름" 행에 남아 있다 |
| 14 | 직접 LAN 연결 판정(첫 SSH 홉의 이름 풀이 결과, `.local`은 풀이 전)과, 두 사유를 FFI 오류로 따로 받는 길: (a) 소켓을 열기 전 앱의 권한 거절·회수로 연결을 시작하지 않음, (b) 연결 중 Android 권한으로 막혔을 때 소켓을 닫기 전에 읽은 차단 사유. API 29 빌드를 유지하는 호출 경계 | 5.5 | `docs/design/mobile.md` "모바일의 SSH" 아래 새 절 "직접 LAN 연결 (Android 지역망 권한)"과 "android 요구의 자리" #14 행 |
| 15 | 모바일 의존 쪽 검사(`6bad6ff` `mobile.md` "의존 쪽 검사")를 앱의 최종 그래프에서도 같은 조건으로 돌릴 수 있게 검사 기준을 공개 문서로 내는 것 | 7절 | `docs/design/mobile.md` "의존 쪽 검사"의 앱 최종 의존 그래프 항목과 "android 요구의 자리" #15 행 |

## 7. CI 계획

### 정해진 것

- develop `84278b0`의 CI(`.github/workflows/ci.yml`)는 `ubuntu-latest` 한 잡, `timeout-minutes: 30`, SDK 설치(`platforms;android-37.0`, `build-tools;37.0.0`) 뒤 4단계: `spotlessCheck` → `lint` → `test` → `assembleDebug`. 실패하면 `app/build/reports/`를 올린다.
- `work/android-terminal`의 CI는 그 위에 다음을 더한다(U1로 반영 예정): SDK 설치에 `ndk;30.0.16248370`, `rust/`에서 `rustup show active-toolchain || rustup toolchain install`과 `cargo install cargo-ndk --version 4.1.2 --locked`, `Swatinem/rust-cache@v2`(`workspaces: rust`), `rust/`에서 `cargo fmt --all --check`·`cargo clippy --workspace --all-targets --locked -- -D warnings`·`cargo test --workspace --locked`, 그 뒤 기존 Gradle 4단계.
- 생성된 UniFFI Kotlin은 커밋하지 않는다. `app/build/` 아래에 생기고 `.gitignore`의 `build/`에 들어간다. `spotlessCheck`도 `**/build/**`를 빼고 본다(루트 `build.gradle.kts`).
- CI의 push 대상 브랜치를 git-flow에 맞추는 일은 U1 반영 작업이 한다.

### 제안

- 생성 작업 의존: 두 ABI의 Rust 출력(`cargoNdkBuild`)과 Kotlin 바인딩(`uniffiBindgen`)은 입력·출력이 선언된 생성 작업으로 둔다. 생성 Kotlin을 컴파일하는 `lint`·`test`도 이 작업에 의존한다(`addGeneratedSourceDirectory`가 그 의존을 만든다). CI 순서상 Rust 빌드가 처음 도는 단계는 `assembleDebug`가 아니라 `lint`다.
- 단계 사이 재사용: Gradle 단계는 따로 호출되지만 같은 작업 폴더를 쓰므로, `lint`에서 만든 `.so`와 Kotlin을 `test`·`assembleDebug`가 최신으로 건너뛰어야 한다. 빌드 캐시와 구성 캐시는 `gradle.properties`에서 이미 켜져 있다.
- 호스트 bindgen: `uniffi-bindgen` 실행도 `--locked`다(지금 그렇다). 4절 입력 보강 뒤에는 생성기 크레이트·`uniffi.toml`·`Cargo.lock`·툴체인 파일이 바뀌면 다시 생성하고, ABI·NDK 판이 바뀌면 생성에 쓰는 첫 ABI `.so`의 내용이 바뀔 때만 다시 생성한다.
- Rust 캐시: `Swatinem/rust-cache@v2`는 기본 설정에서 Cargo의 git 의존 캐시(tasty-network, tasty), 설치된 실행 파일과 설치 기록을 캐시한다(그 action의 문서 "Cache Details"). 다만 지금 순서는 `cargo install cargo-ndk`가 캐시 복원보다 앞에 있어 매번 다시 컴파일한다. 캐시 복원을 앞으로 옮기고, 복원 뒤 `cargo-ndk --version`이 고정한 `4.1.2`인지 확인해 다를 때만 설치한다.
- Android target 검사: 지금 `clippy`는 호스트 target만 본다. `cfg(target_os = "android")` 코드와 tasty-network의 모바일 feature 조합을 보려면 Android target 하나(`aarch64-linux-android`)로 `clippy`를 한 번 더 돌린다. C 코드가 있는 의존(`ring`)은 NDK clang이 필요하므로 cargo-ndk를 거쳐 돌린다.
- 모바일 의존 확인: 2절 계약 4를 CI에서 본다. tasty-network 진행 중 초안(`6bad6ff` `mobile.md` "의존 쪽 검사")과 같은 조건을 최종 Android 일반 의존 그래프에서 확인하는 단계를 둔다. 그 초안은 아직 공개 원격에 없으므로(1절) 요약만 적는다: 다른 프로그램 실행으로 이어지는 feature·크레이트(`system-ssh` 등)가 그래프에 없어야 한다. 조건의 정본은 그 절이고, 공개 기준을 요구한다(6절 #15). Cargo는 같은 의존에 여러 경로가 켠 feature를 합치므로, 검사는 tasty-network 크레이트만 따로 풀지 않고 실제 배포 빌드(`tasty-mobile-core`, Android target, release)의 앱 전체 일반 의존 그래프에서 본다. 앱의 `tasty-terminal`이 쓰는 `portable-pty`는 다른 프로그램 실행 금지의 적용 범위 밖이라는 예외(2절)일 뿐, 의존 쪽 검사 조건은 앱 전체 그래프에 그대로 적용한다.
- 시간: 두 ABI release 빌드(`lto = "thin"`, `codegen-units = 1`)에 tasty-network 의존(`ring`, russh 등)이 더해진다. 첫 CI 실행 시간을 재고 `timeout-minutes: 30`을 그 값으로 다시 정한다.
- 라이선스: 앱 `.so`에 UniFFI 런타임(MPL-2.0)이 링크되고 JNA를 싣는다. `rust/`에 `cargo deny`를 둔다. 허용 목록은 tasty-network `deny.toml`@b103e90에서 시작하되 그대로 쓰지 않는다. (1) tasty-network의 OpenSSH 이식 후속 결정은 이식 크레이트에 `BSD-2-Clause AND ISC AND SSH-short`를 요구하는데 b103e90 허용 목록에는 `SSH-short`가 없다. 앱이 실제로 링크하는 판의 라이선스로 목록을 정한다. (2) 그 정책 파일은 crates.io 밖 git 출처를 거절한다(`unknown-git = "deny"`). 앱은 tasty와 tasty-network를 git으로 받으므로 두 출처를 `allow-git`에 적는다.
- 고지: 배포 경로와 별개로, 앱이 싣는 코드의 고지 항목(OpenSSH 이식 코드의 저작권 표시, UniFFI·JNA 라이선스)을 목록으로 둔다. 고지를 보이는 화면의 모양은 배포 경로(기획 4.9)를 정할 때 정한다.
- 증거 요구: CI 기록에서 (1) `lint` 단계가 `cargoNdkBuild`·`uniffiBindgen`을 실행하고 `test`·`assembleDebug` 단계는 최신으로 건너뛴다, (2) 두 번째 실행에서 cargo-ndk를 다시 컴파일하지 않는다, (3) Android target `clippy`와 `system-ssh` 확인 단계가 돈다, (4) 전체 시간이 새 상한 안이다.

### 결정 대기

- 없음. 라이선스 고지를 보이는 모양은 배포 경로(기획 4.9, 사용자 몫)를 정할 때 함께 정한다.

## 8. 단계

| 단계 | 내용 | 선행(레포·사용자 결정) |
|---|---|---|
| ① 원격 빌드 칸 | Android 빌드와 에뮬레이터 시험을 도는 원격 Windows 빌드 칸 | 없음 |
| ①′ 터미널 브랜치 반영 | `work/android-terminal`을 develop 위로 올리고 CI 4단계와 Rust 검사를 돌려 반영한다 | ①, U1 |
| ② Rust 연결 기반 | tasty-network develop rev 고정(`b103e90`). 앱 cdylib의 임시 export(3절)로 `noBackupFilesDir/net/` 아래 최종 키 폴더(5.4)에 기기 키를 만들고 읽고 기기 ID를 돌려준다. 두 ABI `.so`와 생성 Kotlin | ①′, 이 계획의 검증. b103e90의 transport는 시스템 ssh 크레이트에 늘 의존하므로 이 단계는 identity만 의존한다. 이미 있는 증거(b103e90 크레이트의 `aarch64-linux-android` `cargo check`)와 이 단계가 새로 내야 할 증거(두 ABI에서 API 29로 앱 링크, 기기·에뮬레이터 위 실행)를 나눈다 |
| ③ ffi로 교체 | `tasty-net-ffi`가 생기면 그것을 rlib으로 링크하고 ②의 임시 export를 지운다. ②와 같은 키 폴더를 넘겨 기기 ID를 유지한다. UniFFI 판을 맞추고 scaffolding 재노출(2절)을 붙인다 | tasty-network: transport ssh feature 분리, 내장 SSH(exec·SFTP), ssh-config 이식, ffi 크레이트, UniFFI 판 고정. 진행 중 초안의 ffi 의존에는 `tasty-net-p2p`가 들어 있으므로, 그 구성이 유지되면 p2p 크레이트의 반영도 선행이다 |
| ④ SSH 프로필 + exec + SFTP 읽기 | 5.1~5.3. 프로필 파일·키 생성과 가져오기, Git 정보, Explorer 읽기. 앱 수명 이벤트 연결(`AppEvent`), 백그라운드 정리(5.7), LAN 권한 경로(5.5)를 함께 만든다 | ③, #1·#2·#3·#4·#6·#13·#14. 화면 범위는 Git 정보 범위(기획 3.2)·뷰어(기획 3.3)의 사용자 결정 |
| ⑤-1 원격 Tasty 목록·상태 | 5.4 1단계. 링크·요청 채널의 백그라운드 정리(5.7)를 함께 만든다 | 모델·호출부 개발: ④, #7·#7b(R3 답의 응답 모양으로 시작, 어느 tasty 반영도 기다리지 않는다). 실제 목록 연동: tasty R3 (A) `workspace.overview` 반영, v2 레코드 게시, tasty 전환과 같은 tasty-network 태그 |
| ⑤-2 화면 attach | 5.4 2단계 | ⑤-1, tasty R3 (B) 반영(도메인 메시지·descriptor 타입), tasty mirror 계약 |
| ⑥ 연결 상태 | 5.7. 두 층 상태 화면(앱 수명 이벤트 연결과 백그라운드 정리는 ④·⑤에서 이미 만든다) | 화면 개발: ③, #6b·#8. 통합 검증(두 층을 실제로 모아 확인): ④(일반 SSH 세션), ⑤-1(Tasty 요청 연결) |

- ④~⑥의 구현 TODO는 각 선행이 풀릴 때 이 표를 기준으로 만든다.
- 기본 상태 표시(연결 중·끊김)는 ④·⑤에서 각 화면이 먼저 갖고, ⑥이 두 층을 모은 상태 화면을 만든다.

## 9. iOS

사용자 결정("구조만 iOS가 가능하게")에 따라 이 레포는 iOS를 하지 않는다. tasty-network 쪽 바인딩 경계(`staticlib`, Swift 생성)는 그 레포가 맡는다.

## 10. 기획 초안과의 관계

- [기획 초안](app-plan-draft.md) 4.6 표와 5절 `tasty-ssh` 행은 조사 기준 tasty `89efb5602`의 기록이라 고치지 않는다. b103e90의 `tasty-net-ssh`도 아직 시스템 ssh 위임이다(`crates/tasty-net-ssh/Cargo.toml` description). 모바일이 쓰는 내장 SSH는 tasty-network의 후속 계약이다. 2026-11-27 이후의 SSH 구현 방향은 이 문서를 따른다.
