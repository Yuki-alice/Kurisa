/* ==========================================================================
   Kurisa · design spec board runtime
   ========================================================================== */
const $ = (id) => document.getElementById(id);
const T = window.MD3_TOKENS;

const lum = (hex) => {
  const c = hex.replace('#', '');
  const v = [0, 2, 4].map((i) => parseInt(c.slice(i, i + 2), 16) / 255);
  const l = v.map((x) => (x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4)));
  return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2];
};
const inkOn = (hex) => (lum(hex) > 0.5 ? '#1b1b1f' : '#fbf8fd');
const kebab = (s) => s.replace(/[A-Z]/g, (m) => '-' + m.toLowerCase());

/* ---------- 01 benchmark -------------------------------------------------- */
const BENCH = [
  ['页面骨架', '照搬', [
    '底部 5 目的地：书架 / 更新 / 历史 / 浏览 / 更多；顺序、图标语义与参考应用一致。',
    '书架：TopAppBar（标题 + 数量胶囊 + 搜索/筛选）→ 可滚动分类页签 → 自适应封面网格。',
    '详情：模糊封面背景 + 居中封面 + 居中标题/作者/状态 → 操作行 → 摘要 → 标签 → 章节列表。',
    '阅读器：透明顶栏 + 底栏图标 + 章节滑杆 + 页码指示；点击画面显隐 chrome。',
  ]],
  ['替换为 M3E 组件', '升级', [
    '底部导航 <code>NavigationBar</code> 80dp → <code>ShortNavigationBar</code> 64dp。',
    '分段选择 / 工具条 → connected <code>ButtonGroup</code>、<code>HorizontalFloatingToolbar</code>（多选态）。',
    '下载进度 → <code>LinearWavyProgressIndicator</code>；滑杆换成 M3E 4×44 手柄。',
    '主操作 FAB 用 56dp <code>SmallExtendedFloatingActionButton</code>；弹层统一顶部 28dp 圆角。',
  ]],
  ['视觉语言', '收敛', [
    '圆角：封面/卡片 12dp（<code>CornerMedium</code>），弹层 28dp，chip 8dp→全圆（选中态）。',
    '字阶：titleLarge 22 / titleSmall 14 / bodyMedium 14 / bodySmall 12 / labelMedium 12；标题用 600 字重作强调。',
    '颜色分配：surface 与 container 承担 70%，primary 约 12%（主操作、选中、链接），tertiary 8%（进度/已下载）。',
  ]],
  ['不改的东西', '保留', [
    '长按多选、区间选择、下拉刷新、章节筛选 Sheet、双击缩放、左右点按分区 —— 不重新发明。',
    '封面 3:4 比例（项目 <code>Tokens.coverAspect = 3f / 4f</code>）；网格间距 11dp、页边距 12dp。',
    '阅读器黑场与 primary 色 tint 的底栏图标；页码 <code>8 / 24</code> 使用等宽数字与描边。',
  ]],
];
function renderBench() {
  $('bench').innerHTML = BENCH.map(
    ([title, tag, items]) => `<div class="bench-card">
      <h3>${title}<span class="tag">${tag}</span></h3>
      <ul>${items.map((i) => `<li>${i}</li>`).join('')}</ul>
    </div>`
  ).join('');
}

/* ---------- 02 colors ----------------------------------------------------- */
const GROUPS = [
  ['primary', ['primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer', 'inversePrimary', 'primaryFixed', 'primaryFixedDim', 'onPrimaryFixed', 'onPrimaryFixedVariant']],
  ['secondary', ['secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer', 'secondaryFixed', 'secondaryFixedDim', 'onSecondaryFixed', 'onSecondaryFixedVariant']],
  ['tertiary', ['tertiary', 'onTertiary', 'tertiaryContainer', 'onTertiaryContainer', 'tertiaryFixed', 'tertiaryFixedDim', 'onTertiaryFixed', 'onTertiaryFixedVariant']],
  ['error', ['error', 'onError', 'errorContainer', 'onErrorContainer']],
  ['surface', ['background', 'onBackground', 'surface', 'onSurface', 'surfaceVariant', 'onSurfaceVariant', 'surfaceTint', 'inverseSurface', 'inverseOnSurface']],
  ['container', ['surfaceDim', 'surfaceBright', 'surfaceContainerLowest', 'surfaceContainerLow', 'surfaceContainer', 'surfaceContainerHigh', 'surfaceContainerHighest']],
  ['outline', ['outline', 'outlineVariant', 'scrim']],
];
function roleHTML(mode) {
  return GROUPS.map(([name, roles]) => {
    const cards = roles
      .map((r) => {
        const hex = T[r][mode];
        return `<div class="role">
          <div class="sw" style="background:${hex};color:${inkOn(hex)}">${kebab(r)}</div>
          <div class="rm"><span>${r}</span><code>${hex}</code></div>
        </div>`;
      })
      .join('');
    return `<div class="role-group">${name} · ${roles.length}</div><div class="roles">${cards}</div>`;
  }).join('');
}

