# 아키텍처

> 확정된 설계를 명세한다. 결정의 근거는 [README.md](../README.md) 4장, 결정 과정은 [JOURNAL.md](../JOURNAL.md)에 있다.

## 1. 전체 구성
<!-- 구성도: 클라이언트 → 애플리케이션 → MySQL, Mock Supplier(A·B). 모듈, 포트, 호출 방향 -->

## 2. 모듈·패키지 구조

### 2.1 모듈

| 모듈 | 역할 | 포트 | 진입점 |
|---|---|---|---|
| 루트 (`:`) | 본 애플리케이션. 프로필로 역할을 나눈다: 기본 = 웹 앱(검색 API, 인메모리 매핑), `sync` = 매핑 갱신 잡(웹 서버 없이 1회 실행 후 종료) | 8080 (웹) | `StaySupplierIntegrationApplication` |
| `:mock-supplier` | 공급사 A·B를 흉내 내는 Mock 서버 | 9090 | `MockSupplierApplication` |

- 두 모듈은 서로 코드를 참조하지 않는다. Mock은 외부 시스템을 흉내 내는 것이므로 본 앱의 DTO를 공유하지 않는다.
- 한 저장소에 두는 이유는 같이 빌드·관리하고 평가자가 한 번에 받기 위해서다. 실행 시점에는 별개 프로세스다.

### 2.2 패키지
<!-- 본 앱 패키지별 책임과 의존 방향 (구조 확정 후 기입) -->

## 3. 유스케이스와 핵심 흐름

### 3.1 행위자

| 행위자 | 역할 |
|---|---|
| 검색 클라이언트 | 날짜·인원으로 통합 검색 API를 호출하는 쪽 (앱·웹 서버) |
| 운영자 / 스케줄러 | 매핑 생성을 트리거하는 쪽 |
| 공급사 A·B (Mock) | 숙소 목록과 재고·요금을 제공하는 외부 시스템 |

### 3.2 UC-1 매핑 생성·갱신

- 목적: 공급사의 숙소·객실 타입 코드를 내부 식별자에 대응시켜 저장한다.
- 트리거: <!-- 결정: 앱 기동 시 / 주기 / 별도 명령 -->
- 기본 흐름
  1. 공급사별 숙소 목록 API를 호출한다.
  2. 숙소마다 (공급사, 숙소 코드)에 대한 내부 숙소 식별자를 확보한다. 이미 있으면 재사용한다.
  3. 객실 타입마다 (공급사, 숙소 코드, 객실 타입 코드)에 대한 내부 객실 타입 식별자를 확보한다.
  4. 결과(공급사별 건수, 실패 여부)를 기록한다.
- 대안 흐름
  - A1 공급사 숙소 목록 조회 실패: <!-- 결정: 기존 매핑 유지 / 해당 공급사만 건너뜀 / 중단 -->
  - A2 재실행: 같은 코드는 같은 내부 식별자로 돌아와야 한다.
  - A3 공급사 목록에서 사라진 숙소: <!-- 결정: 유지 / 비활성 / 삭제 -->
  - A4 동시 실행: 식별자가 중복 생성되지 않아야 한다.

### 3.3 UC-2 통합 검색

- 목적: 날짜·인원으로 보유 숙소 전체의 예약 가능 객실과 요금을 하나의 형태로 돌려준다.
- 트리거: `GET /api/v1/stays/search`
- 사전 조건: 매핑이 저장되어 있다.
- 기본 흐름
  1. 요청을 검증한다 (날짜 순서, 인원).
  2. 매핑에서 보유 숙소를 조회해 공급사별 코드 묶음으로 나눈다 (요청당 숙소 수 상한 준수).
  3. 공급사별 재고·요금 API를 병렬 호출한다 (연결·응답 타임아웃 적용).
  4. 응답을 표준 모델로 정규화한다 (공급사별 실패 판정 포함).
  5. 요청 기간 전체의 예약 가능 객실 수를 판정한다.
  6. 공급사별 결과를 병합하고, 부분 실패 사실을 담아 응답한다.
