'use strict';
// slop-confirm.js — the shared branded confirm modal. Every destructive
// action goes through window.slopConfirm(title, message, confirmLabel) and
// resolves true only on an explicit confirm.
// slop-confirm.js — destructive-action confirmations.
// slopSure: the quick inline "sure?" — morphs a button into yes/no chips,
// auto-reverting after a moment. Returns false if already asking.
window.slopSure = (btn, timeoutMs = 3000) => {
  if (btn.dataset.asking === '1') return false;
  btn.dataset.asking = '1';
  btn._orig = btn.innerHTML;
  btn.innerHTML = '<span class="sure-ask">sure? <b data-yes>yes</b><b data-no>no</b></span>';
  btn.classList.add('sure-asking');
  btn._t = setTimeout(() => window.slopSureReset(btn), timeoutMs);
  return true;
};
window.slopSureReset = (btn) => {
  clearTimeout(btn._t);
  if (btn._orig !== undefined) btn.innerHTML = btn._orig;
  delete btn.dataset.asking;
  btn.classList.remove('sure-asking');
};

window.slopConfirm = (title, message, confirmLabel = 'Confirm') => new Promise((resolve) => {  const backdrop = document.createElement('div');
  backdrop.className = 'confirm-backdrop';
  backdrop.innerHTML = `
    <div class="confirm-card">
      <h4>${title}</h4><p>${message}</p>
      <div class="btns">
        <button class="btn ghost" data-x="no">Cancel</button>
        <button class="btn danger" data-x="yes">${confirmLabel}</button>
      </div>
    </div>`;
  const done = (yes) => { backdrop.remove(); resolve(yes); };
  backdrop.addEventListener('click', (e) => {
    if (e.target === backdrop) return done(false);
    const b = e.target.closest('[data-x]');
    if (b) done(b.dataset.x === 'yes');
  });
  document.body.appendChild(backdrop);
});
