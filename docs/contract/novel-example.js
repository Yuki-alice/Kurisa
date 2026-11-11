// ==SourceScript==
// @name        示例小说源
// @key         com.example.novel
// @version     1.0.0
// @apiVersion  2
// @minApp      1.0.0
// @kind        novel
// @domains     example.com
// @capabilities search,chapterText
// ==/SourceScript==

/** M1 冻结契约的最小可跑示例（与 architecture.md 附录 B 同构，可作 fixture）。 */
class ExampleNovel extends ComicSource {
  name = '示例小说源'
  key = 'com.example.novel'
  version = '1.0.0'
  minAppVersion = '1.0.0'
  apiVersion = 2
  kind = ['novel']
  url = 'https://example.com/sources/novel.js'

  search = {
    load: async (keyword, options, page) => {
      const res = await Network.get(
        `https://example.com/search?q=${encodeURIComponent(keyword)}&page=${page}`,
        { 'User-Agent': 'Mozilla/5.0' }
      )
      const doc = new HtmlDocument(res.body)
      const works = doc.querySelectorAll('.book-item').map(el => ({
        id: el.querySelector('a').attributes.href.split('/').pop(),
        title: el.querySelector('.title').text,
        subtitle: el.querySelector('.author')?.text,
        cover: el.querySelector('img')?.attributes.src,
        tags: [],
        kind: 'novel'
      }))
      doc.dispose()
      return { works, maxPage: 1 }
    }
  }

  novel = {
    loadInfo: async (id) => {
      const res = await Network.get(`https://example.com/book/${id}`, {})
      const doc = new HtmlDocument(res.body)
      const chapters = doc.querySelectorAll('#chapter-list a').map((el, i) => ({
        id: el.attributes.href.split('/').pop(),
        title: el.text,
        ordinal: i
      }))
      const details = {
        work: {
          id, title: doc.querySelector('h1').text,
          cover: doc.querySelector('.cover img').attributes.src,
          tags: [], kind: 'novel'
        },
        chapters
      }
      doc.dispose()
      return details
    },
    loadChapter: async (workId, chapterId) => {
      const res = await Network.get(
        `https://example.com/book/${workId}/${chapterId}`, {}
      )
      const doc = new HtmlDocument(res.body)
      const text = doc.querySelector('#content').innerHtml
      doc.dispose()
      return { text, format: 'html' }
    }
  }
}