- 대안 흐름
  - A1 잘못된 요청 (체크아웃이 체크인보다 빠르거나 같음, 인원 0 등): 400 응답
  - A2 일부 공급사 장애 응답 (HTTP 4xx·5xx / HTTP 200 + 실패 코드): 해당 공급사를 제외하고 부분 실패로 표시
  - A3 일부 공급사 무응답: 타임아웃 뒤 A2와 같이 처리
  - A4 모든 공급사 실패: <!-- 결정: 빈 결과 + 전체 실패 표시 / 오류 응답 -->
  - A5 기간 중 하루라도 재고 0: 예약 불가 <!-- 결정: 응답에서 제외 / 0으로 노출 -->
  - A6 보유 숙소 없음 (매핑이 비어 있음): 빈 결과
  - A7 응답 정규화 실패 (필드 누락, 형식 오류): <!-- 결정: 해당 항목 제외 / 공급사 실패로 처리 -->
  - A8 보유 숙소가 요청당 상한을 넘음: 묶음을 나눠 호출

### 3.4 UC-3 Mock 모드 전환 (개발·검증용)

- 트리거: `POST /control/{supplier}/mode`
- 흐름: 공급사별 모드(정상 / 장애 / 무응답)를 바꾼다. 재고·요금 API에만 적용된다.

### 3.5 선택 유스케이스

- 예약 대행 (선택 구현): 설계만 할 경우 여기에 흐름을 적는다.

## 4. 공급사 연동

### 4.1 어댑터 구조와 경계

구현 상태: ① 숙소 목록 조회까지 구현. ② 재고·요금 조회는 검색 API와 함께 추가한다.

- 공통 인터페이스 `supplier.SupplierClient`: `supplier()`, `fetchHotels()`. 반환은 `Mono<List<SupplierHotel>>`이고, 호출자가 여러 공급사를 합친 뒤 끝에서 한 번 `block()`한다.
- 구현체는 공급사별 패키지에 하나씩: `supplier.a.SupplierAClient`, `supplier.b.SupplierBClient`. 공급사 전용 응답 형식(`SupplierAResponses`, `SupplierBResponses`)은 package-private라 그 패키지 밖에서 참조할 수 없다.
- 어댑터 밖으로 나가는 형태는 `stay.SupplierHotel`, `stay.SupplierRoomType`, `supplier.FailureReason`뿐이다. 식별자는 공급사 코드 그대로이고, 내부 식별자 배정은 매핑(stay-model 3장)이 한다.
- 어댑터의 책임은 호출과 번역까지다. 저장(매핑 델타)·병합·캐시는 서비스가 하고, 재시도도 어댑터에 없이 호출자가 `Mono`에 정책을 붙인다. 같은 어댑터를 크론잡·검색·실시간 재확인이 다른 방식으로 쓰기 때문이다.
- WebClient는 `supplier.SupplierWebClients`가 공급사마다 하나씩 기동 시 만들어 둔다. 기본 URL, 공통 규약의 `X-Api-Key` 헤더, 연결·응답 타임아웃이 미리 적용되어 어댑터 코드에는 경로와 번역만 남는다. 설정이 빠진 공급사가 있으면 기동이 실패한다.
- 정규화 실패 격리: 식별에 필요한 값(숙소 코드·이름, 객실 타입 코드·이름)이 빠진 항목은 그 항목만 버리고 warn 로그를 남긴다. `maxOccupancy`가 없거나 1 미만이면 객실 타입은 살리되 기본값을 넣지 않고 미상(null)으로 둔다. 인원 필터에 추측한 값이 들어가면 안 되기 때문이다. 검색 응답에서는 이 값이 계약상 필수이므로 ② 응답 값 → 매핑 값 순으로 채우고, 둘 다 없으면 그 객실 타입을 응답에서 제외하고 로그·지표를 남긴다. 필드 길이는 예제 데이터 범위(코드 100자, 이름 255자)로 두고 따로 검사하지 않는다.

### 4.2 실패 판정

A는 HTTP 상태 코드로, B는 항상 HTTP 200에 본문 `resultCode`로 실패를 알린다. 어댑터가 둘을 같은 `FailureReason`으로 바꾸고 `SupplierCallException(공급사, 원인, 원본 코드, Retry-After)` 하나로 던진다. 밖에서는 원인만 본다.

