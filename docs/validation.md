# 검증 범위와 공개 snapshot 상태

공개 소스 검증과 과거 실제 생산 검증은 서로 다른 증거다. 이 문서는 2026-09-30 공개 snapshot의 새 검증 결과와 2026-09-15 내부 작업 환경의 실행 이력을 구분한다.

## 2026-09-30 공개 snapshot 검증

| 대상 | 상태 | 확인할 내용 |
| --- | --- | --- |
| Java 21 Maven reactor `verify` | PASS | 1,304 unit tests: 19/680/473/132, 실패·오류·skip 0; 네 모듈 Flower Check no findings |
| Node runner `npm ci --ignore-scripts` | PASS | 공개 lockfile 기반 의존성 설치, exit 0 |
| Node runner `npm test` | PASS / 선택 검사 1 SKIP | 56 통과, 실패 0; 실제 permission-profile integration은 명시적 opt-in 없어 미실행 |
| 로컬 Worker 설정 helper 검사 | PASS | `node --test tools/test/*.test.mjs`: 31 통과, 실패·skip 0 |
| 공개 기본 build | PASS | 위 reactor 패키징과 Spring Boot JAR 생성, exit 0 |
| native PostgreSQL profile | NOT_RUN | 공개 snapshot에서 별도 실행 필요 |
| native sandbox profile | NOT_RUN | Docker·도구·정확한 입력 artifact 준비 후 별도 실행 필요 |
| 실제 ChatGPT 로그인·Worker 격리 smoke | NOT_RUN | 사용자가 별도 전용 환경을 준비해야 함 |
| 실제 공개 checkout 생산 주문·사람 승인·출고 | NOT_RUN | 소스 build 성공과 별개인 후속 생산 검증 |

위 새 결과는 공개 파일만 담은 사본에서 Java 21.0.8과 Node 26.1.0으로 실제 실행했다.
Maven은 `mvnw.cmd -B -ntp verify`로 2026-09-30 19:47 KST에 완료됐다.
native profile과 실제 생산은 이번 소스 게시 검증에 포함하지 않았다.

게시 준비 중 처음의 복사 제외 규칙이 `verification` Java 패키지까지 제외하여 첫 빌드는 실패했다.
빠진 원본 소스 82개를 복원하고 실행 증거 디렉터리만 제외한 뒤 전체 reactor가 통과했다.
helper 검사의 첫 실행도 npm 의존성 설치가 완료되기 전에 시작해 실패했으며, 설치 완료 후
같은 31개 검사를 재실행해 모두 통과했다. 이 초기 실패를 성공 결과로 간주하지 않는다.

## 공개 GitHub 자동 검사

