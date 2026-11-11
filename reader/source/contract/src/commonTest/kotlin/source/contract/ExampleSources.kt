package source.contract

/** M1 最小可跑示例源（合成站点，无真实网络；与 docs/contract/novel-example.js 同构）。 */
object ExampleSources {

    const val MANGA_JS = """
class DemoManga extends ComicSource {
  constructor() {
    super();
    this.name = '演示漫画源';
    this.key = 'demo.manga';
    this.version = '1.0.0';
    this.minAppVersion = '1.0.0';
    this.apiVersion = 2;
    this.kind = ['manga'];
    this.baseUrl = 'https://manga.example.com';
  }
  async init() { this.saveData('init', '1'); }
  search = {
    optionList: [{ options: ['any-Any', 'all-All'] }],
    load: async (keyword, options, page) => {
      if (!Array.isArray(options)) throw 'options must be array';
      const res = await Network.get(this.baseUrl + '/s?q=' + encodeURIComponent(keyword));
      const doc = new HtmlDocument(res.body);
      const items = doc.querySelectorAll('.w').map(function (el, i) {
        return { id: 'w' + (i + 1), title: el.text + '|' + options[0] + '|' + APP.locale };
      });
      doc.dispose();
      return { comics: items, maxPage: 3 };
    }
  };
  comic = {
    loadInfo: async (id) => {
      const res = await Network.get(this.baseUrl + '/m/' + id);
      const doc = new HtmlDocument(res.body);
      const title = doc.querySelector('h1').text;
      doc.dispose();
      return new ComicDetails({
        title: title,
        chapters: new Map([['c1', { id: 'c1', title: '第1话' }], ['c2', { id: 'c2', title: '第2话' }]])
      });
    },
    loadEp: async (comicId, epId) => {
      return { images: ['https://cdn.example.com/' + comicId + '/' + epId + '/p1.jpg'] };
    }
  };
}
"""

    const val NOVEL_JS = """
class DemoNovel extends ComicSource {
  constructor() {
    super();
    this.name = '演示小说源';
    this.key = 'demo.novel';
    this.version = '1.0.0';
    this.minAppVersion = '1.0.0';
    this.apiVersion = 2;
    this.kind = ['novel'];
    this.baseUrl = 'https://novel.example.com';
  }
  async init() { this.saveData('init', '1'); }
  search = {
    optionList: [{ options: ['any-Any'] }],
    load: async (keyword, options, page) => {
      if (!Array.isArray(options)) throw 'options must be array';
      const res = await Network.get(this.baseUrl + '/s?q=' + encodeURIComponent(keyword));
      const doc = new HtmlDocument(res.body);
      const items = doc.querySelectorAll('.b').map(function (el) {
        return { id: 'n1', title: el.text + '|' + options[0] };
      });
      doc.dispose();
      return { works: items, maxPage: 1 };
    }
  };
  novel = {
    loadInfo: async (id) => {
      return { title: 'Demo Novel', chapters: [{ id: 'ch1', title: '第一章', ordinal: 0 }] };
    },
    loadChapter: async (workId, chapterId) => {
      const res = await Network.get(this.baseUrl + '/b/' + workId + '/' + chapterId);
      const doc = new HtmlDocument(res.body);
      const text = doc.querySelector('#content').html;
      doc.dispose();
      return { text: text, format: 'html' };
    }
  };
}
"""

    const val SEARCH_HTML = """
<html><body>
<a class="w">海贼王</a><a class="w">火影</a>
<a class="b">赘婿</a>
<h1>Demo Title</h1>
<div id="content"><p>第一段</p><p>第二段</p></div>
</body></html>
"""
}
