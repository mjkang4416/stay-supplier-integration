# 도메인 리서치 원페이저

설계에 들어가기 전에 조사한 도메인 배경을 정리해요. 공급사 API 스펙 원문은 옮기지 않고, 예시 데이터는 Mock Supplier에 넣은 값을 기준으로 해요. 업계 구조는 일반적으로 알려진 내용을 정리한 것이고, 특정 업체의 실제 계약 관계를 확인한 것은 아니에요.

## 1. 숙박 플랫폼과 공급사

숙박 플랫폼은 숙소 사장님이 직접 등록한 상품만 파는 게 아니에요. 외부 공급사(Supplier)가 API로 제공하는 상품도 함께 팔아요. 공급사는 우리 플랫폼에 숙소를 등록해 주는 쪽이 아니라, 우리가 API를 호출해서 상품을 가져오는 쪽이에요.

공급사 역할을 하는 곳은 보통 이런 유형이에요.

| 유형 | 하는 일 | 예 |
|---|---|---|
| 도매 업체(베드뱅크) | 여러 호텔과 계약해 객실을 확보하고, 다른 판매처에 B2B로 재판매해요 | Hotelbeds, WebBeds |
| 글로벌 OTA의 제휴 API | 자기 플랫폼의 재고를 제휴사가 팔 수 있게 API로 열어줘요 | Expedia, Booking.com, Agoda의 제휴 프로그램 |
| 채널 매니저 | 호텔이 여러 판매처에 재고·요금을 한 번에 배포하도록 중개해요 | SiteMinder |
| 호텔 체인 시스템 | 대형 체인이 본사 예약 시스템을 API로 직접 연결해요 | 글로벌 체인의 파트너 API |

국내 모텔·펜션은 사장님이 직접 등록하는 경우가 많고, 해외 호텔이나 대형 호텔은 이런 공급사를 연동해서 채우는 구조가 일반적이에요.

### 같은 객실이 여러 공급사에 있는 이유

호텔은 빈방을 남기지 않으려고 여러 판매처와 동시에 계약해요. 예를 들어 Riverside Hotel Seoul이 도매 업체 A에는 "하루 10실, 도매가, 조식 없음"으로, 글로벌 OTA B에는 "조식 포함 패키지 요금"으로 계약했다고 해 볼게요. 우리가 A와 B를 둘 다 연동하는 순간, 같은 Deluxe Twin이 두 경로로 들어와요.

| | 공급사 A | 공급사 B |
|---|---|---|
| 숙소 코드 | A-10023 | B77120 |
| 객실 타입 코드 | DLX-TWN | R-401 |
| 3박 요금 | 429,000원 (세금 별도 단가를 합산) | 452,000원 (세금 포함 총액) |
| 조식 | 없음 | 포함 |

값도 조건도 다른데, A와 B는 서로 다른 회사라 "우리 둘 다 이 호텔을 판다"고 알려주지 않아요. 같은 숙소인지 알아볼 공통 키가 없어요.

그래도 플랫폼이 둘 다 연동하는 이유는 네 가지예요.
- 숙소 범위: A에만 있는 숙소(Namsan Garden Stay)가 있어요. B만 붙이면 못 팔아요.
- 조건 선택지: 조식 포함을 원하는 고객과 싼 쪽을 원하는 고객이 달라요.
- 재고 확보: 호텔이 채널별로 객실을 나눠 배정해서, A가 매진이어도 B에는 남아 있을 수 있어요.
- 장애 대비: A 서버가 죽어도 B 결과로 검색 화면을 채울 수 있어요.

### 호텔 쪽 시스템: PMS·RMS·채널 매니저

공급사가 주는 재고·요금은 공급사가 만든 값이 아니라 호텔 쪽 시스템에서 흘러온 값이에요.

```
[호텔]  PMS: 재고·예약 원장 / RMS: 요금 결정
   │  재고·요금·조건(ARI) 변경을 푸시
   ▼
[채널 매니저]  호텔이 구독하는 SaaS. 계약된 판매처 전부에 전파
   ▼
[공급사 A·B]  OTA·도매 업체. 받은 값을 자기 시스템에 반영하고 API로 노출
   ▼
[우리 서버]  공급사 API를 호출(pull)해서 가져옴
```