/* ---------- 03 components ------------------------------------------------- */
function comp(title, meta, stage, note, dark) {
  return `<article class="comp">
    <div class="comp-head"><h3>${title}</h3><span>${meta}</span></div>
    <div class="comp-stage ${dark ? 'stretch' : ''}" data-theme="${dark ? 'dark' : 'light'}">${stage}</div>
    ${note ? `<div class="comp-note">${note}</div>` : ''}
  </article>`;
}
function renderComponents() {
  const nav = `<span class="stage-label">Flexible nav bar · 64dp · compact vertical items</span>
    <div style="width:100%;display:flex;justify-content:space-around;padding-top:6px">
      ${[['collections_bookmark', '书架', 1], ['update', '更新', 0], ['history', '历史', 0], ['explore', '浏览', 0], ['more_horiz', '更多', 0]]
        .map(
          ([ic, label, on]) => `<span style="display:flex;flex-direction:column;align-items:center;gap:4px;font-size:12px;letter-spacing:.4px;color:${on ? 'var(--md-secondary)' : 'var(--md-on-surface-variant)'}">
        <span style="width:56px;height:32px;border-radius:999px;display:grid;place-items:center;background:${on ? 'var(--md-secondary-container)' : 'transparent'};color:${on ? 'var(--md-on-secondary-container)' : 'inherit'}">${icon(ic, on ? 'fill' : '')}</span>${label}</span>`
        )
        .join('')}
    </div>`;

  const buttons = `<span class="stage-label">XS 32 · S 40 · M 56 · filled / tonal / outlined / text</span>
    <button class="m3-btn filled xs">XS 32</button>
    <button class="m3-btn filled">${icon('play_arrow', 'fill')}继续阅读</button>
    <button class="m3-btn tonal">收藏</button>
    <button class="m3-btn outlined">分享</button>
    <button class="m3-btn text">取消</button>
    <button class="m3-btn tonal m">M 56</button>
    <span style="width:100%"></span>
    <button class="m3-icon-btn filled">${icon('search')}</button>
    <button class="m3-icon-btn tonal">${icon('sort')}</button>
    <button class="m3-icon-btn outlined">${icon('download')}</button>
    <button class="m3-icon-btn">${icon('more_vert')}</button>`;

  const chips = `<span class="stage-label">FilterChip 8dp → 全圆 · 标准组 / 连接组</span>
    <button class="m3-chip sel">${icon('check')}全部</button>
    <button class="m3-chip">连载</button>
    <button class="m3-chip">完结</button>
    <button class="m3-chip assist">悬疑</button>
    <span style="width:100%"></span>
    <div class="m3-btngroup">
      <button>${icon('sort')}最新</button>
      <button>${icon('filter_list')}筛选</button>
      <button>${icon('download')}下载</button>
    </div>
    <div class="m3-btngroup connected">
      <button aria-pressed="false">${icon('view_list')}列表</button>
      <button class="on" aria-pressed="true">${icon('grid_view')}网格</button>
    </div>`;

  const list = `<span class="stage-label">Two-line row 72dp · Switch 52×32 · Badge error</span>
    <div style="width:100%;display:flex;align-items:center;gap:16px;padding:8px 0">
      <span class="m3-leading round" style="background:#3f5f9e;color:#fff">漫</span>
      <span style="flex:1;display:flex;flex-direction:column;gap:2px">
        <span style="font-size:16px;line-height:24px;letter-spacing:.5px">漫游图鉴</span>
        <span style="font-size:12px;color:var(--md-on-surface-variant)">v2.3.1 · 中文 · 漫画</span>
      </span>
      <span class="m3-switch on"></span>
    </div>
    <div style="width:100%;display:flex;align-items:center;gap:16px;padding:8px 0">
      <span class="m3-leading round" style="background:#2f6b4f;color:#fff">墨</span>
      <span style="flex:1;display:flex;flex-direction:column;gap:2px">
        <span style="font-size:16px;line-height:24px;letter-spacing:.5px">墨鱼小说</span>
        <span style="font-size:12px;color:var(--md-on-surface-variant)">v1.8.0 · 中文 · 小说</span>
      </span>
      <span class="m3-badge">2</span>
    </div>`;

  const progress = `<span class="stage-label">progress · wavy · slider</span>
    <div style="width:100%;display:flex;flex-direction:column;gap:14px">
      <div class="m3-progress"><i style="width:62%"></i></div>
      ${wavyBar(62)}
      <div style="display:flex;align-items:center;gap:14px">
        <span style="font-size:12px;color:var(--md-on-surface-variant)">亮度</span>
        <div class="m3-slider" style="--fill:38%"><div class="track"></div><div class="handle"></div></div>
        <span style="font-size:12px;font-variant-numeric:tabular-nums">38%</span>
      </div>
    </div>`;

  const fab = `<span class="stage-label">FAB 56 / 80 / 96 · e3 · FloatingToolbar 64dp e3</span>
    <div style="display:flex;gap:14px;align-items:center;width:100%">
      <button class="m3-fab">${icon('add')}</button>
      <button class="m3-fab md">${icon('search')}</button>
      <button class="m3-fab ext primary">${icon('play_arrow', 'fill')}开始阅读</button>
    </div>
    <div class="m3-toolbar" style="margin:6px 0 0;width:100%;justify-content:space-around">
      <button class="m3-icon-btn">${icon('download')}</button>
      <button class="m3-icon-btn on">${icon('label')}</button>
      <button class="m3-icon-btn">${icon('done_all')}</button>
      <button class="m3-icon-btn">${icon('delete')}</button>
      <button class="m3-icon-btn">${icon('more_horiz')}</button>
    </div>`;

  $('compGrid').innerHTML = [
    comp('底部导航', 'flexible · 64dp · 5 目的地', nav, '基线 80dp 已退役；指示器 56×32，选中图标 on-secondary-container，选中标签 secondary。', true),
    comp('按钮与图标按钮', 'XS 32 · S 40 · M 56 · L 96 · XL 136', buttons, '按压时圆角向方形 morph（8/8/12/16/16）；禁用态 on-surface 38% + 12% 容器；图标按钮 40dp，触控区 48dp。'),
    comp('Chips 与 ButtonGroup', 'chip 32dp · 组 40dp', chips, '选中换形不变色单一维度（secondary-container + 全圆）；标准组用间隙（一次性动作），连接组 2dp 仅用于单选/多选 toggle。'),
    comp('列表行与开关', '两行 72dp · switch 52×32', list, '行标题 body-large 16；开关手柄 16 关 / 24 开 / 按压 28；角标只用 error 系，不自创颜色。'),
    comp('进度与滑杆', '4dp / 16dp / 4×44', progress, '下载中用波浪进度；暂停退回线性。滑杆为 M3E 手柄造型。'),
    comp('FAB 与浮动工具条', '56/80/96 · 工具条 64dp', fab, 'FAB 默认 primary-container + elevation 3，按压无弹跳；工具条标准色 surface-container，选中项 secondary-container；docked 版全宽直角无阴影。'),
  ].join('');
}

