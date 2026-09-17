# CLAUDE.md

이 저장소에서 작업할 때 따르는 개발 지침이에요.

## 명령

### 환경
- JDK 21: mise로 관리해요 (`mise install`, 버전은 `mise.toml`에 고정).
- 빌드 도구: Gradle Wrapper (`./gradlew`).
- Docker: 로컬 MySQL·Redis 실행(`compose.yaml`)과 테스트의 Testcontainers에 사용해요.

### 빌드·실행
- `docker compose up -d` - MySQL + Redis 실행 (애플리케이션 실행 전에 필요)
- `docker compose exec redis redis-cli FLUSHALL` - 요금·재고 캐시 비우기 (저하 모드 확인용)
- `docker compose down` - MySQL·Redis 종료 (MySQL 데이터는 `mysql-data` 볼륨에 유지)
- `docker compose down -v` - 종료 + MySQL 데이터 삭제
- 포트 충돌 시: MySQL `MYSQL_PORT=3307 docker compose up -d` + 앱 `DB_PORT=3307`, Redis `REDIS_PORT=6380` (앱도 같은 변수를 읽음), 앱 `--args='--server.port=8081'`
- `./gradlew build` - 컴파일과 전체 테스트
- `./gradlew :bootRun` - 애플리케이션 실행 (기본 포트 8080). `:` 없이 실행하면 Mock 모듈까지 같이 뜨므로 반드시 붙여요
- `./gradlew :bootRun --args='--spring.profiles.active=sync'` - 매핑 갱신 잡 1회 실행 후 종료 (처음 띄울 때 한 번 필요)
- `./gradlew :mock-supplier:bootRun` - Mock Supplier 실행 (포트 9090, 별도 터미널)

### 테스트
- `./gradlew test` - 전체 테스트
- `./gradlew test --tests "클래스명"` - 특정 테스트 클래스만 실행

### API 문서
- Swagger UI: `http://localhost:8080/swagger-ui.html`

### 스킬 (`.claude/skills/`)
- `run-local` 로컬 실행 순서, `query-mapping` 매핑 테이블 조회, `query-cache` Redis 캐시·상태 키 조회, `test-search-api` 검색 API 확인 순서, `mock-fault` Mock 장애 모드 전환, `add-supplier` 신규 공급사 추가 절차
- 형식: 프런트매터 `description`에 한 줄 설명 + `Triggers:`(부르는 말) + `Do NOT use for:`(경계), `allowed-tools`로 쓰는 도구 제한. 본문은 전제 → 실행 순서 → 확인 포인트 → 주의사항

## 아키텍처 개요

여러 외부 숙박 공급사(Supplier)의 상품을 자사 표준 숙박 상품 모델로 통합하고, 통합 검색 API로 제공하는 연동 백엔드예요.

### 핵심 흐름
1. 매핑: 크론잡(`sync` 프로필, 매일 04:00)이 공급사 숙소 목록을 받아 자사 숙소·객실 타입 식별자와의 매핑을 DB에 델타로 저장해요. 웹 앱은 기동 시와 04:30에 DB에서 인메모리로 올려요.
2. 캐시: 갱신 잡이 5분마다 인메모리 매핑의 숙소를 50개씩 묶어 공급사 재고·요금 API를 호출하고, 정규화한 값을 Redis에 숙소당 Hash로 미리 채워요.
3. 검색: 요청(날짜·인원)이 오면 Redis를 먼저 읽고, 값이 없는 숙소(첫 갱신 전·범위 밖 날짜·Redis 장애)와 `fresh=true`일 때만 WebClient로 공급사를 병렬 호출해요.
4. 응답: 표준 모델을 내부 식별자로 바꿔 병합하고, 일부 공급사가 실패해도 나머지 결과로 응답하며 실패는 `failures`에 드러내요.

### 기술 스택
- Java 21, Spring Boot 4.0.8, Gradle (Kotlin DSL)
- Spring MVC, WebClient (공급사 호출. RestTemplate·RestClient 사용 안 함)
- MyBatis, MySQL 8.4 (매핑만 저장)
- Redis 7.4, Spring Data Redis (요금·재고 캐시)
- SpringDoc OpenAPI
- 테스트: JUnit 5, Mockito, AssertJ, WireMock, Testcontainers(MySQL·Redis)

