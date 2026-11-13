/* ==========================================================================
   Kurisa · MD3E prototype — data layer
   Synthetic catalog + procedural cover art + procedural comic pages.
   No external assets, no third-party content: everything is generated.
   ========================================================================== */

/* ---------- deterministic randomness -------------------------------------- */
function hashStr(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}
function rng(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function pick(arr, r) {
  return arr[Math.floor(r() * arr.length) % arr.length];
}

/* ---------- cover palettes ------------------------------------------------ */
/* 12 duotone identities: paper-ink, dusk, jade, sienna, indigo, moss, rose,
   graphite, sand, petrol, plum, oxblood */
const COVER_RAMPS = [
  ['#F2E4D2', '#D9A066', '#7C3E1D', '#2A140A'],
  ['#E8EEF3', '#8AA9C4', '#3B5C7A', '#111C26'],
  ['#E3EFE7', '#7FB39B', '#2F6E5C', '#0D1F1B'],
  ['#F6E7E4', '#E2A090', '#A6553F', '#2B0E08'],
  ['#E6E5F2', '#9C97C9', '#4B4584', '#14122B'],
  ['#EAF0DF', '#A9BE7C', '#5A6F35', '#1A2110'],
  ['#F7E9EF', '#D6A0B5', '#8D4967', '#2A0F1A'],
  ['#EDEDEF', '#ABADB4', '#55585F', '#141517'],
  ['#F3EBDD', '#D5C09A', '#8E7343', '#241B0D'],
  ['#E2EEF0', '#7FB4BC', '#2F6973', '#0B1E22'],
  ['#F0E6F1', '#B58ABE', '#6B467B', '#1D1023'],
  ['#F3E3E1', '#C98A82', '#8A3D36', '#220B08'],
];

/* ---------- procedural cover ---------------------------------------------- */
function coverSVG(work, opts) {
  const o = Object.assign({ w: 300, h: 400, title: true }, opts || {});
  const seed = hashStr(work.id + work.title);
  const r = rng(seed);
  const ramp = COVER_RAMPS[(work.ramp != null ? work.ramp : seed) % COVER_RAMPS.length];
  const motif = work.motif != null ? work.motif : Math.floor(r() * 6);
  const [paper, soft, mid, ink] = ramp;
  const dark = ramp[3];
  // luminance of the mid tone decides the typography color
  const lum = luminance(mid);
  const fg = lum > 0.55 ? '#1A120C' : '#FFF9F2';
  const fgSoft = lum > 0.55 ? 'rgba(26,18,12,.62)' : 'rgba(255,249,242,.74)';
  const gid = 'g' + seed.toString(36);

  let art = '';
  if (motif === 0) {
    // rising sun over horizon
    art = `
      <rect width="300" height="400" fill="${mid}"/>
      <circle cx="150" cy="168" r="86" fill="${soft}"/>
      <circle cx="150" cy="168" r="86" fill="none" stroke="${paper}" stroke-width="1.5" opacity=".55"/>
      <path d="M0 268 H300 V400 H0Z" fill="${ink}"/>
      <path d="M0 268 H300" stroke="${paper}" stroke-width="2" opacity=".5"/>
      <path d="M28 268 V400 M86 268 V400 M214 268 V400 M272 268 V400" stroke="${paper}" stroke-width="1" opacity=".18"/>`;
  } else if (motif === 1) {
    // layered waves
    art = `
      <rect width="300" height="400" fill="${paper}"/>
      <path d="M0 210 C60 168 96 246 150 214 C204 182 246 244 300 206 V400 H0Z" fill="${soft}"/>
      <path d="M0 258 C58 222 100 292 152 262 C208 230 250 286 300 254 V400 H0Z" fill="${mid}" opacity=".92"/>
      <path d="M0 318 C66 288 104 344 156 316 C210 288 252 338 300 312 V400 H0Z" fill="${ink}"/>
      <circle cx="236" cy="92" r="34" fill="${mid}" opacity=".9"/>`;
  } else if (motif === 2) {
    // halftone dot field (pattern keeps the DOM light)
    art = `<defs><pattern id="dot-${gid}" width="16" height="16" patternUnits="userSpaceOnUse">
        <circle cx="4" cy="4" r="3.2" fill="${mid}" opacity=".55"/>
        <circle cx="12" cy="12" r="2.2" fill="${mid}" opacity=".4"/>
      </pattern></defs>
      <rect width="300" height="400" fill="${paper}"/>
      <rect width="300" height="400" fill="url(#dot-${gid})"/>
      <circle cx="150" cy="196" r="78" fill="${ink}" opacity=".92"/>
      <circle cx="150" cy="196" r="78" fill="none" stroke="${paper}" stroke-width="1" opacity=".35"/>`;
  } else if (motif === 3) {
    // giant glyph / type as image
    const glyph = work.glyph || work.title.slice(0, 1);
    art = `
      <rect width="300" height="400" fill="${mid}"/>
      <text x="150" y="268" text-anchor="middle" font-family="Songti SC, Noto Serif SC, serif"
        font-size="248" font-weight="700" fill="${paper}" opacity=".9">${glyph}</text>
      <rect x="0" y="0" width="300" height="400" fill="none" stroke="${ink}" stroke-width="8" opacity=".14"/>`;
  } else if (motif === 4) {
    // diagonal light bands
    art = `
      <rect width="300" height="400" fill="${ink}"/>
      <g transform="rotate(-24 150 200)">
        <rect x="-160" y="60" width="620" height="34" fill="${mid}" opacity=".85"/>
        <rect x="-160" y="130" width="620" height="14" fill="${soft}" opacity=".7"/>
        <rect x="-160" y="196" width="620" height="58" fill="${mid}" opacity=".45"/>
        <rect x="-160" y="292" width="620" height="8" fill="${soft}" opacity=".55"/>
      </g>
      <circle cx="228" cy="82" r="22" fill="${paper}" opacity=".92"/>`;
  } else {
    // architectural arcs
    art = `
      <rect width="300" height="400" fill="${paper}"/>
      <path d="M40 400 V214 A110 110 0 0 1 260 214 V400" fill="${mid}"/>
      <path d="M86 400 V228 A64 64 0 0 1 214 228 V400" fill="${ink}" opacity=".9"/>
      <path d="M132 400 V244 A18 18 0 0 1 168 244 V400" fill="${soft}"/>
      <circle cx="150" cy="150" r="30" fill="none" stroke="${mid}" stroke-width="2" opacity=".7"/>`;
  }

  const titleColor = motif === 3 ? ink : fg;
  const titleSoft = motif === 3 ? 'rgba(20,14,10,.6)' : fgSoft;
  const scrim = motif === 3
    ? 'none'
    : `<rect x="0" y="248" width="300" height="152" fill="url(#scrim-${gid})"/>`;

  const titleText = o.title
    ? `<defs><linearGradient id="scrim-${gid}" x1="0" y1="0" x2="0" y2="1">
         <stop offset="0" stop-color="${dark}" stop-opacity="0"/>
         <stop offset="1" stop-color="${dark}" stop-opacity=".72"/>
       </linearGradient></defs>
       ${scrim}
       <g>
         <text x="20" y="${o.compact ? 350 : 352}" font-family="PingFang SC, Noto Sans SC, sans-serif"
           font-size="${o.compact ? 30 : 26}" font-weight="700" letter-spacing="1" fill="${titleColor}">${esc(work.title)}</text>
         ${o.compact ? '' : `<text x="21" y="376" font-family="PingFang SC, Noto Sans SC, sans-serif" font-size="13"
           letter-spacing="1" fill="${titleSoft}">${esc(work.author)}</text>`}
       </g>`
    : '';

  return `<svg viewBox="0 0 300 400" preserveAspectRatio="xMidYMid slice" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="${esc(work.title)} 封面">
    ${art}${titleText}
    <rect x="0" y="0" width="10" height="400" fill="#000" opacity=".14"/>
    <rect x="10" y="0" width="3" height="400" fill="#fff" opacity=".10"/>
    <rect x="1" y="1" width="298" height="398" fill="none" stroke="#000" stroke-width="2" opacity=".12"/>
  </svg>`;
}

function luminance(hex) {
  const c = hex.replace('#', '');
  const v = [0, 2, 4].map((i) => parseInt(c.slice(i, i + 2), 16) / 255);
  const lin = v.map((x) => (x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4)));
  return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
}
function esc(s) {
  return String(s).replace(/[&<>"]/g, (m) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[m]));
}

/* ---------- procedural comic page ----------------------------------------- */
/* A synthetic manga page: 4 panels, screentone, speed lines, balloons.
   Grayscale on purpose — real pages are monochrome; the app chrome carries color. */
function comicPageSVG(pageIndex) {
  const seed = 8817 + pageIndex * 97;
  const r = rng(seed);
  const offset = (r() * 20 - 10).toFixed(1);
  return `<svg viewBox="0 0 340 500" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet">
  <defs>
    <pattern id="tone-${pageIndex}" width="6" height="6" patternUnits="userSpaceOnUse">
      <circle cx="1.6" cy="1.6" r="1.05" fill="#1b1b1b" opacity=".5"/>
    </pattern>
    <pattern id="hatch-${pageIndex}" width="7" height="7" patternUnits="userSpaceOnUse" patternTransform="rotate(28)">
      <line x1="0" y1="0" x2="0" y2="7" stroke="#1b1b1b" stroke-width="1.2" opacity=".38"/>
    </pattern>
    <linearGradient id="sky-${pageIndex}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#f4f4f2"/><stop offset="1" stop-color="#d9d9d6"/>
    </linearGradient>
  </defs>
  <rect width="340" height="500" fill="#fbfbf9"/>
  <g stroke="#151515" stroke-width="2.4" fill="none">
    <!-- panel 1: wide establishing shot -->
    <rect x="14" y="12" width="312" height="150"/>
    <!-- panel 2 + 3: two rows -->
    <rect x="14" y="172" width="150" height="176"/>
    <rect x="176" y="172" width="150" height="176"/>
    <!-- panel 4: wide -->
    <rect x="14" y="358" width="312" height="130"/>
  </g>

  <!-- P1 art: night sky, moon, phone poles -->
  <g transform="translate(14,12)">
    <clipPath id="clip1-${pageIndex}"><rect width="312" height="150"/></clipPath>
    <g clip-path="url(#clip1-${pageIndex})">
      <rect width="312" height="150" fill="url(#sky-${pageIndex})"/>
      <circle cx="238" cy="44" r="26" fill="#ffffff" stroke="#151515" stroke-width="1.6"/>
      <circle cx="228" cy="38" r="5" fill="#dcdcd8"/>
      <circle cx="248" cy="52" r="3.4" fill="#dcdcd8"/>
      <path d="M0 108 L60 96 L120 104 L188 88 L252 100 L312 92 V150 H0Z" fill="#1b1b1b" opacity=".9"/>
      <g stroke="#151515" stroke-width="2">
        <path d="M42 8 V150 M42 22 L104 12 M42 26 L96 34 M42 40 L110 46"/>
        <path d="M120 18 V150"/>
        <path d="M120 26 L152 18 M120 32 L160 40"/>
      </g>
      <g fill="#151515">
        <path d="M170 40 q6 -5 12 0 q-6 4 -12 0Z"/><path d="M186 32 q6 -5 12 0 q-6 4 -12 0Z"/>
        <path d="M158 50 q5 -4 10 0 q-5 4 -10 0Z"/>
      </g>
    </g>
  </g>

  <!-- P2 art: figure on rooftop -->
  <g transform="translate(14,172)">
    <clipPath id="clip2-${pageIndex}"><rect width="150" height="176"/></clipPath>
    <g clip-path="url(#clip2-${pageIndex})">
      <rect width="150" height="176" fill="#f0f0ee"/>
      <rect x="0" y="96" width="150" height="80" fill="#1b1b1b" opacity=".86"/>
      <path d="M0 96 L24 84 L48 92 L76 78 L104 90 L128 82 L150 88 V96Z" fill="#151515"/>
      <!-- figure silhouette -->
      <g fill="#101010">
        <circle cx="86" cy="66" r="9"/>
        <path d="M78 74 q8 -5 16 0 l4 26 q-12 5 -24 0Z"/>
        <path d="M78 96 l-5 34 h7 l6 -26 l6 26 h7 l-5 -34Z"/>
      </g>
      <path d="M20 66 l30 -2" stroke="#151515" stroke-width="1.4" opacity=".5"/>
    </g>
  </g>

  <!-- P3 art: close-up with speed lines + balloon -->
  <g transform="translate(176,172)">
    <clipPath id="clip3-${pageIndex}"><rect width="150" height="176"/></clipPath>
    <g clip-path="url(#clip3-${pageIndex})">
      <rect width="150" height="176" fill="#e9e9e6"/>
      <g stroke="#151515" stroke-width="1" opacity=".55">
        ${Array.from({ length: 26 }, (_, i) => {
          const y = 8 + i * 6.6;
          return `<line x1="0" y1="${y}" x2="150" y2="${(y - 26).toFixed(1)}"/>`;
        }).join('')}
      </g>
      <g transform="rotate(-7 75 96)">
        <rect x="24" y="60" width="102" height="68" rx="3" fill="#ffffff" stroke="#151515" stroke-width="2.2"/>
        <path d="M24 60 L75 96 L126 60" fill="none" stroke="#151515" stroke-width="1.8"/>
        <path d="M24 128 L58 96 M126 128 L92 96" fill="none" stroke="#151515" stroke-width="1.4" opacity=".7"/>
        <circle cx="75" cy="101" r="9" fill="#151515" opacity=".88"/>
        <path d="M71 101 l3 3 l6 -7" stroke="#fbfbf9" stroke-width="1.6" fill="none"/>
      </g>
    </g>
  </g>

  <!-- balloon in P1 -->
  <g>
    <path d="M188 26 q30 -12 58 2 q12 6 10 16 q-2 10 -18 12 q-22 3 -40 -4 q-14 -6 -14 -14 q0 -8 4 -12Z"
      fill="#ffffff" stroke="#151515" stroke-width="2"/>
    <path d="M198 54 l-8 12 l4 -13Z" fill="#ffffff" stroke="#151515" stroke-width="2"/>
    <text x="226" y="46" text-anchor="middle" font-size="11" font-family="PingFang SC, sans-serif" fill="#151515">风不一样了</text>
  </g>

  <!-- P4 art: train window with rain -->
  <g transform="translate(14,358)">
    <clipPath id="clip4-${pageIndex}"><rect width="312" height="130"/></clipPath>
    <g clip-path="url(#clip4-${pageIndex})">
      <rect width="312" height="130" fill="#dededb"/>
      <rect x="16" y="14" width="130" height="102" rx="6" fill="#f7f7f5" stroke="#151515" stroke-width="2.4"/>
      <rect x="164" y="14" width="130" height="102" rx="6" fill="#f7f7f5" stroke="#151515" stroke-width="2.4"/>
      <g stroke="#151515" stroke-width="1.1" opacity=".55">
        ${Array.from({ length: 14 }, (_, i) => {
          const x = 26 + i * 8.4;
          return `<line x1="${x}" y1="20" x2="${(x - 8).toFixed(1)}" y2="110"/>`;
        }).join('')}
        ${Array.from({ length: 12 }, (_, i) => {
          const x = 174 + i * 9.4;
          return `<line x1="${x}" y1="20" x2="${(x - 9).toFixed(1)}" y2="110"/>`;
        }).join('')}
      </g>
      <rect x="0" y="116" width="312" height="14" fill="#1b1b1b" opacity=".8"/>
      <text x="286" y="26" text-anchor="end" font-size="10" font-family="PingFang SC, sans-serif" fill="#151515">12</text>
    </g>
  </g>
  <g transform="translate(0,${offset})"/>
</svg>`;
}

/* ---------- catalog ------------------------------------------------------- */
const WORKS = [
  { id: 'w-nightflight', title: '夜航西飞', author: '仓岛遥', kind: '漫画', source: '漫游图鉴', chapters: 48, read: 12, unread: 2, status: '连载中', rating: 4.7, tags: ['悬疑', '都市', '公路'], ramp: 0, motif: 0,
    desc: '深夜航班穿过云层时，副驾驶在驾驶舱里发现了一封写给十年前自己的信。两个时空的航线开始重叠，而她必须决定，哪一个降落才是真实的。',
    updated: '今天 09:20' },
  { id: 'w-silver', title: '银盐少年', author: '白井千夏', kind: '漫画', source: '漫游图鉴', chapters: 32, read: 32, status: '已完结', rating: 4.5, tags: ['青春', '摄影'], ramp: 7, motif: 2,
    desc: '暗房里显影的不只是照片，还有少年时代没能说出口的话。一部关于光、盐与告别的短篇集。', updated: '3 天前' },
  { id: 'w-teahouse', title: '山茶文具店', author: '林间雪', kind: '小说', source: '墨鱼小说', chapters: 86, read: 3, unread: 1, status: '连载中', rating: 4.8, tags: ['治愈', '日常'], ramp: 8, motif: 3, glyph: '茶',
    desc: '镰仓一间代笔文具店，替人写下无法开口的信。第七封信来自一位想向亡妻道歉的老人。', updated: '今天 07:45' },
  { id: 'w-dive', title: '深潜', author: 'K. Hazama', kind: '漫画', source: '浮世绘源', chapters: 24, read: 0, status: '连载中', rating: 4.3, tags: ['科幻', '惊悚'], ramp: 9, motif: 1,
    desc: '海面下 4800 米，考察队收到的信号来自四十年前的自己。', updated: '昨天 22:10' },
  { id: 'w-snowcountry', title: '雪国列车', author: '李墨', kind: '小说', source: '星海书库', chapters: 40, read: 18, status: '连载中', rating: 4.6, tags: ['架空', '群像'], ramp: 4, motif: 4,
    desc: '列车永远向北，车厢里的季节却被严格分配。第三节车厢的人从未见过夏天。', updated: '2 小时前' },
  { id: 'w-glassmask', title: '玻璃面具', author: '三澄真白', kind: '漫画', source: '漫游图鉴', chapters: 60, read: 41, status: '连载中', rating: 4.4, tags: ['悬疑', '心理'], ramp: 10, motif: 5,
    desc: '一张从旧剧院地下室里找到的面具，戴上它的人会说出最不愿承认的真心话。', updated: '今天 11:02' },
  { id: 'w-catbook', title: '猫与旧书店', author: '白鸟蓝', kind: '漫画', source: '浮世绘源', chapters: 18, read: 18, status: '已完结', rating: 4.9, tags: ['日常', '治愈'], ramp: 1, motif: 2,
    desc: '神保町的一家旧书店，店猫会在雨天替人找到那本「刚好需要」的书。', updated: '1 周前' },
  { id: 'w-circuits', title: '电路之诗', author: 'Arata', kind: '小说', source: '墨鱼小说', chapters: 55, read: 7, status: '连载中', rating: 4.2, tags: ['赛博', '哲思'], ramp: 5, motif: 5,
    desc: '在算力配给的年代，一位老工程师用废弃的机器写下了最后一行诗。', updated: '今天 06:30' },
  { id: 'w-rainmail', title: '雾港来信', author: '夏目澪', kind: '小说', source: '星海书库', chapters: 30, read: 22, status: '连载中', rating: 4.5, tags: ['推理', '民国'], ramp: 2, motif: 1,
    desc: '雾夜里，港口邮局收到一封没有收件人的信。信纸上有海盐的味道。', updated: '昨天 19:40' },
  { id: 'w-stardust', title: '星屑尘埃', author: '伊东ハル', kind: '漫画', source: '漫游图鉴', chapters: 72, read: 60, status: '连载中', rating: 4.8, tags: ['宇宙', '冒险'], ramp: 3, motif: 0,
    desc: '清扫太空垃圾的少女，在轨道上捡到了一颗还在呼吸的星星。', updated: '今天 13:15' },
  { id: 'w-ring', title: '环形废墟', author: '博尔赫斯迷', kind: '小说', source: '本地书库', chapters: 12, read: 0, status: '实体书', rating: 4.9, tags: ['文学', '短篇'], ramp: 11, motif: 4,
    desc: '一个人在梦中梦见另一个人，直到发现自己也是被梦见的那一个。', updated: '本地导入' },
  { id: 'w-thaw', title: '解冻', author: '林知远', kind: '漫画', source: '浮世绘源', chapters: 20, read: 2, status: '连载中', rating: 4.1, tags: ['灾难', '家庭'], ramp: 6, motif: 3, glyph: '冻',
    desc: '永冻土开始融化的那年夏天，父亲的谎言也跟着浮了上来。', updated: '4 小时前' },
];

const BY_ID = Object.fromEntries(WORKS.map((w) => [w.id, w]));
const SHELF = WORKS.slice(0, 10);

/* chapters for the detail screen */
const CHAPTERS = [
  { id: 'c14', n: 14, title: '第 14 话 · 灯塔以南', date: '今天 09:20', read: false, dl: 'done', new: true },
  { id: 'c13', n: 13, title: '第 13 话 · 一封信的重量', date: '11 月 6 日', read: false, dl: 'done', new: true },
  { id: 'c12', n: 12, title: '第 12 话 · 云上的城市', date: '10 月 30 日', read: true, dl: 'done', current: true },
  { id: 'c11', n: 11, title: '第 11 话 · 没有降落的航班', date: '10 月 23 日', read: true, dl: 'done' },
  { id: 'c10', n: 10, title: '第 10 话 · 塔台的最后一次呼叫', date: '10 月 16 日', read: true, dl: 'queued' },
  { id: 'c09', n: 9, title: '第 9 话 · 逆风区', date: '10 月 9 日', read: true, dl: 'idle' },
  { id: 'c08', n: 8, title: '第 8 话 · 值机柜台前', date: '10 月 2 日', read: true, dl: 'idle' },
  { id: 'c07', n: 7, title: '第 7 话 · 蓝色航线', date: '9 月 25 日', read: true, dl: 'idle' },
  { id: 'c06', n: 6, title: '第 6 话 · 迟到的行李', date: '9 月 18 日', read: true, dl: 'idle' },
];

/* ---------- sources ------------------------------------------------------- */
const SOURCES = [
  { key: 'manrope', name: '漫游图鉴', ver: '2.3.1', letter: '漫', kinds: '漫画 · 搜索 · 探索', on: true, signed: true, api: 4, repo: 'kurisa-registry', color: 0 },
  { key: 'inkfish', name: '墨鱼小说', ver: '1.8.0', letter: '墨', kinds: '小说 · 搜索', on: true, signed: true, api: 4, repo: 'kurisa-registry', color: 1 },
  { key: 'ukiyoe', name: '浮世绘源', ver: '0.9.7', letter: '浮', kinds: '漫画 · 小说 · 探索', on: true, signed: false, api: 3, repo: '社区索引', color: 2 },
  { key: 'starsea', name: '星海书库', ver: '3.0.2', letter: '星', kinds: '小说 · 搜索', on: false, signed: true, api: 4, repo: 'kurisa-registry', color: 3 },
  { key: 'local', name: '本地文件夹', ver: '内置', letter: '本', kinds: '本地导入 · CBZ/EPUB', on: true, signed: true, api: null, repo: '内置', color: 4, builtin: true },
];

const DOWNLOADS = [
  { work: 'w-nightflight', chapter: '第 13 话 · 一封信的重量', status: 'running', done: 18, total: 24, speed: '2.4 MB/s', size: '38.2 MB' },
  { work: 'w-stardust', chapter: '第 61 话 · 轨道的另一边', status: 'running', done: 7, total: 22, speed: '1.1 MB/s', size: '54.6 MB' },
  { work: 'w-glassmask', chapter: '第 42 话 · 台词之外', status: 'paused', done: 12, total: 30, speed: '已暂停', size: '72.0 MB' },
];

/* ---------- explore ------------------------------------------------------- */
const CATEGORIES = [
  { name: '奇幻', icon: 'auto_awesome', tone: 'p' },
  { name: '悬疑', icon: 'search_insights', tone: 's' },
  { name: '恋爱', icon: 'favorite', tone: 't' },
  { name: '日常', icon: 'local_cafe', tone: 'p' },
  { name: '科幻', icon: 'rocket_launch', tone: 's' },
  { name: '历史', icon: 'account_balance', tone: 't' },
];

const RANKING = [
  { work: 'w-stardust', delta: '+2' },
  { work: 'w-teahouse', delta: '+1' },
  { work: 'w-nightflight', delta: '0' },
  { work: 'w-snowcountry', delta: '-2' },
  { work: 'w-catbook', delta: '+4' },
];

/* ---------- search -------------------------------------------------------- */
const RECENT_QUERIES = ['夜航西飞', '治愈系小说', '雨宫遥', '太空 群像', '昭和推理'];
const HOT_QUERIES = ['本周新番', '完结漫画', '女性向', '短篇集', '赛博朋克'];

/* ---------- helper: relative time label ---------------------------------- */
function progressPct(w) {
  if (!w.chapters) return 0;
  return Math.min(100, Math.round((w.read / w.chapters) * 100));
}