| `FailureReason` | Supplier A (HTTP 상태) | Supplier B (`resultCode`) | 공통 (전송 계층) |
|---|---|---|---|
| `INVALID_REQUEST` | 400 (`INVALID_DATE_RANGE`, `INVALID_PARAMETER`, `TOO_MANY_HOTEL_CODES`), 그 밖의 4xx | `E400` | |
| `UNAUTHORIZED` | 401 | `E401` | |
| `RATE_LIMITED` | 429 (`Retry-After` 초 단위면 예외에 실음) | `E429` | |
| `SUPPLIER_ERROR` | 500, 그 밖의 5xx | `E500` | |
| `UNAVAILABLE` | 503 | `E503` | B가 스펙과 달리 HTTP 오류를 주면 A와 같은 상태 규칙으로 판정 |
| `TIMEOUT` | | | 응답 타임아웃 초과 |
| `CONNECTION` | | | 연결 거부·연결 타임아웃·DNS 실패 |
| `INVALID_RESPONSE` | 본문 파싱 실패, `items` 구조 없음 | 모르는 `resultCode`, `0000`인데 `data.items` 구조 없음 | 코덱 오류 |

"없음"은 두 종류로 나눈다. 공급사가 `items: []`로 "팔 게 없다"고 말한 것은 정상 0건이고, 성공 코드인데 `data`나 `items` 구조 자체가 없는 것은 깨진 응답이다. 후자를 0건으로 읽으면 실제로 있는 상품이 검색에서 사라지는데 지표는 성공으로 남아 알림이 울리지 않는다. 판정 코드는 각 공급사 패키지 안에 있다(`SupplierBClient.reasonOf`, 공통 규칙은 `SupplierFailures`).

### 4.3 타임아웃

설정 위치는 `supplier.endpoints.{a|b}.connect-timeout`(기본 1초)과 `response-timeout`(기본 3초)이고, `SupplierWebClients`가 공급사별 WebClient를 만들 때 적용한다(Spring Boot `HttpClientSettings` → reactor-netty 연결 타임아웃·응답 타임아웃). 값의 근거는 [README 5.5](../README.md)에 있다. 초과하면 `FailureReason.TIMEOUT`(응답) 또는 `CONNECTION`(연결)으로 통일된다.

### 4.4 부분 실패 처리

구현 상태: 크론잡 경로는 구현. 검색 경로는 검색 API와 함께 추가한다.

실패는 병합하기 전에 공급사 단위로 가둔다. Reactor의 `Flux.merge`·`Mono.zip`은 하나라도 에러면 전체를 에러로 끝내고 나머지를 취소하므로, 각 공급사 체인 안(`flatMap` 안)에서 `SupplierCallException`을 그 공급사의 실패 결과로 바꾼 뒤 `collectList()`로 모은다. 병합 뒤에서 잡으면 늦다.

```
Flux.fromIterable(clients)
    .flatMap(client -> client.fetch(...)
        .map(items -> Result.success(client.supplier(), items))
        .onErrorResume(SupplierCallException.class, ex -> Mono.just(Result.failure(client.supplier(), ex.getReason()))))
    .collectList()   // 성공·실패가 섞인 목록. 여기서는 에러가 나올 수 없다
    .block()
```

| 경로 | 실패한 공급사 | 나머지 |
|---|---|---|
| 크론잡 (`MappingSyncJob`) | 건너뛰고 기존 매핑 유지, 결과에 원인 기록, 잡은 종료 코드 1 | 델타 반영 |
| 검색 (직접 호출) | `failures: [{ supplier, reason }]`에 표시. 어댑터의 묶음 일부 실패는 실패한 묶음의 숙소 수도 함께 | 200으로 응답. 전부 실패면 503 + `Retry-After` |
| Redis 갱신 잡 (설계) | 상태 키에 마지막 실패 원인·시각 기록. 값은 TTL까지 유지 | 검색이 상태 키를 읽어 `failures`에 `REFRESH_FAILED`로 표시 |

전체 지연은 가장 느린 공급사 하나(≤ 응답 타임아웃)다. 실패한 공급사가 다른 공급사를 늦추지 않는다.

### 4.5 신규 Supplier 추가 절차

