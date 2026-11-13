/* ==========================================================================
   Kurisa · design prototype — screens
   Structure follows the reference reader: 5-tab shell, adaptive cover grid,
   blurred info header, chapter list, immersive reader, settings list.
   ========================================================================== */

const icon = (name, cls) => `<span class="md-icon ${cls || ''}">${name}</span>`;

function statusBar(onDark) {
  return `<div class="statusbar ${onDark ? 'on-dark' : ''}">
    <span class="tnum">9:41</span>
    <span class="sb-right">
      <svg viewBox="0 0 18 12" fill="currentColor"><rect x="0" y="7" width="3" height="5" rx="1"/><rect x="5" y="4.5" width="3" height="7.5" rx="1"/><rect x="10" y="2" width="3" height="10" rx="1"/><rect x="15" y="0" width="3" height="12" rx="1" opacity=".35"/></svg>
      <svg viewBox="0 0 18 12" fill="currentColor"><path d="M9 11.2 6.4 8.4a3.7 3.7 0 0 1 5.2 0L9 11.2Zm-4.3-4.6a7.3 7.3 0 0 1 8.6 0l1.4-1.5a9.3 9.3 0 0 0-11.4 0l1.4 1.5ZM9 1.3C5.6 1.3 2.5 2.5.3 4.5L1.7 6A10.4 10.4 0 0 1 9 3.4c2.8 0 5.4 1 7.3 2.6l1.4-1.5A12.4 12.4 0 0 0 9 1.3Z"/></svg>
      <svg viewBox="0 0 26 12" fill="none"><rect x=".5" y=".5" width="21" height="11" rx="3.5" stroke="currentColor" opacity=".45"/><rect x="2" y="2" width="15" height="8" rx="2" fill="currentColor"/><path d="M23.5 4v4c1.2-.3 1.8-1 1.8-2s-.6-1.7-1.8-2Z" fill="currentColor" opacity=".5"/></svg>
    </span>
  </div>`;
}
const island = `<div class="device-island"></div>`;
const homeBar = (onDark) => `<div class="home-indicator ${onDark ? 'on-dark' : ''}"></div>`;

/* ---------- MD3E wavy determinate progress -------------------------------- */
function wavyBar(pct) {
  const W = 300, AMP = 5, P = 30;
  let d = 'M0 8';
  for (let i = 0; i < W / P; i++) {
    const x = i * P;
    d += ` Q ${x + P / 4} ${8 - AMP} ${x + P / 2} 8 Q ${x + (3 * P) / 4} ${8 + AMP} ${x + P} 8`;
  }
  const id = 'wv' + Math.round(pct);
  return `<svg class="m3-wavy" viewBox="0 0 ${W} 16" preserveAspectRatio="none">
    <clipPath id="${id}"><rect x="0" y="0" width="${((W * pct) / 100).toFixed(1)}" height="16"/></clipPath>
    <path class="wv-track" d="${d}"/>
    <path class="wv-fill" d="${d}" clip-path="url(#${id})"/>
  </svg>`;
}

/* ---------- shell pieces -------------------------------------------------- */
function navBar(active) {
  const items = [
    ['library', '书架', 'collections_bookmark'],
    ['updates', '更新', 'update'],
    ['history', '历史', 'history'],
    ['browse', '浏览', 'explore'],
    ['more', '更多', 'more_horiz'],
  ];
  return `<nav class="m3-navbar">${items
    .map(
      ([k, label, ic]) => `<button class="m3-nav-item ${active === k ? 'on' : ''}" data-tab="${k}">
      <span class="nav-ind">${icon(ic)}</span><span>${label}</span>
      ${k === 'updates' ? '<i class="nav-dot"></i>' : ''}
    </button>`
    )
    .join('')}</nav>`;
}