/* ---------- 04 gallery ---------------------------------------------------- */
const SHOTS = [
  ['library', '书架', '主页', [
    '<b>5 目的地短导航</b>：书架 / 更新 / 历史 / 浏览 / 更多，与参考应用一致。',
    'TopAppBar 标题 + <b>数量胶囊 24</b> + 搜索 / 筛选；分类页签不带计数，避免拥挤。',
    '3:4 封面 12dp 圆角；未读角标在左上、继续阅读圆钮在右下、进度条压在封面底边。',
  ]],
  ['select', '书架 · 多选', '长按', [
    '长按进入选择态：顶栏换成「已选 3 项」，封面加 3dp primary 描边并压暗。',
    '底部升起浮动工具条（64dp 全胶囊、elevation 3、两侧 16dp 内缩），5 个动作。',
    'tab 行保留但不再可点，返回键退选择态 —— 与参考应用的长按语义一致。',
  ]],
  ['updates', '更新', '列表', [
    '按「今天 / 昨天」分组，组头带数量胶囊；每行右侧是逐章下载按钮。',
    '行高 72dp 两行：封面 46×61、标题 body-large 16、副标题带新章节圆点。',
    '顶栏「全部标为已读」用 done_all 图标，不做文字按钮。',
  ]],
  ['history', '历史', '列表', [
    '同一套行组件 + 3dp 进度条与百分比，阅读到哪一目了然。',
    '右侧播放按钮直接续读；底部提示仅保留 30 天记录。',
  ]],
  ['browse', '浏览 · 源', '扩展', [
    '源列表：40dp 圆形字母图标 + 名称 + 版本/语言/类型 + 开关。',
    '未签名源在顶部显示 <b>errorContainer 警告</b>，不折叠进二级页。',
    '仓库用 <b>填充卡片</b>：名称、源数量、同步时间与「检查更新」。',
  ]],
  ['detail', '作品详情', '决策页', [
    '模糊封面背景 + 24/12dp 双层渐变；封面 196×261 居中，12dp 圆角。',
    '标题用 emphasized headline-small 居中，作者行带 person 图标，状态行含来源 / 连载 / 类型。',
    '<b>5 个操作</b>等宽排列（继续阅读为 primary 态）；摘要 3 行 + 展开；标签为 AssistChip（描边）。',
    '章节行 56dp，当前章 primaryContainer 底；右下 56dp extended FAB「开始阅读」。',
  ]],
  ['reader', '阅读器', '沉浸', [
    '纯黑画布，页面 342dp 居中；顶栏透明渐变、底栏图标 on-surface-variant，选中项 secondary-container。',
    '章节导航：40dp 圆形前后键 + 44dp 胶囊（页码 · <b>4×44 滑杆</b> · 总页数）。',
    '页码 <code>8 / 24</code> 用等宽数字 + 描边；点击画面显隐 chrome（200ms slide / 150ms fade）。',
  ]],
  ['reader-settings', '阅读设置', '弹层', [
    'ModalBottomSheet：顶部 28dp 圆角 + 32×4 拖拽条，三页签（阅读/显示/操作）。',
    '阅读模式、翻页方向、缩放各一行 <b>FilterChip</b>（选中变全圆）。',
    '作用域「仅本书」用 primaryContainer chip；亮度滑杆 4×44 手柄，开关 52×32。',
  ]],
  ['novel', '小说阅读器', '排版', [
    '同一阅读器外壳，内容层换成宋体 16.5 / 30 的正文；页边距 24dp。',
    '顶部保留返回 / 搜索 / 更多，底部 4dp 线性进度 + 页码 + 4 个工具按钮。',
    '划线用 primary 10% 底，不引入额外颜色；下一章用 tonal 按钮收束。',
  ]],
  ['search', '搜索', '跨源', [
    '顶栏返回 + 48dp 搜索框（聚焦时显示光标）。',
    '最近 / 热门 chip；结果行给出来源徽标，跨源聚合的关键信息。',
    '结果复用列表行组件：封面 46×61 + 标题 + 作者/章节 + 来源。',
  ]],
  ['downloads', '下载', '离线', [
    '顶部摘要卡：仅 Wi-Fi、速度、存储与今日用量，进度条 4dp。',
    '进行中任务用 <b>LinearWavyProgressIndicator</b>；暂停退回普通线性进度。',
    '右侧图标按钮控制暂停/继续；行内给出页数与速度。',
  ]],
  ['more', '更多', '设置', [
    'Logo header：64dp 图标 + 名称 + 版本，居中，上下 34dp 留白；一行 56dp 一行 72dp。',
    '两个开关行（仅 Wi-Fi、更新通知）后分组：阅读 / 内容 / 其它。',
    '行内边距 24dp 水平、12dp 垂直；右侧 chevron，分组标题 primary 11.5/600。',
  ]],
  ['library-dark', '书架 · 深色', '深色', [
    'surface #1B1B1F，container #1F1F23；primary 切到 #B0C6FF。',
    '导航栏用 surfaceContainer，与内容形成一层 tonal 分隔。',
    '封面保持原色，仅加深阴影；不在深色下降低封面亮度。',
  ]],
];

