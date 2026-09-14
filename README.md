# stay-supplier-integration

## 1. 한눈에 보기

### 배경과 문제
<!-- 본인 말로 3~5줄: 공급사마다 같은 숙박 상품을 다르게 표현해서 그대로 노출할 수 없는 문제 -->

### 목표 / 비목표
| 목표 | 비목표 |
|---|---|
| <!-- 반드시 동작해야 하는 것 --> | <!-- 이번에 다루지 않는 것 --> |

### 핵심 흐름
1. [사전] 공급사 숙소 목록을 조회해 자사 숙소·객실 타입 식별자와의 매핑을 저장한다.
2. 검색 요청(날짜·인원)이 오면 보유 숙소를 공급사별 코드로 묶는다.
3. 공급사 재고·요금 API를 병렬 조회한다.
4. 각 응답을 표준 모델로 정규화하고, 일부 공급사가 실패해도 나머지 결과를 병합해 반환한다.

### 핵심 결정 요약
| 결정 | 선택 | 한 줄 근거 |
|---|---|---|
| 표준 모델 (살린 정보·버린 정보) | | |
| 요금 기준 | | |
| 매핑 생성 시점 | | |
| 연박 예약 가능 객실 수 판정 | | |
| 예약 불가 상품 처리 | | |
| 부분 실패 표현 | | |
| 타임아웃 | | |
| Spring MVC + WebClient vs WebFlux | | |

## 2. 저장소 구조

```
stay-supplier-integration/          # Gradle 멀티 모듈 루트
├── README.md                       # 결론: 문제 → 구현 항목 → 흐름 → 결정, 빌드·실행, 구현 범위
├── JOURNAL.md                      # 과정: 설계 의사결정 기록, 일자별 진행, 테스트, AI 활용
├── CLAUDE.md                       # 개발 지침 (명령, 개요, docs 라우팅, 코드 규칙)
├── docs/                           # 공식 명세
│   ├── architecture.md             #   전체 구성, 유스케이스, 공급사 연동, Mock, 테스트 구성
│   ├── stay-model.md               #   표준 숙박 상품 모델, 공급사 필드 대응, 매핑
│   ├── stay-search-api.md          #   통합 검색 API 명세
│   └── domain-research.md          #   도메인 리서치 원페이저
├── build.gradle.kts                # 본 앱 빌드
├── settings.gradle.kts             # 모듈 등록
├── mise.toml                       # JDK 21 고정
├── compose.yaml                    # 로컬 MySQL 8.4
├── docker/mysql/conf.d/my.cnf      # MySQL 설정 (utf8mb4, UTC)
├── src/                            # 본 애플리케이션 (:8080)
└── mock-supplier/                  # Mock Supplier (:9090). 본 앱과 코드 참조 없음
    └── src/main/resources/responses/   # 공급사 A·B 정상 응답 JSON
```

패키지 구조는 [architecture.md 2장](docs/architecture.md)에 있다.

## 3. 빌드·실행

### 요구 사항
- JDK 21 (`mise install`로 설치할 수 있다. 버전은 `mise.toml`에 고정)
- Docker (로컬 MySQL 8.4 실행)

### 실행
```bash
docker compose up -d # MySQL 실행
./gradlew build      # 컴파일 + 테스트
./gradlew bootRun    # 애플리케이션 실행 (포트 8080)
```
- Mock Supplier 실행: <!-- Mock 구성 확정 후 기입 -->
- API 문서(Swagger UI): http://localhost:8080/swagger-ui.html

### 로컬 MySQL

| 항목 | 값 |
|---|---|
| 버전 | MySQL 8.4 (Docker, `compose.yaml`) |
| 설정 파일 | `docker/mysql/conf.d/my.cnf` |
| 포트 | 3306 (충돌 시 `MYSQL_PORT`로 변경, 애플리케이션은 `DB_PORT`로 맞춤) |
| DB / 계정 | `stay_supplier` / `stay`·`stay` (로컬 개발용) |
| 문자셋 | `utf8mb4` / `utf8mb4_unicode_ci` |
| 타임존 | UTC |
| 데이터 | `mysql-data` 볼륨에 유지, 초기화는 `docker compose down -v` |