function cover(work, opts) {
  const o = opts || {};
  const pct = progressPct(work);
  return `<div class="cover">
    ${coverSVG(work, { compact: o.compact !== false })}
    ${work.unread ? `<span class="lib-new">新 ${work.unread}</span>` : ''}
    ${o.selected ? `<span class="c-check">${icon('check_circle', 'fill s20')}</span>` : ''}
    ${o.resume ? `<span class="lib-resume">${icon('play_arrow', 'fill s18')}</span>` : ''}
    ${pct > 0 && pct < 100 ? `<div class="c-progress"><i style="width:${pct}%"></i></div>` : ''}
  </div>`;
}

/* ==========================================================================
   LIBRARY
   ========================================================================== */
function libraryScreen(opts) {
  const o = opts || {};
  const tabs = [
    ['全部', '', true],
    ['更新', ''],
    ['连载', ''],
    ['完结', ''],
    ['本地', ''],
  ];
  const items = SHELF.slice(0, 9)
    .map(
      (w) => `<button class="lib-item ${o.selected ? 'sel' : ''}" data-go="detail:${w.id}">
      ${cover(w, { resume: w.id === 'w-nightflight', selected: o.selected })}
      <span class="lib-title">${w.title}</span>
      <span class="lib-sub">第 ${w.read || 1} / ${w.chapters} ${w.kind === '小说' ? '章' : '话'}</span>
    </button>`
    )
    .join('');

  const topbar = `<header class="m3-topbar">
    <h1 class="m3-topbar-title">书架 <span class="count-pill">24</span></h1>
    <button class="m3-icon-btn">${icon('search')}</button>
    <button class="m3-icon-btn">${icon('tune')}</button>
  </header>`;

  const selTopbar = `<header class="sel-topbar">
    <button class="m3-icon-btn">${icon('close')}</button>
    <span class="sel-title">已选 3 项</span>
    <button class="m3-icon-btn">${icon('select_all')}</button>
    <button class="m3-icon-btn">${icon('check')}</button>
  </header>`;

  return `<div class="screen lib">
    ${statusBar(o.dark)}
    <div class="appbar-wrap">
      ${o.selected ? selTopbar : topbar}
      <div class="m3-tabrow">
        ${tabs
          .map(
            ([t, n, on]) => `<button class="m3-tab ${on ? 'on' : ''}">${t}${n ? `<span class="tab-num">${n}</span>` : ''}</button>`
          )
          .join('')}
      </div>
    </div>
    <div class="scroll">
      <div class="lib-grid">
        ${items}
        <div class="lib-end">${icon('done_all', 's18')} 已显示筛选后的 24 本</div>
      </div>
    </div>
    ${
      o.selected
        ? `<div class="sel-toolbar">
      <button class="sel-act">${icon('download')}下载</button>
      <button class="sel-act">${icon('label')}分类</button>
      <button class="sel-act">${icon('done_all')}已读</button>
      <button class="sel-act">${icon('delete')}删除</button>
      <button class="sel-act">${icon('more_horiz')}更多</button>
    </div>`
        : ''
    }
    ${navBar('library')}
    ${homeBar(o.dark)}
  </div>`;
}

/* ==========================================================================
   UPDATES
   ========================================================================== */
function updatesScreen() {
  const groups = [
    ['今天', [
      ['w-nightflight', '第 14 话 · 灯塔以南', '今天 09:20'],
      ['w-glassmask', '第 42 话 · 台词之外', '今天 11:02'],
      ['w-thaw', '第 8 话 · 融雪', '今天 08:15'],
    ]],
    ['昨天', [
      ['w-stardust', '第 61 话 · 轨道的另一边', '昨天 21:40'],
      ['w-dive', '第 9 话 · 4800 米', '昨天 18:02'],
      ['w-teahouse', '第 7 章 · 代笔人的第七封信', '昨天 12:30'],
    ]],
  ];
  return `<div class="screen">
    ${statusBar()}
    <div class="appbar-wrap">
      <header class="m3-topbar">
        <h1 class="m3-topbar-title">更新 <span class="count-pill">6</span></h1>
        <button class="m3-icon-btn">${icon('done_all')}</button>
        <button class="m3-icon-btn">${icon('more_vert')}</button>
      </header>
    </div>
    <div class="scroll">
      ${groups
        .map(
          ([label, rows]) => `<div class="sec-label">${label}<span class="num">${rows.length}</span></div>
        ${rows
          .map(([id, ch, time]) => {
            const w = BY_ID[id];
            return `<button class="list-row" data-go="detail:${id}">
              <span class="list-cover">${coverSVG(w)}</span>
              <span class="list-main">
                <span class="list-title">${w.title}</span>
                <span class="list-sub"><i class="new-dot"></i>${ch} · ${time}</span>
              </span>
              <span class="row-btn">${icon('download')}</span>
            </button>`;
          })
          .join('')}`
        )
        .join('')}
      <div style="height:12px"></div>
    </div>
    ${navBar('updates')}
    ${homeBar()}
  </div>`;
}