function renderGallery() {
  $('gallery').innerHTML = SHOTS.map(
    ([key, title, tag, notes], i) => `<article class="shot">
      <div class="shot-head">
        <span class="shot-no">S${String(i + 1).padStart(2, '0')}</span>
        <span class="shot-title">${title}</span>
        <span class="shot-tag">${tag}</span>
      </div>
      <div class="device">
        <div class="device-screen" data-theme="${key.endsWith('-dark') || ['reader', 'reader-settings'].includes(key) ? 'dark' : 'light'}"
          ${key.endsWith('-dark') || ['reader', 'reader-settings'].includes(key) ? 'data-fixed-theme="dark"' : ''}
          ${key === 'novel' ? 'data-fixed-theme="light"' : ''}>${SCREENS[key].render()}</div>
        ${island}
      </div>
      <ul class="shot-notes">${notes.map((n) => `<li>${n}</li>`).join('')}</ul>
    </article>`
  ).join('');
}

/* ---------- 05 responsive: live adaptive demo ------------------------------- */
/* Same 5 destinations as the phone. Navigation transforms by container width:
   <600 ShortNavigationBar · 600–839 NavigationRail 80dp · >=840 WideNavigationRail
   240dp + supporting pane. Grid/margins follow the WSC table above. */
const AD_DESTS = [
  ['collections_bookmark', '书架', true],
  ['update', '更新', false],
  ['history', '历史', false],
  ['explore', '浏览', false],
  ['more_horiz', '更多', false],
];

