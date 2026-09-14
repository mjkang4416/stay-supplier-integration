# stay-supplier-integration

## 1. 한눈에 보기

### 1.1 배경
숙박 플랫폼은 직접 등록한 숙소 외에 외부 공급사(Supplier)의 상품도 함께 판매한다. 공급사는 우리가 API로 호출해서 상품을 가져오는 외부 시스템이고, 같은 숙소가 여러 공급사에 동시에 있을 수 있다. 자세한 배경은 [도메인 리서치](docs/domain-research.md)에 있다.

### 1.2 문제점
공급사 두 곳(A·B)을 연동하면서 드러난 문제를 세 묶음으로 정리한다.

**공급사 간 표현 차이 (A와 B가 다름)**

| # | 문제 | 왜 문제인가 |
|---|---|---|
| P1 | 같은 상품을 다르게 표현한다. 요금은 날짜별 단가·세금 별도(A) vs 기간 총액·세금 포함(B), 식별자와 필드명도 제각각이다 | 고객에게는 어느 공급사에서 왔든 같은 형태의 검색 결과를 보여줘야 한다 |
| P2 | 실패를 알리는 방식이 다르다. A는 HTTP 상태 코드로, B는 항상 200을 주면서 본문 코드로 알린다 | HTTP 상태만 보면 B의 장애를 정상 응답으로 처리하게 된다 |

**공급사 API의 공통 성격 (A와 B가 같음)**

| # | 문제 | 왜 문제인가 |
|---|---|---|
| P3 | 위치·조건으로 검색해 주지 않는다. 숙소 코드 목록을 받아 그 숙소의 재고·요금만 돌려준다 | 어떤 숙소를 물어볼지 우리가 먼저 알고 있어야 한다. 플랫폼 검색은 보통 위치로 필터링하지만 공급사는 그 기능이 없으므로, 우리 쪽 데이터에서 대상 숙소를 정해야 한다 (이번 범위에서는 보유 숙소 전체를 조회한다) |
| P4 | 재고·요금 조회는 요청당 숙소 수에 상한이 있다 | 보유 숙소가 많아지면 한 번에 조회할 수 없다 |
| P5 | 재고는 날짜별로 따로 온다 | 연박 검색은 기간 전체를 예약할 수 있는지 판정해야 한다 |

**외부 연동의 일반 성격**

| # | 문제 | 왜 문제인가 |
|---|---|---|
| P6 | 외부 연동은 실패하거나 지연된다 | 공급사 한 곳 때문에 검색 전체가 멈추거나 오류가 나면 안 된다 |
| P7 | 실제 공급사 서버가 없다 | 정상·장애·무응답을 재현할 수단이 있어야 연동 동작을 검증할 수 있다 |

### 1.3 문제에서 도출한 구현 항목

| 구현 항목 | 푸는 문제 | 설계 근거 |
|---|---|---|
| 자사 표준 숙박 상품 모델 | P1 | [5.1](#51-표준-숙박-상품-모델) |
| 공급사 코드 ↔ 내부 식별자 매핑 저장 | P3, P4 | [5.2](#52-매핑) |
| Supplier 어댑터 (정규화, 실패 판정) | P1, P2 | [5.3](#53-supplier-어댑터) |
| 통합 검색 API (공급사별 묶음, 병렬 조회, 연박 판정, 병합) | P3, P4, P5 | [5.4](#54-통합-검색-api) |
| 연동 견고성 (타임아웃, 부분 실패 허용) | P6 | [5.5](#55-연동-견고성) |
| Mock Supplier | P7 | [architecture.md](docs/architecture.md) |

이번 범위 밖: 인증·인가, 결제, 관리자 기능, 프론트엔드, 실제 외부 상용 API 연동, 지역·키워드 필터, 정렬·페이징. 서로 다른 공급사 상품을 하나로 합치는 병합은 선택 항목으로 분리한다.

### 1.4 핵심 흐름
1. [사전] 공급사 숙소 목록을 조회해 자사 숙소·객실 타입 식별자와의 매핑을 저장한다.
2. 검색 요청(날짜·인원)이 오면 보유 숙소를 공급사별 코드로 묶는다.
3. 공급사 재고·요금 API를 병렬 조회한다.
4. 각 응답을 표준 모델로 정규화하고, 일부 공급사가 실패해도 나머지 결과를 병합해 반환한다.

유스케이스별 기본 흐름과 대안 흐름은 [architecture.md 3장](docs/architecture.md)에 있다.

### 1.5 핵심 결정 요약
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
