# 주문부터 출고까지의 생산 공정

Factory는 요구사항에 맞는 제품을 만들고, 독립 검사와 정확한 승인을 거쳐 출고 증거를 인계한다. 제품을 만드는 작업자와 제품의 출시 여부를 판단하는 검사·승인 경계를 분리한다.

## 공정에서 남기는 사실

| 공정 | 수행 내용 | 남는 산출물 또는 원장 |
| --- | --- | --- |
| 주문 접수 | 요청자·tenant·제품라인·기한·중복 요청을 검증 | BuildSession, 요구사항, 등록 접수 Action |
| 설계와 작업지시 | 제품 규격·catalog·입력 부품·빌드 정책을 고정 | Blueprint, WorkOrder, 정확한 입력 참조 |
| 생산 | AI 작업자에게 생성·수정을 요청하거나 기존 모듈을 조립 | WorkerRun 또는 라인별 작업 intent, 후보 artifact, BOM |
| 독립 검사 | Factory 기준으로 후보나 완제품을 실행·검사 | VerificationRun, 검사 결과와 증거 |
| 사람 검토 | 검사된 정확한 제품을 승인 대상에 결박 | DecisionPoint와 Decision |
| 인증·출고 | 현재 권한·상태·hash·승인·효과 소유자를 재검증 | 인증·출고 intent, 성공 ActionRun, release manifest |
| 인계 | 검증된 출고물을 새 폴더로 export하고 bytes를 검사 | payload, provenance, 인계 index |

이 표는 이해를 위한 논리적 공정이다. 각 ProductLine의 실제 Flow Step 수와 동일하다는 뜻은 아니다. 예를 들어 Incident Application의 durable Step은 `BUILD_APPLICATION`, `VERIFY_WHOLE_APPLICATION`, `REVIEW_AND_RELEASE_APPLICATION` 세 개다.

## 두 종류의 생산 설비

Agent Pack 라인은 실제 Coding Worker의 설계·생성·수정 루프를 사용한다. Worker가 자체 테스트에 성공했다고 보고해도 출고할 수 없다. Factory가 소유한 별도 업무/API 검사와 기술 검사를 통과해야 한다. 실패하면 해당 후보와 실패 증거에 결박된 수정 작업지시를 발행하고 새 후보를 다시 검사한다.

Incident Application의 현재 BASIC/HISTORY 주문은 인증된 기존 코어와 catalog 모듈을 조립한다. Factory가 입력 부품을 해석하고 요구사항·설계·모듈 선택을 고정한 후, 제한된 Docker 환경에서 컴파일한다. 별도 전체 제품 검사는 실제 HTTP 입력, 코어 연결, 결과·보고서, 변형별 저장 동작을 확인한다. 부품 검사가 통과했다는 사실만으로 앱의 통합 검사를 면제하지 않는다.

## Flower와 Action Runtime의 역할

Flower는 공정 진행과 durable 복구를 조직한다. 장시간 모델 호출·Docker 프로세스·HTTP 응답을 Worker tick에서 기다리지 않는다. Flow가 원장에 기록된 진행을 관찰하고, 별도 제한된 실행 레인이 작업을 처리한다.

Action Runtime은 등록된 효과의 입력 검증, 정책·권한, 중복 통제, 필요한 승인, 실행 직전 검증과 실행 기록을 담당한다. 원장은 어느 Action·Worker·intent가 효과를 소유하는지 연결한다. callback, signal, Future와 checkpoint는 전달·복구 수단이며 제품 출고 권한의 정본으로 사용하지 않는다.

변경 가능한 상태는 version CAS로 전진시킨다. 재시작 후에는 저장된 사실과 효과 소유자를 읽어 수렴한다. 외부 효과의 상태가 불확실하면 무조건 재실행하지 않고 조사할 수 있는 상태로 남긴다. 이 구조를 모든 외부 호출의 exactly-once 보장으로 확대하지 않는다.

## 정확한 제품에 대한 승인

사람 승인은 검사된 후보·제품 hash와 해당 검토 요청에 결박된다. 다른 후보, 다른 주문 또는 과거 Component의 승인을 새 완제품의 승인으로 사용할 수 없다. Component 인증과 완제품 출고 승인도 별개다.

현재 Incident Application에는 만료된 검토 창의 정식 one-shot 갱신 경로가 있다. 기존 주문·기한·후보·검사 bytes와 이전 검토 기록을 보존하고, 동일 검사 대상의 새 검토 요청을 만든다. 창 갱신 자체는 출고 승인이 아니다. 후속 승인은 새 요청에 현재 시각으로 기록한다.

출고 직전에는 Component 인증, 제품 검사, 현재 Decision, intent와 canonical 성공 Action의 연결을 재검증한다. `RELEASED` 상태 필드 하나만으로 조회·인계 권한을 주지 않는다.

## provenance가 설명하는 것

Provenance는 어떤 요구사항과 부품을 사용해 어떤 bytes를 만들었고, 어떤 검사를 통과했으며, 어떤 정확한 승인을 거쳐 출고했는지 연결한다. BOM은 제품에 들어간 구성 요소를, hash는 특정 bytes를 식별한다. hash가 있다는 사실만으로 신뢰할 수 있는 검사·승인이나 현재 인증 상태가 증명되지는 않는다.

읽기 전용 인계 도구는 이미 승인·출고된 graph를 검증해 허용된 artifact만 export한다. 제품 실행, 새 승인, DB 상태 수정 또는 배포를 수행하지 않는다. 인계 index와 payload hash 확인은 파일 무결성 검사이며 별도의 새 업무 검사로 세지 않는다.

## Factory의 책임이 끝나는 지점

Factory의 성공 조건은 검증·승인된 제품과 출고 manifest·provenance를 인계하는 것이다. 이후 환경 선택, secret binding, 설치·배포, rollout, 상시 실행·모니터링, 장애 대응과 업그레이드·rollback은 상위 Digital Organization의 Operations Plane이 담당한다.

`RELEASED`는 `DEPLOYED`, `RUNNING`, `HEALTHY`와 다른 사실이다. Factory 내부 복구 pump는 진행 중 생산 주문을 수렴시키며 출고된 제품의 운영 제어기로 동작하지 않는다.
