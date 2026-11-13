// ==SourceScript==
// @name         演示漫画源
// @key          demo.manga
// @version      1.0.0
// @apiVersion   2
// @minApp       1.0.0
// @kind         manga
// @domains      manga.example.com
// @capabilities search,comic
// ==/SourceScript==

/**
 * M1 最小可跑漫画示例（合成站点，与 novel-example.js 同构）。
 * 可跑断言见 :source:contract ContractRunnerTest（fixture 版）。
 */
class DemoManga extends ComicSource {
  name = '演示漫画源'
  key = 'demo.manga'
  version = '1.0.0'
  minAppVersion = '1.0.0'
  apiVersion = 2
  kind = ['manga']
  url = 'https://manga.example.com/sources/manga.js'

  search = {
    optionList: [{ options: ['any-Any', 'all-All'] }],
    load: async (keyword, options, page) => {
      const res = await Network.get(
        `https://manga.example.com/s?q=${encodeURIComponent(keyword)}&page=${page}`, {}
      )
      const doc = new HtmlDocument(res.body)
      const comics = doc.querySelectorAll('.w').map((el, i) => ({
        id: 'w' + (i + 1),
        title: el.text,
        kind: 'manga'
      }))
      doc.dispose()
      return { comics, maxPage: 3 }
    }
  }

  comic = {
    loadInfo: async (id) => {
      const res = await Network.get(`https://manga.example.com/m/${id}`, {})
      const doc = new HtmlDocument(res.body)
      const title = doc.querySelector('h1').text
      doc.dispose()
      return new ComicDetails({
        title,
        chapters: new Map([['c1', { id: 'c1', title: '第1话' }]])
      })
    },
    loadEp: async (comicId, epId) => {
      return { images: [`https://cdn.example.com/${comicId}/${epId}/p1.jpg`] }
    }
  }
}