/* ==========================================================================
   HISTORY
   ========================================================================== */
function historyScreen() {
  const rows = [
    ['w-nightflight', '第 12 话 · 云上的城市', 82, '今天 12:04'],
    ['w-teahouse', '第 7 章 · 代笔人的第七封信', 34, '今天 09:12'],
    ['w-stardust', '第 61 话 · 轨道的另一边', 12, '昨天 22:30'],
    ['w-snowcountry', '第 19 章 · 第三节车厢', 56, '昨天 20:11'],
    ['w-dive', '第 8 话 · 信标', 45, '11 月 11 日'],
    ['w-rainmail', '第 4 章 · 海盐味的信纸', 100, '11 月 10 日'],
  ];
  return `<div class="screen">
    ${statusBar()}
    <div class="appbar-wrap">
      <header class="m3-topbar">
        <h1 class="m3-topbar-title">历史</h1>
        <button class="m3-icon-btn">${icon('delete_sweep')}</button>
        <button class="m3-icon-btn">${icon('more_vert')}</button>
      </header>
    </div>
    <div class="scroll">
      ${rows
        .map(([id, ch, pct, time]) => {
          const w = BY_ID[id];
          return `<button class="list-row" data-go="detail:${id}">
          <span class="list-cover">${coverSVG(w)}</span>
          <span class="list-main">
            <span class="list-title">${w.title}</span>
            <span class="list-sub">${ch} · ${time}</span>
            <span class="list-progress"><span class="m3-progress"><i style="width:${pct}%"></i></span><span class="pct">${pct}%</span></span>
          </span>
          <span class="row-btn">${icon('play_arrow')}</span>
        </button>`;
        })
        .join('')}
      <div class="lib-end" style="padding:18px 0 26px">${icon('history', 's18')} 仅保留最近 30 天记录</div>
    </div>
    ${navBar('history')}
    ${homeBar()}
  </div>`;
}

/* ==========================================================================
   BROWSE (sources)
   ========================================================================== */
