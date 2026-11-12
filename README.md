# Risu Manager

Risu Manager는 Android에서 호환되는 Risu 서버의 설치와 실행을 관리하는 앱입니다. 관리 화면은 Jetpack Compose로 만들었고, 실제 PocketRisu와 Haejeok RisuAI 서버는 **Termux 안의 별도 Node.js 프로세스**로 실행됩니다. Manager APK에 Node.js나 서버 코드를 내장하지 않습니다.

현재 제품 카탈로그에는 PocketRisu와 Haejeok RisuAI의 **Termux 배포판**이 있습니다. 기능은 배포판별로 다릅니다. PocketRisu는 설치·실행 외에 `.bin` 백업, 복제, 업데이트, 복구 지점 및 롤백을 지원합니다. Haejeok은 검증된 공식 Termux 릴리스 압축 파일을 설치하고 인스턴스별 PostgreSQL을 사용하며, 현재 서버 네트워크 설정만 제공합니다.

> 이 프로젝트는 독립적인 관리자 앱입니다. 상위 프로젝트 이름은 호환 대상을 식별하기 위한 것이며, 상위 프로젝트의 공식 앱 또는 보증을 뜻하지 않습니다.

## 목차

- [지원 범위와 요구 사항](#지원-범위와-요구-사항)
- [처음 사용하기](#처음-사용하기)
- [인스턴스 사용 설명서](#인스턴스-사용-설명서)
- [백업과 복구의 차이](#백업과-복구의-차이)
- [구조와 데이터 위치](#구조와-데이터-위치)
- [빌드와 검증](#빌드와-검증)
- [문제 해결](#문제-해결)
- [상세 문서](#상세-문서)
- [라이선스](#라이선스)

## 지원 범위와 요구 사항

| 항목 | PocketRisu | Haejeok RisuAI (Termux) |
| --- | --- | --- |
| 소스 | `PocketRisu/PocketRisu` | `nevaeh5379/HaejeokRisuai` |
| 설치 방식 | 공식 저장소의 고정된 커밋을 받아 Termux용 빌드 | 공식 stable 릴리스의 `RisuAI-Termux-b*.tar.gz` 압축 파일 |
| 채널 | Stable, Develop, 특정 커밋 | Stable |
| CPU | `aarch64` | `aarch64`, `arm`, `x86_64`, `x86` |
| Node.js | 22.12.0 이상 | 20.19.x 또는 22.12.0 이상 |
| 추가 기능 | `.bin` 백업, 복제, 업데이트, 복구 지점·롤백, 이미지 최적화, HTTPS 설정 | 네트워크 설정 |
| 데이터 저장 | 인스턴스별 PocketRisu `save/` 및 `backups/` | 인스턴스별 `save/`, PostgreSQL 데이터·소켓·설정 |

Android 앱의 최소 SDK는 24, 대상 SDK는 37입니다. 설치 대상 Android 기기에는 현재 버전의 공식 Termux가 필요합니다. PocketRisu를 설치하려면 ARM64(`aarch64`) 기기가 필요합니다. GitHub 릴리스/커밋 조회와 Termux의 패키지·소스 다운로드를 위한 네트워크 연결 및 빌드·복구 지점용 여유 공간도 필요합니다.

현재 **지원하지 않는 기능**은 Android APK/PWA 배포판 설치, 임의 저장소 등록·실행, 인스턴스 제거, Manager에서 내보낸 `.bin` 파일의 가져오기입니다. PocketRisu 업데이트와 롤백은 Manager가 관리하는 설치 및 검증된 복구 지점에 한정됩니다. 설치 가능 여부와 기능 표시에는 앱에 내장된 신뢰 프로필을 사용합니다.

## 처음 사용하기

### 1. 앱 빌드 또는 설치

개발 환경에서는 아래 [빌드 명령](#빌드와-검증)으로 디버그 APK를 만든 뒤 `app/build/outputs/apk/debug/app-debug.apk`를 Android 기기에 설치합니다. 배포 APK를 직접 만들 경우에는 별도의 서명 설정이 필요합니다.

### 2. Termux 연결

1. 공식 Termux를 설치하고 한 번 실행합니다.
2. Risu Manager에서 **Settings → Termux integration**을 엽니다. 설치 화면의 **Connect Termux**로도 이동할 수 있습니다.
3. **Grant permission**을 눌러 Android의 `com.termux.permission.RUN_COMMAND` 권한 요청을 승인합니다.
4. 화면에 표시되는 일회성 설정 명령을 **Copy setup command**로 복사해 Termux에서 실행합니다. 이 명령은 Termux의 외부 명령 허용 설정(`allow-external-apps=true`)을 적용하고 설정을 다시 읽게 합니다.
5. Manager로 돌아와 **Check again**을 누릅니다. 연결과 Manager 소유 helper의 버전 확인이 끝나면 준비 상태가 표시됩니다.

Manager는 Termux 터미널의 글자를 읽어 제어하지 않습니다. Termux의 `RUN_COMMAND` 서비스로 허용된 명령만 전달하고, APK에 들어 있는 helper를 `~/.risu-manager/bin/risu-manager`에 설치합니다. Termux가 열려 있어도 권한 또는 외부 명령 설정이 빠지면 연결이 완료되지 않습니다.

### 3. 서버 설치

1. 하단 **Install** 탭에서 배포판을 선택합니다.
2. 채널을 선택합니다. PocketRisu의 **Develop**와 **Specific commit**은 고급 옵션입니다. 특정 커밋을 선택하면 커밋 SHA를 입력합니다.
3. Termux 연결 상태를 확인하고 **Install**을 누릅니다. Android가 알림 권한을 요청하면 설치 진행 알림을 위해 처리합니다.
4. 진행 단계와 **Detailed installer log**를 확인합니다. 총 작업량을 알 수 없는 빌드 단계는 퍼센트 대신 진행 중 표시가 나옵니다.
5. 완료 후 **Home**에서 새 인스턴스 카드의 상태와 포트를 확인합니다.

Stable은 초안과 시험판을 제외한 최신 GitHub 릴리스를 찾아 **전체 커밋 SHA**로 고정한 뒤 설치합니다. PocketRisu는 해당 커밋의 상위 Termux 빌드 스크립트를 실행합니다. 따라서 첫 설치는 네이티브 의존성 설치와 프런트엔드 빌드 때문에 오래 걸릴 수 있습니다. Haejeok은 공식 릴리스 압축 파일과 체크섬을 검증한 뒤 설치합니다.

설치가 실패하면 같은 화면의 오류 코드와 로그를 먼저 확인합니다. 복구 가능한 패키지·빌드·다운로드 오류에는 제한된 자동 재시도가 적용됩니다. **Clean leftovers and retry**는 실패한 인스턴스 ID를 재사용하며, 완료되지 않은 Manager 소유 임시 파일만 정리합니다. 이미 존재하는 사용자 데이터나 검증된 설치는 자동 삭제하지 않습니다.

## 인스턴스 사용 설명서

### Home에서 실행하고 열기

**Home**의 각 카드는 설치 버전, 채널, 포트, 실행 상태를 보여 줍니다.

- **Start / Stop / Restart**: 서버 프로세스를 제어합니다. Helper는 기록된 PID가 해당 인스턴스의 실제 서버인지 확인한 다음 종료 신호를 보냅니다.
- **Open**: 실행 중인 서버를 앱 내부 WebView로 엽니다. 브라우저 아이콘은 외부 브라우저로 엽니다. 내부 WebView는 해당 인스턴스의 로컬 원점으로 이동을 제한합니다.
- 로그 아이콘: 최근 서버 로그를 확인합니다. 설치 로그는 **Install** 화면에서 별도로 볼 수 있습니다.
- 설정 아이콘: 포트, 접속 범위 및 배포판에서 지원하는 서버 설정을 엽니다.
- 화면 상단 새로고침: 상태를 다시 조회합니다.

`RUNNING`은 소유 PID와 로컬 HTTP/HTTPS 상태 검사가 함께 성공한 상태입니다. 프로세스는 살아 있지만 상태 검사가 실패하면 `UNHEALTHY`로 표시될 수 있습니다. 실행 중인 인스턴스에는 Termux 감시 프로세스가 붙어 Node가 예기치 않게 종료되면 다시 시작합니다. 이 과정에서 잠깐 연결이 끊길 수 있고, Android가 Termux와 감시 프로세스를 함께 종료하면 자동 복구도 중단됩니다. Termux wake lock은 실행 중인 서버를 돕지만 Android의 배터리·메모리 정책을 무효화하지는 못합니다.

### 서버 설정

**Home → 인스턴스의 설정 아이콘**에서 다음 값을 조정합니다.

- **Port / Access scope**: 기본 포트는 6001이고, 기본 접속 범위는 **This device only**(loopback)입니다. **Same network**로 바꾸면 같은 네트워크의 다른 기기에서 포트에 접근할 수 있으므로 서버 인증을 먼저 구성해야 합니다.
- **PocketRisu 전용**: 요청 본문 크기(1–1024 MB), 자동 저장 간격(1–30초), 서버 스냅샷 주기(1–1440분), 보관 개수(1–100), 저장 공간 한도(10–51200 MB), 이미지 최적화 품질(1–100).
- **HTTPS**: PocketRisu는 기존에 설치된 수동 인증서와 Let’s Encrypt 방식의 상태를 다룹니다. 앱은 수동 인증서/개인 키를 가져오는 화면을 제공하지 않습니다. Let’s Encrypt에서는 단일 공개 도메인, 이메일, 내부 HTTP-01 포트를 입력한 뒤 **Check connection prerequisites**로 DNS와 내부 포트를 확인하고 **Issue certificate and enable HTTPS**를 사용합니다. 라우터의 **외부 TCP 80 → 설정한 내부 포트** 전달, DNS A/AAAA, CGNAT 여부는 사용자가 확인해야 합니다. 발급 후 상태·만료일·갱신 결과를 볼 수 있고 **Check renewal now** 또는 **Disable HTTPS**를 사용할 수 있습니다. 자동 갱신은 네트워크 조건이 맞을 때 Android WorkManager가 정기적으로 시도하므로 정확한 시각의 실행을 보장하지 않습니다.

**Save and apply**는 Android와 helper 양쪽에서 값을 검증한 뒤 Termux 설정 파일을 원자적으로 바꿉니다. 실행 중인 서버는 재시작하고 상태 검사를 통과해야 적용 완료로 표시됩니다. 원래 중지된 서버는 중지 상태를 유지합니다. 적용 실패 시 이전 설정과 실행 상태로 되돌리기를 시도하며, 화면에서 저장값과 실제 적용값을 따로 확인할 수 있습니다. Haejeok에는 검증된 네트워크 설정만 표시됩니다.

**Open image optimization**은 PocketRisu의 인증된 활성 세션에서만 사용할 수 있는 일회성 작업입니다. 이미지 파일을 WebP로 바꿀 수 있으므로 먼저 `.bin` 백업을 만드세요. 작업 화면에서 실시간 진행 상태를 확인할 수 있습니다.

### PocketRisu 복제

**Home → Clone**에서 새 이름을 입력합니다. Manager는 원본이 실행 중이었다면 잠시 정상 종료해 일관된 파일 스냅샷을 만들고, 검증 후 원본을 기존 상태로 되돌립니다. 새 인스턴스에는 별도 ID, 자동 할당 포트, 데이터와 설정이 주어지며 **중지 상태**로 등록됩니다. 공개 도메인, TLS 개인 키, 인증서 갱신 소유권은 복사되지 않고 로컬 HTTP가 기본값입니다. 복제는 같은 설치 버전을 복사하며 업데이트를 실행하지 않습니다.

### PocketRisu 업데이트

**Home → Update**를 누르면 설치 버전·커밋, 대상 버전·커밋, 복구 지점 예상 크기와 필요한 여유 공간이 표시됩니다. 승인하면 Manager가 현재 서버·저장 데이터·설정을 포함한 **완전한 복구 지점**을 검증한 후 대상 커밋을 별도 위치에 빌드·활성화합니다. 빌드와 시작·상태 검사가 성공해야 업데이트가 완료됩니다. 실패 시 이전 전체 상태로 되돌리기를 시도하며, 복원이 실패하면 복구가 필요하다고 표시합니다.

복구 지점 기본 보관 기간은 7일입니다. **Settings → Update recovery → Retention (days)**에서 이후 업데이트에 적용할 기간을 1–365일로 설정할 수 있습니다. 커밋이 다르더라도 상위 앱 데이터 호환성이 자동으로 보장되는 것은 아니므로 중요한 인스턴스는 별도의 내보내기 백업도 보관하세요.

### PocketRisu 복구 지점과 롤백

**Home → Recovery points**에는 Manager가 업데이트나 롤백 전에 만든 전체 인스턴스 스냅샷이 나옵니다. 각 지점의 시간, 버전, 크기, 만료일, 사용 가능 상태를 확인할 수 있습니다.

- **Review differences and restore**: 현재 상태와 선택한 지점의 서버·데이터·설정 차이를 검토합니다. 복원을 시작하기 전에 **현재 상태의 별도 복구 지점**을 만들고 검증합니다. 선택한 시점 이후의 대화, 저장 내용, 설정 변경은 활성 인스턴스에서 사라집니다.
- **Create new instance from this point**: 역사적 상태를 별도 인스턴스로 복제합니다. 원본과 선택한 복구 지점은 유지되며, 새 인스턴스는 별도 포트의 로컬 HTTP·중지 상태로 시작합니다.
- **Pin / Unpin**: 자동 만료 정리에서 보호하거나 원래 만료 일정으로 되돌립니다.
- **Delete**: 고정되지 않았고 사용 중이거나 실패 복구에 필요한 상태가 아닌 지점만 삭제할 수 있습니다.

복구 지점은 Termux 내부의 **비공개 전체 상태**이며 계정 정보나 자격 증명을 포함할 수 있습니다. 선택 가능한 임의의 Git 커밋이나 `.bin` 파일이 롤백 대상이 되는 것은 아닙니다. 실패나 중단 후 복구가 필요하다고 표시되면 관련 복구 지점을 삭제하지 말고 오류 코드와 진단 보고서를 보관하세요.

### Settings와 진단

**Settings**에서 시스템/밝은/어두운 테마, 향후 업데이트 복구 지점 보관 기간, Termux 연결, 배터리 최적화 설정, **Diagnostic Report**를 찾을 수 있습니다. 진단 보고서는 작업 단계와 오류 코드를 담되 서버 로그 본문과 사용자 데이터는 제외합니다. 서버 로그나 설치 로그가 필요한 문제는 각각 해당 화면에서 확인하세요.

## 백업과 복구의 차이

| 종류 | 만드는 곳 | 포함 범위와 사용법 |
| --- | --- | --- |
| **Backup (.bin)** | PocketRisu 카드 → Android 문서 저장 위치 선택 | PocketRisu와 호환되는 내보내기 파일입니다. 서버가 실행 중이면 잠시 정상 종료해 SQLite를 비운 뒤 백업하고 다시 시작합니다. Termux에서 Android 문서로 옮길 때 크기와 SHA-256을 확인합니다. 현재 Manager에는 이 파일을 가져오는 UI가 없습니다. |
| **서버 스냅샷** | PocketRisu 서버 설정의 주기·보관량 | PocketRisu 서버 자체의 주기적 데이터베이스 스냅샷입니다. 앱에서 즉시 스냅샷을 만드는 버튼이나 마지막 성공 시각 조회는 제공하지 않습니다. |
| **Manager 복구 지점** | PocketRisu 업데이트·롤백 직전 자동 생성 | 서버 체크아웃, 저장 데이터, 자산, 설정을 묶은 Termux 비공개 전체 상태입니다. **Recovery points**에서 전체 인스턴스 롤백 또는 새 인스턴스 복제에 사용합니다. Android 문서로 내보내는 `.bin`과 형식·용도가 다릅니다. |

백업 중 정상 종료가 시간 안에 끝나지 않으면 강제 종료 대신 백업을 중단합니다. 내보내기 전송용 파일은 일회성 토큰을 쓰는 loopback 서버에서 제공되며 전송 후 정리되거나 15분 후 만료됩니다. 전송 실패·취소 시 선택한 Android 문서가 불완전할 수 있으므로 성공 메시지와 검증된 바이트 수를 확인하세요.

## 구조와 데이터 위치

`app` 단일 Android 모듈 안에서 제품 정의, 소스 조회, 실행, 영속성, UI를 분리합니다.

```text
Compose UI / ViewModels
          │
          ├── OperationCoordinator + WorkManager (장시간 설치·백업·복제·업데이트·롤백)
          ├── VariantDefinition / VariantProfile (내장 신뢰 프로필과 기능 목록)
          ├── SourceRepository → GitHubSourceAdapter (릴리스·커밋 조회)
          ├── InstanceRepository → SQLite / DataStore (메타데이터·설정)
          └── RisuRuntime → TermuxRuntimeAdapter
                                │
                       Termux RUN_COMMAND
                                │
                    Manager 소유 allowlist helper
                                │
                 Termux 안의 Node.js / PostgreSQL 프로세스
```

| 경로 | 역할 |
| --- | --- |
| `app/src/main/kotlin/io/github/risumanager/ui/` | Compose 화면, 탐색, ViewModel |
| `domain/model/`, `domain/planning/`, `domain/validation/` | 배포판 계약, 작업 계획, 입력 검증 |
| `data/github/`, `data/local/`, `data/repository/`, `data/settings/` | GitHub 조회, SQLite, 저장소, DataStore |
| `runtime/termux/` | RUN_COMMAND 통신, helper 프로토콜, 서버 설정 적용 |
| `operation/` | WorkManager 작업과 백업·복제·업데이트·롤백 조정 |
| `app/src/main/assets/termux-helper/install.sh` | APK에 포함되는 Termux helper 설치본과 명령 구현 |
| `tools/` | 에뮬레이터 설정 및 결정적 helper 계약 시험 |
| `docs/` | 기능별 동작, 보안 경계, 검증 기록 |

Android의 `risu-manager.db`에는 인스턴스, 작업 이력, 서버 설정, 복구 지점 **메타데이터**가 저장됩니다. 테마와 보관 기간 등 사용자 설정은 DataStore에 있습니다. PocketRisu 대화·자격 증명과 실제 복구 파일은 Android Manager DB가 아니라 Termux의 인스턴스 또는 비공개 복구 디렉터리에 있습니다.

Termux의 주요 경로는 다음과 같습니다.

```text
~/.risu-manager/
├── bin/risu-manager                 # 명령 helper
├── instances/<instance-id>/
│   ├── repo/                        # 서버 및 해당 인스턴스의 데이터
│   ├── run/                         # 소유 PID와 서버 로그
│   ├── manager/                     # 적용 설정·TLS/ACME 상태
│   └── postgres/                    # Haejeok에만 해당
├── state/                           # 작업 상태와 중단 복구 저널
├── logs/                            # 작업 로그
├── backups/install-recovery/        # 실패한 설치 시도의 보존 자료
├── recovery-points/                 # 비공개 전체 복구 지점
└── update-staging/                  # 업데이트·롤백 임시 작업
```

Helper는 허용된 명령과 인수를 검증합니다. GitHub 응답이나 원격 README에서 셸 명령을 받아 실행하지 않습니다. PocketRisu의 상위 빌드 스크립트는 고정된 공식 저장소의 확인된 커밋에서 실행되므로, 설치 전 표시된 소스와 리비전을 확인하는 것이 좋습니다. Manager 자체의 분석·광고·클라우드 계정·운영 서버는 없지만 GitHub 조회와 Termux 및 상위 서버의 네트워크 동작은 별개입니다. 자세한 경계는 [보안 모델](docs/security.md)에 있습니다.

## 빌드와 검증

개발 환경 요구 사항은 **JDK 17**, **Android SDK Platform 37**, **Android SDK Build Tools 36.0.0**입니다. Windows에서는 저장소 루트에서 실행합니다.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

결과 APK: `app/build/outputs/apk/debug/app-debug.apk`. Android 기기나 에뮬레이터가 필요한 계측 시험은 `app/src/androidTest`에 있습니다. 로컬 API 35 Google APIs 에뮬레이터용 스크립트도 포함되어 있습니다.

```powershell
.\tools\setup-android-emulator.ps1
.\tools\setup-android-emulator.ps1 -Start
```

WSL을 사용할 수 있으면 Termux 없이 helper의 주요 계약을 가짜 명령 환경에서 점검할 수 있습니다.

```powershell
wsl sh tools/test-termux-helper.sh app/src/main/assets/termux-helper/install.sh
wsl sh tools/test-termux-install-recovery.sh app/src/main/assets/termux-helper/install.sh
wsl sh tools/test-termux-clone.sh app/src/main/assets/termux-helper/install.sh
wsl sh tools/test-termux-update.sh app/src/main/assets/termux-helper/install.sh
wsl sh tools/test-termux-rollback.sh app/src/main/assets/termux-helper/install.sh
```

`node:sqlite`를 제공하는 Node.js에서는 백업 내보내기의 SQL 계약을 별도로 검사할 수 있습니다.

```powershell
node tools/test-termux-helper-exporter-sql.cjs
```

이 시험은 helper 로직과 Android 코드의 계약을 검증하지만, 실제 PocketRisu 설치·업데이트·롤백이 특정 기기에서 끝까지 동작함을 보증하지는 않습니다. 특히 PocketRisu의 공식 프로필은 ARM64 전용이므로 `x86_64` 에뮬레이터에서 실제 설치·업데이트 검증을 대체할 수 없습니다.

## 문제 해결

| 증상 | 먼저 확인할 것 |
| --- | --- |
| Termux를 찾지 못함 | 최신 공식 Termux 설치 여부와 첫 실행 여부를 확인합니다. |
| 권한 요청 또는 연결 검사 실패 | **Settings → Termux integration**에서 `RUN_COMMAND` 권한을 확인하고, 앱에 표시된 일회성 명령을 Termux에서 실행한 뒤 **Check again**을 누릅니다. |
| 설치가 오래 걸리거나 실패 | **Detailed installer log**의 단계·오류 코드를 확인합니다. 저장 공간, 네트워크, Termux 패키지 다운로드 및 상위 네이티브 빌드 오류를 점검합니다. 실패 시도 자료는 `~/.risu-manager/backups/install-recovery/`에 남을 수 있습니다. |
| 서버가 `UNHEALTHY` | 최근 서버 로그와 설정의 포트·프로토콜을 확인하고 재시작합니다. PID만 살아 있는 상태는 정상 실행으로 처리하지 않습니다. |
| 같은 네트워크에서 접속 안 됨 | **Same network** 적용 상태, 기기 IP·포트, 서버 인증, 방화벽/공유기 설정을 확인합니다. |
| Let’s Encrypt 발급 실패 | 도메인 A/AAAA가 올바른 주소를 가리키는지, 외부 TCP 80 전달과 내부 challenge 포트가 열려 있는지, CGNAT가 없는지 확인합니다. 사전 점검은 외부에서 실제 도달 가능한지까지 보증하지 않습니다. |
| 복구 지점 사용 불가 | **Recovery points**의 사용 가능 상태와 오류 코드를 확인합니다. 저장 공간이나 검증 실패를 해결하고, 복구가 필요한 지점은 삭제하지 않습니다. |
| Android가 서버를 종료 | Termux의 배터리 최적화 정책을 확인합니다. Manager의 wake lock만으로 백그라운드 실행을 항상 보장할 수 없습니다. |

더 자세한 오류별 절차는 [문제 해결 문서](docs/troubleshooting.md)를 참고하세요.

## 상세 문서

- [아키텍처](docs/architecture.md)
- [Termux 연동과 helper 프로토콜](docs/termux-integration.md)
- [배포판 프로필](docs/variants.md)
- [보안 모델](docs/security.md)
- [상위 프로젝트 분석](docs/upstream-analysis.md)
- [인스턴스 복제](docs/instance-cloning.md) · [1단계 검증 기록](docs/phase1-cloning-verification.md)
- [PocketRisu 업데이트](docs/pocketrisu-updates.md)
- [복구 지점과 롤백](docs/pocketrisu-rollbacks.md)
- [제3자 소프트웨어 고지](THIRD_PARTY_NOTICES.md)

## 라이선스

Risu Manager의 자체 코드는 [Apache License 2.0](LICENSE)에 따라 배포됩니다. Copyright 2026 yuzugoa.

Android/Kotlin 라이브러리 등 제3자 구성요소에는 각 프로젝트의 라이선스가 적용됩니다. 자세한 내용은 [제3자 소프트웨어 고지](THIRD_PARTY_NOTICES.md)를 참고하세요. Manager가 별도로 설치하는 PocketRisu와 Haejeok RisuAI 서버에는 각 상위 프로젝트의 GPL-3.0 라이선스가 적용됩니다.