### 동작 확인
<!-- 검색 API 호출 예시, Mock 장애 모드 전환 후 부분 실패 확인 방법 -->

## 4. 구현 범위

| 항목 | 상태 | 문서 |
|---|---|---|
| 표준 숙박 상품 모델·매핑 저장 | 진행 전 | [stay-model.md](docs/stay-model.md) |
| Supplier 연동 어댑터 | 진행 전 | [architecture.md](docs/architecture.md) |
| 통합 검색 API | 진행 전 | [stay-search-api.md](docs/stay-search-api.md) |
| 연동 견고성 (타임아웃·부분 실패·실패 판정 통일) | 진행 전 | [architecture.md](docs/architecture.md) |
| Mock Supplier | 진행 전 | [architecture.md](docs/architecture.md) |
| 연동 지표·모니터링 설계 (권장) | 진행 전 | [architecture.md](docs/architecture.md) |
| 재시도·서킷 브레이커 (선택, Resilience4j) | 진행 전 | [architecture.md](docs/architecture.md) |
<!-- 그 밖의 선택 구현은 진행한 항목만 추가: 상태는 구현 / 설계만 / 미구현 -->

## 5. 설계 의사결정과 근거

> 결정마다 선택, 근거, 잃는 것을 적는다. 선택지 비교와 폐기한 대안은 [JOURNAL.md](JOURNAL.md)의 "설계 의사결정 기록"에 있다.

### 5.1 표준 숙박 상품 모델
<!-- 숙소·객실 타입·요금·재고를 각각 어떤 단위로 잡았는지 -->

#### 살린 정보와 버린 정보
| 정보 | Supplier A | Supplier B | 표준 모델 | 비고 |
|---|---|---|---|---|

#### 요금 기준
<!-- 선택 / 근거 / 잃는 것 -->

#### 재고 표현
<!-- 선택 / 근거 / 잃는 것 -->

### 5.2 매핑

#### 생성 시점과 실패 처리
<!-- 선택 / 근거 / 잃는 것 -->

#### 내부 식별자 안정성
<!-- 선택 / 근거 / 잃는 것 -->

### 5.3 Supplier 어댑터

#### 반환 형태와 실패 판정
<!-- 선택 / 근거 / 잃는 것 -->

#### 신규 Supplier 추가 시 수정 범위
<!-- 무엇을 추가하고 무엇은 건드리지 않는지 -->

### 5.4 통합 검색 API

#### 연박 예약 가능 객실 수 판정
<!-- 선택 / 근거 / 잃는 것 -->

#### 예약 불가 상품 처리
<!-- 응답에서 제외 / 0으로 노출 중 선택과 이유 -->

#### 부분 실패 표현
<!-- 선택 / 근거 / 잃는 것 -->

#### 대량 숙소 조회
<!-- 요청당 숙소 코드 수 제한과 숙소가 수천 개일 때의 처리 -->

### 5.5 연동 견고성

#### 타임아웃
<!-- 연결·응답 타임아웃 값과 근거 -->

### 5.6 기술 선택

#### Spring MVC + WebClient vs WebFlux
<!-- 선택 / 근거 / 잃는 것 -->

#### 모듈·패키지 구조
<!-- 선택 / 근거 / 잃는 것 -->

## 6. 한계와 향후 개선
<!-- 알려진 한계, 설계만 하고 구현하지 않은 것, 시간이 더 있다면 할 일 -->

## 7. 문서
- [docs/architecture.md](docs/architecture.md): 아키텍처 (전체 구성, 공급사 연동, Mock Supplier)
- [docs/stay-model.md](docs/stay-model.md): 통합 모델 설계 (표준 모델, 공급사 필드 대응, 매핑)
- [docs/stay-search-api.md](docs/stay-search-api.md): API 명세
- [docs/domain-research.md](docs/domain-research.md): 도메인 리서치 원페이저 (공급사 구조, 공급사 간 표현 차이)
- [JOURNAL.md](JOURNAL.md): 설계 의사결정 기록, 진행 기록, 테스트 전략과 결과, AI 활용 기록