function browseScreen() {
  return `<div class="screen">
    ${statusBar()}
    <div class="appbar-wrap">
      <header class="m3-topbar">
        <h1 class="m3-topbar-title">浏览</h1>
        <button class="m3-icon-btn">${icon('search')}</button>
        <button class="m3-icon-btn">${icon('add')}</button>
      </header>
      <div class="m3-tabrow">
        <button class="m3-tab on">源<span class="tab-num">5</span></button>
        <button class="m3-tab">仓库<span class="tab-num">2</span></button>
        <button class="m3-tab">迁移</button>
      </div>
    </div>
    <div class="scroll">
      <div class="notice">
        ${icon('gpp_maybe')}
        <span>浮世绘源未通过签名校验，来源为社区索引。仅在你信任作者时启用。</span>
      </div>
      <div class="sec-label">已安装 <span class="num">4</span></div>
      ${SOURCES.filter((s) => !s.builtin)
        .map(
          (s, i) => `<button class="src-row" data-go="detail:w-nightflight">
          <span class="src-icon c${s.color}">${s.letter}</span>
          <span class="src-main">
            <span class="src-name">${s.name}</span>
            <span class="src-meta">v${s.ver} · 中文 · ${s.kinds}</span>
          </span>
          <span class="src-state">
            <span class="m3-switch ${s.on ? 'on' : ''}"></span>
          </span>
        </button>`
        )
        .join('')}
      <div class="sec-label">内置 <span class="num">1</span></div>
      <div class="src-row">
        <span class="src-icon c4">${icon('folder_open', 's20')}</span>
        <span class="src-main">
          <span class="src-name">本地文件</span>
          <span class="src-meta">CBZ · EPUB · TXT · 3 本</span>
        </span>
        <span class="src-hint">3 本</span>
      </div>
      <div class="sec-label" style="padding-top:22px">仓库</div>
      <div class="repo-card">
        <div class="rc-title">${icon('inventory_2', 's20')} kurisa-registry</div>
        <div class="rc-sub">官方索引 · 12 个源 · 已启用自动更新</div>
        <div class="rc-foot">
          <span class="rc-upd">2 小时前同步</span>
          <button class="m3-btn text">检查更新</button>
        </div>
      </div>
      <div class="repo-card">
        <div class="rc-title">${icon('public', 's20')} 社区索引</div>
        <div class="rc-sub">第三方仓库 · 4 个源 · 需手动确认签名</div>
        <div class="rc-foot">
          <span class="rc-upd">3 天前同步</span>
          <button class="m3-btn text">管理</button>
        </div>
      </div>
      <div style="height:16px"></div>
    </div>
    ${navBar('browse')}
    ${homeBar()}
  </div>`;
}

/* ==========================================================================
   DETAIL
   ========================================================================== */
function detailScreen(workId) {
  const w = BY_ID[workId] || BY_ID['w-nightflight'];
  const acts = [
    ['play_arrow', '继续阅读', true],
    ['bookmark_add', '收藏'],
    ['download', '下载'],
    ['share', '分享'],
    ['more_horiz', '更多'],
  ];
  return `<div class="screen det">
    ${statusBar(true)}
    <div class="scroll det-scroll">
      <div class="det-hero">
        <div class="det-hero-bg">${coverSVG(w, { title: false })}</div>
        <div class="det-hero-fade"></div>
        <div class="det-topbar">
          <button class="m3-icon-btn on-media" data-go="back">${icon('arrow_back')}</button>
          <span class="spacer"></span>
          <button class="m3-icon-btn on-media">${icon('share')}</button>
          <button class="m3-icon-btn on-media">${icon('bookmark_border')}</button>
        </div>
        <div class="det-cover">${coverSVG(w)}</div>
      </div>

      <div class="det-info">
        <h1 class="det-title">${w.title}</h1>
        <div class="det-author">${icon('person')}${w.author}</div>
        <div class="det-meta">
          <span>${w.source}</span><i class="dot"></i><span>${w.status}</span><i class="dot"></i><span>${w.kind}</span>
        </div>
      </div>

      <div class="det-actions">
        ${acts
          .map(
            ([ic, label, on]) => `<button class="det-act ${on ? 'on' : ''}">${icon(ic)}${label}</button>`
          )
          .join('')}
      </div>

      <div class="det-desc">${w.desc}</div>
      <button class="det-expand">展开简介 ${icon('expand_more', 's18')}</button>

      <div class="det-tags">
        ${w.tags.map((t) => `<span class="m3-chip assist">${t}</span>`).join('')}
        <span class="m3-chip assist">${w.kind}</span>
      </div>

      <div class="det-chap-head">
        <span class="ch-title">章节</span>
        <span class="ch-count">${w.chapters} ${w.kind === '小说' ? '章' : '话'} · 已读 6</span>
        <button class="m3-icon-btn s32">${icon('filter_list', 's20')}</button>
        <button class="m3-icon-btn s32">${icon('sort', 's20')}</button>
      </div>

      ${CHAPTERS.map(
        (c) => `<button class="chapter-row ${c.read ? 'read' : ''} ${c.current ? 'cur' : ''}" data-go="reader:${w.id}">
          <span class="ch-main">
            <span class="ch-name">${c.title}</span>
            <span class="ch-sub">${c.date}${c.new ? ' · <b style="color:var(--md-primary);font-weight:600">NEW</b>' : ''}</span>
          </span>
          <span class="ch-dl ${c.dl === 'done' ? 'done' : c.dl === 'queued' ? 'wait' : ''}">
            ${c.dl === 'done' ? icon('check_circle', 'fill') : c.dl === 'queued' ? icon('schedule') : icon('download', 's20')}
          </span>
        </button>`
      ).join('')}
      <div style="height:100px"></div>
    </div>
    <button class="m3-fab ext primary det-fab" data-go="reader:${w.id}">${icon('play_arrow', 'fill')}开始阅读</button>
    ${homeBar(true)}
  </div>`;
}

