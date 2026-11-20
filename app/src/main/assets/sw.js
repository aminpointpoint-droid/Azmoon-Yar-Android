/* Service Worker — آزمون‌یار
   استراتژی: همه‌چیز از کش بالا می‌آید (آفلاین کار می‌کند) و در پس‌زمینه به‌روز می‌شود.
   هر وقت فایلی به PRECACHE اضافه/حذف کردی، عدد نسخه‌ی CACHE را یکی بالا ببر. */
const CACHE = 'azmoon-pwa-v4';
const PRECACHE = [
  "./",
  "./Azmoon_Trend/Trend.html",
  "./Book_Yar/Book-YAR.html",
  "./Book_Yar/Planner-YAR.html",
  "./Book_Yar/azmoonsanj.html",
  "./Book_Yar/exam-center-exam.html",
  "./Book_Yar/exam-center.html",
  "./Book_Yar/exam.html",
  "./Book_Yar/karnameh-engine.html",
  "./Book_Yar/shared.css",
  "./Book_Yar/test-exam-launcher.html",
  "./Book_Yar/troubleshoot.html",
  "./Digital_yar/DigitalYar.html",
  "./Digital_yar/exam.html",
  "./Digital_yar/karnameh-engine.html",
  "./Exam_Folders.html",
  "./Help_videos/help-videos.html",
  "./Weak_Panel.html",
  "./exam-folder-store.js",
  "./icon.png",
  "./icons/apple-touch-icon.png",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./icons/icon-maskable-512.png",
  "./index.html",
  "./libs/chart.umd.js",
  "./libs/fontawesome/css/all.min.css",
  "./libs/fontawesome/webfonts/fa-regular-400.woff2",
  "./libs/fontawesome/webfonts/fa-solid-900.woff2",
  "./libs/fontawesome/webfonts/fa-v4compatibility.woff2",
  "./libs/fonts-local.css",
  "./libs/html2canvas.min.js",
  "./libs/jszip.min.js",
  "./libs/pdf-lib.min.js",
  "./libs/theme.css",
  "./libs/theme.js",
  "./libs/vazirmatn/Vazirmatn-font-face.css",
  "./libs/vazirmatn/fonts/webfonts/Vazirmatn-Black.woff2",
  "./libs/vazirmatn/fonts/webfonts/Vazirmatn-Bold.woff2",
  "./libs/vazirmatn/fonts/webfonts/Vazirmatn-ExtraBold.woff2",
  "./libs/vazirmatn/fonts/webfonts/Vazirmatn-ExtraLight.woff2",
  "./libs/vazirmatn/fonts/webfonts/Vazirmatn-Light.woff2",
  "./libs/vazirmatn/fonts/webfonts/Vazirmatn-Medium.woff2",
  "./libs/vazirmatn/fonts/webfonts/Vazirmatn-Regular.woff2",
  "./libs/vazirmatn/fonts/webfonts/Vazirmatn-SemiBold.woff2",
  "./libs/vazirmatn/fonts/webfonts/Vazirmatn-Thin.woff2",
  "./manifest.webmanifest"
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE)
      // هر فایل جدا و با catch (بدون Promise.allSettled تا روی مرورگرهای قدیمی‌تر هم کار کند)
      .then((cache) => Promise.all(PRECACHE.map((url) => cache.add(url).catch(() => {}))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;              // فقط هم‌مبدأ
  if (url.pathname.includes('/api/')) return;              // سرور محلی/API را دست نزن
  if (req.headers.has('range')) return;                    // درخواست‌های Range (ویدیو/صدا)

  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const cached = await cache.match(req, { ignoreSearch: true });
    // کتابخانه‌ها، فونت‌ها و آیکون‌ها فقط با بالا بردن عدد CACHE عوض می‌شوند؛ هر بار از شبکه دوباره
    // گرفته نمی‌شوند (صرفه‌جویی در پردازنده/باتری تبلت)
    if (cached && /\/(libs|icons)\//.test(url.pathname)) return cached;
    const refresh = fetch(req).then((res) => {
      if (res && res.ok && res.type === 'basic') cache.put(req, res.clone()).catch(() => {});
      return res;
    });
    if (cached) { refresh.catch(() => {}); return cached; }   // کش سریع + به‌روزرسانی پس‌زمینه
    try { return await refresh; }
    catch (err) {
      if (req.mode === 'navigate') {
        const shell = await cache.match('./index.html');
        if (shell) return shell;
      }
      throw err;
    }
  })());
});
