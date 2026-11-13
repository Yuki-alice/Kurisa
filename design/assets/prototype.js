/* ==========================================================================
   Kurisa · interactive prototype runtime
   ========================================================================== */

const C = {};

const ROUTES = {
  library: { title: '书架', note: '点封面进入详情 · 点「更新」切 tab', render: () => libraryScreen() },
  updates: { title: '更新', note: '按日分组 · 逐章下载', render: () => updatesScreen() },
  history: { title: '历史', note: '进度条 + 续读', render: () => historyScreen() },
  browse: { title: '浏览', note: '源列表 · 开关启停', render: () => browseScreen() },
  more: { title: '更多', note: '设置列表', render: () => moreScreen() },
  detail: { title: '作品详情', note: '继续阅读 → 阅读器', render: (r) => detailScreen(r.id || 'w-nightflight'), path: (r) => 'detail/' + (r.id || 'w-nightflight') },
  reader: { title: '阅读器', note: '点画面显隐 chrome · 设置打开弹层', fixed: 'dark', render: () => readerScreen(), path: () => 'reader/w-nightflight/c12' },
  'reader-settings': { title: '阅读设置', note: '底部弹层 · 点遮罩关闭', fixed: 'dark', render: () => readerSettingsScreen(), path: () => 'reader/w-nightflight/c12/settings' },
  novel: { title: '小说阅读器', note: '宋体排版 · 划线', fixed: 'light', render: () => novelScreen(), path: () => 'reader/w-teahouse/c7' },
  search: { title: '搜索', note: '跨源聚合', render: () => searchScreen(), path: () => 'search?q=夜航' },
  downloads: { title: '下载', note: '波浪进度 · 暂停/继续', render: () => downloadsScreen(), path: () => 'downloads' },
  select: { title: '书架 · 多选', note: '浮动工具条', render: () => libraryScreen({ selected: true }), path: () => 'library/selection' },
};

const TABS = ['library', 'updates', 'history', 'browse', 'more'];
const JUMP = [
  ['library', '书架'],
  ['detail', '作品详情'],
  ['reader', '阅读器'],
  ['reader-settings', '阅读设置'],
  ['updates', '更新'],
  ['history', '历史'],
  ['browse', '浏览'],
  ['downloads', '下载'],
  ['search', '搜索'],
  ['novel', '小说'],
  ['more', '更多'],
  ['select', '多选态'],
];

const state = { stack: [{ type: 'library' }], theme: 'light' };

function boot() {
  C.screen = document.getElementById('screen');
  C.title = document.getElementById('captionTitle');
  C.note = document.getElementById('captionNote');
  C.route = document.getElementById('routeLabel');
  C.jump = document.getElementById('jumpList');
  C.themeSeg = document.getElementById('themeSeg');

  C.jump.innerHTML = JUMP.map(
    ([k, label], i) =>
      `<button data-jump="${k}"><span class="pj-no">${String(i + 1).padStart(2, '0')}</span>${label}</button>`
  ).join('');

  document.addEventListener('click', onClick);
  document.addEventListener('keydown', onKey);
  document.getElementById('resetBtn').addEventListener('click', () => {
    state.stack = [{ type: 'library' }];
    state.theme = 'light';
    C.themeSeg.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.t === 'light'));
    render('back');
  });
  C.themeSeg.addEventListener('click', (e) => {
    const b = e.target.closest('button[data-t]');
    if (!b) return;
    state.theme = b.dataset.t;
    C.themeSeg.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b));
    paintTheme();
  });

  render('in');
}

const current = () => state.stack[state.stack.length - 1];
const R = (r) => ROUTES[r.type] || ROUTES.library;

function render(dir) {
  const r = current();
  const def = R(r);
  C.screen.dataset.theme = def.fixed || state.theme;
  C.screen.innerHTML = def.render(r);
  C.screen.classList.remove('slide-in', 'slide-back');
  if (dir !== 'none') {
    void C.screen.offsetWidth;
    C.screen.classList.add(dir === 'back' ? 'slide-back' : 'slide-in');
  }
  C.title.textContent = def.title;
  C.note.textContent = def.note;
  C.route.textContent = '/' + (def.path ? def.path(r) : r.type);
  C.jump.querySelectorAll('button').forEach((b) =>
    b.classList.toggle('on', b.dataset.jump === r.type)
  );
}

function paintTheme() {
  C.screen.dataset.theme = R(current()).fixed || state.theme;
}

function goTab(k) {
  state.stack = [{ type: k }];
  render('back');
}

function onClick(e) {
  const jump = e.target.closest('[data-jump]');
  if (jump) {
    const k = jump.dataset.jump;
    if (TABS.includes(k)) return goTab(k);
    state.stack = [tabBefore(k), { type: k }];
    return render('in');
  }

  const tab = e.target.closest('[data-tab]');
  if (tab) return goTab(tab.dataset.tab);

  const go = e.target.closest('[data-go]');
  if (go) {
    const v = go.dataset.go;
    if (v === 'back' || v === 'close') return pop();
    if (v.startsWith('sheet:')) {
      state.stack.push({ type: v.slice(6) });
      return render('in');
    }
    if (v.includes(':')) {
      const [k, id] = v.split(':');
      state.stack.push({ type: k, id });
      return render('in');
    }
    if (TABS.includes(v)) return goTab(v);
    state.stack.push({ type: v });
    return render('in');
  }

  const canvas = e.target.closest('[data-reader-tap]');
  if (canvas) {
    canvas.closest('.screen').classList.toggle('chrome-off');
    return;
  }

  const chip = e.target.closest('.m3-chip');
  if (chip) {
    chip.classList.toggle('sel');
    return;
  }
  const tabBtn = e.target.closest('.m3-tabrow .m3-tab');
  if (tabBtn) {
    tabBtn.parentElement.querySelectorAll('.m3-tab').forEach((t) => t.classList.toggle('on', t === tabBtn));
    return;
  }
  const seg = e.target.closest('.m3-btngroup button');
  if (seg) {
    seg.parentElement.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b === seg));
    return;
  }
  const act = e.target.closest('.det-act');
  if (act) {
    act.parentElement.querySelectorAll('.det-act').forEach((a) => a.classList.toggle('on', a === act));
  }
}

function tabBefore(k) {
  if (k === 'detail' || k === 'search' || k === 'select') return { type: 'library' };
  if (k === 'reader' || k === 'reader-settings' || k === 'novel') return { type: 'library' };
  if (k === 'downloads') return { type: 'more' };
  return { type: 'library' };
}

function pop() {
  if (state.stack.length <= 1) return;
  state.stack.pop();
  render('back');
}

function onKey(e) {
  if (e.key === 'Escape') {
    const t = current().type;
    if (t === 'reader-settings') pop();
    return;
  }
  if (e.key === 'Backspace') {
    if (document.activeElement === document.body) pop();
  }
}

document.addEventListener('DOMContentLoaded', boot);