/* ==========================================================================
   READER (comic)
   ========================================================================== */
function readerScreen() {
  const w = BY_ID['w-nightflight'];
  const page = 8, total = 24, pct = 30;
  return `<div class="screen reader">
    ${statusBar(true)}
    <div class="reader-page-wrap" data-reader-tap>
      <div class="reader-page">${comicPageSVG(page)}</div>
    </div>
    <div class="page-indicator tnum">${page} / ${total}</div>
    <div class="reader-chrome">
      <div class="reader-top">
        <button class="m3-icon-btn" style="color:#fff" data-go="back">${icon('arrow_back')}</button>
        <div class="reader-topbar-text">
          <span class="reader-title">${w.title}</span>
          <span class="reader-sub">第 12 话 · 云上的城市</span>
        </div>
        <button class="m3-icon-btn" style="color:var(--md-primary-fixed)">${icon('bookmark', 'fill')}</button>
      </div>
      <div class="reader-bottom">
        <div class="chap-nav">
          <button class="nav-round">${icon('chevron_left')}</button>
          <div class="chap-pill">
            <span class="cur tnum">${page}</span>
            <div class="m3-slider" style="--fill:${pct}%"><div class="track"></div><div class="handle"></div></div>
            <span class="total tnum">${total}</span>
          </div>
          <button class="nav-round">${icon('chevron_right')}</button>
        </div>
        <div class="reader-tools">
          <button class="reader-tool">${icon('toc')}</button>
          <button class="reader-tool">${icon('bookmark_border')}</button>
          <button class="reader-tool">${icon('crop_free')}</button>
          <button class="reader-tool on">${icon('brightness_6')}</button>
          <button class="reader-tool" data-go="sheet:reader-settings">${icon('tune')}</button>
        </div>
      </div>
    </div>
    ${homeBar(true)}
  </div>`;
}

/* ==========================================================================
   READER SETTINGS (sheet)
   ========================================================================== */
function readerSettingsSheet() {
  return `<div class="scrim" data-go="close"></div>
  <div class="m3-sheet" style="max-height:84%;overflow-y:auto;padding-bottom:34px">
    <div class="handle"></div>
    <div class="rs-hero">
      <h2 class="m3-sheet-title" style="padding:0;flex:1">阅读设置</h2>
      <span class="m3-chip scope-chip">${icon('menu_book', 's16')}仅本书</span>
    </div>
    <div class="m3-tabrow rs-tabs">
      <button class="m3-tab on">阅读</button>
      <button class="m3-tab">显示</button>
      <button class="m3-tab">操作</button>
    </div>
    <div class="rs-body">
      <div class="rs-group">
        <div class="rs-label">阅读模式</div>
        <div class="chip-scroll" style="padding-left:24px;padding-right:24px">
          <button class="m3-chip sel">${icon('auto_stories')}单页</button>
          <button class="m3-chip">${icon('view_day')}长条</button>
          <button class="m3-chip">${icon('chrome_reader_mode')}双页</button>
        </div>
      </div>
      <div class="rs-group">
        <div class="rs-label">翻页方向</div>
        <div class="chip-scroll" style="padding-left:24px;padding-right:24px">
          <button class="m3-chip">左 → 右</button>
          <button class="m3-chip sel">右 → 左</button>
          <button class="m3-chip">纵向</button>
        </div>
      </div>
      <div class="rs-group">
        <div class="rs-label">图片缩放</div>
        <div class="chip-scroll" style="padding-left:24px;padding-right:24px">
          <button class="m3-chip sel">适应</button>
          <button class="m3-chip">适宽</button>
          <button class="m3-chip">适高</button>
          <button class="m3-chip">原图</button>
        </div>
      </div>
      <div class="rs-row">
        <span class="rs-name">亮度</span>
        <div class="m3-slider" style="--fill:24%"><div class="track"></div><div class="handle"></div></div>
        <span class="rs-val">−22%</span>
      </div>
      <div class="rs-row">
        <span class="rs-name">自动裁边<br><span style="font-size:11.5px;color:var(--md-on-surface-variant)">移除扫描白边并写回缓存</span></span>
        <span class="m3-switch on"></span>
      </div>
      <div class="rs-row">
        <span class="rs-name">翻页闪烁<br><span style="font-size:11.5px;color:var(--md-on-surface-variant)">长条模式下每 4 页闪一次</span></span>
        <span class="m3-switch"></span>
      </div>
    </div>
  </div>`;
}

