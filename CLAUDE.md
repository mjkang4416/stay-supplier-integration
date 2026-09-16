# CLAUDE.md

이 저장소에서 작업할 때 따르는 개발 지침이다.

## 명령

### 환경
- JDK 21: mise로 관리한다 (`mise install`, 버전은 `mise.toml`에 고정).
- 빌드 도구: Gradle Wrapper (`./gradlew`).
- Docker: 로컬 MySQL 실행에 사용한다 (`compose.yaml`).

### 빌드·실행
- `docker compose up -d` - MySQL 실행 (애플리케이션 실행 전에 필요)
- `docker compose down` - MySQL 종료 (데이터는 `mysql-data` 볼륨에 유지)
- `docker compose down -v` - MySQL 종료 + 데이터 삭제
- 3306 포트가 이미 사용 중이면 `MYSQL_PORT=3307 docker compose up -d`, 애플리케이션은 `DB_PORT=3307`로 실행
- `./gradlew build` - 컴파일과 전체 테스트
- `./gradlew :bootRun` - 애플리케이션 실행 (기본 포트 8080). `:` 없이 실행하면 Mock 모듈까지 같이 뜨므로 반드시 붙인다
- `./gradlew :bootRun --args='--spring.profiles.active=sync'` - 매핑 갱신 잡 1회 실행 후 종료 (처음 띄울 때 한 번 필요)
- `./gradlew :mock-supplier:bootRun` - Mock Supplier 실행 (포트 9090, 별도 터미널)

### 테스트
- `./gradlew test` - 전체 테스트
- `./gradlew test --tests "클래스명"` - 특정 테스트 클래스만 실행

### API 문서
- Swagger UI: `http://localhost:8080/swagger-ui.html`

### 스킬 (`.claude/skills/`)
- `run-local` 로컬 실행 순서, `query-mapping` 매핑 테이블 조회, `test-search-api` 검색 API 확인 순서, `mock-fault` Mock 장애 모드 전환

## 아키텍처 개요

여러 외부 숙박 공급사(Supplier)의 상품을 자사 표준 숙박 상품 모델로 통합하고, 통합 검색 API로 제공하는 연동 백엔드다.

### 핵심 흐름
1. [사전] 공급사 숙소 목록을 조회해 자사 숙소·객실 타입 식별자와의 매핑을 저장한다.
2. 검색 요청(날짜·인원)이 오면 보유 숙소를 공급사별 코드로 묶는다.
3. WebClient로 공급사 재고·요금 API를 병렬 조회한다.
4. 각 응답을 표준 모델로 정규화하고, 일부 공급사가 실패해도 나머지 결과를 병합해 반환한다.

### 기술 스택
- Java 21, Spring Boot 4.0.8, Gradle (Kotlin DSL)
- Spring MVC, WebClient (공급사 호출)
- MyBatis, MySQL 8.4
- SpringDoc OpenAPI

### 디렉토리 구조
기능별 패키지. 공급사 전용 형식은 `supplier.a`, `supplier.b` 밖으로 나가지 않는다.
- `supplier/` - `SupplierClient` 인터페이스, `Supplier` enum, `SupplierProperties`(설정), `SupplierWebClients`(공급사별 WebClient), `FailureReason`·`SupplierCallException`·`SupplierFailures`(실패 판정 통일)
- `supplier/a`, `supplier/b` - 공급사별 어댑터와 전용 응답 형식(package-private). 새 공급사는 `supplier/c`
- `stay/` - 표준 형태 (`SupplierHotel`, `SupplierRoomType`)
- `mapping/` - 매핑 엔티티, MyBatis 매퍼(XML은 `resources/mapper/`), `MappingSyncJob`(크론잡 델타), `MappingSyncRunner`(sync 프로필), `MappingRegistry`·`MappingRegistryLoader`(인메모리, 기동 시·04:30 로드)
- `config/` - 설정 바인딩·빈 등록 (`SupplierClientConfig`, `MappingConfig`, `SchedulingConfig`)
- `search/` - 통합 검색 API (`StaySearchController`, `StaySearchService`, 요청·응답·오류 응답)

## Docs (작업 전에 해당 문서를 읽을 것)

문서는 세 층으로 나뉜다. 설계를 확정하면 README → docs 순서로 반영한다.

| 문서 | 역할 |
|------|------|
| `README.md` | 결론: 1장 한눈에 보기(배경 → 문제점 → 문제에서 도출한 구현 항목 → 핵심 흐름 → 핵심 결정 요약), 2장 저장소 구조, 3장 빌드·실행, 4장 구현 범위, 5장 결정별 선택·근거·잃는 것 |
| `docs/` | 공식 명세: 확정된 설계가 실제로 어떻게 구성되어 있는지 |
| `JOURNAL.md` | 과정: 설계 의사결정 기록(선택지 비교·폐기한 대안), 일자별 진행, 테스트 전략과 결과, AI 활용 기록 |

| 작업 대상 | 문서 |
|-----------|------|
| 전체 구성, 모듈·패키지 구조, 핵심 흐름, 공급사 연동(어댑터·실패 판정·타임아웃·부분 실패·재시도·서킷 브레이커·연동 지표), Mock Supplier, 로컬 실행·테스트 구성 | `docs/architecture.md` |
| 표준 숙박 상품 모델, 공급사 필드 대응, 매핑 스키마·생성·식별자 규칙 | `docs/stay-model.md` |
| 통합 검색 API 요청·응답·에러, 조회 규칙(묶음·연박 판정·예약 불가·병합) | `docs/stay-search-api.md` |
| 공급사 구조, 공급사 간 표현 차이 같은 도메인 배경 (원페이저) | `docs/domain-research.md` |

## 코드 규칙

### 연동
- 공급사 API 호출에는 WebClient만 사용한다. RestTemplate, RestClient는 사용하지 않는다.
- 공급사별 요청/응답 DTO는 어댑터 패키지 밖으로 노출하지 않는다. 어댑터 밖에서는 표준 모델만 사용한다.
- 실제 외부 서비스는 호출하지 않는다. 공급사 호출 대상은 Mock Supplier뿐이다.

### 저장소
- DB에는 공급사 코드 ↔ 내부 식별자 매핑만 저장한다. 요금과 재고는 저장하지 않는다.
- MyBatis 매퍼는 XML(`src/main/resources/mapper/*.xml`)로 작성한다. upsert·조건 갱신 SQL이 길어 애너테이션보다 읽기 편하다.

### 문서
- 설계를 바꾸면 `JOURNAL.md` 설계 의사결정 기록에 과정을 남기고, `README.md` 5장(결정·근거)과 해당 `docs/` 명세를 함께 고친다.
- 문서에는 실제 코드에 반영된 내용만 적는다. 설계만 한 항목은 "구현 상태: 설계만"으로 표시한다.

### 커밋
- 의미 있는 작은 단위로 자주 커밋한다.