### 디렉토리 구조
기능별 패키지. 공급사 전용 형식은 `supplier.a`, `supplier.b` 밖으로 나가지 않아요.
- `supplier/` - `SupplierClient` 인터페이스, `Supplier` enum, `SupplierProperties`(설정), `SupplierWebClients`(공급사별 WebClient), `SupplierRateLimiter`(호출 한도), `ChunkedFetch`(50개 묶음·부분 실패), `FailureReason`·`SupplierCallException`·`SupplierFailures`(실패 판정 통일)
- `supplier/a`, `supplier/b` - 공급사별 어댑터와 전용 응답 형식(package-private). 새 공급사는 `supplier/c`
- `stay/` - 표준 형태. ① `SupplierHotel`·`SupplierRoomType`, ② `AvailabilityQuery`·`SupplierRoomOffer`(기간)·`SupplierDailyOffer`(날짜별)·결과 묶음
- `mapping/` - 매핑 엔티티, MyBatis 매퍼(XML은 `resources/mapper/`), `MappingSyncJob`(크론잡 델타), `MappingSyncRunner`(sync 프로필), `MappingRegistry`·`MappingRegistryLoader`(인메모리, 기동 시·04:30 로드)
- `config/` - 설정 바인딩·빈 등록 (`SupplierClientConfig`, `MappingConfig`, `CacheConfig`, `SchedulingConfig`)
- `search/` - 통합 검색 API (`StaySearchController`, `StaySearchService`, 요청·응답·오류 응답)
- `cache/` - 요금·재고 캐시 (`AvailabilityCache` Redis Hash, `AvailabilityRefreshJob` 5분 주기 미리 채우기, `CachedRate` 값 형식)

## Docs (작업 전에 해당 문서를 읽을 것)

문서는 세 층으로 나뉘어요. 설계를 확정하면 README → docs 순서로 반영해요.

| 문서 | 역할 |
|------|------|
| `README.md` | 결론: 1장 빠른 시작, 2장 무엇을 만들었나(구현 범위 표: 구현/부분/설계만/미구현), 3장 어떻게 동작하나(흐름도), 4장 설계 결정(결정별 선택·근거·잃는 것), 5장 실행 상세(포트·동작 확인), 6장 문서 |
| `docs/` | 공식 명세: 확정된 설계가 실제로 어떻게 구성되어 있는지 |
| `JOURNAL.md` | 과정: 설계 의사결정 기록(선택지 비교·폐기한 대안), 일자별 진행, 테스트 전략과 결과, AI 활용 기록 |

| 작업 대상 | 문서 |
|-----------|------|
| 전체 구성, 모듈·패키지 구조, 유스케이스, 공급사 연동(어댑터·실패 판정·타임아웃·부분 실패·재시도·서킷 브레이커·연동 지표·요금·재고 캐시), Mock Supplier, 운영 구성(설계) | `docs/architecture.md` |
| 표준 숙박 상품 모델, 공급사 필드 대응, 매핑 스키마·생성·식별자 규칙 | `docs/stay-model.md` |
| 통합 검색 API 요청·응답·에러, 조회 규칙(묶음·연박 판정·예약 불가·병합) | `docs/stay-search-api.md` |
| 공급사 구조, 공급사 간 표현 차이 같은 도메인 배경 (원페이저) | `docs/domain-research.md` |

## 코드 규칙

### 연동
- 공급사 API 호출에는 WebClient만 사용해요. RestTemplate, RestClient는 사용하지 않아요.
- 공급사별 요청/응답 DTO는 어댑터 패키지 밖으로 노출하지 않아요. 어댑터 밖에서는 표준 모델만 사용해요.
- 실제 외부 서비스는 호출하지 않아요. 공급사 호출 대상은 Mock Supplier뿐이에요.

### 저장소·캐시
- DB에는 공급사 코드 ↔ 내부 식별자 매핑만 저장해요. 요금과 재고는 저장하지 않아요. 요금·재고는 Redis 캐시(TTL 있음)에만 두고 원본은 공급사예요.
- MyBatis 매퍼는 XML(`src/main/resources/mapper/*.xml`)로 작성해요. upsert·조건 갱신 SQL이 길어 애너테이션보다 읽기 편해요.
- 검색은 Redis에 값이 없는 숙소만 공급사를 불러요. 캐시를 채우는 것은 갱신 잡뿐이며 검색은 캐시에 쓰지 않아요.
- 실패 판정은 `FailureReason` 8가지로만 표현해요. 공급사 원본 코드는 예외·로그에만 둬요.

### 관측
- 지표·모니터는 코드에 넣지 않아요(설계만, `docs/architecture.md` 4.5). 외부 호출·DB·Redis 실패는 반드시 공급사·원인·원본 코드를 warn/error 로그로 남겨요.

### 문서
- 설계를 바꾸면 `JOURNAL.md` 설계 의사결정 기록에 과정을 남기고, `README.md` 4장(결정·근거)과 해당 `docs/` 명세를 함께 고쳐요.
- 문서에는 실제 코드에 반영된 내용만 적어요. 설계만 한 항목은 "구현 상태: 설계만"으로 표시해요.

### 커밋
- 의미 있는 작은 단위로 자주 커밋해요.