function readerSettingsScreen() {
  return `<div class="screen reader">
    ${statusBar(true)}
    <div class="reader-page-wrap">
      <div class="reader-page">${comicPageSVG(8)}</div>
    </div>
    ${readerSettingsSheet()}
    ${homeBar(true)}
  </div>`;
}

/* ==========================================================================
   NOVEL READER
   ========================================================================== */
function novelScreen() {
  const w = BY_ID['w-teahouse'];
  return `<div class="screen novel">
    ${statusBar()}
    <div class="novel-top">
      <button class="m3-icon-btn" data-go="back">${icon('arrow_back')}</button>
      <span class="nt">${w.title}</span>
      <button class="m3-icon-btn">${icon('search')}</button>
      <button class="m3-icon-btn">${icon('more_vert')}</button>
    </div>
    <div class="novel-scroll">
      <div class="novel-head">
        <div class="novel-ch">第七章</div>
        <h1 class="novel-h1">代笔人的第七封信</h1>
        <div class="novel-meta">林间雪 · 3,240 字 · 约 9 分钟</div>
      </div>
      <div class="novel-body">
        <p>梅雨落在镰仓的那天，文具店门口的铜铃响了三下。我放下手里的墨条，看见一位穿藏青外套的老人站在檐下，伞尖滴着水，像一支被遗忘的笔。</p>
        <p>「我想写一封信。」他说，「给一个已经收不到信的人。」</p>
        <p>代笔这件事，我做过七年。替人写求婚、辞职、道歉、表白，甚至替人写下一句「我从来不曾怪你」。可这一封，我在草稿纸上写了整整两个下午，都写不成第一行。</p>
        <p class="hl">他说，他妻子生前最讨厌别人替她做决定，包括替她难过。</p>
        <p>于是我换了一张纸。不再写「节哀」，也不写「保重」。我写：今年的山茶开得比去年早，你种在窗下的那一株，第一朵是白的。我把花晒干，夹在这封信里。</p>
        <p>老人读到一半，把信纸举到窗边，好像要透过它看看天空。他说：「她要是收到，大概会嫌我的字丑。」</p>
        <p>我说：「这封信不用好看。」</p>
        <p>雨停的时候，他把伞留在了店里。伞柄上刻着两个几乎磨平的字，我看了很久才认出来，是一个人的名字。</p>
      </div>
      <div class="novel-foot">
        <span class="nf">第七章 · 完</span>
        <button class="m3-btn tonal">下一章 ${icon('arrow_forward')}</button>
      </div>
    </div>
    <div class="novel-hud">
      <div class="m3-progress"><i style="width:34%"></i></div>
      <div class="novel-hud-row">
        <span class="pg">7 / 20</span>
        <div class="novel-tools">
          <button class="m3-icon-btn s32">${icon('toc', 's20')}</button>
          <button class="m3-icon-btn s32">${icon('format_size', 's20')}</button>
          <button class="m3-icon-btn s32">${icon('bookmark_border', 's20')}</button>
          <button class="m3-icon-btn s32">${icon('tune', 's20')}</button>
        </div>
      </div>
    </div>
    ${homeBar()}
  </div>`;
}