function adClass(w) {
  if (w < 600)
    return ['Compact', 'ShortNavigationBar · 底部 64dp', '3 列', '16 / 8'];
  if (w < 840)
    return ['Medium', 'NavigationRail · 侧边 80dp', '4 列', '24 / 16'];
  if (w < 1050)
    return ['Expanded', 'WideNavigationRail · 240dp + 详情副窗格', '3 列', '24 / 16'];
  return ['Large', 'WideNavigationRail · 240dp + 详情副窗格', '4 列', '24 / 24 · ≤1040 居中'];
}

function renderDesk() {
  const w = BY_ID['w-nightflight'];
  const cells = WORKS.map(
    (it) => `<button class="ad-cell">
      <div class="cover">${coverSVG(it, { compact: true })}</div>
      <span class="t">${it.title}</span>
      <span class="s">第 ${it.read || 1} / ${it.chapters} ${it.kind === '小说' ? '章' : '话'}</span>
    </button>`
  ).join('');
  const rail = AD_DESTS.map(
    ([ic, label, on]) => `<button class="ad-rail-item ${on ? 'on' : ''}">
      <span class="ind">${icon(ic)}</span><span>${label}</span>
    </button>`
  ).join('');
  const wide = AD_DESTS.map(
    ([ic, label, on]) => `<button class="ad-wr-item ${on ? 'on' : ''}">${icon(ic)}<span>${label}</span></button>`
  ).join('');
  const bar = AD_DESTS.map(
    ([ic, label, on], i) => `<button class="m3-nav-item ${on ? 'on' : ''}">
      <span class="nav-ind">${icon(ic)}</span><span>${label}</span>
      ${i === 1 ? '<i class="nav-dot"></i>' : ''}
    </button>`
  ).join('');
  const chapters = CHAPTERS.slice(0, 5)
    .map(
      (c) => `<button class="ch ${c.read ? 'read' : ''}">
        <span class="truncate">${c.title}</span>
        ${c.new ? '<span class="m3-badge new">NEW</span>' : ''}
      </button>`
    )
    .join('');

  $('deskStage').innerHTML = `
    <div class="ad-controls">
      <div class="ad-presets" id="adPresets">
        <button data-w="390">390 · 手机</button>
        <button data-w="700">700 · 平板竖</button>
        <button data-w="950" class="on">950 · 平板横</button>
        <button data-w="1100">1100 · 桌面</button>
      </div>
      <label class="ad-slider">窗口宽度
        <input type="range" id="adRange" min="390" max="1100" step="10" value="950">
      </label>
      <span class="ad-badge" id="adBadge"></span>
    </div>
    <div class="ad-viewport">
      <div class="ad-frame" id="adFrame" data-theme="light">
        <div class="ad-shell">
          <nav class="ad-rail" aria-label="medium navigation">${rail}</nav>
          <nav class="ad-widerail" aria-label="expanded navigation">
            <div class="ad-wr-brand"><span class="mark">K</span>Kurisa</div>
            ${wide}
            <div class="ad-wr-foot">
              <button class="ad-wr-item">${icon('tune')}<span>偏好</span></button>
            </div>
          </nav>
          <section class="ad-main">
            <header class="ad-topbar">
              <h2>书架 <span class="count-pill">24</span></h2>
              <span class="ad-search">${icon('search')}搜索作品、作者或标签<span class="ad-kbd">⌘K</span></span>
              <button class="m3-icon-btn">${icon('add')}</button>
            </header>
            <div class="m3-tabrow">
              ${['全部', '更新', '连载', '完结', '本地']
                .map((t, i) => `<button class="m3-tab ${i === 0 ? 'on' : ''}">${t}</button>`)
                .join('')}
            </div>
            <div class="ad-grid">${cells}</div>
          </section>
          <div class="ad-drag" role="separator" aria-orientation="vertical"></div>
          <aside class="ad-side">
            <div class="side-cover">${coverSVG(w, { compact: true })}</div>
            <h3>${w.title}</h3>
            <div class="meta">${w.author}</div>
            <div class="meta">${w.source} · ${w.status} · ${w.chapters} 话</div>
            <div class="acts">
              <button class="m3-btn filled" style="flex:1">${icon('play_arrow', 'fill')}继续阅读</button>
              <button class="m3-icon-btn outlined">${icon('bookmark_add')}</button>
            </div>
            <p class="desc">${w.desc}</p>
            ${chapters}
          </aside>
          <nav class="m3-navbar" aria-label="compact navigation">${bar}</nav>
        </div>
      </div>
    </div>`;

  const frame = $('adFrame');
  const range = $('adRange');
  const badge = $('adBadge');
  const presets = $('adPresets');
  const paint = () => {
    const px = Number(range.value);
    frame.style.width = px + 'px';
    const [cls, nav, cols, mg] = adClass(px);
    badge.textContent = `${cls} · ${px}dp · 网格 ${cols} · 边距 ${mg} · ${nav}`;
    presets.querySelectorAll('button').forEach((b) =>
      b.classList.toggle('on', Number(b.dataset.w) === px)
    );
  };
  range.addEventListener('input', paint);
  presets.addEventListener('click', (e) => {
    const b = e.target.closest('button[data-w]');
    if (!b) return;
    range.value = b.dataset.w;
    paint();
  });
  paint();
}

/* ---------- theme --------------------------------------------------------- */
function setTheme(mode) {
  document.querySelectorAll('#themeSwitch button').forEach((b) =>
    b.classList.toggle('on', b.dataset.themeSet === mode)
  );
  $('roleGridLight').hidden = mode !== 'light';
  $('roleGridDark').hidden = mode !== 'dark';
  document.querySelectorAll('.gallery .device-screen').forEach((d) => {
    if (d.dataset.fixedTheme) return;
    d.dataset.theme = mode;
  });
  const desk = document.querySelector('#deskStage [data-theme]');
  if (desk) desk.dataset.theme = mode;
}

/* ---------- boot ---------------------------------------------------------- */
renderBench();
$('roleGridLight').innerHTML = roleHTML('light');
$('roleGridDark').innerHTML = roleHTML('dark');
renderComponents();
renderGallery();
renderDesk();
document.querySelectorAll('#themeSwitch button').forEach((b) =>
  b.addEventListener('click', () => setTheme(b.dataset.themeSet))
);