| 시스템 | 하는 일 | 예 |
|---|---|---|
| PMS (Property Management System) | 호텔의 기본 장부. 객실·예약·재고의 원본 | Opera, Cloudbeds, Mews |
| RMS (Revenue Management System) | 수요 예측·경쟁 가격·점유율을 보고 요금을 자동으로 조정 | IDeaS, Duetto, RoomPriceGenie, PriceLabs |
| 채널 매니저 | ARI를 여러 판매처에 동시에 뿌리고, 판매처의 예약을 PMS로 되돌려 오버부킹을 막음 | SiteMinder, RateGain, D-EDGE |

이 시스템들은 우리에게 보이지 않아요. 우리가 보는 건 공급사 API뿐이고, 이 그림은 "왜 값이 자주 바뀌는지"를 이해하기 위한 배경이에요.

## 2. 상품 구조

- 상품은 숙소 > 객실 타입 2단계예요. 101호 같은 개별 물리 객실은 다루지 않아요. 고객도 "Deluxe Twin"을 고르지, 방 번호를 고르지 않아요.
- 요금과 재고는 모두 객실 타입 단위예요. "남은 객실 3"은 그 타입의 객실이 그날 3개 남았다는 뜻이에요.
- 최대 수용 인원은 성인과 아동을 합산한 기준이에요. 공급사는 요청 인원을 수용할 수 있는 객실 타입만 돌려줘요.
- 재고는 날짜별로 따로 있어요. 체크아웃일은 숙박일에 포함되지 않아서, 9/1 체크인·9/4 체크아웃이면 9/1·9/2·9/3 세 밤이에요. 연박은 세 밤 모두 재고가 있어야 해요.

## 3. 공급사 간 표현 차이

| 항목 | 차이 | 설계에 주는 의미 |
|---|---|---|
| 요금 | A는 날짜별 1박 단가에 세금이 별도(net)이고, B는 기간 총액에 세금이 포함(gross)돼요. B는 날짜별 요금과 세금 금액을 주지 않아요 | 날짜별 값은 합쳐서 총액을 만들 수 있지만, 총액은 날짜별로 쪼갤 수 없어요. 무엇을 기준으로 삼든 한쪽 정보를 잃어요 |
| 조식 | 같은 객실이라도 공급사마다 조식 포함 여부가 달라요 | 가격만 보고 싼 쪽을 고르면 조건이 다른 상품을 비교하게 돼요 |
| 식별자 | 공급사마다 자기 체계로 코드를 붙여요. 객실 타입 코드는 해당 숙소 안에서만 유일해요 | 객실 타입 하나를 가리키려면 (공급사, 숙소 코드, 객실 타입 코드) 세 값이 필요해요. 공급사끼리 같은 숙소를 이어 줄 공통 키는 없어요 |
| 실패 표현 | A는 HTTP 상태 코드로 알리고, B는 항상 200을 주면서 본문 코드로만 알려요 | HTTP 상태만 보면 B의 장애를 정상 응답으로 처리하게 돼요. 실패 판정을 공급사별로 통일해야 해요 |
| 조회 구조 | 숙소 목록(정적, 조건 없음)과 재고·요금(동적, 숙소 코드 목록으로 조회)이 나뉘어요. 한 번에 조회할 수 있는 숙소 수에 상한이 있어요 | 어떤 숙소를 물어볼지 우리가 먼저 알고 있어야 해요. 그래서 매핑을 미리 저장해요 |

## 4. 데이터가 바뀌는 주기

"숙소 목록은 자주 안 바뀌고 재고·요금은 매번 바뀐다"를 업계 자료로 확인했어요. "몇 초마다 바뀐다" 같은 통계는 없어요. 호텔·시기·판매 채널마다 달라서 그런 수치가 성립하지 않아요. 대신 갱신 주기와 전파 속도 자료는 있어요.

출처 성격을 먼저 밝혀 둬요. 호텔 업계는 예약·가격 변경 데이터를 공개하지 않아서 협회 통계나 학술 집계 같은 공식 수치는 없어요. 아래 수치는 RMS·채널 매니저 벤더가 자기 제품을 설명하며 공개한 자료와 OTA 사례 해설에서 가져온 것이라, 정확한 값이 아니라 규모(요금은 시간 단위, 재고는 이벤트 단위, 숙소 정보는 일·주 단위)를 확인하는 용도로만 써요. 설계 결론은 이 규모 차이만으로 서고, 수치가 두 배쯤 달라도 바뀌지 않아요.