/* ==========================================================================
   SEARCH
   ========================================================================== */
function searchScreen() {
  const results = [BY_ID['w-nightflight'], BY_ID['w-teahouse'], BY_ID['w-stardust'], BY_ID['w-snowcountry']];
  return `<div class="screen">
    ${statusBar()}
    <div class="search-head">
      <button class="m3-icon-btn" data-go="back">${icon('arrow_back')}</button>
      <div class="m3-searchfield">
        ${icon('search')}
        <span class="q">夜航<span class="caret"></span></span>
        <button class="m3-icon-btn s32">${icon('close', 's20')}</button>
      </div>
    </div>
    <div class="scroll">
      <div class="search-body">
        <div class="sec-label" style="padding-left:0;padding-right:0">最近搜索
          <span class="spacer"></span>
          <button class="m3-btn text" style="height:32px">清除</button>
        </div>
        <div class="hist-chips">
          ${RECENT_QUERIES.map((q, i) => `<button class="m3-chip assist ${i === 0 ? 'sel' : ''}">${q}</button>`).join('')}
        </div>
        <div class="sec-label" style="padding-left:0;padding-right:0;padding-top:16px">热门</div>
        <div class="hist-chips">
          ${HOT_QUERIES.map((q) => `<button class="m3-chip assist">${q}</button>`).join('')}
        </div>
      </div>
      <div class="sec-label">全部来源 · 4 个结果</div>
      ${results
        .map(
          (w) => `<button class="res-row" data-go="detail:${w.id}">
        <span class="list-cover">${coverSVG(w)}</span>
        <span class="res-main">
          <span class="res-title">${w.title}</span>
          <span class="res-meta">${w.author} · ${w.chapters} ${w.kind === '小说' ? '章' : '话'}</span>
          <span class="res-src">${icon('public')}${w.source}</span>
        </span>
        <span class="row-btn">${icon('chevron_right')}</span>
      </button>`
        )
        .join('')}
      <div style="height:16px"></div>
    </div>
    ${homeBar()}
  </div>`;
}

/* ==========================================================================
   DOWNLOADS
   ========================================================================== */
function downloadsScreen() {
  return `<div class="screen">
    ${statusBar()}
    <div class="appbar-wrap">
      <header class="m3-topbar">
        <button class="m3-icon-btn" data-go="back">${icon('arrow_back')}</button>
        <h1 class="m3-topbar-title" style="margin-left:0">下载</h1>
        <button class="m3-icon-btn">${icon('pause_circle')}</button>
        <button class="m3-icon-btn">${icon('more_vert')}</button>
      </header>
      <div class="m3-tabrow">
        <button class="m3-tab on">队列<span class="tab-num">3</span></button>
        <button class="m3-tab">已完成<span class="tab-num">34</span></button>
        <button class="m3-tab">失败<span class="tab-num">1</span></button>
      </div>
    </div>
    <div class="scroll">
      <div class="dl-summary">
        <div class="dl-sum-top">
          ${icon('wifi', 's18')}<span>仅 Wi-Fi 下载</span>
          <span class="spacer"></span>
          <span class="speed">3.5 MB/s</span>
        </div>
        <div class="m3-progress"><i style="width:15%"></i></div>
        <div class="dl-sum-foot"><span>存储 1.2 GB / 8 GB</span><span>今日 412 MB</span></div>
      </div>
      ${DOWNLOADS.map((d) => {
        const w = BY_ID[d.work];
        const pct = Math.round((d.done / d.total) * 100);
        return `<div class="dl-card">
          <span class="list-cover">${coverSVG(w)}</span>
          <span class="dl-main">
            <span class="dl-top"><span class="dl-name">${w.title}</span><span class="dl-size">${d.size}</span></span>
            <span class="dl-ch">${d.chapter}</span>
            <span class="dl-prog">
              ${
                d.status === 'running'
                  ? wavyBar(pct)
                  : `<span class="m3-progress"><i style="width:${pct}%;opacity:.45"></i></span>`
              }
              <span class="pct">${d.status === 'paused' ? '已暂停' : pct + '%'}</span>
            </span>
            <span class="dl-state">${d.status === 'running' ? `${d.done}/${d.total} 页 · ${d.speed}` : d.status === 'paused' ? `${d.done}/${d.total} 页 · 等待恢复` : '排队中'}</span>
          </span>
          <button class="m3-icon-btn">${icon(d.status === 'paused' ? 'play_arrow' : 'pause')}</button>
        </div>`;
      }).join('')}
      <div style="height:16px"></div>
    </div>
    ${homeBar()}
  </div>`;
}

