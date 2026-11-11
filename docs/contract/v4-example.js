// ==SourceScript==
// @name        v4 演示源
// @key         com.example.v4demo
// @version     1.0.0
// @apiVersion  4
// @minApp      1.0.0
// @kind        manga,novel
// @domains     cdn.example.com
// @capabilities search,comic,novel,novelChapter,htmlHelper,settings,translation,imageLoad
// ==/SourceScript==

/**
 * v4 写法示范（合成站点 example.com，无真实站点逻辑）。
 * 要点：无状态调用、Html helper 自动 dispose、统一 page、throw helper、
 * cleanNovelHtml、format 必透出、设置回退链。
 */
class V4Demo extends ComicSource {
  name = 'v4 演示源'
  key = 'com.example.v4demo'
  version = '1.0.0'
  minAppVersion = '1.0.0'
  apiVersion = 4
  kind = ['manga', 'novel']
  url = 'https://example.com/sources/v4demo.js'
  domains = ['cdn.example.com']
  capabilities = ['search', 'comic', 'novel', 'novelChapter', 'htmlHelper', 'settings', 'translation', 'imageLoad']
  translation = { 'zh_CN': { 'hello': '你好' } }
  settings = {
    quality: { title: '画质', type: 'select', options: [{ value: 'orig', text: '原图' }], default: 'orig' }
  }

  search = {
    optionList: [{ title: '排序', options: ['new-最新'] }],
    load: async (keyword, options, page) => {
      const r = await Network.get('https://cdn.example.com/s?q=' + encodeURIComponent(keyword) + '&page=' + page)
      const titles = Html.list(r.body, '.w', (el) => el.text)
      return { works: titles.map((t, i) => ({ id: 'w' + (i + 1), title: t, kind: 'manga' })), maxPage: 5 }
    },
    loadNext: async (keyword, options, next) => {
      const r = await Network.get('https://cdn.example.com/s?cursor=' + encodeURIComponent(next || ''))
      const j = JSON.parse(r.body)
      return { works: j.works || [], next: j.next || null }
    },
    page: async (keyword, options, pageOrNext) => {
      if (typeof pageOrNext === 'number') return await this.search.load(keyword, options, pageOrNext)
      return await this.search.loadNext(keyword, options, pageOrNext)
    }
  }

  comic = {
    loadInfo: async (id) => {
      const r = await Network.get('https://cdn.example.com/m/' + id)
      const title = Html.text(r.body, 'h1')
      const cover = Html.attr(r.body, '.cover img', 'src')
      return { title: title, cover: cover, chapters: [{ id: 'c1', title: '第一话' }] }
    },
    loadEp: async (workId, chapterId) => {
      return { images: ['https://cdn.example.com/' + workId + '/' + chapterId + '/p1.jpg'] }
    },
    onImageLoad: (url, workId, chapterId) => ({
      url: url,
      headers: { Referer: 'https://cdn.example.com/' },
      maxRetries: 2
    })
  }

  novel = {
    loadInfo: async (id) => ({ title: 'Demo Novel', chapters: [{ id: 'ch1', title: '第一章' }] }),
    loadChapter: async (workId, chapterId) => {
      if (chapterId === 'plain') return { text: 'plain body', format: 'plain' }
      const r = await Network.get('https://cdn.example.com/n/' + workId + '/' + chapterId)
      return { content: cleanNovelHtml(r.body), format: 'html' }
    },
    onImageResolve: (url) => ({ url: url, headers: { Referer: 'https://cdn.example.com/' } })
  }

  async probeError(kind) {
    if (kind === 'login') throwLoginExpired('need relogin')
    if (kind === 'rate') throwRateLimited('slow down')
    if (kind === 'missing') throwNotFound('gone')
    return 'ok'
  }
}