[Verify workflow](../.github/workflows/verify.yml)는 공개 checkout에서 Java 21,
Node 22와 Windows runner로 기본 Maven 검사, Node runner 검사와 helper 검사를 수행한다.
각 커밋의 실제 결과는 [GitHub Actions](https://github.com/flowerjvm/flower-agent-factory/actions/workflows/verify.yml)에서 확인한다.
native profile이나 실제 모델 생산은 이 workflow의 범위가 아니다.

[첫 공개 실행](https://github.com/flowerjvm/flower-agent-factory/actions/runs/36705528991)은
`MaintenanceAcceptanceGateTest` 6개에서 `unsafe acceptance directory`로 실패했다.
Windows runner의 임시 경로 별칭을 정상 fixture로 사용하지 않도록 JUnit 임시 디렉터리를
`toRealPath()`로 정규화했다. 제품의 alias/link 거부 조건과 main 소스는 변경하지 않았다.
로그가 실제 임시 경로를 출력하지 않으므로 별칭의 정확한 유형은 추론이며,
수정 후 로컬 acceptance gate 검사 9개는 실패·오류·skip 없이 통과했다.
첫 실패는 통과로 기록하지 않으며, 수정 커밋의 재실행 결과와 구분한다.

[두 번째 실행](https://github.com/flowerjvm/flower-agent-factory/actions/runs/36706020292)에서
앞선 acceptance 검사와 인프라 검사 473개는 통과했지만, host의 두 local-decision
테스트 클래스에서 같은 경로 조건으로 7개가 실패했다. 해당 JUnit fixture도
정규화했으며 CI의 `TEMP`/`TMP`는 runner 소유 임시 루트로 지정했다.
사람 승인·문서 읽기·권한 정책을 변경하거나 검사를 제외하지 않는다.
수정 후 두 host 클래스의 로컬 검사 21개는 실패·오류·skip 없이 통과했다.

[세 번째 실행](https://github.com/flowerjvm/flower-agent-factory/actions/runs/36706832160)은
job-level `env`에서 사용할 수 없는 `runner.temp` context 때문에 job을 시작하지 못했다.
임시 루트 설정을 [GitHub의 context 허용 범위](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#context-availability)에
맞는 step-level `env`로 이동했다. 이 실행은 코드 검사를 수행한 결과로 집계하지 않는다.

## 기본 검증 재현

저장소 루트에서 Java 21을 선택하고 Node.js 20 이상을 PATH에 둔 뒤 Windows에서는 다음을 실행한다. Maven unit의 `CodexCodingWorkerContractTest`도 실제 Node 프로세스로 합성 Worker를 실행하므로 Maven 검사 단계부터 Node가 필요하다.

```powershell
.\mvnw.cmd -B -ntp clean verify
Push-Location codex-worker-runner
npm ci --ignore-scripts
npm test
Pop-Location
node --test tools/test/codex-worker-local.test.mjs tools/test/codex-windows-sandbox-setup.test.mjs
```

POSIX 환경에서 Maven wrapper는 `./mvnw -B -ntp clean verify` 형태로 호출한다. 기본 소스 검증은 실제 모델 호출·생산 DB·사용자 인증을 성공했다는 증거가 아니다. opt-in 검사의 실행 또는 skip 여부는 별도로 기록한다.

## 추가 profile과 준비 조건

```powershell
.\mvnw.cmd -B -ntp -pl factory-infrastructure -am -Pnative-database-tests verify
.\mvnw.cmd -B -ntp -pl factory-infrastructure -am -Pnative-sandbox-tests verify
```

`native-database-tests`는 실행 가능한 Docker/Testcontainers 환경과 해당 PostgreSQL 이미지를 준비해야 한다. 실제 별도 DB 연결에서 CAS·동시성·원장 경계를 검사한다. 기존 사용자의 생산 DB를 기본 검사 대상으로 사용하지 않는다.

`native-sandbox-tests`는 실행 가능한 Docker Linux engine, 고정 이미지, provisioning 도구, exact dependency closure와 입력 artifact가 필요하다. 현재 provisioning은 PowerShell을 호출한다. 일부 Incident Application native 검사는 내부 역사적 handoff의 exact 조사 코어를 참조한다. 해당 snapshot은 이 공개 저장소에 포함하지 않으므로 별도 승인된 입력 준비 또는 검사 fixture 정비 없이는 전체 profile의 클린 checkout 성공을 주장할 수 없다. 필요한 입력이 없는 검사를 성공한 것처럼 기록하지 않는다.

공개 사본에서는 해당 native 조사 소스 경로를
`factory.incident.native.componentSourceDirectory`로, PowerShell 7 실행기를
`factory.incident.native.powershell`로 지정할 수 있다. standalone 모듈 스크립트에도
`-ComponentSourceDirectory`, `-MavenRepository` 옵션이 있다. 정확한 source/JAR 지문 검사는
유지하며, 경로 설정의 추가를 실제 native 검사 성공으로 기록하지 않는다.

실제 Worker 인증·permission-profile smoke도 별도 준비가 필요한 검사다. 일반 Node 테스트의 합성 인증·격리 계약 검사가 사용자의 실제 로그인과 OS 격리를 대신하지 않는다.

## 2026-09-15 내부 검증 이력

당시의 제한된 실제 데모 환경에서는 다음을 확인했다.

| 범위 | 당시 관측 결과 |
| --- | --- |
| 최종 Java reactor | 1,304 unit tests, 실패·오류·skip 0 |
| Flower Check | 4개 모듈, no findings |
| 같은 main 코드의 native PostgreSQL 검사 | 43개 통과 |
| 독립 readback 도구 | 18개 synthetic 그룹 통과 |
| 출고 BASIC/HISTORY 소비자 smoke | 75/75 통과 |
| 실제 생산 계보 | Worker 생성·검사 실패·수정·재검사·Component 인증·다른 라인의 소비·출고 확인 |

위 수치는 서로 다른 검사 집합이며 하나의 합산 품질 점수로 표현하지 않는다. 소비자 smoke에서는 실제 출고본의 한국어 입력·보고서·오류 처리, BASIC 비저장, HISTORY 정상 재실행 보존과 종료·정리를 확인했다. 제품의 웹 브라우저 시각 QA나 production readiness 전체의 증거로 확대하지 않는다.

공개 저장소에는 당시 원시 로그, DB dump, 운영자 identity, 승인 대화와 출고 snapshot을 넣지 않는다. 따라서 이 표는 내부 검증 이력의 범위 설명이며 외부 독자가 해당 생산 원장을 그대로 재실행하거나 독립적으로 감사할 수 있는 증거 bundle은 아니다.

## 무엇이 검증되지 않았는가

현재 소스 검증 결과는 TOS 생산, 범용 동적 Recipe 등록, Agent Runtime Host, Digital Organization 운영 제어, 다중 사용자 공개 서비스, 배포·상시 운영 또는 호스트 전원 장애 내구성을 증명하지 않는다. 새로운 제품·환경에는 해당 계약과 검사를 추가해야 한다.

[현재 제품](product-lines.md), [생산 공정](production-process.md), [로컬 준비](local-setup.md)를 함께 참조한다.
