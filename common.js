/* ルビカメラ 共通処理
   index.html（写真版）／ live.html（かざして読む）／ imi.html（意味しらべ）の3画面で共有します。
   3画面は同じAPIキーと同じモデル選びの仕組みを使うため、重複していた処理をここにまとめました。
   画面ごとに違う処理（カメラ制御・オフラインモード・意味しらべなど）は、各HTMLの中に残しています。

   ※このファイルを直すと3画面すべてに反映されます。直したら各HTMLの APP_VERSION も上げてください。 */

const $ = id => document.getElementById(id);
const sleep = ms => new Promise(r => setTimeout(r, ms));

/* localStorage が使えない環境（プライベートモード等）では、その場かぎりのメモリに退避する */
const mem = {};
const store = {
  get(k){ try{ return localStorage.getItem(k); }catch(e){ return mem[k] ?? null; } },
  set(k,v){ try{ localStorage.setItem(k,v); }catch(e){ mem[k]=v; } },
  del(k){ try{ localStorage.removeItem(k); }catch(e){ delete mem[k]; } }
};

/* ---------- APIキー（3画面で共通の保存場所 'rubiKey'） ---------- */
function apiKey(){ return (store.get('rubiKey') || '').trim(); }
/* 貼り付け内容のよくある間違いを自動修正してキーだけ取り出す */
function normalizeKey(v){
  let s = (v || '');
  /* 全角英数字・全角記号を半角へ（日本語キーボードで入力された場合の対策） */
  s = s.replace(/[！-～]/g, ch => String.fromCharCode(ch.charCodeAt(0) - 0xFEE0));
  /* 空白・改行・見えない文字を除去 */
  s = s.replace(/[\s 　​-‍﻿]/g, '');
  /* URLごと貼られた場合は #k= より後ろのキー部分だけを取り出す */
  const u = s.match(/[#&]k=([^&#?]+)/);
  if(u){ try { s = decodeURIComponent(u[1]); } catch(e){ s = u[1]; } }
  return s;
}
/* キーの形式は複数あるため厳しくしない：半角の英数記号だけで20文字以上ならキーらしい形とみなす */
function keyLooksValid(k){ return /^[\x21-\x7E]{20,}$/.test(k); }

/* ---------- バージョン表示と「最新版に更新する」ボタン ---------- */
function setupVersionUI(version){
  $('verLabel').textContent = version;
  /* 端末に古い画面が残っているとき用：URLに印を付けて読み込み直させる */
  $('btnUpdate').onclick = () => {
    location.replace(location.pathname + '?v=' + Date.now());
  };
}

/* ---------- 画面の切り替えバー（3画面共通） ----------
   各HTMLの <main> の先頭に <nav id="pageNav"></nav> を置き、
   画面ごとに setupPageNav('自分のファイル名') を呼ぶと、3画面へのボタンが並ぶ。
   今いる画面は色を反転させて、押せないようにする。 */
const PAGES = [
  {file: 'index.html', label: '①ふりがな', note: 'しゃしんから'},
  {file: 'live.html',  label: '②かざして', note: 'カメラで すぐ'},
  {file: 'imi.html',   label: '③いみ',     note: 'ことばの いみ'}
];
const PAGE_NAV_CSS = `
.page-nav{display:flex;gap:8px}
.page-nav > *{
  flex:1;display:flex;flex-direction:column;align-items:center;gap:2px;
  padding:9px 4px;border:1px solid var(--line);border-radius:12px;
  background:var(--paper);color:var(--accent);
  font-size:13px;font-weight:700;line-height:1.25;text-decoration:none;
}
.page-nav .d{font-size:10px;font-weight:500;color:var(--ink-soft)}
.page-nav .current{background:var(--accent);border-color:var(--accent);color:#fff}
.page-nav .current .d{color:rgba(255,255,255,.85)}
`;
function setupPageNav(current){
  const nav = $('pageNav');
  if(!nav) return;
  if(!document.getElementById('pageNavStyle')){
    const st = document.createElement('style');
    st.id = 'pageNavStyle';
    st.textContent = PAGE_NAV_CSS;
    document.head.appendChild(st);
  }
  nav.className = 'page-nav';
  nav.textContent = '';
  for(const p of PAGES){
    const here = (p.file === current);
    const item = document.createElement(here ? 'span' : 'a');
    if(here){
      item.className = 'current';
      item.setAttribute('aria-current', 'page');
    } else {
      item.href = p.file;
    }
    const label = document.createElement('span');
    label.textContent = p.label;
    const note = document.createElement('span');
    note.className = 'd';
    note.textContent = p.note;
    item.appendChild(label);
    item.appendChild(note);
    nav.appendChild(item);
  }
}

/* ---------- 混雑・一時的な不調かどうかの判定（再挑戦する価値があるか） ---------- */
function isTransient(status, detail){
  return [429, 500, 502, 503, 504].includes(status)
    || /high demand|overloaded|UNAVAILABLE|try again later|RESOURCE_EXHAUSTED|quota/i.test(detail || '');
}

/* ---------- 使えるAIモデルをGoogleに問い合わせて自動で選ぶ ----------
   新しい順の上位3つに加えて、混雑しにくい安定版・軽量版も予備として後ろに並べる。
   options.preferLite = true（ルビカメラ２）… 速さ優先で、同じ世代なら軽量版（lite）を先に使う */
let modelCache = null;
const STABLE_BACKUPS      = ['gemini-flash-latest', 'gemini-2.5-flash', 'gemini-flash-lite-latest', 'gemini-2.5-flash-lite'];
const STABLE_BACKUPS_LITE = ['gemini-flash-lite-latest', 'gemini-2.5-flash-lite', 'gemini-flash-latest', 'gemini-2.5-flash'];
async function getModelCandidates(options){
  const preferLite = !!(options && options.preferLite);
  const backups = preferLite ? STABLE_BACKUPS_LITE : STABLE_BACKUPS;
  if(modelCache) return modelCache;
  const fallback = ['gemini-flash-latest', 'gemini-2.5-flash', 'gemini-2.5-flash-lite', 'gemini-2.0-flash'];
  try {
    const res = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models?key=${encodeURIComponent(apiKey())}&pageSize=200`
    );
    if(!res.ok) return fallback;
    const data = await res.json();
    const names = (data.models || [])
      .filter(m => (m.supportedGenerationMethods || []).includes('generateContent'))
      .map(m => (m.name || '').replace('models/', ''))
      .filter(n => /^gemini-/.test(n) && /flash/.test(n))
      .filter(n => !/(preview|exp|thinking|image|tts|audio|live|8b)/.test(n));
    /* バージョン番号が新しい順。同じ世代なら、速さ優先なら「lite」を先に、そうでなければ後ろに */
    names.sort((a, b) => {
      const va = parseFloat((a.match(/gemini-(\d+(?:\.\d+)?)/) || [0,0])[1]);
      const vb = parseFloat((b.match(/gemini-(\d+(?:\.\d+)?)/) || [0,0])[1]);
      if(vb !== va) return vb - va;
      const al = a.includes('lite') ? 1 : 0, bl = b.includes('lite') ? 1 : 0;
      return preferLite ? (bl - al) : (al - bl);
    });
    const list = names.slice(0, 3);
    /* 予備：Googleの一覧にあるものだけ後ろに足す（一覧が空のときは固定の予備を使う） */
    for(const b of backups){
      if(!list.includes(b) && (names.includes(b) || b.endsWith('-latest'))) list.push(b);
    }
    /* 新しい系列が全滅しても粘れるよう、最新でない軽量版も1つ入れる
       （速さ優先の画面はもともと軽量版が前に来ているので足さない） */
    if(!preferLite){
      const oldLite = names.find(n => n.includes('lite') && !list.includes(n));
      if(oldLite) list.push(oldLite);
    }
    modelCache = list.length ? list : fallback;
    return modelCache;
  } catch(e){
    return fallback;
  }
}

/* ---------- 漢字を含むかどうかの判定 ---------- */
const KANJI_RE = /[㐀-鿿々〆ヶ]/;

/* ---------- 写真をAIに送れる形（base64）にする ---------- */
/* まず縮小変換を試し、開けない形式（HEIC等）は原本をそのままAIに送る */
async function toBase64(file, maxSize, quality){
  try {
    return await canvasBase64(file, maxSize, quality);
  } catch(e){
    if(file.size > 15 * 1024 * 1024){
      const err = new Error('too big');
      err.userMessage = '写真のサイズが大きすぎて送れませんでした。カメラ設定の画質を下げるか、写真のスクリーンショットを撮ってそれを選んでください。';
      throw err;
    }
    let mime = file.type;
    if(!mime){
      const n = (file.name || '').toLowerCase();
      mime = (n.endsWith('.heic') || n.endsWith('.heif')) ? 'image/heic' : 'image/jpeg';
    }
    const raw = await fileToBase64(file);
    return {base64: raw, mediaType: mime};
  }
}

function canvasBase64(file, maxSize, quality){
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      let {width:w, height:h} = img;
      const scale = Math.min(1, maxSize / Math.max(w, h));
      w = Math.round(w * scale); h = Math.round(h * scale);
      const canvas = document.createElement('canvas');
      canvas.width = w; canvas.height = h;
      canvas.getContext('2d').drawImage(img, 0, 0, w, h);
      const dataUrl = canvas.toDataURL('image/jpeg', quality);
      resolve({base64: dataUrl.split(',')[1], mediaType: 'image/jpeg'});
    };
    img.onerror = () => reject(new Error('image load failed'));
    img.src = URL.createObjectURL(file);
  });
}

function fileToBase64(file){
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).split(',')[1]);
    r.onerror = () => reject(new Error('read failed'));
    r.readAsDataURL(file);
  });
}
