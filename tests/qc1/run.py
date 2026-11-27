# 조립QC 시험 묶음 — 실제 사례로 읽기 · 대조 규칙 확인 (코드 고칠 때마다 배포 전에 실행)
#   python3 tests/qc1/run.py        → 모두 통과하면 0, 하나라도 틀리면 1
import json, os, sys
from playwright.sync_api import sync_playwright
D = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.abspath(os.path.join(D, '..', '..'))
cases = json.load(open(os.path.join(D, 'cases.json'), encoding='utf-8'))
parts = json.load(open(os.path.join(D, 'parts.json'), encoding='utf-8'))
JS = r"""
async ([cases, parts]) => {
  const T = window.__q1t; T.qcpSet(parts);
  const get = (o, p) => p.split('.').reduce((a, k) => a == null ? a : a[k], o);
  const out = [];
  for (const c of cases) {
    let got, err = '';
    try {
      if (c.fn === 'expect') got = T.q1Expect(c.P);
      else if (c.fn === 'expect2') got = T.q1Expect2(c.P);
      else if (c.fn === 'match') got = T.c1Match(c.text, c.E || T.q1Expect(c.P), c.E2 || (c.P ? T.q1Expect2(c.P) : null));
      else if (c.fn === 'doclike') got = { v: !!T.q1DocLike(c.Q || { order: c.order || '' }, c.text) };
      else if (c.fn === 'mbvga') { const Q = T.q1MbVga({}, c.text); got = { mb: Q.mb ? Q.mb.br : null, vga: Q.vga ? Q.vga.fam : null }; }
      else if (c.fn === 'mbv') got = { v: T.mbv(c.text).join(',') };
      else if (c.fn === 'vendor') got = { v: T.vendor(c.text) };
      else if (c.fn === 'vgashort') got = { v: T.vgaShort(c.text) };
      else if (c.fn === 'parse') got = T.qcParse(c.text);
    } catch (e) { err = String(e && e.message || e); }
    const bad = [];
    if (err) bad.push('오류 ' + err);
    else for (const [p, v] of Object.entries(c.expect || {})) { const g = get(got, p); if (JSON.stringify(g === undefined ? null : g) !== JSON.stringify(v)) bad.push(p + ' = ' + JSON.stringify(g) + ' (기대 ' + JSON.stringify(v) + ')'); }
    out.push({ id: c.id, ok: !bad.length, bad });
  }
  return out;
}"""
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page(); errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.goto('file://' + os.path.join(ROOT, 'index.html') + '?qtest=1'); pg.wait_for_function('window.__q1t', timeout=15000)
    R = pg.evaluate(JS, [cases, parts]); b.close()
fail = [r for r in R if not r['ok']]
for r in R: print(('✓ ' if r['ok'] else '✗ ') + r['id'] + ('' if r['ok'] else '  → ' + ' / '.join(r['bad'])))
print(f"\n{len(R) - len(fail)}/{len(R)} 통과" + (f" · 페이지 오류 {errs[:2]}" if errs else ''))
sys.exit(1 if fail or errs else 0)
