# Android 네트워크 접근 기능 계획

## 1. 목적과 상태

- 상태: **초안**. Codex 계획 검증 3회차까지 끝났다(1회차 24건, 2회차 17건, 3회차 9건을 모두 반영. 규칙에 따라 3회차 반영본은 다시 검증하지 않았다).
- 요청: 2026-11-27의 Android 네트워크 접근 요청. 이 문서는 그 요청의 범위 2(Rust 연결 기반의 구조), 3(attach 등 네트워크 접근 기능의 계획), 4(CI 계획)를 맡는다. 화면 설계는 하지 않는다. 흐름과 필요한 API만 적는다.
- 기준 커밋:
  - 이 레포 develop `84278b0`.
  - tasty-network develop `b103e9073b9e0e33ac5f586d8cb3b3825f54fa1b`(짧게 `b103e90`). 이 문서에서 `@b103e90`을 붙인 인용은 그 커밋의 파일이다.
  - tasty-network의 모바일 설계(`docs/design/mobile.md`)와 SSH 백엔드 설계(`docs/design/ssh-backends.md`)는 develop `558d4c7`(`558d4c72fa9b541046d3bbafa205771b87dd123c`)에 들어갔다. 이 문서는 그 두 문서를 develop `558d4c7`로 인용한다. 그 전의 진행 중 초안(`bcbcfba`, 그 뒤 수정본 `6bad6ff`)을 인용한 곳은 develop 판과 내용이 달라 그대로 남긴 곳이다(그 초안의 기록이다). 이름과 값은 구현 작업에서 바뀔 수 있다.
  - 사용자 작업 브랜치 `work/android-terminal` `4415132f9e35247b98f328d2d5546b302c5dd8d6`(기점 `ab57f98`, 커밋 9개, 공개 원격에는 아직 없다). 로컬 터미널 화면과 Rust 연결 구조가 들어 있다. 사용자 결정 U1에 따라 이 브랜치는 develop `ccc0022`에 반영됐다(원본 커밋 9개를 develop 위로 올린 커밋과 CI 트리거 커밋 `ccc0022`. 올린 커밋이라 sha는 원본과 다르다. develop push CI 성공). 이 문서는 그 구조를 이어 쓴다.
- 쓰는 방법: 각 절은 **정해진 것 / 제안 / 결정 대기**로 나눈다. "정해진 것"은 아래 결정 표나 레포 지침에 출처가 있는 것이다. "제안"은 이 계획이 고른 설계이며 Codex 검증과 구현 리뷰에서 바뀔 수 있다. "결정 대기"는 사용자나 다른 레포가 정할 것이다.

### 결정 표

| 번호 | 내용 | 출처 |
|---|---|---|
| 전제 1~6 | 레포 지침 [`CLAUDE.md`](../../CLAUDE.md) "역할": 모바일은 데스크톱과 다른 형태로 만들고 원칙을 따로 정한다, 플러그인 시스템 없음(공식 플러그인 기능은 내장), Explorer 내장, 원격 파일은 SSH(파일은 SFTP, Git 정보는 원격 `git` 실행)이고 이를 위해 본체에 전용 채널을 더하지 않는다, 기능이 없으면 시각적 피드백, Kotlin + Compose와 UniFFI로 공유하는 Rust 크레이트. 전용 채널 금지는 파일·Git 경로의 규칙이다. 5.4의 요청 채널은 원격 Tasty 연동을 위해 tasty-network가 주는 채널이며 파일·Git에는 쓰지 않는다 | 레포 지침 |
| A1 | 앱을 열면 로컬 터미널 하나, 상단 탭바에 탭 하나, 탭바 오른쪽 위 메뉴 버튼 | 사용자 결정 2026-11-11 |
| SSH 백엔드 | 모바일 SSH는 tasty-network의 내장(russh) 백엔드를 쓴다. 내장 백엔드는 OpenSSH 설정·키·known_hosts 형식을 같은 뜻으로 읽고, exec 채널과 SFTP도 지원한다 | 같은 요청(2026-11-27)의 "이미 정해진 것" |
| handshake v2 | v2는 v1 상대를 거절한다. 마이그레이션은 지원하지 않는다. Android attach는 tasty-network로 전환한 데스크톱 Tasty에만 붙고, tasty 전환과 Android attach는 같은 tasty-network 태그를 기준으로 함께 간다 | 같은 요청 |
| U1 | `work/android-terminal`을 develop 위로 올려 반영한다 | 요청 답 2026-11-27 |
| U2 | 원격 Tasty는 목록·상태를 먼저 하고, 화면 attach는 다음 단계에서 한다(C) | 같은 답 |
| U3 | 앱에서 Ed25519 키를 만들어 Android Keystore의 AES 키로 감싸 저장한다. 기존 개인 키 가져오기와 공개키 복사 화면을 둔다. 비밀번호는 저장하지 않고 매번 묻는다(B) | 같은 답 |
| U4 | 접속 프로필은 앱의 `ssh/config`(OpenSSH 형식, UI 편집과 붙여넣기 가져오기)이고, Tasty 전용 값은 앱 고유 파일에 둔다(A) | 같은 답 |
| U5 | 연결은 앱이 앞에 있을 때만 유지한다(A)로 시작한다. 백그라운드 지속 연결을 실제로 제공하는 단계에서 다시 묻는다 | 같은 답 |
| U6 | Git 정보는 읽기만 한다. status·log·diff에 worktree 정보(worktree가 몇 개이고 어디에 있는지)를 더한다. 쓰기는 없다. 브랜치 목록 화면은 이번 범위에 요구되지 않았다(5.2) | 사용자 결정 2026-11-28. 요지: "읽기만 지원하는데, worktree 정보까지는 보여줘야" 하고 "worktree가 몇개있고 어디어디에 있다" 정도 |
| U7 | 뷰어는 공식 플러그인이 지원하는 형식만 연다. 지금은 Markdown·이미지·HTML이고, 공식 플러그인이 지원하는 형식이 늘면 따라 늘린다. 이미지 그리기(편집)는 이번 범위에 요구되지 않아 보기만 한다(5.3) | 사용자 결정 2026-11-28. 요지: "공식 플러그인에서 지원하는 목록만", "추후 … 늘어나면 다른 포맷도 지원" |
| U8 | 편집은 선택지 C 전체다: 텍스트 편집과 파일 관리(새 폴더·이름 바꾸기·지우기·기기 파일 업로드). 옮기기와 빈 파일 만들기는 이 계획이 더한 확장이다. 시점은 ④(읽기) 뒤이고, 지우기에는 확인 단계를 둔다(5.3) | 사용자 결정 2026-11-28: 선택지 C(텍스트 편집 + 파일 관리), 같은 날 보정 답 "기기 파일 업로드도 넣는다. 선택지 C 전체". 옮기기·빈 파일 만들기와, 시점(④ 뒤. ④의 목록·`stat`·범위 읽기를 쓰기 때문)과 지우기 확인 단계는 사용자가 정하지 않아 이 계획이 정했다 |
| U9 | 대화형 원격 SSH 터미널을 넣는다. 순서는 ⑤-2 화면 attach 뒤다. 첫 판은 앱이 뒤로 가면 셸이 끊긴다(U5 그대로, 5.9) | 사용자 결정 2026-11-28 |
| U10 | 라이선스 고지는 앱 안의 한 장짜리 고지 화면으로 보인다. 같은 고지 파일을 릴리스에도 붙인다. `cargo deny`와 CI의 고지 파일 생성 확인과 묶는다(7절) | 사용자 결정 2026-11-28 |
| R1 | 바인딩 크레이트 `tasty-net-ffi`는 tasty-network에 두고, 앱 cdylib에 rlib으로 링크해 ABI별 `.so` 하나로 싣는다. Kotlin 생성은 앱 Gradle 작업이 라이브러리 모드로 한다. UniFFI 판은 tasty-network가 고정하는 최신 안정판을 두 레포가 함께 쓴다(판 규칙은 뒤에 바뀌었다. 3절) | 같은 답 |
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
  2. 같은 UniFFI 판을 쓴다. 앱은 지금의 `uniffi = "0.32"`를 유지하고, tasty-network가 ffi 구현 시점에 고정하는 판에 맞춘다(3절).
  3. 모든 tasty-network 의존이 같은 rev를 가리킨다.
  4. 최종 Android 배포 빌드의 앱 전체 일반 의존 그래프에서 tasty-network의 `system-ssh` feature가 꺼져 있고, tasty-network develop `130c5d4` `docs/design/mobile.md` "의존 쪽 검사"의 앱 최종 의존 그래프 기준(7절)을 통과한다.
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
- UniFFI 판은 두 레포가 같은 판을 쓴다. 앱은 지금 0.32를 유지한다. 후보는 0.33.0이고, 판 고정은 tasty-network의 ffi 구현 시점에 한다(tasty-network develop `130c5d4` `mobile.md` "크레이트"의 UniFFI 판 항목도 "앱과 같은 판, 앱은 지금 0.32"로 적는다).
- Android용 태그가 나오기 전에는 git 의존 `rev = "130c5d46a59e7fb05b598ae2eb5ff5c77fda0005"`(tasty-network develop `130c5d4`) 고정으로 시작한다. ②에서 기기 키 게시 수정을 받으려고 `rev = "36cd2a29adeb33e0b8383767db74ec233c07535c"`(tasty-network develop `36cd2a2`)로 올렸다(8절). 태그로 올리는 시점은 tasty 전환과 같은 태그다.

### 제안

- 새로 합의한 것: 진행 중 초안 `bcbcfba`의 "바인딩 층"은 `crate-type = ["lib", "cdylib", "staticlib"]`의 `lib`를 "생성과 시험용"으로만 적었다. R1은 다른 cdylib에 rlib으로 링크돼도 동작하는 것(`uniffi::setup_scaffolding!` 네임스페이스 충돌 없음)을 요구로 더한다. 이 요구는 tasty-network ffi 작업에 전달됐다.
- ②단계의 임시 브리지: b103e90의 `tasty-net-identity`는 UniFFI에 의존하지 않고 UniFFI export가 없다(`crates/tasty-net-identity/Cargo.toml`@b103e90). 그래서 ②에서는 `tasty-mobile-core`가 기기 키 만들기·읽기와 기기 ID 문자열 반환만 자기 export로 감싼다. ③에서 `tasty-net-ffi`가 같은 기능을 내면 이 임시 export를 지우고 ffi 쪽 API로 바꾼다. 두 단계가 같은 키 폴더를 넘겨야 기기 ID가 바뀌지 않는다(5.4). 임시 export는 앱 안에서만 쓰고 다른 Rust 타입을 FFI로 노출하지 않아, 바꿀 때 Kotlin 호출 자리만 고치면 되게 한다.
- 판을 올릴 때 생성 명령을 다시 본다. tasty-network develop `558d4c7`의 `mobile.md` "크레이트"는 UniFFI 0.33.0의 `uniffi-bindgen`이 라이브러리 모드를 기본으로 하고 `--library` 플래그를 deprecated로 둔다고 적는다. `work/android-terminal`의 `uniffiBindgen` 작업은 `--library`를 넘긴다. 판을 올리는 커밋에서 그 판의 문서로 인자를 고친다.
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
- 내장 백엔드가 읽는 자리: 앱 내부 저장소의 `noBackupFilesDir`(5.6) 아래 OpenSSH 형식 파일. tasty-network 진행 중 초안의 설정값은 판마다 다르다. `bcbcfba`는 `embedded.home` 하나로 `~`를 펼쳤다. develop `558d4c7`(`ssh-backends.md` "내장 백엔드가 읽는 파일")은 두 값을 함께 둔다: `embedded.ssh_dir`(ssh 폴더. 설정 파일 안의 `~/.ssh/`로 시작하는 경로가 이 폴더로 펼쳐진다. 모바일은 바인딩 층이 앱의 `ssh/`를 넣는다)과 `embedded.home`(그 밖의 `~/`). 앱은 두 역할을 각각 준다: `embedded.ssh_dir`에는 `noBackupFilesDir/ssh`, `embedded.home`에는 앱 홈으로 쓸 `noBackupFilesDir` 아래 폴더. 이름은 확정된 설계를 따른다.

제안:

- 호스트 키 정책 보존: tasty-network 설계(develop `558d4c7` `ssh-backends.md` "진입점"·"옵션 적용 순서")의 `with_verify`는 `StrictHostKeyChecking=accept-new`를 사용자 config보다 먼저 적용하는 옵션이지만, `system-ssh` 쪽 런처(`SshConnector::with_launcher`)로만 받는다. 모바일 빌드는 `system-ssh`를 끄므로(2절 계약 4) 앱에는 이 옵션이 없다. ffi나 내장 백엔드가 같은 강제값을 둘 경우에도 앱은 쓰지 않고, 가져온 config의 `ask`를 포함해 프로필의 유효 호스트 키 정책을 그대로 둔다. config에 값이 없을 때의 기본값은 ④에서 정하되 config 값보다 앞에 두지 않는다. 진행 중 설계라 확정판에서 다시 본다.
- 저장 배치(따로 적지 않은 것은 `filesDir` 아래. 비밀과 신뢰 기록은 모두 `noBackupFilesDir` 아래다):

  | 자리 | 내용 |
  |---|---|
  | `ssh/config`, `ssh/known_hosts` | OpenSSH 형식. 내장 백엔드가 그대로 읽는다. `ssh/` 폴더 전체가 `noBackupFilesDir` 아래(5.6) |
  | `ssh/<이름>.pub` | 공개키(복사 화면이 읽는다) |
  | `keys/<이름>.wrapped` | Keystore AES-GCM으로 감싼 개인 키. 평문 개인 키 파일은 만들지 않는다. `noBackupFilesDir` 아래(5.6) |
  | `tasty/profiles.toml`(가칭) | Host 별명마다 Tasty 전용 값. 원격 `tasty` 경로, 데이터 폴더 이름, 포트 파일 경로처럼 포트 발견 설정(`PortDiscoveryConfig`, `repo-boundary.md` "포트 발견의 tasty 관례"@b103e90)에 넣을 값 |
  | `net/` | tasty-network 기기 키 폴더(5.4)와 원격 Tasty 신뢰 저장소(5.4). `noBackupFilesDir` 아래(5.6) |

