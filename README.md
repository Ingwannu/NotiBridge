# NotiBridge

안드로이드에 도착한 알림을 감지해서 지정한 웹훅 서버로 전송하는 앱입니다.
Kotlin + Jetpack Compose + Room + WorkManager + OkHttp로 작성됐습니다.

## 기능

### 훅 관리
- 여러 개의 웹훅 생성·수정·삭제
- 훅별 활성화/비활성화 스위치
- 훅 이름, URL, 타임아웃(1~120초) 설정
- 특정 앱만 선택하거나, 미선택 시 모든 앱 알림 감지
- 앱 이름·패키지명 검색 지원

### 웹훅 요청 설정
- POST / PUT / GET 지원 (GET도 마지막에 추가)
- HTTP와 HTTPS URL
- Content-Type: JSON, XML, 일반 텍스트, Form URL Encoded, HTML, Binary
- 사용자 지정 HTTP 헤더 (이름/값 자유 추가)
- 인증 토큰 전용 입력란 (헤더 이름 지정 가능, 기본 `Authorization`)
- 알림 내용 조건에 따른 전송 제외 필터 (포함 텍스트 또는 정규식, 제목/내용 대상 지정)

### 요청 Body 작성
- 앱에서 직접 템플릿 입력
- 외부 파일을 Body로 선택 (1MB 이하, 앱 내부에 캐시)
- 알림 정보 자동 치환:
  - `{notification}` 전체 알림 JSON
  - `{title}`, `{text}`, `{subtext}`
  - `{big_text}`, `{summary}`, `{ticker}`
  - `{app_name}`, `{app_package}`
  - `{timestamp}` (ISO-8601), `{timestamp_unix}` (초)
  - `{package}`는 `{app_package}` 별칭
  - Content-Type이 JSON이면 치환값은 JSON 이스케이프 처리됨 (따옴표/줄바꿈 안전)

### 정규식 데이터 추출
- 알림 제목·내용·전체(제목+내용)를 정규식으로 분석
- 이름 있는 캡처 그룹 `(?<이름>...)` → `{var_이름}`으로 Body에서 사용
- 전역변수로 지정하면 `{global.이름}` 형태로 다른 알림이나 훅에서도 재사용
- 규칙별 테스트 입력과 캡처 결과 즉시 확인
- 잘못된 정규식은 저장 차단

### 웹훅 테스트
- 저장 전에 테스트 알림 데이터로 실제 요청 전송
- 응답 상태 코드, 응답 시간, 응답 본문 표시
- 선택한 메서드·Content-Type·Body 파일·템플릿 모두 반영

### 전송 로그
- 최근 200건까지 성공·실패 기록 (Room에 유지)
- 알림 앱/제목/내용, 요청 URL·메서드·Body, 응답 코드·본문, 오류 확인
- 로그 상세 복사, 길게 눌러 요약 복사, 전체 삭제 지원

### 프리셋 공유
- 훅 하나를 `.notif` 파일로 보내기 (공유 시트)
- 다른 `.notif` 파일을 가져와 새 훅으로 등록 (비활성 상태로 가져옴)

### 백그라운드 안정성
- 부팅·앱 업데이트 후 자동 재개 (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`)
- 포그라운드 서비스 상태 확인 및 수동 재시작 (설정 탭)
- 15분 간격 워치독 알람이 서비스·큐를 자가 복구 (OEM 작업 관리자 대응)
- 알림 수신~큐 적재 구간 PARTIAL_WAKE_LOCK으로 doze 중에도 유실 방지
- 실패한 전송은 10초부터 지수 백오프(최대 15분 간격), 최대 8회 자동 재시도
  - 재시도는 Room 큐 + WorkManager로 구현돼 재부팅/프로세스 종료에도 유지
- 동시에 처리할 최대 훅 수 설정 (1~16, 설정 탭)
- 같은 알림의 짧은 시간 내 중복 전송 방지 (기본 60초, 설정에서 조절 가능)
- 자체 알림과 계속 표시되는 고정(ongoing) 알림은 전송하지 않음
- 알림 접근 권한과 배터리 최적화 해제 상태를 홈/설정 화면에서 안내

## 아키텍처

```
NotificationListenerService (NotificationHookService)
  │  알림 스냅샷 → 필터(앱/제외필터/중복) → Room 큐 enqueue
  ▼
DeliveryWorker (WorkManager)
  │  due 태스크 claim → Semaphore로 동시성 제한 → WebhookSender
  ▼
WebhookSender (OkHttp)
  │  템플릿/정규식 치환 → HTTP 전송 → 성공/실패 기록
  ▼
Room: hooks, delivery_tasks, send_logs(200 trim), global_variables
```

- 리스너는 네트워크 I/O를 하지 않고 큐에만 넣어 느린 웹훅이 알림 유실을
  만들지 않도록 설계
- 재시도는 훅 스냅샷 기반이라 재시도 중 훅 편집이 진행 중인 전송을 망가뜨리지 않음
- 포그라운드 서비스(KeepAliveService)는 프로세스 유지와 리스너 리바인드 요청만 담당

## 빌드

요구 사항: JDK 17, Android SDK 34

```bash
./gradlew :app:assembleDebug      # 디버그 APK
./gradlew :app:assembleRelease    # 릴리스 APK
./gradlew :app:testDebugUnitTest  # 유닛 테스트
./gradlew :app:lintDebug          # 린트
```

> 참고: 이 리포지토리의 `gradle.properties`에는 개발 머신(arm64)에서
> aapt2(x86-64 전용)를 qemu로 실행하기 위한
> `android.aapt2FromMavenOverride`가 들어 있습니다. 일반 x86-64
> 머신에서 빌드할 때는 그 줄을 지우세요.

## 설치 후 설정

1. 앱 설치 → 첫 실행 시 알림 권한(Android 13+) 허용
2. 홈 상단 배너에서 **알림 접근 권한** 허용 (시스템 설정으로 이동)
3. 같은 배너에서 **배터리 최적화 해제** 권장
4. `+` 버튼으로 첫 웹훅 생성 후 테스트 전송으로 확인

## Decision Log

- 목적과 의도: 알림→웹훅 브리지. 재시도 내구성과 사용자 편집 자유도가 핵심
- 기존 구현 및 제약 조건: Android 8.0(API 26)+, 백그라운드 제한, 알림 리스너는 리바인드될 수 있음
- 검토한 주요 대안:
  - 리스너에서 직접 HTTP 전송 → 느린 웹훅이 후속 알림을 블로킹/유실시켜 기각
  - AlarmManager 기반 재시도 → 정확도와 배터리 정책 때문에 WorkManager 채택
  - Hilt 등 DI 프레임워크 → 단일 모듈 규모에 과해 Application 위임 채택
- 선택한 방식: Room 큐 + WorkManager 디스패처 + 훅 스냅샷
- 다른 대안 대신 이 방식을 선택한 이유: 재부팅/업데이트/프로세스 사망에도
  재시도가 살아남고, 편집 중인 훅이 진행 중 전송을 오염시키지 않음
- 장점, 단점 및 영향: 장점은 내구성과 단순한 의존성. 단점은 WorkManager의
  지연 스케줄링이 수 초 수준으로 정확하지 않을 수 있다는 점(재시도 용도로는 충분)