추가하는 것 세 곳:
1. `supplier.Supplier` enum에 값 추가 (예: `C`).
2. `supplier/c` 패키지에 `SupplierCClient implements SupplierClient`와 전용 응답 형식. 그 공급사의 실패 신호를 `FailureReason`으로 바꾸는 규칙도 이 패키지 안에 둔다. `@Component`면 자동으로 `List<SupplierClient>`에 들어간다.
3. `application.properties`에 `supplier.endpoints.c.base-url`, `api-key`, `connect-timeout`, `response-timeout`. 빠뜨리면 기동 시 실패한다.

수정하지 않는 것: 크론잡(`MappingSyncJob`), 매핑 테이블·매퍼, 검색 서비스, 표준 형태, `SupplierWebClients`, `SupplierFailures`. 이들은 인터페이스와 표준 형태만 본다.

같이 추가할 것: WireMock 테스트(정규화 결과, `X-Api-Key` 전송, 실패 판정, 타임아웃)와 stay-model 2장 필드 대응표의 열 하나.

### 4.6 재시도·서킷 브레이커 (선택)

구현 상태: 크론잡 재시도는 구현(`MappingSyncJob`, `mapping.sync.retry-*`). 검색 재시도는 검색 API와 함께. 서킷 브레이커는 설계만.

재시도 정책은 어댑터가 아니라 호출자가 정한다. 사람이 기다리는지에 따라 다르기 때문이다.

| 원인 | 크론잡 (새벽, 사람 안 기다림) | 검색 직접 호출 (`fresh=true`, 사람 기다림) | Redis 갱신 잡 (설계) |
|---|---|---|---|
| 연결 실패, 500, 503 | 고정 30초 × 3회 | 즉시 1회 (대기 0~200ms) | 다음 바퀴 |
| 타임아웃 | 고정 30초 × 3회 | 없음 (이미 3초 소진) | 다음 바퀴 |
| 429 | 위와 같되 `Retry-After` 우선 | 없음 (더 부르면 악화) | 지수 백오프 1→2→4→…→60초, 재개 시 예산 절반 |
| 400·401, B `resultCode` 오류, 깨진 응답 | 없음 (다시 해도 같음) | 없음 | 없음 |

잡 수준 재실행은 K8s `backoffLimit: 2`(6.2)가 맡는다. 재시도 실패는 지표에 `result=retry`로 남긴다.

**서킷 브레이커 (설계만)**: 검색 직접 호출 경로와 Redis 갱신 잡처럼 호출이 잦은 곳에만 건다. 최근 10회 중 5회 이상 실패(최소 호출 수 10)면 30초 열고, 반개방에서 시험 호출 1회가 성공하면 닫는다. 열린 동안 그 공급사는 즉시 `failures: [{ supplier, reason: CIRCUIT_OPEN }]`로 표시되어 사용자가 타임아웃을 기다리지 않는다. 크론잡 목록 호출은 하루 두 번이라 걸지 않는다. 구현하면 Resilience4j `CircuitBreaker`를 WebClient 체인에 붙인다.

### 4.7 연동 지표·모니터링

구현 상태: 지표 수집은 구현 예정, 알림 규칙과 한도 탐색 절차는 설계만.

**지표** (Micrometer, 어댑터 호출 지점에서 기록, Actuator `/actuator/metrics`로 노출)

| 지표 | 타입 | 태그 |
|---|---|---|
| `supplier.request` | counter | `supplier`(A/B), `result`(success / timeout / rate_limited / error / invalid_response) |
| `supplier.request.duration` | timer (p50/p95/p99) | `supplier` |
| `search.partial_failure` | counter | 실패한 공급사 |

성공률 = success / 전체, 타임아웃 비율 = timeout / 전체, 429 비율 = rate_limited / 전체로 계산한다.

**알림 규칙** (메트릭 모니터. 임계치는 보수적으로 시작해 관측으로 조정)

| 조건 (5분 창) | 수준 | 의미 |
|---|---|---|
| 429 1건 이상 | 알림 | 호출 한도에 닿기 시작. 허용 속도·배분 조정 신호 |
| 429 비율 > 1% 또는 부분 실패 비율 > 5% | 긴급 | 고객 검색 결과가 실제로 줄고 있음 |
| 공급사 성공률 < 97% 또는 타임아웃 비율 > 2% | 긴급 | 공급사 장애 또는 타임아웃 값 부적절 |
| p95 지연 > 응답 타임아웃의 80% | 알림 | 타임아웃 임박. 값 재검토 |

