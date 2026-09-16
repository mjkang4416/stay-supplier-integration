# API 명세

> 확정된 설계를 명세한다. 결정의 근거는 [README.md](../README.md) 5.4, 결정 과정은 [JOURNAL.md](../JOURNAL.md)에 있다.

## 1. 통합 검색 API

구현 상태: 구현. 공급사 직접 호출 경로다. 요금·재고 캐시(Redis)가 붙으면 평소 검색은 캐시를 읽고, 직접 호출은 `fresh=true`와 캐시가 비었을 때만 쓴다([architecture 4.8](architecture.md)).

### 1.1 요청
`GET /api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0`

| 파라미터 | 타입 | 필수 | 설명 | 검증 |
|---|---|---|---|---|
| `checkIn` | `YYYY-MM-DD` | 예 | 체크인일 | 날짜 형식 |
| `checkOut` | `YYYY-MM-DD` | 예 | 체크아웃일. 숙박일에 포함되지 않는다 (9/1~9/4 = 3박) | `checkIn`보다 뒤, 박수 ≤ 30 (`search.max-nights`) |
| `adults` | int | 예 | 성인 수 | 1 이상 |
| `children` | int | 아니요 (기본 0) | 아동 수 | 0 이상 |
| `fresh` | boolean | 아니요 (기본 false) | true면 캐시를 건너뛰고 공급사에 직접 묻는다 (예약 직전 재확인). 캐시가 없는 현재는 값과 무관하게 직접 호출 | |
| `includeSoldOut` | boolean | 아니요 (기본 false) | true면 예약 가능 객실 수가 0인 객실 타입도 응답에 넣는다 | |

검색 조건은 날짜와 인원뿐이고 대상은 자사가 보유한 숙소 전체(인메모리 매핑의 active 숙소)다. 검증에 어긋나면 공급사를 부르지 않고 400이다.

### 1.2 응답

| 필드 | 타입 | 설명 |
|---|---|---|
| `checkIn`, `checkOut`, `nights`, `adults`, `children` | | 요청 그대로와 박수 |
| `stays[]` | array | 숙소 단위. `hotelId` 오름차순 |
| `stays[].hotelId` | long | 내부 숙소 식별자 (매핑) |
| `stays[].hotelName` | string | 숙소명 (매핑) |
| `stays[].supplier` | `A` / `B` | 출처 공급사 |
| `stays[].roomTypes[]` | array | 객실 타입. `roomTypeId` 오름차순 |
| `roomTypes[].roomTypeId` | long | 내부 객실 타입 식별자 (매핑) |
| `roomTypes[].roomTypeName` | string | 객실 타입명 (매핑) |
| `roomTypes[].maxOccupancy` | int | 객실 1실 최대 수용 인원 (성인+아동). 재고·요금 응답 값, 없으면 매핑 값. 둘 다 없는 객실 타입은 응답에서 뺀다 |
| `roomTypes[].availableRooms` | int | 요청 기간 전체를 예약할 수 있는 객실 수 = 날짜별 재고의 최솟값. 0이면 예약 불가 |
| `roomTypes[].price.total` | long | 요청 기간 총액, 세금 포함. 통화 최소 단위 정수 |
| `roomTypes[].price.currency` | string | ISO 4217 |
| `roomTypes[].price.taxIncluded` | boolean | 항상 true (요금 기준) |
| `roomTypes[].price.breakfastIncluded` | boolean | 요금에 조식이 포함되는지. 같은 객실도 공급사마다 다를 수 있어 비교 조건 |
| `failures[]` | array | 실패한 공급사. `supplier`, `reason`(architecture 4.2의 `FailureReason`), `affectedHotels`(값을 못 받은 숙소 수) |
| `pending[]` | long[] | 응답 예산 안에 값을 채우지 못한 숙소 식별자 (캐시가 비었을 때의 저하 모드). 현재는 항상 비어 있음 |
| `fresh` | boolean | 공급사에 직접 물어 만든 응답인지 |

### 1.3 부분 실패 표현
일부 공급사가 실패하면 상태 코드는 200이고, 성공한 공급사의 결과와 함께 `failures`에 실패한 공급사·원인·영향 숙소 수를 담는다. 어댑터 안에서 묶음(50개) 일부만 실패한 경우도 같은 배열에 묶음 단위로 들어간다. 조회 대상 공급사가 전부 실패하면 503이다.

### 1.4 에러 응답

| 상황 | HTTP 상태 | 본문 |
|---|---|---|
| 파라미터 누락·형식 오류·검증 실패 | 400 | `{ "code": "INVALID_REQUEST", "message": "...", "failures": [] }` |
| 조회 대상 공급사 전부 실패 | 503, `Retry-After: 5` | `{ "code": "ALL_SUPPLIERS_FAILED", "message": "...", "failures": [ { "supplier", "reason", "affectedHotels" } ] }` |

