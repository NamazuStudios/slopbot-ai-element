'use strict';
// slop-confirm.js — the shared branded confirm modal. Every destructive
// action goes through window.slopConfirm(title, message, confirmLabel) and
// resolves true only on an explicit confirm.
window.slopConfirm = (title, message, confirmLabel = 'Confirm') => new Promise((resolve) => {
  const backdrop = document.createElement('div');
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