| 데이터 | 바뀌는 주기 | 업계에서 다루는 방식 |
|---|---|---|
| 숙소·객실 타입 정보 (이름, 구성, 최대 인원) | 일·주 단위. 숙소 추가·제거, 객실 타입 개편, 이름 변경 | 정적 콘텐츠 API를 야간·주간 배치로 받아 로컬 DB에 저장 |
| 요금 | 기본 시스템은 하루 1회, 자동 요금 관리(RMS)는 하루 12~40회, AI 기반은 수백 회. 실시간 옵션은 예약·취소 직후 갱신. 소규모 숙소는 주 단위 수동 조정 | 검색마다 실시간 호출이 기본 |
| 재고 | 주기가 아니라 예약·취소 이벤트마다 바뀜. 채널 매니저가 연결된 판매처에 수초 안에 전파 | 검색마다 실시간 호출이 기본, 예약 직전 재확인 |

요금 변경 간격은 호텔 유형에 따라 폭이 넓어요.

| 호텔 유형 | 요금 변경 간격 |
|---|---|
| 소규모·수동 조정 | 주 단위 |
| 기본 시스템 | 하루 1회 |
| 자동 RMS (중대형 호텔) | 30분~2시간 (하루 12~40회) |
| AI 기반 | 수 분 (하루 수백 회) |
| 실시간 옵션 | 예약·취소 즉시 |

평균은 산술 평균이라 실제로는 저녁 피크 시간대와 체크인 임박 날짜에 변경이 몰려요.

검색 빈도와 재고 변경 빈도를 비교할 수 있는 지표로 업계에서 쓰는 것이 Look-to-Book(검색 대비 예약) 비율이에요. 예약이 곧 재고 변경이라, 이 비율이 "검색이 재고 변경보다 얼마나 잦은가"를 보여줘요.

| 지표 | 값 | 출처 성격 (확인 결과) |
|---|---|---|
| 대형 도매 업체의 하루 처리량 | 검색 1,400만 건 / 예약 8만 건 (약 175:1). 취급 숙소 30만 개 기준 숙소당 하루 검색 약 47건, 예약 약 0.27건 | **제3자 연동 업체 페이지의 인용.** 출처 표기가 없고, 해당 도매 업체 공식 페이지에서는 확인되지 않음 |
| 유통사 수준 Look-to-Book | 과거 500:1 → 최근 20,000:1 | **2016년 업계 칼럼(여행 데이터 분석 업체 임원).** "probably", "I suspect" 같은 표현을 쓴 경험 기반 추정 |
| 호텔 API 계약의 Look-to-Book 상한 | 1,000~5,000:1 | 여행 IT 개발 업체 블로그. 근거 표기 없음 |

세 수치 모두 공식 통계가 아니라 자기 보고·추정·해설이에요. 그래도 셋이 독립적으로 "검색이 예약보다 수백~수천 배 많다"는 같은 규모를 가리켜서, 규모 판단에는 쓸 수 있다고 봤어요. 공급사가 여러 판매처에 같은 재고를 팔아도, 공급사 안에서 집계한 예약(=재고 변경)이 검색보다 두 자릿수 이상 드물다는 결론은 이 규모 위에 서 있어요. 인기 숙소가 하루 10~50건 예약된다고 해도 숙소 전체 기준 30분~2시간에 한 번이고, 특정 날짜 키 하나로 보면 그보다 더 드물어요. 즉 검색이 재고 변경보다 훨씬 잦아서 짧은 TTL 캐시가 성립해요. 다만 마지막 방이 팔리는 순간(1 → 0)은 확률은 낮아도 영향이 커서, 예약 직전 재확인이 반드시 필요해요.

### 재고 변경 빈도 추정 (공개 통계 기반)

재고 변경 빈도를 직접 잰 공개 데이터는 없어서, 공식 산업 통계로 예약 건수를 역산했어요. 예약·취소 1건이 재고 변경 1건이에요.

| 입력값 | 값 | 출처 |
|---|---|---|
| 점유율 | 미국 2025년 연간 약 62% | STR/CoStar (산업 벤치마크, 공식 통계) |
| 평균 숙박일수 | 미국 2025년 1.61박 (1박 비중 73%) | SiteMinder 예약 트렌드 (자사 플랫폼 집계) |
| 예약 리드타임 | 미국 평균 약 31일, 전 세계 약 32일 | SiteMinder 예약 트렌드 |
| 취소율 | 20% 미만 | SiteMinder |