### 1.5 예시

정상 (Mock 예제 데이터, 9/1~9/4 성인 2). Namsan Garden Stay는 9/2 재고가 0이라 빠졌다.
```json
{
  "checkIn": "2026-09-01", "checkOut": "2026-09-04", "nights": 3, "adults": 2, "children": 0,
  "stays": [
    { "hotelId": 1, "hotelName": "Riverside Hotel Seoul", "supplier": "A",
      "roomTypes": [ { "roomTypeId": 1, "roomTypeName": "Deluxe Twin", "maxOccupancy": 2, "availableRooms": 1,
                       "price": { "total": 429000, "currency": "KRW", "taxIncluded": true, "breakfastIncluded": false } } ] },
    { "hotelId": 3, "hotelName": "Riverside Hotel Seoul", "supplier": "B",
      "roomTypes": [ { "roomTypeId": 3, "roomTypeName": "Deluxe Twin Room", "maxOccupancy": 2, "availableRooms": 1,
                       "price": { "total": 452000, "currency": "KRW", "taxIncluded": true, "breakfastIncluded": true } } ] }
  ],
  "failures": [], "pending": [], "fresh": true
}
```

부분 실패 (Supplier A 무응답 → 3초 타임아웃)
```json
{
  "checkIn": "2026-09-01", "checkOut": "2026-09-04", "nights": 3, "adults": 2, "children": 0,
  "stays": [ { "hotelId": 3, "hotelName": "Riverside Hotel Seoul", "supplier": "B", "roomTypes": [ "..." ] } ],
  "failures": [ { "supplier": "A", "reason": "TIMEOUT", "affectedHotels": 2 } ],
  "pending": [], "fresh": true
}
```

## 2. 조회 규칙

### 2.1 조회 대상과 공급사별 묶음
인메모리 매핑(`MappingRegistry`)에서 공급사별 active 숙소 코드를 꺼내 공급사마다 한 번 `fetchAvailability`를 부른다. 공급사 한도(요청당 50개)는 어댑터가 알고 안에서 잘라 병렬 호출하며, 묶음 일부가 실패하면 성공한 항목과 실패 묶음을 함께 돌려준다. active 숙소가 없는 공급사는 부르지 않는다. 공급사 간 호출은 병렬이고, 실패는 공급사 단위로 가둔 뒤 병합한다([architecture 4.4](architecture.md)).

### 2.2 연박 예약 가능 객실 수 판정
`availableRooms` = 요청 기간(체크인일 ~ 체크아웃 전날)의 날짜별 `remainingRooms` 중 최솟값. 하루라도 0이면 0이고 예약 불가다. 요금은 A는 날짜별 (nightlyRate + taxAmount)의 합, B는 totalPrice 그대로. 응답이 객실 타입 단위라 호실 배정·업그레이드·분할 예약은 다루지 않는다.

### 2.3 예약 불가 상품 처리
`availableRooms == 0`인 객실 타입은 기본으로 응답에서 뺀다. 객실 타입이 모두 빠진 숙소도 뺀다. `includeSoldOut=true`면 `availableRooms: 0`으로 포함한다. 근거는 [README 5.4](../README.md).

### 2.4 병합 규칙
1. 공급사 코드를 매핑으로 내부 식별자로 바꾼다. 매핑에 없는 코드(지난 새벽 이후 추가된 상품)는 버리고 로그를 남긴다. 다음 새벽 크론잡이 채우면 그때부터 나온다.
2. 최대 수용 인원은 재고·요금 응답 값 → 매핑 값 순으로 채우고, 둘 다 없으면 그 객실 타입을 뺀다(계약상 필수 필드를 비워 보내지 않는다).
3. 인원 필터: `maxOccupancy ≥ adults + children`인 객실 타입만 남긴다.
4. 예약 불가 필터(2.3).
5. 숙소 단위로 묶고 `hotelId`, `roomTypeId` 오름차순으로 정렬한다. 공급사가 다르면 같은 호텔이어도 다른 숙소로 나온다(중복 상품 병합은 선택 항목, 미구현).
6. 실패는 `failures`로, 조회 대상 공급사가 전부 실패하면 503.

### 2.5 재시도
검색은 사용자가 기다리므로 연결 실패·500·503만 즉시 1회 다시 시도한다(`search.retry-attempts`, `search.retry-delay` 200ms). 타임아웃(이미 3초 소진)과 429(더 부르면 악화), 잘못된 요청·인증·깨진 응답은 다시 하지 않는다.