/* ==========================================================================
   MORE
   ========================================================================== */
function moreScreen() {
  return `<div class="screen">
    ${statusBar()}
    <div class="scroll" style="padding-top:0">
      <div class="more-logo">
        <span class="app-mark">K</span>
        <span class="app-name">Kurisa</span>
        <span class="app-ver">0.5.0 · 本地优先的阅读器</span>
      </div>
      <div class="divider" style="margin:0 0 4px"></div>
      <div class="more-row"><span class="mr-text"><span class="mr-title">仅 Wi-Fi 下载</span></span><span class="m3-switch on"></span></div>
      <div class="more-row"><span class="mr-text"><span class="mr-title">更新时通知</span><span class="mr-sub">书架作品有新章节时提醒</span></span><span class="m3-switch on"></span></div>
      <div class="divider" style="margin:8px 0"></div>
      <div class="more-sec">阅读</div>
      ${moreRow('auto_stories', '阅读设置', '默认 RTL · 单页 · 适应')}
      ${moreRow('bookmark', '书签与划线', '12 条')}
      ${moreRow('record_voice_over', '朗读', '系统 TTS')}
      <div class="more-sec">内容</div>
      ${moreRow('extension', '源管理', '4 / 5 已启用', '浏览')}
      ${moreRow('download', '下载与存储', '1.2 GB 已用')}
      ${moreRow('folder_open', '本地书', '3 本 · 24.6 MB')}
      <div class="more-sec">其它</div>
      ${moreRow('palette', '外观', '跟随系统')}
      ${moreRow('backup', '备份与恢复', '导出 .kurisa 归档')}
      ${moreRow('info', '关于 Kurisa', '开源许可 · 0.5.0')}
      <div class="more-foot">Kurisa 不提供任何内容，仅提供源的安装与阅读工具<br>所有数据保存在本机</div>
    </div>
    ${navBar('more')}
    ${homeBar()}
  </div>`;
}

function moreRow(ic, title, sub, trail) {
  return `<button class="more-row">
    ${icon(ic, 'lead')}
    <span class="mr-text"><span class="mr-title">${title}</span>${sub ? `<span class="mr-sub">${sub}</span>` : ''}</span>
    ${trail ? `<span class="trail">${trail}</span>` : ''}
    ${icon('chevron_right', 's20')}
  </button>`;
}

/* ---------- registry ------------------------------------------------------ */
const SCREENS = {
  library: { title: '书架', render: () => libraryScreen() },
  select: { title: '书架 · 多选', render: () => libraryScreen({ selected: true }) },
  updates: { title: '更新', render: () => updatesScreen() },
  history: { title: '历史', render: () => historyScreen() },
  browse: { title: '浏览 · 源', render: () => browseScreen() },
  detail: { title: '作品详情', render: () => detailScreen('w-nightflight') },
  reader: { title: '阅读器', render: () => readerScreen() },
  'reader-settings': { title: '阅读设置', render: () => readerSettingsScreen() },
  novel: { title: '小说阅读器', render: () => novelScreen() },
  search: { title: '搜索', render: () => searchScreen() },
  downloads: { title: '下载', render: () => downloadsScreen() },
  more: { title: '更多', render: () => moreScreen() },
  'library-dark': { title: '书架 · 深色', render: () => libraryScreen({ dark: true }) },
};