200실 호텔에 대입하면 (가정: 임박 4일에 예약의 40%가 몰린다고 봄)

| 단위 | 계산 | 변경 간격 |
|---|---|---|
| 호텔 전체 | 200 × 0.62 ÷ 1.61 ≈ 하루 77건 예약, 취소 포함 약 92건 | 약 15분에 1건 |
| (호텔, 날짜) 키 | 92 × 1.61 ÷ 30일 창 ≈ 하루 5건 | 약 5시간에 1건 |
| (호텔, 임박 날짜) 키 | 92 × 1.61 × 0.4 ÷ 4일 ≈ 하루 15건 | 약 1.6시간에 1건 |
| (호텔, 객실 타입, 날짜) 키 | 위를 객실 타입 수(3~5)로 나눔 | 더 드묾 |

재고를 밀어넣는 소프트웨어의 기술 상한도 확인했어요. PMS API가 property당 초당 5회(기술 파트너 초당 10회)까지 받는다는 공식 문서가 있어요(Cloudbeds). 하루로 환산하면 43만 건이라 실제 예약 건수(하루 100건 안팎)보다 세 자릿수 위예요. 즉 이 값은 "재고가 아무리 빨라도 이 이상은 못 바뀐다"는 천장일 뿐이고, 갱신 주기 근거로는 예약 건수 역산이 더 가까워요. 판매처 쪽(Booking.com Connectivity)도 갱신 API에 엔드포인트별 제한이 있다고만 공개하고 값은 계정 담당자에게 문의하라고 해요.

요금은 자동 RMS 기준 하루 12~40회이고, 한 번 갱신하면 여러 날짜 키가 함께 바뀌어요. 그래서 **키 하나로 보면 요금(30분~2시간)이 재고(1.6~5시간)보다 오히려 자주 바뀔 수 있어요.** 어느 쪽이든 변경 간격은 시간 단위이고, 검색은 인기 키에서 분당 단위라 캐시가 성립해요. 캐시 주기는 둘 중 짧은 쪽(30분)을 기준으로 잡아요. 이 계산은 평균이라 실제 값은 운영에서 불일치율로 측정해요.

### 공급사 API가 공개한 호출 한도

우리 스펙에는 요청당 50개 상한과 429의 존재만 있고 초당 한도는 없어요. 출발값을 잡으려고 실제 숙박 공급사 API가 공개한 한도를 확인했어요.

| 공급사 API | 공개된 한도 | 429 뒤 동작 |
|---|---|---|
| Booking.com Demand API | 샌드박스 분당 50회(≈초당 0.8회). 운영은 계정별로 담당자에게 문의 | 약 1분 차단 후 재개. 지수 백오프 권고 |
| Hotelbeds | Production plan 초당 4회 | 명시 없음 |
| Amadeus Self-Service | 100ms당 1회(초당 10회). 운영은 계약별 | 명시 없음 |
| Expedia Rapid | 초당 횟수가 아니라 요청 부하(숙소 ≤250, 객실 ≤8, 박수)로 제한. 트래픽에 따라 조정 | 명시 없음 |

공개된 값이 초당 0.8~10회 범위라, 공급사당 초당 1회에서 시작하면 그 아래쪽이에요. 429를 받았을 때 물러나는 시간의 상한(60초)은 Booking.com의 1분 차단에 맞췄어요. 어느 공급사도 운영 한도를 공개 문서에 고정하지 않고 계정·계약별로 정하므로, 설계에서도 한도는 설정값으로 두고 실측으로 맞춰요.

### 이용이 가장 적은 시간대

매핑 sync처럼 하루 1회 도는 작업은 소비자 접근이 적은 시간대에 두려고 조사했어요.

