---
name: add-supplier
description: |
  신규 공급사(예: C)를 연동할 때 고치는 곳과 고치지 않는 곳을 순서대로 안내한다. Supplier enum → supplier/c 패키지 → 설정 → WireMock 테스트 → 문서.
  Triggers: "공급사 추가", "Supplier C 붙여", "새 공급사 연동", "어댑터 추가", "add supplier"
  Do NOT use for: 기존 공급사 A·B 수정, Mock Supplier 에 공급사 추가(mock-supplier 모듈은 별도), 검색·크론잡·매핑 로직 변경(수정 대상이 아니다)
argument-hint: "공급사 코드 한 글자 (예: C)"
allowed-tools: Read, Grep, Glob, Edit, Write, Bash(./gradlew*)
---

# 신규 공급사 추가

절차의 근거는 `docs/architecture.md` 4.5(수정 범위)와 4.1~4.2(어댑터 경계·실패 판정)다. 이 스킬은 순서와 확인 방법만 담는다.

## 전제
- 새 공급사의 API 스펙(엔드포인트, 인증 헤더, 응답 형식, 실패를 알리는 방식)을 알고 있다.
- 어댑터 밖으로는 표준 형태(`stay/` 패키지)만 나간다. 공급사 전용 요청·응답 형식은 새 패키지 안에 package-private 으로 둔다.

## 실행 순서
1. `supplier/Supplier.java` enum 에 값 추가 (예: `C`). 이 값이 DB `supplier` 컬럼과 검색 응답의 출처 표시에 그대로 쓰인다.
2. `supplier/c/` 패키지 생성
   - `SupplierCResponses.java`: 공급사 응답 형식을 record 로. package-private (`final class` + 중첩 record). `supplier/a/SupplierAResponses.java` 가 본보기.
   - `SupplierCClient.java`: `@Component`, `implements SupplierClient`, 생성자는 `(SupplierWebClients webClients, SupplierRateLimiter rateLimiter)`. `@Component` 만으로 `List<SupplierClient>` 에 들어가므로 별도 등록은 없다. 구현할 메서드는 세 개다.
     - `fetchHotels()`: ① 숙소 목록 → `SupplierHotel`·`SupplierRoomType`. 코드·이름이 빠진 항목은 버리고 warn 로그, `maxOccupancy` 가 없으면 null(미상).
     - `fetchAvailability(query)`: ② 기간 재고·요금 → `SupplierRoomOffer`. 50개 묶음과 부분 실패는 `ChunkedFetch.fetch` 를 그대로 쓴다.
     - `fetchDailyAvailability(codes, from, to)`: 캐시용 날짜별 1박 값 → `SupplierDailyOffer`. `ChunkedFetch.fetchDaily`.
   - 실패 판정: HTTP 상태로 알리는 공급사면 `SupplierFailures.fromStatus`, 본문 코드로 알리는 공급사면 B 의 `reasonOf` 처럼 코드 → `FailureReason` 표를 이 패키지 안에 둔다. 예외는 항상 `SupplierCallException` 하나다.
   - 요금은 세금 포함 총액으로 정규화한다(A 는 nightlyRate + taxAmount 의 합, B 는 totalPrice). 캐시용은 1박 값.
3. `src/main/resources/application.properties` 에 설정 네 줄
   ```properties
   supplier.endpoints.c.base-url=http://localhost:${MOCK_SUPPLIER_PORT:9090}
   supplier.endpoints.c.api-key=${SUPPLIER_C_API_KEY:local-c-key}
   supplier.endpoints.c.connect-timeout=1s
   supplier.endpoints.c.response-timeout=3s
   ```
   빠뜨리면 `SupplierWebClients` 가 기동 시 `missing configuration: supplier.endpoints.c` 로 실패한다.
4. 테스트
   - `src/test/java/com/staysupplier/supplier/SupplierClientTestSupport.java` 의 `webClients(...)` 에 `Supplier.C` 엔드포인트와 `API_KEY_C` 상수를 추가한다. enum 에 C 가 생기면 이 지원 클래스가 C 설정도 만들어야 기존 어댑터 테스트가 뜬다.
   - `src/test/java/com/staysupplier/supplier/c/SupplierCClientTest.java` 를 WireMock 으로 작성한다. `SupplierAClientTest`(상태 코드 판정)·`SupplierBClientTest`(본문 코드 판정)가 본보기.
   - 최소 검증: 목록·재고 정규화 값, `X-Api-Key` 전송, 실패 판정(→ `FailureReason`), 타임아웃 → `TIMEOUT`, 0건 vs 구조 누락(`INVALID_RESPONSE`), 50개 분할.
5. Mock Supplier 에 C 엔드포인트를 추가할지 정한다. 로컬 확인에만 필요하며 `mock-supplier` 모듈은 본 앱과 코드를 공유하지 않는다.
6. 문서: `docs/stay-model.md` 2장 필드 대응표에 열 추가, `docs/architecture.md` 4.2 실패 판정에 규칙 추가, README 4.1 살린·버린 정보 표에 열 추가.
7. `./gradlew build` 로 전체 테스트를 돌린다.

## 확인 포인트
- 고치지 않아야 하는 파일: `MappingSyncJob`, 매핑 테이블·매퍼 XML, `StaySearchService`, `AvailabilityRefreshJob`, `stay/` 표준 형태, `SupplierWebClients`, `SupplierFailures`, `ChunkedFetch`. 이 중 하나라도 고치고 있다면 공급사 전용 형식이 경계 밖으로 샌 것이다.
- 기동 뒤 `run-local` 3번(크론잡)을 돌리면 C 의 숙소가 `hotel_mapping` 에 `supplier = C` 로 들어가고(`query-mapping`), 갱신 잡 첫 바퀴 뒤 `stay:v1:status:C` 키가 생긴다(`query-cache`).

## 주의사항
- 공급사 원본 오류 메시지는 예외의 원본 코드와 로그에만 두고 응답에 내보내지 않는다.
- 기본값을 넣어 추측하지 않는다. 최대 인원이 없으면 null, 코드·이름이 없으면 그 항목을 버린다.
- 실패 사유는 `FailureReason` 8가지 밖으로 늘리지 않는다. 새 종류가 필요하면 먼저 `docs/architecture.md` 4.2 를 고친다.
