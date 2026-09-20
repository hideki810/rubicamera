/* ルビカメラ サービスワーカー

   ここではファイルを保存（キャッシュ）しません。
   ホーム画面に「アプリとして」追加できるようにするためだけのものです。
   ファイルを保存すると、以前あった「古い画面が端末に残る」問題が
   ぶり返すため、通信は毎回そのままインターネットへ流します。 */

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', e => {
  /* 画面を開くときだけ、圏外なら短いお知らせを出す。
     それ以外（画像やAIへの通信）は、いつもどおりそのまま流す。 */
  if(e.request.mode === 'navigate'){
    e.respondWith(
      fetch(e.request).catch(() => new Response(
        '<!DOCTYPE html><meta charset="utf-8"><title>つながりません</title>'
        + '<body style="font-family:system-ui,sans-serif;padding:28px;line-height:2;color:#1b2a41">'
        + 'インターネットにつながっていません。<br>電波のあるところで、もう一度ひらいてください。</body>',
        {headers: {'Content-Type': 'text/html; charset=utf-8'}}
      ))
    );
  }
});