| 지표 | 값 | 출처 성격 |
|---|---|---|
| 호텔 예약이 가장 몰리는 시각 | 저녁 9시 (요일은 월요일) | 호텔 기술 업체(Net Affinity) 조사 인용. **예약 완료 기준**이고 검색량이 아니며, 국내 앱 자료가 아님 |
| 일반 웹 트래픽 피크 | 18~22시 | 웹 트래픽 분석 업체 자료. **모든 사이트 합산**, 숙박 전용 아님 |
| 일반 웹 트래픽 최저 | **03~05시** (00~06시 전반이 낮음) | 위와 같음. 숙박 검색을 잰 값이 아니라 일반 패턴에서 유추 |
| 국내 숙박 앱 시간대별 검색량 | **공개 자료 없음** (앱 사업자 비공개 데이터) | 이 값이 없어서 **일반 웹 트래픽 최저 시간대(03~05시)를 대신 기준으로 잡았다** |

**결정 근거 명시: 국내 숙박 앱의 시간대별 검색량은 공개 자료가 없어서, 일반 웹 트래픽(모든 사이트 합산)의 최저 시간대 03~05시를 대신 기준으로 잡고 한국 시간 04:00을 정했어요.** 숙소 검색만 따로 잰 값이 아니라 가정이고, 기본값 04:00을 설정값으로 둔 뒤 운영에서 연동 지표(검색 요청 카운터)로 시간대별 검색량을 직접 재서 정하는 게 맞아요. 당일 예약이 늘어나는 추세라 밤늦은 검색 꼬리가 길 수 있어요.

캐시에 대한 업계 관행도 같이 정리해요.
- 동적 데이터(재고·요금)는 검색마다 실시간 호출이 기본이에요. 캐시하더라도 짧은 TTL을 두고, 예약 직전에 다시 확인해요. 캐시된 결과가 이미 팔린 방을 보여주는 현상을 막기 위해서예요.
- 큰 OTA의 파트너 API는 요금·가용성에 캐시 계층이 여러 겹 있어서 가격 불일치를 "예상되는 상황"으로 취급하고, 예약 직전 가격 확인 단계를 따로 둬요.
- 요금·가용성 스냅샷을 매시간 갱신한 파일로 제공하는 도매 업체도 있어요. 대량 트래픽·패키지 용도이고, 이 경우에도 예약 시점 재확인은 필수예요.
- OTA 캐시 사례: 인기 지역·최근 조회 재고를 담는 캐시는 아주 짧은 TTL을 쓰고, 결제 직전 화면은 10분이 상한이라 그보다 오래됐으면 새로 호출해요. 일부 OTA는 가격 변동성과 검색 빈도에 따라 TTL을 자동으로 조절해요.
- 메타서치(구글 호텔 등)는 검색 가격과 예약 페이지 가격이 같아야 한다는 정책을 두고, 정확도가 낮은 파트너는 노출을 제한해요. 파트너가 가격에 만료 시각을 붙여 보내요.

## 5. 설계에 반영한 시사점

- 검색 대상은 보유 숙소 전체이고, 공급사가 다르면 같은 숙소여도 각각 별도 상품으로 다뤄요. 핵심은 같은 공급사 상품이 항상 같은 내부 식별자로 돌아오는 거예요.
- "여러 공급사 중 싼 쪽만 보여주기"는 기본 동작이 아니에요. 조건이 다른 상품을 가격만으로 비교하게 되기 때문이에요. 병합은 선택 항목으로 두고, 기본은 공급사별로 따로 노출해요. 병합을 하더라도 조식 같은 조건 차이를 함께 다뤄야 해요.
- 나중에 병합하거나 비교하려면 표준 모델에 조식 포함 여부, 세금 포함 총액, 숙소명이 남아 있어야 해요.
- 공급사 한 곳이 실패해도 나머지 결과로 응답해야 하고, B의 본문 코드 실패를 A의 HTTP 실패와 같은 실패로 다뤄야 해요.
- 정적 정보(숙소·객실 타입)는 DB에 저장하고 하루 1회 갱신해요. 동적 정보(재고·요금)는 저장하지 않고 검색마다 호출해요. 캐시는 선택 항목으로 두고, 하더라도 짧은 TTL과 예약 직전 재확인을 전제로 해요.