**호출 한도 탐색 절차** (한도 값을 모르는 상태에서 안전하게 찾기)
1. 허용 속도 설정값을 보수적으로 시작한다(공급사당 초당 1회). 실제 공급사 API가 공개한 한도가 초당 0.8~10회 범위라 그 아래쪽이다([domain-research 4장](domain-research.md)).
2. 429를 받으면 코드가 로그(공급사·현재 속도·`Retry-After`)와 지표를 남기고 지수 백오프(1→2→4→…→60초, `Retry-After`가 있으면 그 값 우선, 5회를 넘기면 이번 바퀴 포기)로 물러난 뒤 속도를 절반으로 낮춰 재개한다.
3. 올리는 것은 자동이 아니다. 429 알림이 일정 기간(예: 수 일) 없으면 알림을 본 사람이 설정값을 올린다(상한 초당 4회).
4. 찾은 값은 설정으로 고정하고, 이후에는 429 비율 모니터로 이탈만 감시한다.

**역할 구분**: 비율·추세·임계치는 메트릭 모니터(예: Datadog), 정규화 실패·예상 못 한 예외처럼 한 건이라도 조사해야 하는 것은 예외 추적(예: Sentry)에 보낸다. 429는 정상 운영 상황이므로 예외로 보내지 않는다. 429 모니터의 알림은 갱신 잡의 초당 호출 횟수를 조정하는 신호로만 쓴다(4.8).

### 4.8 요금·재고 캐시 (선택)

구현 상태: 설계 확정, 최소 구현 예정(단일 주기 스케줄 잡 + Redis 읽기). 검토 과정은 [JOURNAL](../JOURNAL.md)의 "요금/재고 캐시" 항목에 있다.

요구사항과의 관계: 요금·재고를 DB에 쌓지 않는다는 요구는 지킨다(캐시는 TTL이 있는 휘발성 저장이고 원본은 공급사). 안내 문서의 기본 흐름은 검색마다 공급사를 호출하는 것이고 캐시는 선택 구현(§3.3)이다. 우리는 검색 응답 시간(수 ms) 때문에 캐시를 주 경로로 두되, 검색 시점 직접 호출 경로(병렬·타임아웃·부분 실패·실패 판정 통일)는 `fresh=true`와 저하 모드에 그대로 남긴다. 갱신 잡도 같은 어댑터·견고성 코드를 쓴다.

**구조: 미리 채우기(주기 갱신형).** 웹 앱 안의 스케줄 잡이 5분마다 창(오늘 ~ +30일) 전체를 공급사에서 받아 관리형 Redis(예: ElastiCache)에 올린다. 검색은 Redis만 읽는다. 검색된 조합만 채우는 캐시 어사이드도 검토했지만, 새 기간을 처음 검색한 사용자가 초 단위(묶음 수 ÷ 허용 속도)를 기다리게 되어 뺐다.

```
Redis Hash  키: stay:v1:{hotelId}
            필드: {roomTypeId}:{yyyyMMdd} → 재고|세금 포함 1박 요금|통화|조식(0/1)   예) 11:20260901 → 3|132000|KRW|0
            필드: _refreshedAt → 마지막 갱신 시각
            날짜 창: 오늘 ~ +30일 · TTL = 주기 × 3 (안전장치)
상태 키     stay:v1:status:{supplier} → 마지막 성공 시각, 마지막 실패 원인·시각

[갱신 잡]   5분마다: 인메모리 매핑의 공급사별 코드 50개 묶음 → 재고·요금 API(adults=1) → 정규화 → HSET 파이프라인 → 상태 키
            A: 묶음당 체크인 오늘·체크아웃 +30일 한 번 호출로 dailyRates 30일치
            B: 기간 총액만 주므로 묶음당 날짜마다 1박 호출(30회). 연박 요금은 1박 값의 합으로 근사
[매일 04:00] 매핑 델타로 비활성 숙소 키 삭제, 지난 날짜 필드 HDEL, 날짜 창 전진
[검색]      숙소마다 HMGET {roomTypeId}:{date} × 숙박일 (파이프라인) → 객실 타입별 min 재고, Σ 요금 → 인원 필터 → 응답
            상태 키의 실패는 failures 에 REFRESH_FAILED 로, _refreshedAt 이 주기 × 2 를 넘으면 stale 로 표시
[fresh=true] 예약 직전 재확인. Redis 를 건너뛰고 공급사에 직접 호출
[저하 모드]  Redis 가 비었을 때(장애·초기화)만: 검색이 응답 예산 3초 안에서 직접 호출, 채운 것만 응답하고 나머지는 pending. 알림
[콜드 스타트] 갱신 잡 첫 바퀴가 끝나야 웹 팟 readiness 가 올라간다. 롤링 배포라 그동안은 이전 팟이 응답
```