- 가져온 키가 passphrase로 잠겨 있으면 잠긴 채 감싸 두고, 연결할 때 `SshPrompt`로 passphrase를 묻는다. 비밀번호를 저장하지 않는다는 U3의 원칙을 passphrase에도 같게 적용한다.
- 메모리 키와 프로필의 연결: 감싼 키를 메모리로 넘길 때는 "어느 키를 어느 Host에 써도 되는가"가 정해져야 한다. tasty-network는 메모리 키를 config의 `IdentityFile` 참조와 이름으로 잇지 않고, 앱이 그 대상에 준 키를 그 대상의 키로 본다(`130c5d4:docs/design/ssh-backends.md` "android 요구의 자리" #2 행의 #13 문단, 640행). 그래서 앱은 대상(Host 프로필)마다 쓸 감싼 키를 정해 그 대상의 메모리 키로 준다. 그 키는 OpenSSH `-i`와 같은 자리라, 대상에 메모리 키가 있으면 기본 키 목록(`id_rsa`·`id_ecdsa`·`id_ed25519` 등)을 채우지 않고, `IdentitiesOnly`와 상관없이 쓰이며, 공개키 지문 목록으로 세션 재사용 키(5.3)에 든다(같은 행). 감싼 키 이름을 `IdentityFile ~/.ssh/<이름>`이나 기본 키 자리 이름과 잇는 방식은 tasty-network "후속 작업과 미정"의 "메모리 키와 `IdentityFile` 이름" 행(같은 문서 670행)에 남은 후속 항목이다.
- 사용자 이름: 붙여 넣은 config에 `User`가 없고 tasty-network의 로컬 사용자 기본값(진행 중 설계의 `embedded.local_user`)도 없으면 연결이 `UserRequired`로 끝난다(develop `558d4c7` `ssh-backends.md` "설정값"). Android에는 쓸 만한 로컬 사용자 이름이 없으므로, 최종 대상이나 `ProxyJump`의 어느 홉에서 유효 사용자가 비면 연결 전에 사용자에게 받고 그 Host의 `User`로 `ssh/config`에 적는다. config에 이미 있는 값은 그대로 쓴다.
- 붙여 넣은 config의 거절·미지원 사유는 tasty-network 진행 중 설계가 주는 설정 점검 결과(`SshConnector::inspect`, develop `558d4c7` `ssh-backends.md` "설정 조회")를 그대로 화면에 보인다.

결정 대기: 없음.

### 5.2 내장 SSH exec

정해진 것:

- Git 정보(전제 4)는 원격에서 `git` 명령을 exec 채널로 실행해 읽는다.
- 원격 Tasty 포트 발견도 exec 채널로 같은 원격 명령을 돌린다(진행 중 초안 `bcbcfba` `mobile.md` "내장 백엔드의 경계"). 포트 발견은 transport 안에서 일어나고 앱은 설정값만 준다.
- Git 범위(U6): 읽기만 한다. status·log·diff와 worktree 목록(개수, 경로, 알 수 있으면 브랜치)을 보인다. 쓰기 명령은 없다. 브랜치 목록 화면은 이번 범위에 요구되지 않았다.

제안:

- 앱이 직접 쓰는 exec(Git용)는 tasty-network가 주는 exec API(#3)를 쓴다. 출력 상한, 시간 제한, 취소가 API에 있어야 한다. 포트 발견이 이미 쓰는 상한·마감 규칙(정본: `tasty-net-ssh::discover_remote_port`와 `repo-boundary.md` "포트 발견의 tasty 관례"@b103e90)과 같은 모양을 기대한다.
- Git 출력 해석은 Kotlin에 둔다. 본체는 git2로 로컬 저장소를 읽으므로(기획 초안 5절) CLI 출력 파서는 새로 필요하다. 다만 본체 `tasty-git-core`(`89efb5602`)에는 상태·로그·diff 결과 타입이 있다. 기획 3.2의 범위가 정해질 때, 같은 뜻의 필드는 그 결과 모델의 이름과 의미에 맞춘다(기획 초안 5절도 결과 타입 공유를 후보로 적는다).
- 성공 출력은 Git의 기계 판독 형식(`status --porcelain=v2 -z`, `log`의 지정 형식 등)으로 받아 로캘과 특수문자 경로에 덜 기대게 한다. 읽기 전용 조회가 원격 인덱스를 고쳐 쓰지 않게 `git --no-optional-locks`를 붙인다(`status`는 기본으로 인덱스의 상태 캐시를 갱신해 쓸 수 있다).
- worktree 목록(U6): `git --no-optional-locks worktree list --porcelain -z`로 읽는다. porcelain 형식은 worktree마다 `worktree <경로>`로 시작하는 묶음이고, `HEAD <sha>`, `branch <ref>` 또는 `detached`, 그리고 `bare`·`locked`·`prunable` 표지가 붙을 수 있다. `-z`는 줄 끝을 NUL로 바꿔 경로에 줄바꿈이 있어도 나눌 수 있게 한다(Git 문서 `git-worktree` "Porcelain Format"). 화면에는 개수, 경로, 브랜치(`branch`의 짧은 이름, `detached`면 HEAD의 짧은 sha), 경로가 사라진 worktree(`prunable`)를 보인다. `worktree list`의 `-z`는 Git 2.36부터 있으므로(Git 2.36.0 릴리스 노트) 원격 Git이 `-z`를 모르면 `--porcelain`만으로 다시 읽고, 그때는 줄바꿈이 든 경로를 나눌 수 없다고 보인다. worktree 경로는 원격 Git이 주는 경로 그대로이므로 Explorer로 열 때는 SFTP 경로 규칙(5.3)으로 바꾼다. Windows 원격에서 Git 경로(`C:/...`)와 SFTP 경로(`/C:/...`)를 잇는 규칙은 ④에서 실제 출력으로 확인한다.
- 읽기만 한다는 범위를 지키려고, diff와 log에는 `--no-ext-diff`·`--no-textconv`·`--no-color`를 붙여 저장소 설정의 외부 diff·변환 프로그램이 원격에서 돌지 않게 하고 색 코드 없는 출력을 받는다. 같은 뜻으로 모든 Git 명령에 `-c core.fsmonitor=false`를 붙여 `status`가 저장소 설정의 fsmonitor 명령을 실행하지 않게 한다(옛 Git의 이 설정 해석 차이는 `git-config` 문서로 확인한다). 큰 diff는 exec 출력 상한(#3)으로 자르고, 잘린 사실을 보인다.
- 실패 분류: exec 결과는 종료 코드 말고도 종료 신호, 세션 손실, 종료 상태 미수신이 있다(develop `558d4c7` `ssh-backends.md` "터널·exec·SFTP"). 그래서 "`git` 없음"은 종료 코드 하나로 정하지 않는다. 지원하는 원격 셸(POSIX sh, Windows PowerShell·cmd)마다 명령과 경로를 넘기는 방식과 `git` 설치 여부를 확인하는 명령을 따로 정한다. 포트 발견의 `detect_port_mode`는 원격에 도달한 발견 방식(성공한 방식, 그리고 원격 인스턴스 종료 확정 `RemoteInstanceExited`를 보고한 방식도 포함. `detect_first_success`와 시험 `detect_port_mode_returns_the_mode_that_reported_an_exited_instance`)만 돌려주고 셸 자체는 판별하지 않으므로(`crates/tasty-net-ssh/src/discovery.rs`@b103e90), Git용 원격 셸 판정은 따로 둔다(프로필 값 또는 앱의 판정 명령). 저장소 판정은 한 번의 실패로 정하지 않고 셋을 따로 본다: (1) 저장소 발견 여부(대상 경로 접근 실패와 "저장소를 찾지 못함"을 나눈다), (2) 작업 트리가 필요한 기능(상태, 작업 트리·인덱스와의 미커밋 변경 비교)의 사용 가능 여부(bare 저장소나 `.git` 폴더 안에서는 `--is-inside-work-tree`가 `false`라 저장소는 있어도 그 기능만 비활성. 커밋 사이 비교와 기록은 bare에서도 된다), (3) Git 실행 거부(`safe.directory` 등)와 일반 실행 실패. 판정 결과는 5.8의 시각적 피드백으로 보인다.

결정 대기: 없음. Git 정보로 보여 줄 범위(기획 초안 3.2)는 U6으로 정해졌다.

### 5.3 내장 SSH SFTP

정해진 것:

- Explorer(전제 3)는 SFTP로 읽는다.
- 뷰어(U7): 공식 플러그인이 지원하는 형식만 연다. 지금은 Markdown·이미지·HTML이다. 이미지 그리기는 이번 범위에 요구되지 않아 보기만 한다.
- 쓰기(U8): 텍스트 편집과 파일 관리(새 폴더·이름 바꾸기·지우기·기기 파일 업로드, 이 계획이 더한 옮기기·빈 파일 만들기). 범위는 사용자 결정이고, ④의 읽기 뒤에 만드는 순서와 지우기 확인 단계는 이 계획이 정했다(U8 출처 열).

제안:

- 읽기 단계(8절 ④)는 읽기만 한다(쓰기는 아래 "쓰기와 파일 관리", ④′): 목록(이름·종류·크기·mtime·권한), 링크 대상 읽기(진행 중 설계는 목록 `read_dir`와 링크 대상 `read_link`를 따로 둔다), `stat`·`lstat`, `realpath`, 범위 읽기(offset·len). 실패 사유를 권한 없음·없음으로 나눠 받는다(#4).
- subsystem 실패: SSH의 subsystem 요청 실패 응답은 서버가 SFTP를 껐는지까지 알려 주지 않는다(RFC 4254 6.5). 그래서 "SFTP 꺼짐"이라고 단정할 근거가 있는 경우와 "서버가 SFTP 요청을 거절함(사유 모름)"을 나눠 보인다.
- 세션 공유(#6): 진행 중 설계는 용도(터널·포트 발견·exec·SFTP)마다 config를 해석하고, 해석 결과의 연결 값 묶음(세션 키)이 같을 때만 세션 하나를 나눠 쓴다. `Match command`·`Match sessiontype`으로 용도마다 대상이나 키가 달라지면 같은 Host 별명이라도 세션이 둘이다. 세션은 그 위 핸들(터널·exec·SFTP)이 하나라도 살아 있는 동안 유지되고 마지막 핸들이 닫히면 닫힌다. 앱은 같은 Host의 Explorer와 Git 화면이 대개 한 세션을 쓴다고 기대하되, 화면 수명과 핸들 수명을 맞춰 쓰지 않는 핸들을 닫는다.
- 경로 문법: Explorer의 경로 잇기와 부모 구하기는 원격 Tasty가 descriptor에 알리는 `path_syntax`(`Posix`·`Windows`, 5.4)를 따른다. 경로 모양(드라이브 문자, 구분자)으로 원격 OS를 추정하지 않는다.
- 원격 Tasty가 없는 Host(SFTP만 쓰고 descriptor가 없음): Explorer는 SFTP 경로 문법을 쓴다. SFTP 프로토콜의 파일 이름은 `/`를 디렉터리 구분자로 쓰고, `/`로 시작하면 절대 경로, 그 밖은 사용자 기본 폴더 기준이며, `..`은 부모 폴더다([draft-ietf-secsh-filexfer-02](https://datatracker.ietf.org/doc/html/draft-ietf-secsh-filexfer-02) 6.2 "File Names"). Windows OpenSSH의 SFTP 서버도 Windows 경로를 `/`로 시작하는 `/C:/...` 꼴로 준다(PowerShell/openssh-portable `latestw_all` 브랜치 `contrib/win32/win32compat/misc.c`의 `realpath`: 앞에 `/`를 붙이고 구분자를 `/`로 바꾼다, 주석 "/x:/users/test1 format"). 그래서 경로 잇기와 부모 구하기는 SFTP 경로 규칙으로 하고, 이때도 OS를 추정하지 않는다.

- 뷰어 형식(U7)과 기준 목록: 공식 플러그인 레포(tasty-official-plugins develop `4c80e96`)에서 파일을 여는 핸들러(`[[contributes.handler]]`, `action.kind = "open_surface"`)는 네 개이고, 판별은 모두 확장자 규칙(`kind = "extension"`)이다. MIME 규칙은 없다.

  | 공식 플러그인 | 핸들러의 감지기 | 확장자 | 감지기 정의 |
  |---|---|---|---|
  | Markdown Viewer(`com.tasty.markdown`) | `markdown` | `md`, `markdown` | `tasty-official-plugins:crates/tasty-plugin-markdown/tasty-plugin.toml@4c80e96` |
  | Image(`com.tasty.image`) | `image` | `png`, `jpg`, `jpeg`, `gif`, `webp`, `bmp`, `ico`, `tiff`, `tif` | `tasty-official-plugins:crates/tasty-plugin-image/tasty-plugin.toml@4c80e96` |
  | HTML Viewer(`com.tasty.html`) | `html`(본체 감지기) | `html`, `htm` | `tasty:crates/tasty-file-format/src/defaults/default-file-format.toml@72c036b` |
  | HTML Viewer(`com.tasty.html`)의 `svg-viewer` | `svg`(본체 감지기) | `svg` | 같은 본체 파일. 이미지 플러그인이 SVG를 디코드하지 못해 WebView로 그린다고 매니페스트가 적는다(`tasty-official-plugins:crates/tasty-plugin-html/tasty-plugin.toml@4c80e96`) |

  - 목록을 맞추는 방법: 앱의 형식 표는 위 표 하나로 두고 기준 sha 두 개(공식 플러그인 레포, 본체)를 함께 적는다. 갱신할 때는 공식 플러그인 레포 `crates/*/tasty-plugin.toml`의 `open_surface` 핸들러를 다시 모으고, 핸들러가 가리키는 감지기의 확장자를 그 플러그인의 `[[contributes.detector]]`에서, 플러그인에 없으면 본체 `default-file-format.toml`에서 읽는다. 새 핸들러가 생기면 앱 뷰어를 더한 뒤에 표에 넣는다(표에 있지만 앱이 열지 못하는 형식을 만들지 않는다). 파일 이름 대소문자는 본체 판별과 같은 규칙을 따른다(④에서 `tasty-file-format`의 규칙으로 확인한다).
  - 공식 플러그인의 다른 기능(Image의 그리기 캔버스, HTML Viewer의 URL 열기·원격 HTTP 제공 등)은 U7의 범위가 아니다. Explorer에서 고른 원격 파일을 보는 것만 한다.
- 크기 상한: 두 값을 나눈다. (1) 확인 기준: 넘으면 받기 전에 크기를 보이고 확인을 받는다. (2) 강제 상한: 확인을 받아도 넘지 않는 수신·디코드 상한이다. 넘으면 열지 않는다. 대상마다 따로 둔다: Markdown·HTML·SVG 본문(문서 하나), 하위 리소스 총량(문서 하나가 받는 하위 리소스의 바이트 합과 개수. 넘으면 그 뒤 리소스는 비운다), 이미지 파일 수신, 이미지 디코드 크기(가로×세로 픽셀. 작은 파일이 큰 비트맵이 되는 경우를 막는다). 받기 전 크기는 `stat`으로 보고, 받는 중에도 센다. 데스크톱 Markdown Viewer도 큰 파일 확인 팝업 `large-file-confirm`을 둔다(같은 매니페스트). 값은 ④에서 정한다.
- 이미지: 보기만 한다. SFTP 범위 읽기(#4)로 받고, 위 크기 상한을 따른다. 디코드는 Android 플랫폼 디코더로 한다. 표의 형식 가운데 플랫폼 디코더가 열지 못하는 것(TIFF 등. 지원 목록은 작업 시점 Android 문서 "Supported media formats"로 확인한다)은 아래 판단 필요 항목이다.
- Markdown·HTML·SVG는 WebView로 그리고, 원격 파일 내용은 믿지 않는 입력으로 다룬다. 정책은 기본 차단이다. 이 계획은 Android WebView API가 무엇을 보장하는지 단정하지 않는다. 아래에 무엇이 막혀야 하는지(수용 조건)를 적고, 각 조건을 채우는 수단(가로채기 콜백, `setBlockNetworkLoads`, CSP, WebView 설정)은 후보로 적는다. 수단이 조건을 채우는지는 확인 필요이고, 대상 WebView에서 ④ 전에 시험한다. 한 수단에 기대지 않고 겹쳐 막는다.
  - 수용 조건:
    1. 가상 출처 밖으로 나가는 네트워크 요청이 0이다(http·https 원격 주소, 다른 scheme).
    2. WebSocket·Service Worker·DNS prefetch·hyperlink auditing·WebRTC로 나가는 연결이 0이다.
    3. 최상위 탐색은 앱이 발급한 초기 탐색 하나만 받는다. 링크, 리디렉션, 폼 제출(POST 포함), 이력 이동, 프레임 안 탐색은 받지 않는다.
    4. 새 창과 다운로드가 0이다. 권한 요청(위치 등)은 허용하지 않는다.
    5. 하위 리소스는 허용한 유형(이미지·CSS·글꼴)과 앱이 정한 MIME으로만 응답받고, 문서(최상위·프레임)로 열리지 않는다. SVG는 하위 이미지로 받을 때와 최상위 문서로 열 때를 나눠, 하위 리소스로 받은 SVG가 문서가 되지 않는다.
    6. file·content 같은 로컬 스킴 적재가 0이다.
    7. 문서별 출처 격리: 문서마다 다른 가상 출처(문서 세대를 넣은 호스트 이름)로 싣고, 앞 문서의 응답과 WebView 저장소(쿠키·캐시·Web Storage)가 뒤 문서에 쓰이지 않는다.
    8. 네이티브 JS 브리지가 0이다. `addJavascriptInterface`와 `WebMessage` 포트를 쓰지 않는다(앱 코드의 선택이다).
  - 거절 방식: 가로채기 콜백(`WebViewClient.shouldInterceptRequest`, Service Worker 쪽은 `ServiceWorkerController`의 같은 콜백)이 받는 비허용 요청에는 앱이 만든 실패 응답(오류 상태, 빈 본문)을 돌려준다. `null`을 돌려주면 WebView가 그 요청을 직접 적재하므로 쓰지 않는다. 범위 밖 리소스와 읽기에 실패한 리소스도 같은 실패 응답으로 비운다.
  - 수단 후보와 확인할 것(모두 확인 필요):
    - 조건 1·6: 가로채기의 실패 응답, `WebSettings.setBlockNetworkLoads(true)`, `setAllowFileAccess(false)`·`setAllowContentAccess(false)`.
    - 조건 2: CSP `connect-src 'none'`·`worker-src 'none'`과 네트워크 적재 차단. WebSocket과 Service Worker 요청이 가로채기 콜백을 거치는지, DNS prefetch·hyperlink auditing·WebRTC를 끄는 설정이 있는지, 위 설정이 그 연결을 막는지를 시험한다.
    - 조건 3: `shouldOverrideUrlLoading`의 거절, CSP `form-action 'none'`·`frame-src 'none'`, 가로채기가 초기 탐색 밖의 문서 요청에 실패 응답을 주는 것. 이 콜백이 POST·이력 이동·프레임 탐색에서 불리는지는 시험으로 본다.
    - 조건 4: `setSupportMultipleWindows(false)`, `setJavaScriptCanOpenWindowsAutomatically(false)`, `DownloadListener`, `WebChromeClient.onPermissionRequest`의 거절. 리스너를 두지 않는 것만으로 다운로드가 막히는지는 시험으로 본다.
    - 조건 5: 가로채기 콜백에서 요청 종류(초기 탐색, 프레임 탐색, 하위 리소스)와 정규화 전 URL을 판별할 수 있는지, 가상 응답에 붙인 CSP와 `X-Content-Type-Options: nosniff`가 적용되는지를 시험한다.
  - CSP 후보: 가로챈 모든 응답에 `Content-Security-Policy`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`를 붙인다. CSP는 `default-src` 그 문서의 가상 출처, `script-src` Markdown (a)일 때 번들 스크립트의 hash·nonce만이고 HTML·SVG는 `'none'`, `connect-src 'none'`, `frame-src 'none'`, `form-action 'none'`, `object-src 'none'`, `worker-src 'none'`, `base-uri 'none'`이다. `style-src`와 `font-src`는 아래 "CSP와 렌더링"에서 ④에 정한다.
  - 데스크톱 선례(Android 동작의 증거로 쓰지 않는다): 원격 파일 entry 응답은 CSP·`no-store`·`nosniff`를 붙이고, entry 전용 CSP가 외부 script·CSS·HTTP(S)·fetch·frame·worker를 막으며, DNS prefetch·hyperlink auditing·WebRTC·media stream과 file URL 접근을 끈다(`tasty:docs/design/systems/webview.md@72c036b` 68행). 같은 문서 87~91행의 view마다 다른 예약 호스트와 "허용한 두 탐색 밖의 예약 주소 탐색과 예약 주소 새 창" 취소는 Windows에서 큰 HTML 본문을 host 전용 주소로 싣는 경로에 한한 규칙이다.
  - 상대 리소스: 문서별로 승인한 원격 경로 범위(아래 판단 필요 항목이 정한다. 추천은 문서 폴더와 그 아래) 안에서만 읽는다. 요청 경로를 그 문서의 가상 출처 기준으로 풀고, 백분율 인코딩을 풀어 정규화한다(`.`·`..`·겹친 구분자를 정리하고, 역슬래시·인코딩된 구분자·NUL은 거절한다. 절대 경로와 `//host` 꼴은 범위 밖으로 본다). 범위의 뿌리(문서 폴더)도 같은 SFTP 세션의 `realpath`로 풀고, 요청 경로를 `realpath`로 푼 결과와 경로 구성 요소 단위로 비교한다(문자열 앞부분 비교를 쓰지 않는다). 정규화한 경로와 링크를 푼 경로가 모두 범위 안일 때만 그 세션의 범위 읽기로 읽는다. `realpath`와 읽기는 다른 연산이라, 그 사이에 원격 경로의 링크가 바뀌면 범위 밖 파일을 읽을 틈이 남는다. 이 틈은 한계로 적는다. 검사한 대상을 그대로 여는 방법(예: 연 핸들의 실제 경로 조회)을 #4의 수용 조건으로 요구할지, 범위를 보장할 수 없는 요청을 거절할지는 ④ 전에 정한다. 문서의 `<base>`는 CSP `base-uri 'none'`으로 쓰지 않는다. 유형은 이미지(5.3 표의 이미지 형식과 SVG)·CSS·글꼴만 응답하고, MIME은 서버나 내용 추측이 아니라 앱이 확장자로 정한다. 문서 형식(HTML·스크립트 등) 요청에는 실패 응답을 준다. 크기 상한은 위 "크기 상한"을 따른다.
  - 자바스크립트: HTML·SVG 문서는 끈다(`setJavaScriptEnabled(false)`, CSP `script-src 'none'`). 데스크톱 HTML Viewer도 스크립트 격리 기본값이 켜짐이고(`sandbox_scripts = true`), 문서마다 스크립트와 원격 콘텐츠를 판정해 차단 안내와 허용 버튼을 보이는 게이트(`script_gate = true`)를 둔다(같은 매니페스트, tasty `docs/dev-guide/plugin-development.md`@72c036b의 `script_gate` 항목). 첫 판의 기본은 이 차단이다. 허용 버튼을 두기로 하면 위 JS 끔과 `script-src 'none'`으로는 켤 수 없으므로, 그때 켜는 범위(그 문서 하나)와 그 문서의 CSP·네트워크·상대 리소스 정책을 따로 정한다(아래 판단 필요 항목).
  - Markdown의 스크립트: 데스크톱 Markdown Viewer는 원문을 ammonia로 정화한 HTML에 플러그인이 번들한 스크립트(highlight.js, KaTeX, mermaid, 주소창·찾기 스크립트)를 넣어 그리므로, 그 kind의 기본값은 스크립트 켜짐이다(`[surface_kinds.webview_defaults] sandbox_scripts = false`, `tasty-official-plugins:crates/tasty-plugin-markdown/tasty-plugin.toml`·`src/render.rs`@4c80e96). 그래서 "자바스크립트 끔"을 Markdown에 그대로 적용하면 코드 강조·수식·다이어그램을 그릴 수 없다. 아래 판단 필요 항목이다. (a)를 고르면 번들 라이브러리에 안전 설정을 준다(mermaid `securityLevel: "strict"`, KaTeX `trust: false`). 이 값들의 이름과 뜻은 번들하는 판의 문서로 다시 확인한다.
  - CSP와 렌더링: Markdown (a)는 데스크톱과 같은 출력을 약속하지 않는다. 데스크톱 문서는 인라인 `<style>`을 쓰고(`render.rs`@4c80e96 251~265행), KaTeX 글꼴을 `data:` URI로 CSS에 넣으며(1952~1966행), 수식 스크립트와 함께 인라인 `<style>`을 넣는다(1979~1989행). 위 CSP 후보의 `default-src`만으로는 이것들이 막힌다. 앱이 신뢰하는 스타일과 글꼴을 가상 출처의 응답으로 줄지, 그 스타일만 hash로 허용하는 `style-src`·`font-src`를 줄지는 ④에서 정하고, 결과는 대상 WebView에서 확인 필요다. HTML 문서의 인라인 `style`도 같은 `style-src` 결정에 따른다.

쓰기와 파일 관리(U8, 8절 ④′) 제안:

- ④의 목록·`stat`·범위 읽기 위에 쓰기 API(6절 #5a~#5h)를 더한다. 실패 사유는 #5f의 갈래로 받아 5.8로 보인다.
- 텍스트 편집 대상: UTF-8로 읽히고 편집 상한(뷰어 상한과 따로 ④′에서 정한다) 이하인 파일. 줄 끝(LF·CRLF)과 끝 줄바꿈 유무를 그대로 둔다. UTF-8로 읽지 못하는 파일은 편집하지 않는다.
- 저장: 같은 폴더의 임시 이름에 새 내용 전체를 쓰고 닫은 뒤, 원래 파일의 권한을 임시 파일에 맞추고 이름을 바꿔 원래 파일을 바꾼다. 원격에 반쯤 쓴 파일이 보이지 않게 하기 위해서다(#5a). SFTP v3의 이름 바꾸기는 새 이름이 이미 있으면 오류다([draft-ietf-secsh-filexfer-02](https://datatracker.ietf.org/doc/html/draft-ietf-secsh-filexfer-02) 6.5 "Removing and Renaming Files"). 그래서 원래 파일을 바꾸는 이름 바꾸기에는 OpenSSH 확장 `posix-rename@openssh.com`(OpenSSH `PROTOCOL` 문서의 같은 이름 절)이 필요하다. 서버가 그 확장을 주지 않으면 기본은 저장 불가다. 저장할 수 없다고 알리고, 사용자 확인 없이 대체 방식을 쓰지 않는다. 원래 파일을 지운 뒤 이름을 바꾸는 대체는 지우기가 성공하고 이름 바꾸기가 실패하거나 그 사이에 연결이 끊기면 원래 이름에서 파일이 사라지기 때문이다. 대체를 둘지와 그 모양은 아래 판단 필요 항목이다(사용자 몫).
  - 임시 이름: 같은 폴더에 `.<원래 이름>.tasty-save-<무작위>.tmp` 꼴로 만든다(업로드는 `tasty-upload`). 앱이 만든 것을 이름으로 알아볼 수 있게 접두와 접미를 고정하고, 무작위 부분으로 겹치지 않게 한다. 배타적으로 만들어(#5c) 같은 이름이 있으면 다른 무작위로 다시 만든다. 원래 이름이 길어 이 꼴이 파일 이름 길이 상한을 넘으면 원래 이름을 자르는 규칙은 ④′에서 정한다. 이름 패턴은 후보를 찾는 데만 쓴다. 앱이 남긴 파일로 분류하거나 지우기를 제안하는 것은 그 Host 연결의 기록(아래 기기에 남긴 목록)과 맞을 때뿐이다.
  - 쓰기 결과 상태(저장과 업로드에 공통이다. 5.3의 다른 문장과 5.7·5.8은 이 정의를 가리킨다). 쓰기가 어떻게 끝났든(실패, 사용자 취소, #5g의 시간 초과, 연결 끊김, 앱이 뒤로 가 핸들을 닫음) 세 상태 중 하나다.
    1. 이름 바꾸기를 보내기 전에 끝났다: 원래 이름은 바뀌지 않았다. 임시 파일이 남았을 수 있다. 연결이 살아 있으면 앱이 그 임시 파일을 지우고, 지우지 못하면 기록에 남긴다.
    2. 이름 바꾸기의 성공·실패 응답을 받았다: 성공이면 끝이다. 실패면 원래 이름은 바뀌지 않았고, 1과 같이 임시 파일을 정리한다.
    3. 이름 바꾸기를 보냈으나 결과를 모른다(응답 전에 끊김, 백그라운드, 시간 초과, 취소): 미확정이다. 다시 연결한 뒤 읽는 원래 이름의 `stat`과 임시 파일 유무는 관측값일 뿐이다. 다른 클라이언트가 같은 파일을 바꿀 수 있어 그 값만으로 앱 요청의 결과를 늘 가를 수는 없다. 어떤 관측 조합을 확정으로 볼지는 ④′에서 정하고, 판단 근거가 부족하면 미확정 표시를 유지하며 관측값을 그대로 보인다. 결과를 추측하지 않는다.
  - 남은 임시 파일: 앱이 뒤로 가 핸들을 닫았거나(5.7) 연결이 끊겨 지우지 못한 임시 파일은 원격에 남는다. 앱은 남긴 임시 파일(Host, 경로, 위 결과 상태)을 기기에 기록해 두고, 다음에 그 Host에 연결하면 기록과 맞는 임시 파일을 보이고 지울지 묻는다. 자동으로 지우지 않는다(상태 3이면 그 파일이 새 내용의 유일한 사본일 수 있다). Explorer 목록에서는 기록과 맞는 파일만 앱이 남긴 임시 파일로 표시하고, 이름 규칙만 맞는 파일은 후보로만 보인다.
  - 저장 대상이 심볼릭 링크면 이름 바꾸기가 링크 자체를 바꾸므로, 링크가 가리키는 실제 경로(`realpath`)의 폴더에서 저장한다. hard link로 묶인 파일은 저장 뒤 묶임이 풀리고, 소유자·그룹은 서버 권한에 따라 바뀔 수 있다. 이 한계는 저장 전에 알 수 있는 경우(링크 수, 소유자가 접속 사용자와 다름) 알린다.
- 충돌 확인(#5b): 열 때 받은 크기와 mtime을 기억하고, 이름 바꾸기 직전에 원래 파일을 다시 `stat`해 비교한다. 다르면 덮어쓰지 않고 충돌로 보인다(다시 읽기·다른 이름으로 저장 같은 선택지는 화면 설계 몫이다). 잠금은 하지 않는다. SFTP v3의 mtime은 초 단위라 같은 초 안에 크기가 같게 바뀐 것은 놓치고, 비교와 이름 바꾸기 사이의 짧은 틈도 남는다. 이 한계는 문서로만 적고 첫 판에서 더 막지 않는다.
- 만들기(#5c): 빈 파일은 배타적으로 만든다(이미 있으면 실패). 폴더 만들기도 같다. 이름에 `/`·NUL을 넣지 않고 `.`·`..`을 이름으로 쓰지 않는다.
- 이름 바꾸기·옮기기(#5d): 같은 서버 안에서 SFTP 이름 바꾸기로 한다. 대상이 있으면 덮어쓰지 않고 실패로 보인다. 서버가 다른 파일 시스템으로 옮기기를 거절하면 첫 판은 복사 후 지우기로 바꾸지 않고 그 실패를 보인다.
- 지우기(#5e): 늘 확인 단계를 거친다. 확인에는 무엇을 몇 개 지우는지와 되돌릴 수 없다는 사실을 보인다. 파일은 파일 지우기, 빈 폴더는 폴더 지우기로 한다. 폴더 재귀 지우기는 앱이 `lstat`과 목록으로 아래에서부터 지운다. 심볼릭 링크는 따라가지 않고 링크 자체만 지워 링크 너머를 지우지 않는다. 도중에 실패하면 멈추고, 남은 항목과 사유를 보인다.
- 기기 파일 업로드(U8): Android 파일 선택기(Storage Access Framework, `ACTION_OPEN_DOCUMENT`)로 사용자가 고른 파일만 읽는다. 저장소 권한은 요구하지 않는다. 받은 content URI의 입력 스트림을 조각 단위로 원격에 쓰는 스트림 쓰기(6절 #5h)를 쓴다. 파일 전체를 메모리에 올리지 않는다. 쓰기는 저장과 같은 원자 게시로 한다: 같은 폴더의 임시 이름에 다 쓴 뒤 덮어쓰지 않는 모드의 이름 바꾸기로 최종 이름을 만든다. 같은 이름이 이미 있으면 덮어쓰지 않고 묻는다(다른 이름으로 올리기·취소 같은 선택지는 화면 설계 몫이다). 올리기 전 크기(선택기가 주는 크기, 모르면 읽으며 센다)로 상한을 확인하고, 진행률(보낸 바이트)을 보이며, 사용자가 취소할 수 있고 시간 제한을 둔다(#5g). 취소·실패·끊김·시간 초과 때의 결과는 위 "쓰기 결과 상태"를 따른다.
- 편집·파일 관리 뒤에는 그 폴더의 목록과 연 파일의 `stat`을 다시 읽는다. 원격의 다른 쪽(데스크톱 Tasty 등)이 같은 파일을 바꾸는 일은 충돌 확인으로만 다룬다.

결정 대기: 없음. 편집 범위(기획 초안 4.4)는 U8로, 열 수 있는 형식(기획 초안 3.3)은 U7로 정해졌다.

판단 필요(모두 ④ 전에 정한다. 항목마다 판단 주체를 적는다):

- Markdown 렌더링 방식(구현 판단. 사용자 답은 파일 형식을 정했고 데스크톱 렌더링과 같은 결과를 요구하지 않았다): (a) 앱이 원문을 정화한 HTML로 만들고 앱이 번들한 스크립트만 실행한다(원문 안 스크립트는 정화로 지우고, 번들 스크립트의 결함은 CSP·네트워크 차단으로 가둔다. 번들 라이브러리는 원격 원문을 입력으로 받으므로 그 주입 결함이 원격 문서에 스크립트를 줄 수 있다는 전제로 위 차단을 둔다), (b) 자바스크립트를 끄고 코드 강조·수식·다이어그램 없이 그린다, (c) WebView 없이 앱이 직접 그린다. 추천은 (a)다. 위 차단 경계 안에서 코드 강조·수식·다이어그램을 그리는 것이 목표이고, 데스크톱과 같은 출력은 약속하지 않는다(위 "CSP와 렌더링"). (a)를 고르면 번들한 스크립트의 고지가 7절 고지 파일에 들어간다.
- HTML 문서의 스크립트 허용 버튼(제품·보안 판단, 사용자 몫): 데스크톱처럼 문서마다 허용 버튼을 둘지, 첫 판은 늘 끈 채로 둘지. 추천은 첫 판은 늘 끔이다(JS 끔, `script-src 'none'`). 버튼을 고르면 켜는 범위와 그 문서의 CSP·네트워크·상대 리소스 정책을 따로 정한다. 스크립트를 켜면 가상 출처 안의 요청으로 같은 SFTP 자격이 읽을 수 있는 다른 파일을 스크립트가 고를 수 있게 되므로, 버튼을 두는 안은 아래 상대 리소스 범위와 함께 정해야 한다.
- 플랫폼 디코더가 열지 못하는 이미지 형식(TIFF 등): 디코더를 무엇으로 할지(앱에 디코더를 더함, 형식마다 고름)는 구현 판단이다. 그 형식을 지원하지 않고 "이 기기에서 볼 수 없음"으로 두는 것은 U7(공식 목록 전체)을 바꾸므로 사용자 몫이다. 공식 Image 플러그인은 TIFF·ICO 디코더를 포함한다(`image` 크레이트 feature `png`·`jpeg`·`gif`·`bmp`·`webp`·`ico`·`tiff`, `tasty-official-plugins:crates/tasty-plugin-image/Cargo.toml@4c80e96` 22행). Android API 29 이상의 플랫폼 디코더가 어떤 형식과 변형을 실제로 여는지는 확인 필요다. 추천은 플랫폼 디코더가 못 여는 형식만 앱 디코더로 채우는 것이다.
- 덮어쓰는 이름 바꾸기가 없는 서버의 저장(제품·안전 판단, 사용자 몫): (가) 저장 불가로 둔다, (나) 확인 뒤 복구 가능한 대체만 둔다. (나)는 원래 파일을 먼저 백업 이름으로 바꾸고(덮어쓰지 않는 이름 바꾸기), 임시 파일을 원래 이름으로 바꾼 뒤 백업을 지운다. 도중에 실패해도 원래 내용은 백업 이름에 남지만, 원래 이름에 파일이 없는 순간은 남는다. 원래 파일을 먼저 지우는 대체는 어느 쪽에서도 쓰지 않는다. 추천은 첫 판 (가)다.
- 상대 리소스를 어디까지 읽을지(제품·보안 판단, 사용자 몫): 문서 폴더 아래만, 같은 저장소 안까지, 같은 SFTP 자격이 읽는 어디든 중에서 고른다. 추천은 문서 폴더와 그 아래만이다(문서 밖 파일을 문서가 고르지 못하게 한다).
- 외부 링크를 앱에서 열지(제품 판단, 사용자 몫): 링크를 눌렀을 때 아무것도 하지 않음, 주소를 보이고 확인 뒤 기기 브라우저로 넘김, 같은 원격의 다른 파일이면 뷰어로 엶 중에서 고른다. 추천은 첫 판은 주소를 보이고 확인 뒤 기기 브라우저로 넘김이다.

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
- 대화형 원격 SSH 터미널(기획 초안 4.2 "대화형 SSH 터미널을 넣는다" 행): U9로 넣기로 정해졌다. 순서는 2단계(화면 attach) 뒤이고, 흐름은 5.9에 적는다.
- 미지원 메서드 코드: 원격 Tasty가 모르는 메서드에는 JSON-RPC 오류 코드 `-32601`(method not found)로 답한다. tasty는 이 값을 R3 (B) 크레이트의 공개 상수 `METHOD_NOT_FOUND`로 둔다(값은 바뀌지 않는다).

제안:

- 발견과 터널의 대상 일치: 용도마다 config를 해석하므로(5.3) 포트 발견 exec와 터널의 실제 대상이 `Match`로 달라질 수 있다. v2는 상대 기기 서명을 확인하기 전 첫 메시지에 토큰을 싣는다. 그래서 발견한 레코드(토큰 포함)는 발견과 같은 유효 대상(호스트·포트·사용자·점프 홉)으로 가는 터널에만 쓴다. 두 해석의 유효 대상이 다르면 토큰을 보내기 전에 구성 오류로 끝낸다. 이 확인은 발견 결과로 터널을 만들거나 다시 쓰고 레코드의 토큰을 넣는 transport 경로(`crates/tasty-net-transport/src/ssh.rs`@b103e90)에서, 포트 발견과 터널 연결에 실제로 쓴 유효 대상을 비교하는 방식으로 하자고 요구한다. 연결 전 설정 점검(`inspect`)의 결과로는 실제로 쓴 대상을 보증하지 못한다. FFI는 불일치 오류를 그대로 넘긴다(#7).
- 기기 키: tasty-network identity의 `DeviceKey::load_or_create`를 `noBackupFilesDir/net/`(5.6) 아래 키 폴더로 부른다. 같은 폴더의 `device_key`를 다시 쓰므로 폴더가 바뀌면 새 기기 ID가 생긴다. 그래서 ②의 임시 export부터 이 최종 경로를 쓰고, ③에서 ffi의 `TastyNet` 생성 인자에도 같은 폴더를 넘긴다. 기기 키와 연결 레코드의 `File::lock` 동작은 다른 경로이므로, 기기 키 시험이 레코드 쪽 잠금을 증명하지 않는다(#9).
- `PeerKeyStore` 구현: `net/peers/` 아래 상대마다 파일 하나. 저장 키(`PeerRef`)는 편집할 수 있는 Host 별명이 아니라 앱이 프로필마다 붙이는 바뀌지 않는 ID다(별명을 바꿔도 기록이 빠지지 않게). `pin_if_absent`는 hard link 없는 원자 게시로 첫 기록을 정한다: 내용을 다 쓰고 fsync한 임시 파일을 `renameat2(..., RENAME_NOREPLACE)`로 최종 이름에 바꾼다. 최종 경로를 `O_EXCL`로 바로 만들지 않는다. 빈 파일이나 덜 쓴 파일이 먼저 보여 진 호출이 확정된 ID를 읽지 못하고, 쓰다 죽으면 깨진 기록이 남기 때문이다. bionic의 `renameat2` 래퍼는 API 30부터 있으므로(bionic `docs/status.md` "New libc functions in R (API level 30)") `minSdk = 29`에서는 시스템 호출로 직접 부른다. hard link를 쓰지 않는 까닭: 기기 키의 `hard_link` 게시(`crates/tasty-net-identity/src/device_key.rs`@130c5d4)가 Android 앱 데이터 폴더에서 SELinux의 link 거절로 실패하는 것을 확인했다(8절). 임시 파일을 `renameat2(RENAME_NOREPLACE)`로 게시하는 방식은 tasty-network의 기기 키 게시 수정(tasty-network develop `539071c`, 후속 수정 `e656c21`까지 앱 고정 `36cd2a2`에 들어 있다)과 같다. 다만 앱의 `PeerKeyStore`는 hard link 대체 경로를 두지 않는다. 경쟁에서 진 호출은 이미 확정된 ID를 읽어 `Existing`으로 돌려준다. 그래서 같은 상대에게 겹친 첫 연결에서도 `Inserted`는 하나다.
- 키 바뀜 화면: 연결 상태 요약(`ConnectionTable`의 마지막 원인)이 아니라 연결 시도가 돌려준 구조화된 오류(`PeerKeyMismatch { expected, got }`)에서 두 키를 받아 보여 주고, 사용자가 확인하면 `reregister`를 부른다. b103e90의 연결 목록 요약은 이 오류를 문자열로 줄이므로 화면 근거로 쓰지 않는다(`connection_table/info.rs`@b103e90). 전환 전 Tasty 판정(`PortOnlyDiscovery`·`V1Ack`)도 같은 시도 오류에서 읽는다.
- 요청 채널: transport의 `dial_request`는 이미 `RequestClient`를 돌려준다(`handshake.rs`·`request.rs`@b103e90). 요청 ID, 응답 연결, 시간 제한, 채널 닫기를 그 객체가 맡으므로, 1단계는 ffi가 `RequestClient`를 감싸 주고 앱은 `workspace.overview`를 부르기만 한다(#7). 1단계는 attach 링크나 descriptor에 기대지 않는다. 소유: `RequestClient` 핸들은 Kotlin의 원격 Tasty 저장소 객체 하나가 갖고 닫는다. 호출 결과(JSON 바이트 또는 문자열)는 Kotlin이 해석하지 않고 `tasty-mobile-core`의 serde 해석 함수로 넘겨 UniFFI 레코드로 받는다. b103e90의 `dial_request`는 상대가 `net.request`를 광고했는지를 열기 조건으로 보지 않으므로, handshake 뒤 첫 호출 전에 상대 `caps`를 확인하는 일은 ffi가 그 값을 내주면 앱이 한다(#7 확인 항목).
- 두 capability를 나눈다: handshake의 `net.request`(전송 계층 capability, `handshake.md` "capability"@b103e90)는 요청 채널을 열 수 있는지이고, `workspace.overview` 응답의 capabilities는 R3의 도메인 계약이다. 세 근거를 따로 다룬다: `net.request` 미광고는 "원격 Tasty가 요청 채널을 광고하지 않음"(구버전 판정은 `PortOnlyDiscovery`·`V1Ack`로만 한다), 원격 메서드 미지원과 응답의 도메인 capability 부족은 각각 "원격 Tasty가 이 기능을 지원하지 않음"의 다른 사유다. `RequestClient`는 원격이 돌려준 메서드 오류를 `RequestError::Remote(Value)`로 넘기고, 원격의 error 객체를 통째로 보존한다(`crates/tasty-net-transport/src/request.rs`의 `Remote` 변형, tasty-network develop `130c5d4`). 메서드 미지원은 문자열 비교가 아니라 그 객체의 `code`로 판정한다: `error.code`가 숫자 `-32601`(`METHOD_NOT_FOUND`)일 때만 미지원으로 본다. `code`에는 숫자도 문자열도 올 수 있으므로, 문자열 `"-32601"`이나 다른 값은 미지원이 아니라 일반 원격 오류로 다룬다. 미지원이면 시각적 피드백만 보인다(전제 5, 5.8).
- 다시 읽는 조건: `workspace.overview`는 호출 시점의 원자 응답이고, 요청 채널은 호출마다 응답을 돌려줄 뿐 바뀜을 알리지 않는다. `ConnectionTable` 이벤트도 연결 전이만 알린다. 그래서 1단계는 첫 조회, 사용자의 새로고침, 재연결 뒤, 앱이 앞으로 돌아온 뒤에 다시 읽는다. 링크가 살아 있는 동안의 점유 바뀜을 자동으로 받는 일은 2단계(attach 도메인 메시지) 이후로 둔다.
- 응답 크기: b103e90의 요청 채널은 직렬화된 응답을 프레임 하나(`MAX_FRAME_LEN`, 1 MiB)로 제한하고, 넘으면 `RequestError::ResponseTooLarge`(원격 응답 코드 `net.response_too_large`)를 돌려준다(`crates/tasty-net-transport/src/request.rs`@b103e90). `workspace.overview`는 워크스페이스와 서피스를 한 응답에 담으므로, 앱은 이 오류를 FFI에서 그대로 받아 "응답이 너무 큼"으로 보이고 빈 목록이나 기능 미지원으로 읽지 않는다. R3의 원자 응답 계약은 바꾸지 않는다.
- 응답 해석: Kotlin에 JSON 해석 의존을 따로 들이지 않고 `tasty-mobile-core`의 serde 타입으로 해석한다. R3 답은 (B) 크레이트가 attach 도메인 메시지와 descriptor 타입을 담는다고 적고, `workspace.overview` 응답 타입까지 담는지는 적지 않았다. 그래서 우선 `tasty-mobile-core` 안에 R3 답의 응답 모양과 같은 serde 타입을 두고, (B)나 (A)의 반영이 그 응답 타입을 공개하면 그것으로 바꾼다.
- 2단계(화면 attach): A1 탭바에서 원격 서피스를 탭 하나로 연다. 렌더러는 `work/android-terminal`의 터미널 화면을 다시 쓴다. 이 단계에는 tasty의 mirror 계약(descriptor, snapshot·delta, 리사이즈, 입력 확인. `transport.md` "순서 계약과 채널" O1~O15@b103e90)과 R3 (B)의 도메인 메시지가 필요하다. 2단계의 자세한 계획은 1단계가 끝난 뒤 따로 쓴다.

결정 대기:

- tasty: R3 (A)·(B)의 반영과 크레이트 이름·sha(`METHOD_NOT_FOUND`·`path_syntax`가 들어 있는 판), 전환한 Tasty의 v2 레코드 게시.

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
- tasty-network 설계(develop `558d4c7` `mobile.md` "앱 수명")의 기본 `pause`는 대기 중인 SSH 재연결을 멈추고 열린 터널은 그대로 둔다. P2P 등록은 유예(`lifecycle.background_grace`) 뒤 닫는다. 일반 SSH 세션은 마지막 핸들이 닫힐 때 닫힌다(5.3). 그래서 앱이 아무것도 하지 않으면, OS가 앱 실행을 멈췄을 때 모든 연결이 곧 끊기고 원인이 남는다고 단정할 수 없다.

제안:

- 두 층을 나눠 보인다.
  - (a) Tasty 전송 링크: tasty-network의 `ConnectionTable`(포트 발견을 거치는 터널 단위의 항목, `transport.md` "연결 목록"@b103e90). 스냅샷 `connections()`, 전이 이벤트, 상태(`connecting`·`connected`·`reconnecting`·`disconnected`), 마지막 원인.
  - (b) attach 없이 쓰는 일반 SSH 세션(Explorer·Git용). b103e90의 `ConnectionTable`에는 이 단위가 없다. 같은 상태 API로 볼 수 있게 해 달라는 것이 새 요구다(#6b).
- 화면이 쓰는 값: 연결 목록, 실패 사유, 다시 시도할 수 있는지(`is_retryable()`), 재시도 대기 표시. 이벤트는 `EventStream::next(timeout)`으로 끌어오고, 이벤트가 유실되면(`Lagged`) 스냅샷을 다시 읽는다.
- 앱 수명 알림: 앱의 앞·뒤 전환과 망 바뀜·끊김을 `AppEvent`(`Foreground`·`Background`·`NetworkChanged`·`NetworkLost`)로 라이브러리에 넘긴다. 앱이 앞으로 돌아오면 상태 화면이 재연결 상태를 보인다.
- U5(앞에 있을 때만 유지)를 앱이 지키는 방법은 한 가지다: 앱이 `Background`를 받으면 앱이 관리하는 연결을 모두 바로 닫고, `Foreground`를 받으면 앱이 기억한 대상을 앱이 다시 연다. 백그라운드 동안 열어 두는 연결은 없다.
  - 닫을 때: 저장소 객체는 새 연결·호출을 받지 않는다. 진행 중인 연결 시도는 취소하고, 떠 있는 `SshPrompt`는 거절로 답한다. 열어 둔 exec·SFTP 핸들(진행 중인 저장·업로드 쓰기 포함. 닫은 뒤의 결과는 5.3 "쓰기 결과 상태"를 따른다), 대화형 원격 셸 채널(5.9), 터널·링크, 요청 채널(`RequestClient`)을 닫는다. b103e90의 `RequestClient`는 따로 닫는 핸들을 주고, 그냥 drop하면 종료 응답을 기다릴 수 있으므로(`request.rs`@b103e90) 닫는 핸들로 닫는다. 취소한 뒤 늦게 성공해 돌아온 핸들은 받는 즉시 닫고 화면에 넘기지 않는다.
  - 다시 열 때: 앱이 모두 닫았으므로 라이브러리에는 그 대상의 재연결 예약이 남지 않는다(진행 중 설계의 `Foreground`는 라이브러리가 멈춰 둔 재연결만 다시 시작한다). 앱의 저장소 객체가 닫기 전에 화면에 필요했던 대상(열린 Explorer·Git 화면의 Host, 원격 Tasty 목록 화면의 대상)을 기억해 두었다가 다시 열고, 원격 Tasty 목록은 다시 읽는다(5.4). 대화형 원격 셸은 예외다. 끊긴 셸은 자동으로 다시 열지 않고 탭에 끊긴 사유와 새 셸을 여는 길만 보인다(5.9). 끊겨서 마치지 못한 저장·업로드도 자동으로 다시 하지 않고, 미확정(5.3 "쓰기 결과 상태" 3)이면 그 표시를 유지한다.
- 앱 수명 이벤트를 라이브러리에 넘기는 일과 이 정리는 ⑥ 상태 화면을 기다리지 않고 핸들을 처음 여는 ④·⑤에서 만든다. ⑥은 그 결과를 모아 보이는 화면이다(8절).

결정 대기: tasty-network가 #6b를 받을지와 그 모양.

### 5.8 기능이 없을 때의 시각적 피드백

전제 5와 기획 초안 3.4의 경우를 각 흐름의 실패 사유와 짝짓는다. 모양(배너, 빈 화면 안내, 비활성 표시)은 정하지 않는다.

| 경우(기획 3.4) | 흐름 | 받는 실패 사유 |
|---|---|---|
| 원격에 `git`이 없다 | 5.2 | 원격 셸별 설치 확인 명령의 결과(종료 코드 하나로 정하지 않는다) |
| 대상 폴더가 git 저장소가 아니다 | 5.2 | 저장소를 찾지 못함. 경로 접근 실패, `safe.directory` 거부, 일반 실패, 종료 신호, 세션 손실과 나눈다 |
| 저장소는 있지만 작업 트리가 없다(bare 등) | 5.2 | 작업 트리 판정 `false`. 상태와 미커밋 변경 비교만 비활성으로 보이고, 기록과 커밋 사이 비교는 그대로 쓴다 |
| 원격 Git이 worktree 목록을 읽지 못한다 | 5.2 | `worktree list` 실행 실패(판이 낮아 하위 명령이나 `--porcelain`을 모름). worktree 목록만 비활성으로 보이고 status·log·diff는 그대로 쓴다. `-z`만 모르면 위 대체 경로로 읽는다 |
| diff가 너무 크다 | 5.2 | exec 출력 상한 도달(#3). 잘린 diff임을 보인다 |
| SFTP 서브시스템이 꺼져 있거나 서버가 거절했다 | 5.3 | 꺼짐을 단정할 근거가 있는 경우와 subsystem 요청 거절(사유 모름)을 나눈다(#4) |
| 권한이 없다 | 5.3 | SFTP 실패 사유 "권한 없음"(#4) |
| 뷰어가 없는 형식이다 | 5.3 | 확장자가 5.3 형식 표에 없음. 내용을 열지 않고 형식 미지원으로 보인다 |
| 파일이 확인 기준보다 크다 | 5.3 | 받기 전 `stat` 크기. 크기를 보이고 확인을 받는다 |
| 파일이 강제 상한보다 크다 | 5.3 | 받기 전 `stat` 크기 또는 받는 중 센 크기, 디코드 크기. 열지 않고 상한을 보인다 |
| 승인된 형식인데 열지 못했다 | 5.3 | 디코드·해석 실패(확장자는 표에 있지만 내용이 깨졌거나 맞지 않음). 형식 미지원과 나눠 보인다 |
| 이 기기에서 볼 수 없는 형식이다 | 5.3 | 사용자가 5.3 판단 필요 항목에서 그 형식의 지원 축소를 고른 경우에만 생기는 행이다. 플랫폼 디코더가 그 형식을 열지 못함 |
| 편집할 수 없는 파일이다 | 5.3 | UTF-8로 읽히지 않음, 또는 편집 상한 초과. 읽기 전용으로만 연다 |
| 저장하려는 파일이 연 뒤에 바뀌었다 | 5.3 | 저장 직전 `stat`의 크기·mtime이 연 때와 다름(#5b). 덮어쓰지 않고 충돌로 보인다 |
| 원래 파일을 안전하게 바꿀 수 없다 | 5.3 | 서버에 `posix-rename@openssh.com`이 없어 라이브러리가 대체하지 않고 #5f "지원 안 함"으로 알림(#5a). 저장 불가로 보인다(앱 쪽 대체는 5.3 판단 필요 항목의 결정에 따른다) |
| 저장·업로드가 끝났는지 모른다 | 5.3 | 5.3 "쓰기 결과 상태" 3. 다시 연결한 뒤의 관측값(원래 이름의 `stat`, 임시 파일 유무)을 보이고, 판단 근거가 부족하면 미확정 표시를 유지한다 |
| 앱이 남긴 임시 파일이 있다 | 5.3 | 기기에 기록한 임시 파일 목록과 맞는 파일. 그 Host에 다시 연결할 때 보이고 지울지 묻는다. 이름 규칙만 맞는 파일은 후보로만 보인다 |
| 만들기·옮기기 대상이 이미 있다 | 5.3 | #5f "이미 있음". 덮어쓰지 않는다 |
| 폴더가 비어 있지 않다·공간이 없다 | 5.3 | #5f "폴더가 비어 있지 않음"·"공간 없음"(서버가 알려 줄 때) |
| 재귀 지우기가 도중에 멈췄다 | 5.3 | 실패한 항목의 #5f 사유와 남은 항목 목록 |
| 문서가 막힌 리소스를 썼다(앱이 관측) | 5.3 | 가로채기 콜백이 실패 응답을 준 요청(원격 주소, 다른 scheme, 범위 밖, 허용하지 않은 유형, 읽기 실패). 문서는 그대로 보이고 막힌 사실을 알린다 |
| 문서가 쓴 리소스가 막혔을 수 있다(사유를 받지 못함) | 5.3 | CSP·WebView 설정이 막아 앱 콜백에 오지 않은 요청. 앱은 그 사유를 받지 못할 수 있으므로 막힌 개수를 알리지 않고, 앱이 관측한 거절만 보인다. 이 경우를 앱이 알 수단이 있는지는 확인 필요 |
| 원격 Tasty가 없거나 실행 중이 아니다 | 5.4 | 포트 발견 실패(원격 인스턴스 없음·종료), 원격 리스너 없음 |
| 원격 Tasty가 전환 전 판이다 | 5.4 | `PeerTooOld { evidence: PortOnlyDiscovery }` 또는 v1 거절 ACK(`evidence: V1Ack`) |
| 원격 Tasty가 요청 채널을 광고하지 않는다 | 5.4 | handshake `caps`에 `net.request` 없음(구버전 판정과 별개) |
| 원격 Tasty가 기능을 지원하지 않는다 | 5.4 | `RequestError::Remote(Value)`의 `error.code`가 숫자 `-32601`(`METHOD_NOT_FOUND`, 숫자일 때만), `workspace.overview` capabilities에 값 없음. 시각적 피드백만 보인다 |
| 원격 목록이 너무 크다 | 5.4 | `RequestError::ResponseTooLarge`. "응답이 너무 큼"으로 보이고 빈 목록·미지원으로 읽지 않는다 |
| 원격 Tasty 기기 키가 바뀌었다 | 5.4 | 연결 시도 오류 `HandshakeError::PeerKeyMismatch { expected, got }` |
| LAN 권한을 거절했거나 회수됐다(연결 전) | 5.5 | 앱의 권한 요청·확인 결과. 소켓을 열기 전이라 차단 사유를 읽을 소켓이 없다 |
| LAN 연결이 권한 때문에 막혔다(연결 중) | 5.5 | 소켓을 닫기 전에 읽은 차단 사유(6절 #14) |
| 망이 없다 | 5.7 | `NetworkLost` |
| 원격 셸이 끝났다 | 5.9 | `exit-status`·`exit-signal`, 종료 상태 미수신, 세션 손실을 나눠 보인다(#16d) |
| 앱이 뒤로 가서 원격 셸이 끊겼다 | 5.9 | 앱이 `Background`에서 닫은 셸(U5). 다른 끊김과 나눠 "앱이 뒤로 가서 끊김"으로 보인다 |
| 서버가 PTY나 셸을 거절했다 | 5.9 | `pty-req`·`shell` 요청 실패(#16a) |

### 5.9 대화형 원격 셸

정해진 것:

- U9: 대화형 원격 SSH 터미널을 넣는다. 순서는 ⑤-2(화면 attach) 뒤다(8절 ⑤-3).
- 첫 판은 앱이 앞에 있을 때만 셸을 유지한다(U5). 앱이 뒤로 가면 셸이 끊긴다.

제안:

- 채널: 같은 SSH 세션(#6의 세션 공유 규칙) 위에 session 채널을 열고 `pty-req`(터미널 종류, 열·행, 픽셀 크기) 뒤 `shell`을 요청한다(RFC 4254 6.2 "Requesting a Pseudo-Terminal", 6.5 "Starting a Shell or a Command"). 연결과 설정 해석은 exec와 같은 경로를 쓴다(#16a). 터미널 종류는 로컬 터미널이 자식에게 주는 값과 같게 `xterm-256color`로 둔다(`tasty:crates/tasty-terminal/src/pty.rs`@e1e705bc).
- 창 크기: 화면 크기가 바뀌면 `window-change`를 보낸다(RFC 4254 6.7, #16b).
- 렌더러: 로컬 셸과 같은 `tasty-terminal`을 쓴다. 그 크레이트는 PTY 없이 밖에서 받은 바이트만으로 그리드를 만드는 터미널(`Terminal::new_detached`, `Terminal::feed_bytes`)과, 사용자 입력과 VT 응답을 밖의 채널로 내보내는 입력 연결(`ExternalInput`, `bind_external_input`)을 이미 갖고 있다(`tasty:crates/tasty-terminal/src/lib.rs`·`sink.rs`·`io.rs`@e1e705bc). 앱은 셸 채널의 출력 스트림을 `feed_bytes`로 넣고, 입력 연결을 채널의 stdin 쓰기에 잇는다. 이 조합이 앱 고정 rev에서 원격 셸에 그대로 쓰일 수 있는지(입력 연결의 재연결 인자, 원격이 Windows일 때의 키 입력 형식 `set_win32_console_input`)는 ⑤-3을 시작할 때 확인한다. 라이브러리는 바이트 스트림만 주고 화면 모델은 갖지 않는다(#16g).
- 탭: A1 탭바에 탭 하나로 연다. 로컬 셸 탭, 원격 Tasty 서피스 탭(5.4 2단계)과 탭 종류로 나눠 보인다.
- 흐름 조절: 화면이 출력을 따라가지 못할 때는 라이브러리의 채널 창과 버퍼 상한(#16c)에 맡기고, 앱은 받은 바이트를 버리지 않는다.
- 끝과 닫기: 셸이 끝나면 끝 사유(#16d)를 탭에 보이고 다시 열지 않는다(셸 상태는 되살릴 수 없다). 사용자가 탭을 닫거나 앱이 `Background`를 받으면 앱이 닫는 핸들로 닫는다(#16e, 5.7의 정리). 앱이 앞으로 돌아와도 끊긴 셸을 자동으로 다시 열지 않고, 새 셸을 여는 길만 준다.
- 상태: 셸 세션도 #6b의 일반 SSH 세션 상태에 보인다(#16f, 5.7).

결정 대기: 없음. 백그라운드에서도 셸을 유지하는 일은 U5대로 그 기능을 실제로 제공하는 단계에서 다시 묻는다.

## 6. tasty-network 요구 목록 (NEEDS-REPO R2)

R2 처리 결과: 아래 첫 표 #1~#11(6b·7b 포함)을 tasty-network에 전달했다(2026-11-27). 이 문서가 더한 것(#7+ 행, 아래 둘째 표 #12~#15)도 전달됐다(2026-11-27). 사용자 결정 U8·U9(2026-11-28)로 넓어지거나 새로 생긴 #5a~#5h와 #16·#16a~#16g는 2026-11-28에 전달했다. tasty-network의 답(2026-11-28): #5 확장(#5a~#5h)은 그 레포의 M4 2단계(exec·SFTP 읽기와 같은 단계, SFTP 핸들 하나에 읽기·쓰기)에 들어가고, #5h는 #5a·#5g와 한 쓰기 API로 묶인다(#5a는 #5h의 "전체 내용" 경우다). #16 PTY 셸은 M4 3단계다. ffi 첫 판은 그때 있는 Rust API만 노출하고, #5·#16은 ffi 초안에 이름과 모양의 자리만 둔다. 앱 쪽에서는 둘 다 ③ ffi 이후 단계(#5는 ④′, #16은 ⑤-3)의 요구다. tasty-network 설계 문서의 어디에 들어갔는지는 반영된 뒤 각 행에 적는다. #1~#15가 tasty-network 설계 문서의 어디에 들어갔는지는 각 행에 적는다(tasty-network develop `130c5d4` 기준). "원래 상태"는 R2 전에 tasty-network 쪽에 무엇이 있었는지다. R2 전의 요구는 exec(표준 입출력·종료 상태)와 SFTP 지원, 같은 세션 위에서 함께 쓰는 경계까지였고, 그 위의 세부 API(출력 상한·시간 제한·취소·env, 범위 읽기, 실패 분류 등)는 R2가 구체화했다. "후속 작업 문서" 열은 tasty-network의 내장 SSH 작업·ffi 작업 문서에 그 항목이 직접 적혀 있는지(있음), 전달 기록으로만 확인되는지(전달 기록)다.

| # | 항목 | 앱이 쓰는 곳 | 원래 상태 / 후속 작업 문서 |
|---|---|---|---|
| 1 | 내장 SSH 연결: 앱이 준 폴더(앱의 `ssh/`를 `~/.ssh`로 봄)의 OpenSSH 형식 config·키·known_hosts로 접속. 비밀번호·passphrase·keyboard-interactive·호스트 키 확인(`ask`일 때. `accept-new`는 자동 기록)을 `SshPrompt` 콜백으로. 모르는 지시어 거절, `@cert-authority` 1단계 미신뢰 | 5.1 | 진행 중 초안과 후속 결정에 있음 / 있음 |
| 2 | 키를 파일 대신 메모리 바이트로 줄 수 있는 길. 메모리 키의 수명과 지우기, 설정 조회에 키 바이트가 나오지 않는 것. **U3=B라 요구다** | 5.1 | 새 / 있음 |
| 3 | exec: 명령 실행, stdout·stderr 스트림 또는 상한 있는 수집, 종료 상태, 시간 제한·취소, env | 5.2 | exec 실행·표준 입출력·종료 상태는 이미 요청됨, 상한·시간 제한·취소·env는 R2가 구체화 / 있음 |
| 4 | SFTP: 같은 SSH 세션 위 subsystem. 목록(이름·종류·크기·mtime·권한), 링크 대상 읽기(별도 연산), `stat`·`lstat`, `realpath`, 범위 읽기(offset·len), 실패 사유 구분(권한 없음, 없음, subsystem 요청 거절. 꺼짐은 근거가 있을 때만) | 5.3, 5.8 | SFTP 지원은 이미 요청됨, 연산 목록·범위 읽기·실패 사유 구분은 R2가 구체화 / 있음. 전달 당시 문구는 `readdir`에 링크 대상을 넣고 "subsystem 꺼짐"을 구분하는 것이었다. develop `558d4c7`의 설계(`ssh-backends.md` "공개 API (`tasty-net-ssh`)")는 링크 대상을 `read_link`로 따로 두고, 이 문서는 subsystem 요청 거절(사유 모름)을 꺼짐과 나눈다(5.3) |
| 5 | SFTP 쓰기와 파일 관리. U8로 범위가 정해져 아래 #5a~#5h로 넓혔다. 업로드는 #5h(스트림 쓰기)와 #5c·#5d·#5f·#5g를 쓴다 | 5.3(U8, ④′) | 쓰기·이름 바꾸기·삭제는 보류로 있었다 / 넓힌 요구는 새(2026-11-28 전달). tasty-network 답(2026-11-28): M4 2단계(exec·SFTP 읽기와 같은 단계, SFTP 핸들 하나에 읽기·쓰기). ffi 첫 판에는 이름·모양 자리만 |
| 5a | 파일 쓰기: 새 내용으로 바꾸기. 원격에 반쯤 쓴 파일이 보이지 않게 같은 폴더의 임시 이름에 쓰고 이름을 바꾸는 저장. 서버가 `posix-rename@openssh.com`을 주면 그것을 쓰고, 없으면 대체하지 않고 #5f "지원 안 함"으로 알린다. 원래 파일을 먼저 지우는 대체는 요구하지 않는다(앱 쪽 대체를 둘지는 5.3 판단 필요 항목이다). 쓰기 전 확인용으로 대상의 `stat`(크기·mtime)을 함께 쓸 수 있게 | 5.3 저장 | 새(2026-11-28 전달) |
| 5b | 충돌 확인: 연 뒤 원격에서 바뀌었는지 볼 값(크기·mtime)을 읽기 결과에 싣고, 앱이 저장 직전에 다시 비교할 수 있게. 잠금은 요구하지 않는다 | 5.3 충돌 확인 | 새(2026-11-28 전달) |
| 5c | 만들기: 빈 파일 만들기(이미 있으면 실패, `O_EXCL`과 같은 뜻), 폴더 만들기 | 5.3 만들기 | 새(2026-11-28 전달) |
| 5d | 이름 바꾸기·옮기기: 같은 서버 안 다른 폴더로 옮기기 포함. 대상이 이미 있으면 덮어쓰지 않고 실패하는 모드(SFTP v3 이름 바꾸기의 기본 뜻)를 기본으로 | 5.3 이름 바꾸기·옮기기 | 새(2026-11-28 전달) |
| 5e | 지우기: 파일 지우기, 빈 폴더 지우기. 재귀 지우기는 앱이 목록을 돌며 하므로 라이브러리에 요구하지 않는다 | 5.3 지우기 | 새(2026-11-28 전달) |
| 5f | 실패 사유: 이미 있음, 없음, 권한 없음, 폴더가 비어 있지 않음, 공간 없음(서버가 알려 줄 때), 지원 안 함(확장 없음), 연결 끊김을 문자열 비교 없이 갈래로 나눠 받는다 | #5a~#5e, 5.8 | 새(2026-11-28 전달) |
| 5g | 취소·시간 제한: 큰 파일 쓰기를 취소하고 시간 제한을 둘 수 있게(#3 exec와 같은 모양) | 5.3 저장 | 새(2026-11-28 전달) |
| 5h | 스트림 쓰기: 기기 파일 업로드. #5a는 내용 전체를 바꾸는 쓰기라 큰 파일에 맞지 않는다. 앱이 주는 입력 스트림(조각 단위)으로 원격 파일을 쓰는 길, 전체를 메모리에 올리지 않음, 진행률(보낸 바이트), 취소·시간 제한(#5g), 끝에 임시 이름 → 이름 바꾸기(#5a와 같은 원자 게시, 대상이 있으면 덮어쓰지 않는 모드) | 5.3 기기 파일 업로드 | 새(2026-11-28 전달). tasty-network 답(2026-11-28): M4 2단계, #5a·#5g와 한 쓰기 API로 묶는다(#5a는 #5h의 "전체 내용" 경우) |
| 6 | 한 SSH 세션을 터널·exec·SFTP가 함께 쓰는 경계 | 5.3, 5.7 | 이미 요청됨 / 있음 |
| 6b | attach 없이 쓰는 일반 SSH 세션의 수명·끊김 이벤트를 `ConnectionTable`과 같은 상태 API로 보기 | 5.7 | 새 / 있음 |
| 7 | 원격 Tasty attach 경로: exec 포트 발견 → `direct-tcpip` → handshake v2 → `attach`·`request`·`bulk` 채널. 채널 위 프레임 읽기·쓰기를 FFI로 노출(도메인 해석은 앱·tasty 쪽). 토큰은 FFI 밖 로그·UI·오류·Debug 출력에 내지 않는다 | 5.4 | 경로는 설계에 있음. FFI 노출 범위는 새 / 있음 (전달됨) |
| 7+ | #7에 이 문서가 더한 확인 항목: 1단계용으로 `RequestClient`(호출·닫는 핸들)를 감싸 노출, 상대 `caps`(`net.request`)를 앱이 볼 수 있게, `RequestError::ResponseTooLarge`·두 `PeerTooOld` 경로의 `evidence` 보존, 발견과 터널의 유효 대상 일치를 transport 경로에서 확인하고 불일치를 오류로 넘기기 | 5.4 | 새 / 전달됨(2026-11-27). `RequestClient`·`caps`·`evidence`는 `docs/design/mobile.md` "바인딩 API (요구를 받을 자리)"의 요청 채널 행과 "android 요구의 자리" #7+ 행. 발견과 터널의 대상 일치는 `docs/design/ssh-backends.md` "포트 발견과 터널의 대상 일치"(불일치는 `ConnectError::TargetMismatch`) |
| 7b | `PeerKeyStore`(앱이 구현하는 신뢰 저장소)의 FFI 경계: 앱이 구현하는 콜백 인터페이스, 최초 등록 원자성, 사용자 재등록 | 5.4 | 새 / 있음 |
| 8 | 연결 목록·사유·`is_retryable()`·`EventStream::next(timeout)`·`AppEvent` | 5.7 | 진행 중 초안에 있음 / 있음 |
| 9 | identity: 앱이 준 폴더의 기기 키 만들기·읽기. `File::lock`이 Android에서 잠그는지는 연결 레코드 경로의 문제라 따로 확인한다 | 5.4 | 기기 키는 있음. `File::lock` 확인은 새 / 있음 |
| 10 | Android용 태그: 모바일 빌드에서 `system-ssh` 꺼짐, `aarch64-linux-android`·`x86_64-linux-android`, NDK API 29. 앱은 그 태그로 rev를 올린다. 태그 시점을 알린다 | 3절 | 진행 중 초안에 있음. 태그 시점 통지는 새 / 전달 기록 |
| 11 | 내장 백엔드에서 기존 PowerShell·cmd 포트 발견 계약(`repo-boundary.md` "포트 발견의 tasty 관례"@b103e90)이 그대로 도는지 검증 | 5.4(데스크톱이 Windows일 때) | 새(검증 요구) / 있음 |
| 16 | 대화형 원격 셸(PTY). U9로 생긴 요구다. 아래 #16a~#16g | 5.9(⑤-3) | 새(2026-11-28 전달). tasty-network 답(2026-11-28): M4 3단계. ffi 첫 판에는 이름·모양 자리만 |
| 16a | 셸 채널: 같은 SSH 세션(#6의 공유 규칙) 위 session 채널에서 `pty-req`(터미널 종류, 열·행, 픽셀 크기 선택) 뒤 `shell` 요청. exec와 같은 연결·설정 해석 경로 | 5.9 채널 | 새(2026-11-28 전달) |
| 16b | 창 크기 바꾸기: `window-change`를 언제든 보낼 수 있게 | 5.9 창 크기 | 새(2026-11-28 전달) |
| 16c | 입출력: stdin 쓰기, stdout 읽기 스트림(PTY라 stderr는 합쳐진다). 받는 쪽이 느릴 때의 흐름 조절(채널 창)과 버퍼 상한 | 5.9 흐름 조절 | 새(2026-11-28 전달) |
| 16d | 끝: `exit-status`·`exit-signal`, 종료 상태 미수신, 세션 손실을 나눠 받는다(#3 exec 실패 분류와 같은 갈래) | 5.9 끝과 닫기, 5.8 | 새(2026-11-28 전달) |
| 16e | 닫기·취소: 앱이 닫는 핸들로 닫는다(EOF 보내기와 채널 닫기를 나눈다). 앱 수명 `Background`에서 앱이 모두 닫는다(5.7, U5) | 5.9 끝과 닫기 | 새(2026-11-28 전달) |
| 16f | 상태: 셸 세션도 #6b의 일반 SSH 세션 상태 API에 보이게 | 5.9 상태, 5.7 | 새(2026-11-28 전달) |
| 16g | 터미널 모델: 화면 렌더링은 앱의 기존 터미널(`tasty-terminal`, 로컬 셸과 같은 렌더러)이 맡는다. 라이브러리는 바이트 스트림만 준다 | 5.9 렌더러 | 새(2026-11-28 전달) |

R1에서 함께 요구한 것(전달됨): `tasty-net-ffi`가 `lib`로 다른 cdylib에 링크돼도 동작할 것, UniFFI 판을 앱과 맞출 것.

이 문서를 쓰며 새로 찾은 확인 항목(모두 전달됨, 2026-11-27). "tasty-network 쪽 위치"는 tasty-network develop `130c5d4`의 설계 문서다:

| # | 항목 | 앱이 쓰는 곳 | tasty-network 쪽 위치 |
|---|---|---|---|
| 12 | 라이브러리 모드에서 `tasty-net-ffi` 생성 Kotlin의 패키지 이름과, 앱이 그것을 덮어쓸 필요가 있는지 | 2절 | `docs/design/mobile.md` "크레이트"의 Kotlin 패키지 이름 항목과 "android 요구의 자리" #12 행 |
| 13 | #2의 메모리 키를 config의 `IdentityFile` 참조와 잇는 규칙(5.1 제안), `IdentitiesOnly`와 함께 쓸 때의 조건, 고른 메모리 자격이 세션 재사용 키에 들어가는지 | 5.1, 5.3 | `docs/design/ssh-backends.md` "android 요구의 자리" #2 행의 #13 문단, 세션 키는 같은 문서 "용도마다 해석하고, 같을 때만 세션을 나눈다". 5.1의 이름 잇기 제안은 같은 문서 "후속 작업과 미정"의 "메모리 키와 `IdentityFile` 이름" 행에 남아 있다 |
| 14 | 직접 LAN 연결 판정(첫 SSH 홉의 이름 풀이 결과, `.local`은 풀이 전)과, 두 사유를 FFI 오류로 따로 받는 길: (a) 소켓을 열기 전 앱의 권한 거절·회수로 연결을 시작하지 않음, (b) 연결 중 Android 권한으로 막혔을 때 소켓을 닫기 전에 읽은 차단 사유. API 29 빌드를 유지하는 호출 경계 | 5.5 | `docs/design/mobile.md` "모바일의 SSH" 아래 새 절 "직접 LAN 연결 (Android 지역망 권한)"과 "android 요구의 자리" #14 행 |
| 15 | 모바일 의존 쪽 검사(develop `558d4c7` `mobile.md` "의존 쪽 검사")를 앱의 최종 그래프에서도 같은 조건으로 돌릴 수 있게 검사 기준을 공개 문서로 내는 것 | 7절 | `docs/design/mobile.md` "의존 쪽 검사"의 앱 최종 의존 그래프 항목과 "android 요구의 자리" #15 행 |

## 7. CI 계획

### 정해진 것

- develop `84278b0`의 CI(`.github/workflows/ci.yml`)는 `ubuntu-latest` 한 잡, `timeout-minutes: 30`, SDK 설치(`platforms;android-37.0`, `build-tools;37.0.0`) 뒤 4단계: `spotlessCheck` → `lint` → `test` → `assembleDebug`. 실패하면 `app/build/reports/`를 올린다.
- `work/android-terminal`의 CI는 그 위에 다음을 더한다(U1로 develop `ccc0022`에 반영됨): SDK 설치에 `ndk;30.0.16248370`, `rust/`에서 `rustup show active-toolchain || rustup toolchain install`과 `cargo install cargo-ndk --version 4.1.2 --locked`, `Swatinem/rust-cache@v2`(`workspaces: rust`), `rust/`에서 `cargo fmt --all --check`·`cargo clippy --workspace --all-targets --locked -- -D warnings`·`cargo test --workspace --locked`, 그 뒤 기존 Gradle 4단계.
- 생성된 UniFFI Kotlin은 커밋하지 않는다. `app/build/` 아래에 생기고 `.gitignore`의 `build/`에 들어간다. `spotlessCheck`도 `**/build/**`를 빼고 본다(루트 `build.gradle.kts`).
- CI의 push 대상 브랜치를 git-flow에 맞추는 일은 U1 반영에서 했다(`ccc0022`: `develop`·`release`·`main`의 push와 그 브랜치로 가는 pull request에서 CI를 돈다).
- 고지(U10): 앱 안에 한 장짜리 고지 화면을 두고, 같은 고지 파일을 릴리스에도 붙인다. 고지 파일은 `cargo deny`와 함께 CI가 생성·최신 여부를 확인한다(아래 제안).

### 제안

- 생성 작업 의존: 두 ABI의 Rust 출력(`cargoNdkBuild`)과 Kotlin 바인딩(`uniffiBindgen`)은 입력·출력이 선언된 생성 작업으로 둔다. 생성 Kotlin을 컴파일하는 `lint`·`test`도 이 작업에 의존한다(`addGeneratedSourceDirectory`가 그 의존을 만든다). CI 순서상 Rust 빌드가 처음 도는 단계는 `assembleDebug`가 아니라 `lint`다.
- 단계 사이 재사용: Gradle 단계는 따로 호출되지만 같은 작업 폴더를 쓰므로, `lint`에서 만든 `.so`와 Kotlin을 `test`·`assembleDebug`가 최신으로 건너뛰어야 한다. 빌드 캐시와 구성 캐시는 `gradle.properties`에서 이미 켜져 있다.
- 호스트 bindgen: `uniffi-bindgen` 실행도 `--locked`다(지금 그렇다). 4절 입력 보강 뒤에는 생성기 크레이트·`uniffi.toml`·`Cargo.lock`·툴체인 파일이 바뀌면 다시 생성하고, ABI·NDK 판이 바뀌면 생성에 쓰는 첫 ABI `.so`의 내용이 바뀔 때만 다시 생성한다.
- Rust 캐시: `Swatinem/rust-cache@v2`는 기본 설정에서 Cargo의 git 의존 캐시(tasty-network, tasty), 설치된 실행 파일과 설치 기록을 캐시한다(그 action의 문서 "Cache Details"). 다만 지금 순서는 `cargo install cargo-ndk`가 캐시 복원보다 앞에 있어 매번 다시 컴파일한다. 캐시 복원을 앞으로 옮기고, 복원 뒤 `cargo-ndk --version`이 고정한 `4.1.2`인지 확인해 다를 때만 설치한다.
- Android target 검사: 지금 `clippy`는 호스트 target만 본다. `cfg(target_os = "android")` 코드와 tasty-network의 모바일 feature 조합을 보려면 Android target 하나(`aarch64-linux-android`)로 `clippy`를 한 번 더 돌린다. C 코드가 있는 의존(`ring`)은 NDK clang이 필요하므로 cargo-ndk를 거쳐 돌린다.
- 모바일 의존 확인: 2절 계약 4를 CI에서 본다. 기준은 tasty-network develop `130c5d4`의 `docs/design/mobile.md` "의존 쪽 검사"의 앱 최종 의존 그래프 항목(107행)과 "android 요구의 자리" #15 행(314행)이다(6절 #15). 대상은 두 ABI target(`aarch64-linux-android`·`x86_64-linux-android`)이고, 앱 작업 공간에서 앱 cdylib `tasty-mobile-core`를 `-p`로 주고 배포 빌드 feature 인자를 붙인 `cargo tree --locked --target <t> ... -e normal -f '{p} {f}' --prefix none`으로 본다. 실패 조건: `russh-config` 줄이 있다, `tasty-net-ssh` 줄의 feature에 `system-ssh`가 있다(같은 절 104행의 조건 그대로). `tokio`의 `process`는 그 줄만으로 실패로 보지 않는다. 다른 프로그램 실행 금지는 앱 코드(로컬 터미널 PTY 등, 2절)에 적용되지 않아 앱 자신이 켤 수 있기 때문이다. 대신 같은 패키지·target·feature 인자·일반 의존 범위로 거꾸로 본 `cargo tree --locked --target <t> -p tasty-mobile-core <배포 feature 인자> -e normal,features -i tokio`에서 `process`를 켠 경로에 `tasty-net-*` 크레이트가 있을 때만 실패다. Cargo는 같은 의존에 여러 경로가 켠 feature를 합치므로, 검사는 tasty-network 크레이트만 따로 풀지 않고 실제 배포 빌드의 앱 전체 일반 의존 그래프에서 본다. 이 검사를 하는 스크립트는 앱 고정 rev에 이미 있다(`tasty-network:scripts/check-mobile-deps.sh@36cd2a2`). 앱 작업 공간의 `Cargo.toml`(`--manifest-path`), 앱 그래프 기준(`--app`), 두 target, 앱 cdylib(`-p`), 배포 빌드의 feature 인자를 받고, 앱 레포는 tasty-network 의존과 같은 rev의 그 파일을 받아 쓴다(`tasty-network:docs/dev-guide/ci.md@36cd2a2` 52~61행). 앱 CI는 위 두 명령을 직접 쓰지 않고 그 스크립트를 부른다.
- 시간: 두 ABI release 빌드(`lto = "thin"`, `codegen-units = 1`)에 tasty-network 의존(`ring`, russh 등)이 더해진다. 첫 CI 실행 시간을 재고 `timeout-minutes: 30`을 그 값으로 다시 정한다.
- 라이선스: 앱 `.so`에 UniFFI 런타임(MPL-2.0)이 링크되고 JNA를 싣는다. `rust/`에 `cargo deny`를 둔다. 허용 목록은 tasty-network `deny.toml`@b103e90에서 시작하되 그대로 쓰지 않는다. (1) tasty-network의 OpenSSH 이식 후속 결정은 이식 크레이트에 `BSD-2-Clause AND ISC AND SSH-short`를 요구하는데 b103e90 허용 목록에는 `SSH-short`가 없다. 앱이 실제로 링크하는 판의 라이선스로 목록을 정한다. (2) 그 정책 파일은 crates.io 밖 git 출처를 거절한다(`unknown-git = "deny"`). 앱은 tasty와 tasty-network를 git으로 받으므로 두 출처를 `allow-git`에 적는다.
- 고지 파일(U10): 고지 파일 하나를 빌드가 만들고, 앱 안 고지 화면과 릴리스 첨부가 그 같은 파일을 쓴다. 배포 경로(기획 4.9)와 상관없이 같다.
  - 내용: 앱 cdylib이 실제로 링크하는 Rust 의존(배포 빌드 feature, 두 ABI target의 일반 의존 그래프)의 이름·판·라이선스·저작권 표시, UniFFI 런타임(MPL-2.0), JNA, tasty-network의 OpenSSH 이식 코드 저작권 표시(이식 크레이트의 `BSD-2-Clause AND ISC AND SSH-short`, 위 라이선스 항목), 앱이 싣는 Android 쪽 Gradle 의존. 5.3의 Markdown 스크립트를 (a)로 정하면 번들한 highlight.js·KaTeX·mermaid도 들어간다(공식 플러그인도 같은 셋을 `THIRD_PARTY_LICENSES.md`와 `LICENSES/`에 적는다, tasty-official-plugins develop `4c80e96`).
  - 만드는 방법: Rust 부분은 `cargo deny`의 라이선스 검사와 같은 그래프·같은 허용 목록에서 생성기로 만든다. 두 검사가 다른 그래프를 보면 고지에서 빠지는 의존이 생기기 때문이다. Gradle 의존은 Gradle 쪽 라이선스 보고에서, 생성기가 모르는 항목(OpenSSH 이식 고지, 번들 스크립트)은 레포에 둔 원문 파일에서 합친다. 생성 도구는 7절 작업 때 고른다.
  - 확인: 생성한 고지 파일은 레포에 커밋해 의존이 바뀔 때 diff로 보이게 한다. CI는 `cargo deny check` 뒤 고지 파일을 다시 만들어 커밋된 파일과 비교하고, 다르면 실패한다(생성·최신 여부 확인). 빌드는 그 파일을 APK assets에 넣고, 고지 화면은 그 asset을 그대로 보인다. 릴리스 작업은 같은 커밋의 같은 파일을 첨부한다.
- 증거 요구: CI 기록에서 (1) `lint` 단계가 `cargoNdkBuild`·`uniffiBindgen`을 실행하고 `test`·`assembleDebug` 단계는 최신으로 건너뛴다, (2) 두 번째 실행에서 cargo-ndk를 다시 컴파일하지 않는다, (3) Android target `clippy`와 `system-ssh` 확인 단계가 돈다, (4) 전체 시간이 새 상한 안이다, (5) `cargo deny check`가 돌고, 의존을 바꾸고 고지 파일을 갱신하지 않은 커밋에서 고지 확인 단계가 실패한다, (6) APK 안의 고지 파일과 릴리스 첨부 파일이 커밋된 고지 파일과 같은 내용이다.

### 결정 대기

- 없음. 라이선스 고지의 모양은 U10으로 정해졌다(위 "정해진 것").

## 8. 단계

| 단계 | 내용 | 선행(레포·사용자 결정) |
|---|---|---|
| ① 원격 빌드 칸 | Android 빌드와 에뮬레이터 시험을 도는 원격 Windows 빌드 칸 | 없음 |
| ①′ 터미널 브랜치 반영 | `work/android-terminal`을 develop 위로 올리고 CI 4단계와 Rust 검사를 돌려 반영한다. develop `ccc0022`에 반영됐다(develop push CI 성공) | ①, U1 |
| ② Rust 연결 기반 | tasty-network develop rev 고정(`36cd2a29adeb33e0b8383767db74ec233c07535c`, 처음에는 `130c5d4`). 앱 cdylib의 임시 export(3절)로 `noBackupFilesDir/net/` 아래 최종 키 폴더(5.4)에 기기 키를 만들고 읽고 기기 ID를 돌려준다. 두 ABI `.so`와 생성 Kotlin. develop `9a724fe`에 반영됐다(기기 키 만들기·다시 읽기·동시 생성·덮어쓰지 않음 시험이 API 29·37 에뮬레이터에서 통과. 이 시험들은 생성자에 시험용 폴더를 넘긴다). 그때 앱 키 폴더(`forApp`)는 `filesDir/tasty-net`이었고, 뒤 수정에서 이 계획대로 `noBackupFilesDir/net`으로 바꿨다. 그 경로를 쓰는 `forApp` 기기 시험도 API 29·37에서 통과했다 | ①′, 이 계획의 검증. b103e90의 transport는 시스템 ssh 크레이트에 늘 의존하므로 이 단계는 identity만 의존한다. 이미 있는 증거(b103e90 크레이트의 `aarch64-linux-android` `cargo check`)와 이 단계가 새로 내야 할 증거(두 ABI에서 API 29로 앱 링크, 기기·에뮬레이터 위 실행)를 나눈다 |
| ②′ 고지 파일과 고지 화면 | 7절 고지(U10). `cargo deny`, 고지 파일 생성과 CI의 최신 여부 확인, 앱 안 한 장짜리 고지 화면. ②′는 파일·화면·CI 확인까지이고, 같은 파일의 릴리스 첨부는 릴리스 작업이 생길 때 한다(배포 경로 기획 4.9는 아직 열려 있다) | ②. 늦어도 첫 배포 전. ③·④보다 먼저 두는 까닭: 앱은 지금도 UniFFI 런타임·JNA·`tasty-terminal`·`tasty-net-identity`를 싣고, ③이 tasty-network 의존(`ring`, russh 등)을 크게 더한다. 생성과 CI 확인이 먼저 있으면 그 뒤 단계가 더하는 의존이 고지와 허용 목록 검사에 바로 걸린다 |
| ③ ffi로 교체 | `tasty-net-ffi`가 생기면 그것을 rlib으로 링크하고 ②의 임시 export를 지운다. ②와 같은 키 폴더를 넘겨 기기 ID를 유지한다. UniFFI 판을 맞추고 scaffolding 재노출(2절)을 붙인다 | tasty-network: transport ssh feature 분리, 내장 SSH(exec·SFTP), ssh-config 이식, ffi 크레이트, UniFFI 판 고정. 진행 중 초안의 ffi 의존에는 `tasty-net-p2p`가 들어 있으므로, 그 구성이 유지되면 p2p 크레이트의 반영도 선행이다 |
| ④ SSH 프로필 + exec + SFTP 읽기 | 5.1~5.3. 프로필 파일·키 생성과 가져오기, Git 정보(status·log·diff·worktree 목록, U6), Explorer 읽기와 뷰어(Markdown·이미지·HTML·SVG, U7). 앱 수명 이벤트 연결(`AppEvent`), 백그라운드 정리(5.7), LAN 권한 경로(5.5)를 함께 만든다 | ③, #1·#2·#3·#4·#6·#8·#13·#14(#8의 `AppEvent`는 ④가 앱 수명 이벤트 연결에 쓴다. ⑥은 같은 #8을 상태 화면에 쓴다). 화면 범위는 U6·U7로 정해졌다. 5.3의 뷰어 판단 필요 항목(Markdown 렌더링 방식, HTML 스크립트 허용 버튼, 플랫폼 디코더 밖 이미지 형식, 상대 리소스 범위, 외부 링크)을 시작 전에 정한다 |
| ④′ 편집·파일 관리 | 5.3 "쓰기와 파일 관리"(U8). 텍스트 편집 저장(임시 이름 뒤 이름 바꾸기, 충돌 확인), 새 폴더·빈 파일 만들기·이름 바꾸기·옮기기·지우기(확인 단계), 기기 파일 업로드(파일 선택기, 스트림 쓰기) | ④(계획이 정한 순서, 사용자 미지정. ④의 목록·`stat`·범위 읽기를 쓴다), #5a~#5h. 덮어쓰는 이름 바꾸기가 없는 서버의 저장(5.3 판단 필요)을 시작 전에 정한다 |
| ⑤-1 원격 Tasty 목록·상태 | 5.4 1단계. 링크·요청 채널의 백그라운드 정리(5.7)를 함께 만든다 | 모델·호출부 개발: ④, #7·#7b(R3 답의 응답 모양으로 시작, 어느 tasty 반영도 기다리지 않는다). 실제 목록 연동: tasty R3 (A) `workspace.overview` 반영, v2 레코드 게시, tasty 전환과 같은 tasty-network 태그 |
| ⑤-2 화면 attach | 5.4 2단계 | ⑤-1, tasty R3 (B) 반영(도메인 메시지·descriptor 타입), tasty mirror 계약 |
| ⑤-3 대화형 원격 셸 | 5.9(U9). PTY 셸 채널, 창 크기, `tasty-terminal` 렌더러, 앱이 뒤로 가면 끊김(U5) | 순서: ⑤-2 뒤(U9가 정한 사용자 순서). 기술 선행은 ③, ④(SSH 프로필·인증·LAN 판정·앱 수명 이벤트 연결과 정리), #1·#6·#8·#16·#6b이고 ⑤-2에는 기대지 않는다. 그래서 ④ 뒤라면 ⑤-2가 tasty 쪽 반영을 기다리며 멈춰도 ⑤-3의 기술 선행은 따로 풀린다. 다만 사용자에게 보인 비용 근거는 ⑤-2가 같은 `tasty-terminal` 렌더러 연결을 먼저 만든다는 것이었으므로, ⑤-2를 건너뛰고 ⑤-3을 먼저 하면 그 렌더러 연결 비용을 ⑤-3이 진다. 순서를 바꾸는 것은 사용자 몫이다 |
| ⑥ 연결 상태 | 5.7. 두 층 상태 화면(앱 수명 이벤트 연결과 백그라운드 정리는 ④·⑤에서 이미 만든다) | 화면 개발: ③, #6b·#8. 통합 검증(두 층을 실제로 모아 확인): ④(일반 SSH 세션), ⑤-1(Tasty 요청 연결) |

- ②′~⑥(④′·⑤-3 포함)의 구현 작업은 각 선행이 풀릴 때 이 표를 기준으로 나눈다.
- ②의 기기 키 게시(해결됨): `130c5d4`의 `tasty-net-identity` `DeviceKey::load_or_create`는 키 파일을 `std::fs::hard_link`로 게시했다(`crates/tasty-net-identity/src/device_key.rs`@130c5d4). Android 앱 데이터 폴더(SELinux 유형 `app_data_file`)에서는 SELinux가 이 link를 거절했다(에뮬레이터 API 37에서 `avc: denied { link }`, 기기 위 시험 2개 모두 실패). tasty-network는 Linux·Android에서 완성한 임시 파일을 `renameat2(RENAME_NOREPLACE)` 시스템 호출로 직접 게시하도록 고쳤다(`539071c`, 후속 수정 `e656c21`까지 `36cd2a2`에 들어 있다. bionic 래퍼가 없는 API 29에서도 동작한다. hard link는 그 rename을 OS나 파일 시스템이 지원하지 않을 때만 쓴다). 앱은 rev를 `36cd2a2`로 올려 같은 기기 시험을 통과했다.
- 기본 상태 표시(연결 중·끊김)는 ④·⑤에서 각 화면이 먼저 갖고, ⑥이 두 층을 모은 상태 화면을 만든다.

## 9. iOS

사용자 결정("구조만 iOS가 가능하게")에 따라 이 레포는 iOS를 하지 않는다. tasty-network 쪽 바인딩 경계(`staticlib`, Swift 생성)는 그 레포가 맡는다.

## 10. 기획 초안과의 관계

- [기획 초안](app-plan-draft.md) 4.6 표와 5절 `tasty-ssh` 행은 조사 기준 tasty `89efb5602`의 기록이라 고치지 않는다. b103e90의 `tasty-net-ssh`도 아직 시스템 ssh 위임이다(`crates/tasty-net-ssh/Cargo.toml` description). 모바일이 쓰는 내장 SSH는 tasty-network의 후속 계약이다. 2026-11-27 이후의 SSH 구현 방향은 이 문서를 따른다.
- 기획 초안의 열린 선택지 가운데 3.2(Git 정보 범위)·3.3(뷰어)·4.2(대화형 SSH 터미널)·4.4(파일 편집)와 고지의 모양은 U6~U10(2026-11-28)으로 정해졌다. 기획 초안은 조사 기록이라 고치지 않고, 정해진 내용은 이 문서의 결정 표를 따른다. 4.9(배포 경로)는 아직 열려 있다.
