// Error surface: any runtime error on the deck shows on the page itself —
// a broken deck is diagnosable at a glance instead of failing silently.
// Classic script, loaded before the module, so even module-load failures
// surface here.
window.addEventListener('error', (event) => {
  showDeckError(event.message || 'unknown error');
});
window.addEventListener('unhandledrejection', (event) => {
  showDeckError('unhandled: ' + (event.reason?.message ?? event.reason));
});

function showDeckError(message) {
  let banner = document.getElementById('deck-error');
  if (!banner) {
    banner = document.createElement('div');
    banner.id = 'deck-error';
    banner.style.cssText =
      'position:fixed;top:0;left:0;right:0;z-index:9999;background:#5c1030;color:#fff;' +
      'font:12px monospace;padding:6px 10px;white-space:pre-wrap;max-height:40vh;overflow:auto';
    document.body.prepend(banner);
  }
  banner.textContent += new Date().toLocaleTimeString() + ' ' + message + '\n';
}