| 항목 | 설계 |
|---|---|
| 캐시가 성립하는 조건 | 같은 키에 대한 검색이 변경보다 잦을 때. 키(객실 타입·날짜) 하나의 변경 간격은 요금 30분~2시간(RMS 제품 사양, 대리 지표), 재고 1.6~5시간(점유율·숙박일수·리드타임 역산). 인기 키의 검색은 분당 단위라 성립. 실측 데이터는 공개된 것이 없어 갱신 시 변경 감지율(재고·요금 따로)로 대체 |
| 값의 기준 | 요금은 세금 포함 1박(A는 nightlyRate + taxAmount, B는 1박 호출의 totalPrice). B가 세금 금액을 주지 않아 세금 별도 기준으로는 통일할 수 없다. 재고 0도 저장한다(매진 판정용) |
| 키·자료구조 | 숙소당 Hash 하나. 키 수가 숙소 수와 같아 키 오버헤드가 작다. 값을 약 20B 구분자 문자열로 두어 필드 128개(객실 타입 4 × 31일) 이하면 listpack 압축. 숙소당 약 6KB, 10,000개 ≈ 60MB. 접두사 버전은 표준 모델 변경 배포 시 올림 |
| 호출 예산 | 공급사당 초당 4회 시작(설정값), 상한 10회. 실제 공급사가 공개한 운영 한도(Hotelbeds 4회)에서 출발. 429를 받으면 로그·지표를 남기고 지수 백오프(1→2→4→…→60초, `Retry-After` 우선, 5회 초과면 이번 바퀴 포기) 후 예산을 절반으로 낮춰 재개. 올리는 것은 자동이 아니라 알림을 본 사람이 설정값으로 |
| 주기 | 5분(설정값). 요금 30분에 허용 오차 약 15%. 한 바퀴 호출 수 = A 묶음 수 + B 묶음 수 × 30. 숙소 1,000개면 620회 ÷ 4회/초 ≈ 2.6분으로 주기 안. 5,000개면 13분이라 날짜 거리별 계층(임박 5분, 먼 날짜 30~60분)이나 한도 협의가 필요하며 확장 설계로 남긴다 |
| 주기 조정 | 갱신 시 값이 바뀐 비율을 재고·요금 따로, 공급사별로 기록. 5% 미만이면 주기를 늘리고 30% 초과면 줄이거나 예산 상향. `fresh=true` 결과와 캐시 값의 불일치율(목표: 요금 3% 이하, 있음→없음 1% 이하)이 정합성 오차의 실측치 |
| 남은 객실이 적은 키 | 남은 객실 ≤ 2인 키는 예약 1건에 상태가 뒤집히므로 1분마다 다시 보는 핫 리스트를 둔다. 설계만 |
| TTL | 주기의 3배. 만료 장치가 아니라 갱신 잡이 멈췄을 때 옛 값이 남지 않게 하는 안전장치 |
| 저하 모드 검산 (숙소 1,000개, 초당 4회) | 묶음 20개 ÷ 4 = 5초에 전부 복구. 응답 예산 3초에는 12묶음 = 600개 응답 + 400개 `pending`. 3초 안에 전부 채우려면 초당 8회가 필요하지만 예외 케이스라 4회 + 부분 응답으로 결정 |
| 다중 인스턴스 | 갱신 잡은 팟 하나에서만 돈다(리더 선출 또는 별도 팟, 확장 설계). 검색은 모든 팟이 같은 Redis 를 읽는다 |
| 설정값 | 허용 속도, 주기, 날짜 창, 응답 예산, 핫 리스트 기준은 설정으로 두고 변경 감지율·429 발생률을 보며 조정 |

## 5. Mock Supplier

