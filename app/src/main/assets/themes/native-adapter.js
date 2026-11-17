/* Connects the unmodified supplied documents to the native app. No account credentials enter this page. */
(() => {
  'use strict';
  if (!Element.prototype.replaceChildren) Element.prototype.replaceChildren = function (...nodes) { while (this.firstChild) this.removeChild(this.firstChild);this.append(...nodes); };
  if (!Array.prototype.at) Array.prototype.at = function (index) { return this[index < 0 ? this.length + index : index]; };
  const ps5 = window.nativeTheme === 'ps5';
  const root = document.querySelector('#screen');
  const baseRender = render, baseOpen = openView, baseClose = closeView, baseMotion = motion;
  let data = null, applying = false, opened = '', searchTimer;
  const send = (name, value = '') => window.NativeTheme.post(name, String(value));
  const current = () => visibleGames()[selected];
  const text = (node, value) => { if (node) node.textContent = value == null ? '' : String(value); };
  const style = document.createElement('style');
  style.textContent = 'html,body{width:100%;height:100%;overflow:hidden}.studio{width:100%!important;max-width:none!important;padding:0!important;margin:0!important}.studio-head,.studio-foot{display:none!important}.frame,.frame.mobile{width:100%!important;height:100vh!important;max-width:none!important;aspect-ratio:auto!important;border:0!important;box-shadow:none!important;margin:0!important}.screen,.mobile .screen{height:var(--native-height,720px)!important}.native-game .hero-art,.native-game .scrim,.native-game .particles,.native-game .topbar,.native-game .rail,.native-game .feature,.native-game .quick-area,.native-game .console-header,.native-game .main,.native-game .footer{display:none!important}.native-back{position:absolute;right:20px;top:15px;z-index:9;background:transparent;border:0;color:white;font-size:25px;padding:8px 15px}';
  document.head.append(style);
  const resize = () => {
    document.querySelector('#frame').classList.toggle('mobile', innerWidth / innerHeight > 1.95);
    root.style.setProperty('--native-height', `${innerHeight * 1280 / innerWidth}px`);
    scale();
  };
  addEventListener('resize', resize); resize();
  const quotaMark = () => data && data.quota.unlimited ? '∞' : '—';
  function account() {
    if (!data) return;
    document.querySelectorAll('.profile-trigger .avatar,.utilities .avatar').forEach(n => text(n, data.profile.name.slice(0, 1).toUpperCase()));
    const name = document.querySelector('.account');
    if (name) { name.replaceChildren(document.createTextNode(data.profile.name)); const sub = document.createElement('small'); text(sub, data.profile.gamertag); name.append(sub); }
    text(document.querySelector('.quota-pill span'), data.profile.plan);
    text(document.querySelector('.quota-pill b'), quotaMark());
    const benefit = document.querySelector('.quick-card .tier');
    if (benefit) { text(benefit, data.profile.plan); text(benefit.parentElement.querySelector('strong'), data.quota.label); }
    document.querySelectorAll('.quick-card .infinity').forEach(n => text(n, quotaMark()));
    const clock = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    document.querySelectorAll('.clock,.center-time').forEach(n => text(n, clock));
    document.querySelectorAll('.center-heading span').forEach(n => text(n, ''));
    const cards = document.querySelectorAll('.center-card');
    if (cards[2]) { text(cards[2].querySelector('strong'), quotaMark()); text(cards[2].querySelector('p'), `${data.profile.plan}\n${data.quota.label}`); }
    document.querySelectorAll('.demo-stats,.stats-demo').forEach(n => n.classList.remove('open', 'visible'));
  }
  render = function (...args) {
    baseRender(...args); account();
    const list = visibleGames();
    document.querySelectorAll(ps5 ? '#rail .tile' : '#tiles .tile').forEach((button, index) => {
      button.onclick = () => {
        if (selected === index) send('play', list[index].id);
        else { selected = index; render(true);document.querySelector(ps5 ? '#rail' : '#tiles').children[index]?.focus(); }
      };
    });
    if (ps5 && (!current() || !current().hero)) {
      document.querySelector('#hero-art').removeAttribute('src'); currentHero = '';
    }
    if (!applying && current()) send('select', current().id);
  };
  const nativeFavorite = () => { if (current()) send('favorite', current().id); };
  if (!ps5) { favorite = nativeFavorite;document.querySelector('#favorite').onclick = nativeFavorite; }
  const rows = (target, label, value, action) => ps5 ? row(target, label, value, action) : row(label, value, action);
  const container = () => document.querySelector(ps5 ? '#panel' : '#dialog');
  function backButton() {
    const b = document.createElement('button'); b.className = ps5 ? 'back' : 'dialog-back'; text(b, 'Voltar'); b.onclick = closeView; container().append(b);
  }
  function closeButton() {
    if (container().querySelector('.native-back')) return;
    const b = document.createElement('button'); b.className = 'native-back'; b.setAttribute('aria-label', 'Fechar'); text(b, '×'); b.onclick = closeView; container().append(b);
  }
  function profilePanel() {
    const p = container(); heading('Perfil', data.profile.gamertag);
    const card = document.createElement('div'); card.className = ps5 ? 'benefit' : 'profile-card';
    const details = document.createElement('div'), plan = document.createElement('small'), name = document.createElement('h3'), quota = document.createElement('p'), mark = document.createElement('span');
    text(plan, data.profile.plan); text(name, data.profile.name); text(quota, data.quota.label); text(mark, quotaMark()); mark.className = 'infinity';
    details.append(plan, name, quota); card.append(details, mark); p.append(card);
    rows(p, 'Biblioteca e conta', '', () => send('settings', 'ACCOUNT'));
    rows(p, 'Aparência', '', () => openView('settings'));
    backButton(); closeButton();
  }
  function settingsPanel(section = 'appearance') {
    const p = container(); heading('Configurações');
    const titles = { appearance: 'Aparência', video: 'Vídeo e transmissão', controls: 'Controles e toque', audio: 'Áudio e microfone', account: 'Biblioteca e conta', diagnostic: 'Ajuda e diagnóstico' };
    let content = p;
    if (ps5) {
      const layout = document.createElement('div'), nav = document.createElement('nav'); layout.className = 'settings-layout'; nav.className = 'settings-nav'; content = document.createElement('div');
      Object.entries(titles).forEach(([key, label]) => { const b = document.createElement('button'); text(b, label); b.classList.toggle('active', key === section); b.onclick = () => settingsPanel(key); nav.append(b); });
      layout.append(nav, content); p.append(layout);
    }
    const add = (label, value, page) => rows(content, label, value, () => send('settings', page));
    if (!ps5 || section === 'appearance') {
      rows(content, 'Tema', data.night ? 'Noturno' : ps5 ? 'Imersivo' : 'Azul clássico', () => { send('night', !data.night); });
      rows(content, 'Movimento do fundo', data.motion ? 'Ativado' : 'Desativado', () => send('motion', !data.motion));
      add('Aparência e temas', '', 'APPEARANCE');
    }
    if (!ps5 || section === 'video') { add('Resolução solicitada', data.settings.resolution, 'VIDEO'); add('Perfil de imagem', data.settings.image, 'IMAGE'); }
    if (!ps5 || section === 'controls') add('Controles e toque', data.settings.touch, 'CONTROLS');
    if (!ps5 || section === 'audio') add('Áudio e microfone', data.settings.audio, 'AUDIO');
    if (!ps5 || section === 'account') { rows(content, 'Perfil', data.profile.name, () => openView('profile')); rows(content, 'Tempo na nuvem', data.quota.label, () => openView('profile')); rows(content, 'Amigos', '', () => send('friends')); }
    if (section === 'diagnostic') add('Ajuda e diagnóstico', '', 'DIAGNOSTICS');
    backButton(); closeButton();
  }
  function sessionPanel() {
    const p = container(); heading('Partida', current() ? current().name : '');
    const add = (label, action) => rows(p, label, '', () => send(action));
    add('Retomar partida', 'resume');
    if (data.actions.includes('hud')) add(data.hudVisible ? 'Ocultar controle virtual' : 'Mostrar controle virtual', 'hud');
    if (data.actions.includes('touch')) add('Modo de toque da próxima partida', 'touch');
    add('Mostrar / ocultar estatísticas', 'statistics');
    add('Áudio e microfone', 'audio'); add('Vídeo e conexão', 'video'); add('Configurações', 'settings');
    if (data.actions.includes('xbox')) add('Botão Xbox — experimental', 'xbox');
    rows(p, 'Encerrar partida', '', confirmEnd); backButton(); closeButton();
  }
  function confirmEnd() {
    heading('Encerrar partida', 'Encerrar a partida e voltar ao catálogo?');
    rows(container(), 'Encerrar partida', '', () => send('end'));
    rows(container(), 'Retomar partida', '', () => send('resume')); closeButton();
  }
  function paintSearch() {
    const input = container().querySelector('input'), results = container().querySelector('.search-results');
    if (!input || !results) return;
    results.replaceChildren();
    games.forEach(g => {
      const b = document.createElement('button'), im = new Image(), t = document.createElement('span'); b.className = 'search-result'; im.src = g.image; im.alt = ''; text(t, g.name); b.append(im, t); b.onclick = () => send('play', g.id); results.append(b);
    });
    input.oninput = () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => send('search', input.value), 180); };
  }
  openView = function (next) {
    if (!data) return;
    if (next === 'friends') { send('friends'); return; }
    opened = next; baseOpen(next); send('modal', true);
    if (next === 'profile') profilePanel();
    else if (next === 'settings') settingsPanel();
    else if (next === 'game' || next === 'controls' || next === 'image') {
      if (data.inGame && next === 'game') sessionPanel();
      else { heading(next === 'image' ? 'Imagem e tela' : 'Controles e toque'); rows(container(), next === 'image' ? 'Imagem e tela' : 'Controles e toque', next === 'image' ? data.settings.image : data.settings.touch, () => send('settings', next === 'image' ? 'VIDEO' : 'CONTROLS')); backButton(); closeButton(); }
    } else if (next === 'options') {
      heading(current() ? current().name : 'Biblioteca'); rows(container(), 'Favoritos', '', nativeFavorite); rows(container(), 'Atalho na tela inicial', '', () => send('shortcut', current() ? current().id : '')); backButton(); closeButton();
    } else if (next === 'search') { paintSearch(); closeButton();send('category', 'all'); }
    if (next === 'center') {
      account();
      const centerCards = document.querySelectorAll('.center-card');
      if (centerCards[0]) centerCards[0].onclick = () => data.inGame ? send('hud') : send('settings', 'CONTROLS');
      if (centerCards[1]) centerCards[1].onclick = () => send('audio');
      const home = document.querySelector('[data-action="home"]'); if (home) home.onclick = () => data.inGame ? openView('game') : closeView();
    }
    container().querySelector('button')?.focus();
  };
  if (ps5) {
    settings = settingsPanel;
    document.querySelector('#play').onclick = () => { if (current()) send('play', current().id); };
    document.querySelector('#mic').onclick = () => send('audio');
    document.querySelector('#stats-toggle').onclick = () => send('statistics');
  }
  closeView = function () {
    baseClose(); opened = ''; send('modal', false);
    if (data && data.inGame) send('resume');
  };
  window.nativeReceive = function (value) {
    data = value; applying = true;
    if (value.inGame) { root.classList.add('native-game');document.body.style.background='transparent';root.style.background='transparent';document.querySelector('#frame').style.background='transparent'; }
    if (ps5 && value.games[value.selected]?.hero && document.querySelector('#hero-art').getAttribute('src') !== value.games[value.selected].hero) currentHero = '';
    games.splice(0, games.length, ...value.games);
    if (ps5) { Object.keys(labels).forEach(k => delete labels[k]); games.forEach(g => { labels[g.id] = [g.name, g.genre]; }); }
    favorites = new Set(games.filter(g => g.favorite).map(g => g.id));
    tab = value.tab === 'favorites' ? 'favorites' : value.tab === 'recent' ? ps5 ? 'games' : 'recent' : ps5 ? 'library' : 'all';
    selected = value.selected;
    document.querySelector('#motion').checked = value.motion;
    if (ps5) theme(value.night ? 'night' : 'immersive'); else setTheme(value.night ? 'night' : 'blue');
    render(); baseMotion(); account(); applying = false;
    if (!games.length) { text(document.querySelector('#game-title'), value.notice || 'Biblioteca');text(document.querySelector('#description'), value.notice || ''); }
    if (value.inGame && !opened) openView(ps5 ? 'center' : 'game');
    else if (opened === 'profile') profilePanel();
    else if (opened === 'settings') settingsPanel();
    else if (opened === 'search') paintSearch();
    const fixed = document.querySelector('.center-heading span'); if (fixed) text(fixed, '');
    if (!opened && (document.activeElement === document.body || !root.contains(document.activeElement))) root.querySelector('.tile.selected')?.focus();
  };
  document.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => send('category', b.dataset.tab));
  function spatial(key) {
    const active = document.activeElement, origin = active && active.getBoundingClientRect();
    if (!origin || !origin.width) return false;
    const ox = origin.x + origin.width / 2, oy = origin.y + origin.height / 2;
    const candidates = [...root.querySelectorAll('button')].filter(n => n !== active && !n.disabled).map(n => ({ n, r: n.getBoundingClientRect() })).filter(({ r }) => r.width > 0 && r.height > 0);
    let best, score = Infinity;
    candidates.forEach(({ n, r }) => {
      const dx = r.x + r.width / 2 - ox, dy = r.y + r.height / 2 - oy;
      if (key === 'ArrowUp' && dy >= -5 || key === 'ArrowDown' && dy <= 5 || key === 'ArrowLeft' && dx >= -5 || key === 'ArrowRight' && dx <= 5) return;
      const vertical = key === 'ArrowUp' || key === 'ArrowDown';
      const value = (vertical ? Math.abs(dy) : Math.abs(dx)) + (vertical ? Math.abs(dx) : Math.abs(dy)) * 2;
      if (value < score) { score = value;best = n; }
    });
    if (best) { best.focus();return true; } return false;
  }
  window.nativeKey = function (key) {
    if (key === 'y') { nativeFavorite();return; }
    if (key === 'Enter') { const node = document.activeElement; if (node && node.tagName === 'BUTTON') node.click(); else if (current()) send('play', current().id); return; }
    if (key === 'x') { if (ps5) openView('search'); else send('search', ''); return; }
    if (key === 'm') { openView(ps5 ? 'center' : data && data.inGame ? 'game' : 'settings'); return; }
    if ((key === 'ArrowUp' || key === 'ArrowDown') && opened) {
      const scope = ps5 && opened === 'center' ? document.querySelector('#center') : container();
      const buttons = [...scope.querySelectorAll('button')]; const at = buttons.indexOf(document.activeElement); buttons[(at + (key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length]?.focus(); return;
    }
    if (!opened && (key === 'ArrowUp' || key === 'ArrowDown' || !document.activeElement?.matches('.tile') && (key === 'ArrowLeft' || key === 'ArrowRight'))) { if (spatial(key)) return; }
    if ((key === 'ArrowRight' && selected === visibleGames().length - 1 && data.hasNext) || (key === 'ArrowLeft' && selected === 0 && data.hasPrevious)) { send('move', key === 'ArrowRight' ? 1 : -1); return; }
    document.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
  };
  window.nativeAdapterReady = true;
  document.title='xCLOUD';root.setAttribute('aria-label','xCLOUD');
  setInterval(account,60000);
})();
