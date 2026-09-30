package io.github.flowerjvm.product.incident;

/** Static same-origin UI; all user/product strings reach textContent, never HTML interpolation. */
final class WebPage {
    private WebPage() {}
    static final String HTML = """
            <!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>장애 조사 앱</title><link rel="stylesheet" href="/app.css"><script defer src="/app.js"></script></head>
            <body><main><h1 id="title">장애 조사 앱</h1><p id="mode">제품 설정을 읽고 있습니다.</p>
            <p>인증된 규칙 기반 조사 코어를 사용합니다. LLM 대화·자동 수정·배포 기능은 없습니다.</p>
            <form id="incident-form"><label for="incident">장애 JSON</label><textarea id="incident" rows="15" required spellcheck="false">{
              "incidentId": "INC-001", "service": "주문 API", "summary": "응답 지연 및 오류 증가",
              "observations": [
                {"evidenceId":"EV-1","source":"api-log","observedAt":"2026-09-12T12:00:00Z","kind":"log","message":"TIMEOUT"},
                {"evidenceId":"EV-2","source":"metrics","observedAt":"2026-09-12T12:01:00Z","kind":"metric","message":"5xx error rate elevated"}
              ]
            }</textarea><button type="submit">조사 실행</button></form><p id="status" role="status" aria-live="polite"></p>
            <section><h2>조사 보고서</h2><button id="download" disabled>Markdown 내려받기</button><pre id="report">조사를 실행하면 보고서가 표시됩니다.</pre></section>
            <section id="history-section" hidden><h2>조사 이력</h2><button id="refresh">이력 새로고침</button><ul id="history"></ul></section>
            <footer>로컬 데모 제품 · Factory 책임은 검사·승인·출고 인계까지</footer></main></body></html>
            """;
    static final String CSS = """
            :root{font-family:system-ui,sans-serif;color:#17232c;background:#f5f7f8}body{margin:0}main{max-width:900px;margin:32px auto;padding:28px;background:white;border:1px solid #dbe1e5;border-radius:10px}h1{margin-top:0}p{line-height:1.6}label{display:block;margin-bottom:8px;font-weight:600}textarea{width:100%;box-sizing:border-box;font:14px/1.5 monospace;border:1px solid #aab6bf;border-radius:5px;padding:12px}button{margin-top:12px;padding:10px 16px;border:1px solid #476576;border-radius:5px;color:#173343;background:#eef5f7;cursor:pointer}button:disabled{opacity:.5;cursor:default}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f7f9fa;border:1px solid #dde4e8;padding:18px;line-height:1.6}section{margin-top:28px}footer{margin-top:32px;color:#586971;font-size:13px}li{margin-bottom:8px}#status{min-height:1.6em}@media(max-width:650px){main{margin:0;padding:18px;border-radius:0}}
            """;
    static final String JS = """
            'use strict';
            let report = '', historyEnabled = false;
            const byId = id => document.getElementById(id);
            const showError = error => { byId('status').textContent = '요청 실패: ' + error.message; };
            async function request(path, options) {
              const response = await fetch(path, options); const data = await response.json();
              if (!response.ok) throw new Error(data.errorCode || 'APPLICATION_ERROR'); return data;
            }
            function display(envelope) { report = envelope.investigation.reportMarkdown; byId('report').textContent = report; byId('download').disabled = false; }
            async function refresh() {
              if (!historyEnabled) return;
              const data = await request('/api/history'); byId('history').replaceChildren();
              for (const item of data.items) {
                const li = document.createElement('li'), button = document.createElement('button');
                button.textContent = item.incidentId + ' · ' + item.service + ' · ' + item.summary;
                button.onclick = async () => { try { display(await request('/api/history/' + item.id)); byId('status').textContent = '저장된 조사 보고서입니다.'; } catch(error) { showError(error); } };
                li.append(button); byId('history').append(li);
              }
            }
            byId('incident-form').onsubmit = async event => {
              event.preventDefault(); byId('status').textContent = '조사 중...';
              try { display(await request('/api/investigations', {method:'POST',headers:{'Content-Type':'application/json; charset=utf-8'},body:byId('incident').value})); byId('status').textContent = historyEnabled ? '조사 완료 · 이력에 저장했습니다.' : '조사 완료 · 기본형은 이력을 저장하지 않습니다.'; await refresh(); }
              catch(error) { showError(error); }
            };
            byId('download').onclick = () => {
              const url = URL.createObjectURL(new Blob([report], {type:'text/markdown;charset=utf-8'}));
              const link = document.createElement('a'); link.href = url; link.download = 'investigation-report.md'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
            };
            byId('refresh').onclick = () => refresh().catch(showError);
            request('/api/config').then(async config => {
              byId('title').textContent = config.title; document.title = config.title; historyEnabled = config.historyEnabled;
              byId('mode').textContent = historyEnabled ? '이력형 · 최대 100건을 저장하고 다시 조회할 수 있습니다.' : '기본형 · 입력 → 조사 → 보고서 (저장 없음)';
              byId('history-section').hidden = !historyEnabled; await refresh();
            }).catch(showError);
            """;
}