공급사 A·B의 API를 흉내 내는 별도 모듈. 채점 대상이 아니라 연동 동작을 검증하는 수단이므로 최소로 구현한다.

### 5.1 실행
```bash
./gradlew :mock-supplier:bootRun   # 포트 9090
```

### 5.2 엔드포인트

| 엔드포인트 | 역할 | 모드 적용 |
|---|---|---|
| `GET /a/v1/hotels` | Supplier A 숙소 목록 | 없음 |
| `GET /a/v1/availability?hotelCodes=...` | Supplier A 재고·요금 | 있음 |
| `GET /b/api/properties` | Supplier B 숙소 목록 | 없음 |
| `GET /b/api/search?propertyIds=...` | Supplier B 재고·요금 | 있음 |
| `POST /control/{a\|b}/mode?value=...` | 공급사별 모드 전환 | - |

- 응답 본문은 `src/main/resources/responses/*.json`의 고정 데이터이며 요청 파라미터는 무시한다.
- 요청 파라미터 필터링, 요청당 숙소 수 상한 초과 오류, 응답 지연 모드, 숙소 목록 API 장애 모드는 필요해지면 추가한다.

### 5.3 모드

| 모드 | Supplier A 재고·요금 응답 | Supplier B 재고·요금 응답 |
|---|---|---|
| `normal` (기본) | HTTP 200 + 정상 본문 | HTTP 200 + `resultCode: "0000"` |
| `error` | HTTP 503 + `{"error":"SERVICE_UNAVAILABLE"}` | HTTP 200 + `{"resultCode":"E503","data":null}` |
| `no-response` | 응답을 보내지 않고 10분 대기 | 같음 |

```bash
curl -X POST 'http://localhost:9090/control/a/mode?value=error'         # A 장애
curl -X POST 'http://localhost:9090/control/b/mode?value=no-response'   # B 무응답
curl -X POST 'http://localhost:9090/control/a/mode?value=normal'        # A 복구
```

### 5.4 구성 방식 선택
별도 모듈로 둔다. WireMock 독립 실행은 코드가 없는 대신 매핑 문법과 시나리오 상태 API를 익혀야 하고 공급사별 모드 전환이 복잡하다. 본 앱 안의 테스트용 컨트롤러는 같은 프로세스에서 자기 자신을 호출해 스레드가 묶이고, 분리하려면 앱을 두 번 띄우면서 본 앱 로직을 꺼야 한다. 자동 테스트에서는 이 모듈에 의존하지 않고 WireMock을 테스트 안에서 띄운다 (7장).

## 6. 실행 구성

### 6.1 로컬
MySQL은 Docker, Mock Supplier는 별도 프로세스, 애플리케이션은 로컬 실행. 서버 Docker화는 하지 않는다.
```bash
docker compose up -d                                          # MySQL
./gradlew :mock-supplier:bootRun                              # Mock (9090)
./gradlew bootRun --args='--spring.profiles.active=sync'      # 매핑 갱신 잡 1회 실행 후 종료
./gradlew bootRun                                             # 웹 앱 (8080)
```

### 6.2 운영 (설계)
같은 이미지를 두 워크로드로 배포한다.

| 워크로드 | 프로필 | 역할 |
|---|---|---|
| Deployment (팟 N개) | 기본 | 검색 API. 기동 시 DB → 인메모리(숙소·객실 타입 매핑), 매일 04:30 Asia/Seoul 한 번 더 로드 |
| CronJob (`0 4 * * *`, `timeZone: Asia/Seoul`) | `sync` | 매핑 갱신 잡 1회 실행. 수동 실행은 `kubectl create job --from=cronjob/<name>` |

```yaml
# 예시 매니페스트 (설계)
apiVersion: batch/v1
kind: CronJob
metadata: { name: mapping-sync }
spec:
  schedule: "0 4 * * *"
  timeZone: "Asia/Seoul"
  concurrencyPolicy: Forbid
  jobTemplate:
    spec:
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: mapping-sync
              image: <app-image>
              args: ["--spring.profiles.active=sync"]
```
`concurrencyPolicy: Forbid`로 갱신 잡이 겹쳐 돌지 않게 한다.

## 7. 테스트 구성
<!-- 테스트 종류별 범위, 사용하는 DB·Mock, 실행 명령 -->