## 6. 참고 자료
- 재고 갱신 소프트웨어 상한: [Cloudbeds API FAQ (property당 초당 5회)](https://developers.cloudbeds.com/docs/faq), [Booking.com Connectivity APIs (엔드포인트별 제한, 값 비공개)](https://developers.booking.com/connectivity/docs)
- 공급사 API 호출 한도: [Booking.com Demand API, Rate limiting](https://developers.booking.com/demand/docs/development-guide/rate-limiting), [Hotelbeds, How to use Content API (Production 4 QPS)](https://developer.hotelbeds.com/documentation/hotels/content-api/how-use-content-api/), [Amadeus for Developers, Hotel APIs tutorial](https://developers.amadeus.com/self-service/apis-docs/guides/developer-guides/resources/hotels/), [Expedia Rapid, About the Shopping API](https://developers.expediagroup.com/rapid/lodging/shopping/about-shopping-api)
- 이용 시간대: [Priceline, best time to book a hotel (Net Affinity 조사 인용)](https://press.priceline.com/this-is-the-best-time-to-book-a-hotel/), [Loopex Digital, Busiest Hours Online](https://www.loopexdigital.com/blog/busiest-time-online-worldwide), [GrowTraffic, Peak Website Traffic Hours](https://growtraffic.co.uk/what-hours-are-peak-website-traffic-hours/)
- 점유율·숙박일수·리드타임: [CoStar/STR, U.S. hotel performance 2025](https://www.hotelmanagement.net/data-trends/costar-us-hotel-occupancy-revpar-down-yoy-2025), [SiteMinder, Hotel Booking Trends](https://www.siteminder.com/hotel-booking-trends/), [Hotel Management, SiteMinder 2025 트렌드](https://www.hotelmanagement.net/data-trends/siteminder-domestic-bookings-share-32-pps-2025)
- 요금 갱신 빈도: [RoomPriceGenie, Hotel Dynamic Pricing Guide](https://roompricegenie.com/hotel-dynamic-pricing-everything-you-need-to-know-in-2026/), [PriceLabs, Dynamic Pricing Software with Automatic Rate Adjustments](https://hello.pricelabs.co/blog/hotel-dynamic-pricing-software-with-automatic-rate-adjustments/), [SiteMinder, Hotel dynamic pricing](https://www.siteminder.com/r/hotel-dynamic-pricing/)
- 재고 전파(ARI 실시간 동기화): [Stayntouch, Hotel Channel Manager Automation](https://www.stayntouch.com/articles/hotel-channel-manager-automation-2026), [RateGain, Hotel Channel Manager API Guide](https://rategain.com/blog/hotel-channel-manager-api-use-cases-integration-guide-2026/), [software.travel, ARI 용어](https://www.software.travel/glossary/ari/)
- 정적·동적 데이터 분리와 캐시 관행: [Zentrumhub, Hotel Inventory API](https://www.zentrumhub.com/blog/hotel-inventory-api/), [AltexSoft, Hotelbeds API Integration](https://www.altexsoft.com/blog/hotelbeds-api-integration/)
- 스냅샷 API 사례: [Hotelbeds Cache API](https://developer.hotelbeds.com/documentation/hotels/cache-api/)
- 가격 불일치와 예약 전 확인: [Expedia Rapid API, Common error responses](https://developers.expediagroup.com/rapid/lodging/reference/error-responses), [Expedia Rapid API, About the Shopping API](https://developers.expediagroup.com/rapid/lodging/shopping/about-shopping-api)
- OTA 캐시 계층과 TTL: [AltexSoft, Caching Strategies in OTAs](https://www.altexsoft.com/blog/ota-caching-strategies/), [Lighthouse, Hotel metasearch explained](https://www.mylighthouse.com/resources/blog/what-is-metasearch)
- Look-to-Book 비율: [Hospitality Net, Hospitality's 5-digit Look-to-Book (2016, 경험 기반 칼럼)](https://www.hospitalitynet.org/opinion/4074433.html), [Netacea, Look-to-Book Ratio (용어 설명)](https://netacea.com/learn/look-to-book-ratio/), [Zentrumhub, Hotelbeds API Integration (일일 처리량, 제3자 인용·출처 미표기)](https://www.zentrumhub.com/hotelbeds-api-integration/), [OneClick, Hotel Booking API 가이드 (L2B 상한, 근거 미표기)](https://www.oneclickitsolution.com/blog/ultimate-guide-hotel-booking-api)
- 메타서치 가격 정확도 정책: [Google Hotel Center, Price Accuracy Policy](https://support.google.com/hotelprices/answer/6064419?hl=en), [Google Hotel Prices, Pricing overview](https://developers.google.com/hotels/hotel-prices/dev-guide/updating-prices)
