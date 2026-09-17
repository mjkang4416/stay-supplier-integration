---
name: run-local
description: 로컬에서 MySQL(Docker)·Mock Supplier·애플리케이션을 순서대로 띄우고, 처음이면 매핑 갱신 잡을 한 번 돌린다
---

# 로컬 실행

터미널 세 개를 쓴다. 명령은 모두 저장소 루트에서 실행한다.

1. MySQL + Redis
   ```bash
   docker compose up -d
   docker compose ps    # stay-supplier-mysql, stay-supplier-redis 가 healthy 인지 확인
   ```
   3306이 사용 중이면 `MYSQL_PORT=3307 docker compose up -d`, 애플리케이션은 `DB_PORT=3307`로 실행한다.
2. Mock Supplier (별도 터미널, 포트 9090)
   ```bash
   ./gradlew :mock-supplier:bootRun
   curl -s http://localhost:9090/a/v1/hotels | head -c 200
   ```
3. 매핑 갱신 잡 1회 (처음 띄울 때, 또는 DB를 초기화했을 때)
   ```bash
   ./gradlew :bootRun --args='--spring.profiles.active=sync'
   ```
   웹 서버 없이 뜨고 공급사 숙소 목록을 DB에 넣은 뒤 종료한다. 종료 코드 0이면 전부 성공, 1이면 공급사 하나 이상 실패다.
4. 애플리케이션 (포트 8080)
   ```bash
   ./gradlew :bootRun
   ```
   8080이 사용 중이면 `./gradlew :bootRun --args='--server.port=8081'`. 기동 직후 갱신 잡이 오늘~+30일 요금·재고를 Redis에 채운다(로그 `cache refresh supplier=A hotels=2 …`, 약 10초).

주의: `bootRun` 앞의 `:`를 빼면 Mock 모듈의 bootRun까지 같이 실행된다.

확인: `http://localhost:8080/swagger-ui.html`, 검색은 `test-search-api` 스킬.
