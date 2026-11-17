/* Connects the unmodified supplied documents to the native app. No account credentials enter this page. */
(() => {
  'use strict';
  if (!Element.prototype.replaceChildren) Element.prototype.replaceChildren = function (...nodes) { while (this.firstChild) this.removeChild(this.firstChild);this.append(...nodes); };
  if (!Array.prototype.at) Array.prototype.at = function (index) { return this[index < 0 ? this.length + index : index]; };
  const ps5 = window.nativeTheme === 'ps5';
  const root = document.querySelector('#screen');
  const baseOpen = openView, baseClose = closeView, baseMotion = motion;
  const rail = document.querySelector(ps5 ? '#rail' : '#tiles');
  let data = null, applying = false, opened = '', searchTimer, heroTimer, heroTarget = '', settingsSection = 'appearance';
  let touchStart=null, suppressClickUntil=0;
  const send = (name, value = '') => window.NativeTheme.post(name, String(value));
  const current = () => visibleGames()[selected];
  const text = (node, value) => { const next=value==null ? '' : String(value);if (node && node.textContent!==next) node.textContent=next; };
  const style = document.createElement('style');
  style.textContent = 'html,body{width:100%;height:100%;overflow:hidden}.studio{width:100%!important;max-width:none!important;padding:0!important;margin:0!important}.studio-head,.studio-foot{display:none!important}.frame,.frame.mobile{width:100%!important;height:100vh!important;max-width:none!important;aspect-ratio:auto!important;border:0!important;box-shadow:none!important;margin:0!important}.screen,.mobile .screen{height:var(--native-height,720px)!important}.native-game .hero-art,.native-game .scrim,.native-game .particles,.native-game .topbar,.native-game .rail,.native-game .feature,.native-game .quick-area,.native-game .console-header,.native-game .main,.native-game .footer{display:none!important}.native-back{position:absolute;right:20px;top:15px;z-index:9;background:transparent;border:0;color:white;font-size:25px;padding:8px 15px}';
  document.head.append(style);
  style.textContent += '.overlay,.shade{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}.native-lite .quick-card,.native-lite .library-tile,.native-lite .more{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}.native-lite .particles{display:none}.native-lite .tile{transition:none!important}';
  const resize = () => {
    document.querySelector('#frame').classList.toggle('mobile', innerWidth / innerHeight > 1.95);
    root.style.setProperty('--native-height', `${innerHeight * 1280 / innerWidth}px`);
    scale();
  };
  addEventListener('resize', resize); resize();
  const quotaMark = () => data && data.quota.unlimited ? '∞' : data && data.quota.value || '—';
  function account() {
    if (!data) return;
    document.querySelectorAll('.profile-trigger .avatar,.utilities .avatar').forEach(n => text(n, data.profile.name.slice(0, 1).toUpperCase()));
    const name = document.querySelector('.account');
    if (name && name.dataset.profile!==data.profile.name+'\n'+data.profile.gamertag) { name.dataset.profile=data.profile.name+'\n'+data.profile.gamertag;name.replaceChildren(document.createTextNode(data.profile.name)); const sub = document.createElement('small'); text(sub, data.profile.gamertag); name.append(sub); }
    text(document.querySelector('.quota-pill span'), data.profile.plan);
    text(document.querySelector('.quota-pill b'), quotaMark());
    const benefit = document.querySelector('.quick-card .tier');
    if (benefit) { text(benefit, data.profile.plan); text(benefit.parentElement.querySelector('strong'), data.quota.label); }
    document.querySelectorAll('.quick-card .infinity').forEach(n => text(n, quotaMark()));
    const clock = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    document.querySelectorAll('.clock,.center-time').forEach(n => text(n, clock));
    document.querySelectorAll('.center-heading span').forEach(n => text(n, ''));
    text(document.querySelector('#hud-label'),data.settings.touch);
    text(document.querySelector('#mic-label'),'Áudio e microfone');
    const cards = document.querySelectorAll('.center-card');
    if (cards[2]) { text(cards[2].querySelector('strong'), quotaMark()); text(cards[2].querySelector('p'), `${data.profile.plan}\n${data.quota.label}`); }
    document.querySelectorAll('.demo-stats,.stats-demo').forEach(n => n.classList.remove('open', 'visible'));
  }
  function artwork(image,url) {
    if(image.dataset.source===url) return;
    image.dataset.source=url;
    if(url) image.src=url;else image.removeAttribute('src');
  }
  function hero(url) {
    if(!ps5 || heroTarget===url) return;
    heroTarget=url;clearTimeout(heroTimer);
    const image=document.querySelector('#hero-art');
    if(!url) { artwork(image,'');return; }
    // Holding a direction should not decode every intermediate panoramic image.
    heroTimer=setTimeout(() => { if(heroTarget===url) artwork(image,url); },160);
  }
  function tile(game) {
    const button=document.createElement('button'),image=new Image();
    let activate;
    button.className='tile';button.dataset.game=game.id;image.alt='';image.decoding='async';button.append(image);
    const badge=document.createElement('span');badge.className=ps5 ? 'tiny-cloud' : 'tile-index';
    if(ps5) badge.innerHTML=icon('cloud');else { text(badge,'NA NUVEM');const strip=document.createElement('span');strip.className='start-strip';text(strip,'Iniciar');button.append(strip); }
    button.append(badge);
    const select=focus => { const index=visibleGames().findIndex(g=>g.id===button.dataset.game);if(index>=0 && selected!==index) { selected=index;render(focus); } };
    button.onpointerdown=() => { activate=current()?.id===button.dataset.game; };
    button.onclick=event => { if(event.detail===0 ? current()?.id===button.dataset.game : activate===true) send('play',button.dataset.game);else select(true);activate=undefined; };
    button.onfocus=() => select(false);
    return button;
  }
  render = function (focus=false) {
    if(!data) return;
    const list = visibleGames();
    selected=Math.max(0,Math.min(selected,list.length-1));
    const existing=new Map([...rail.children].map(button=>[button.dataset.game,button]));
    list.forEach((game,index) => {
      const button=existing.get(game.id) || tile(game);existing.delete(game.id);
      if(rail.children[index]!==button) rail.insertBefore(button,rail.children[index] || null);
      button.classList.toggle('selected',index===selected);
      const tabIndex=index===selected ? 0 : -1,pressed=String(index===selected);
      if(button.tabIndex!==tabIndex) button.tabIndex=tabIndex;
      if(button.getAttribute('aria-label')!==game.name) button.setAttribute('aria-label',game.name);
      if(button.getAttribute('aria-pressed')!==pressed) button.setAttribute('aria-pressed',pressed);
      artwork(button.querySelector('img'),game.image || '');
    });
    existing.forEach(button=>button.remove());
    const game=current(),title=document.querySelector('#game-title');text(title,game?.name || data.notice || 'Biblioteca');
    if(ps5) {
      title.classList.toggle('long-title',title.textContent.length>18);
      text(document.querySelector('#genre'),game?.genre || 'FAVORITOS');text(document.querySelector('#selected-caption'),game?.name || '');
      text(document.querySelector('#description'),game ? 'Seu catálogo em destaque. Retome o jogo e descubra sua próxima partida.' : data.notice || 'Marque um jogo como favorito para encontrá-lo aqui.');
      document.querySelector('#play').disabled=!game;hero(game?.hero || '');
    } else {
      text(document.querySelector('#game-genre'),game?.genre || 'Use Y ou o coração para adicionar um jogo.');
      document.querySelector('#favorite').setAttribute('aria-pressed',String(!!game && favorites.has(game.id)));document.querySelector('#favorite').disabled=!game;
    }
    document.querySelectorAll('[data-tab]').forEach(button=>{button.classList.toggle('active',button.dataset.tab===tab);button.setAttribute('aria-selected',String(button.dataset.tab===tab));});
    if(focus) rail.children[selected]?.focus();
    if (!applying && current()) send('select', current().id);
  };
  const nativeFavorite = () => { if (current()) send('favorite', current().id); };
  if (!ps5) { favorite = nativeFavorite;document.querySelector('#favorite').onclick = nativeFavorite; }
  const rows = (target, label, value, action) => { const button=ps5 ? row(target,label,value,action) : row(label,value,action);button.dataset.focus=label;return button; };
  const container = () => document.querySelector(ps5 ? '#panel' : '#dialog');
  function backButton() {
    const b = document.createElement('button'); b.className = ps5 ? 'back' : 'dialog-back';b.dataset.focus='back';text(b, 'Voltar'); b.onclick = closeView; container().append(b);
  }
  function closeButton() {
    if (container().querySelector('.native-back')) return;
    const b = document.createElement('button'); b.className = 'native-back';b.dataset.focus='close'; b.setAttribute('aria-label', 'Fechar'); text(b, '×'); b.onclick = closeView; container().append(b);
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
  function settingsPanel(section = settingsSection) {
    settingsSection=section;
    const p = container(); heading('Configurações');
    const titles = { appearance: 'Aparência', video: 'Vídeo e transmissão', controls: 'Controles e toque', audio: 'Áudio e microfone', account: 'Biblioteca e conta', diagnostic: 'Ajuda e diagnóstico' };
    let content = p;
    if (ps5) {
      const layout = document.createElement('div'), nav = document.createElement('nav'); layout.className = 'settings-layout'; nav.className = 'settings-nav'; content = document.createElement('div');
      Object.entries(titles).forEach(([key, label]) => { const b = document.createElement('button');b.dataset.focus='section-'+key; text(b, label); b.classList.toggle('active', key === section); b.onclick = () => { settingsPanel(key);container().querySelector('.settings-nav .active')?.focus(); }; nav.append(b); });
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
    if (!ps5 || section === 'account') [['GAME_PASS','Game Pass'],['PURCHASED','Transmita seu jogo'],['ADDED','Adições recentes']].forEach(([key,label]) => rows(content,label,'',() => { send('category',key);closeView(); }));
    if (section === 'diagnostic') add('Ajuda e diagnóstico', '', 'DIAGNOSTICS');
    backButton(); closeButton();
  }
  function paintSearch() {
    const input = container().querySelector('input'), results = container().querySelector('.search-results');
    if (!input || !results) return;
    input.maxLength=512;
    results.replaceChildren();
    games.forEach(g => {
      const b = document.createElement('button'), im = new Image(), t = document.createElement('span'); b.className = 'search-result'; im.src = g.image; im.alt = ''; text(t, g.name); b.append(im, t); b.onclick = () => send('play', g.id); results.append(b);
    });
    input.oninput = () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => send('search', input.value), 180); };
  }
  openView = function (next) {
    if (!data) return;
    if (next === 'home') { closeView();return; }
    if (next === 'friends') { send('friends'); return; }
    opened = next; baseOpen(next); send('modal', true);
    if (next === 'profile') profilePanel();
    else if (next === 'settings') settingsPanel();
    else if (next === 'game' || next === 'controls' || next === 'image') {
      heading(next === 'image' ? 'Imagem e tela' : 'Controles e toque'); rows(container(), next === 'image' ? 'Imagem e tela' : 'Controles e toque', next === 'image' ? data.settings.image : data.settings.touch, () => send('settings', next === 'image' ? 'VIDEO' : 'CONTROLS')); backButton(); closeButton();
    } else if (next === 'options') {
      heading(current() ? current().name : 'Biblioteca'); rows(container(), 'Favoritos', '', nativeFavorite); rows(container(), 'Atalho na tela inicial', '', () => send('shortcut', current() ? current().id : '')); backButton(); closeButton();
    } else if (next === 'search') { paintSearch(); closeButton();send('category', 'all'); }
    if (next === 'center') {
      account();
      const centerCards = document.querySelectorAll('.center-card');
      if (centerCards[0]) centerCards[0].onclick = () => send('settings', 'CONTROLS');
      if (centerCards[1]) centerCards[1].onclick = () => send('audio');
      const home = document.querySelector('[data-action="home"]'); if (home) home.onclick = closeView;
    }
    (ps5 && next === 'center' ? document.querySelector('#center') : container()).querySelector('button')?.focus();
  };
  if (ps5) {
    settings = settingsPanel;
    document.querySelector('#play').onclick = () => { if (current()) send('play', current().id); };
    document.querySelector('#mic').onclick = () => send('audio');
    document.querySelector('#stats-toggle').onclick = () => send('statistics');
  }
  closeView = function () {
    clearTimeout(searchTimer);
    baseClose(); opened = ''; send('modal', false);
  };
  function refreshPanel(build) {
    const key=document.activeElement?.dataset.focus,scroll=container().scrollTop;
    build();container().scrollTop=scroll;
    const button=[...container().querySelectorAll('[data-focus]')].find(n=>n.dataset.focus===key);
    if(button) button.focus();
  }
  window.nativeReceive = function (value) {
    const previous=data;data = value; applying = true;
    root.classList.toggle('native-lite',!!value.lowEffects);
    games.splice(0, games.length, ...value.games);
    if (ps5) { Object.keys(labels).forEach(k => delete labels[k]); games.forEach(g => { labels[g.id] = [g.name, g.genre]; }); }
    favorites = new Set(games.filter(g => g.favorite).map(g => g.id));
    tab = value.tab === 'favorites' ? 'favorites' : value.tab === 'recent' ? ps5 ? 'games' : 'recent' : ps5 ? 'library' : 'all';
    selected = value.selected;
    document.querySelector('#motion').checked = value.motion;
    if (ps5) theme(value.night ? 'night' : 'immersive'); else setTheme(value.night ? 'night' : 'blue');
    render(); baseMotion(); account(); applying = false;
    if (!games.length) { text(document.querySelector('#game-title'), value.notice || 'Biblioteca');text(document.querySelector('#description'), value.notice || ''); }
    if (opened === 'profile' && JSON.stringify([previous?.profile,previous?.quota])!==JSON.stringify([value.profile,value.quota])) refreshPanel(profilePanel);
    else if (opened === 'settings' && JSON.stringify([previous?.settings,previous?.profile,previous?.quota,previous?.night,previous?.motion])!==JSON.stringify([value.settings,value.profile,value.quota,value.night,value.motion])) refreshPanel(settingsPanel);
    else if (opened === 'search') paintSearch();
    const fixed = document.querySelector('.center-heading span'); if (fixed) text(fixed, '');
    if (!opened && (document.activeElement === document.body || !root.contains(document.activeElement))) root.querySelector('.tile.selected')?.focus();
  };
  document.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => send('category', b.dataset.tab));
  rail.addEventListener('touchstart',event => { const t=event.touches[0];touchStart=t ? {x:t.clientX,y:t.clientY} : null; },{passive:true});
  rail.addEventListener('touchend',event => {
    const t=event.changedTouches[0];if(!t || !touchStart) return;
    const dx=t.clientX-touchStart.x,dy=t.clientY-touchStart.y;touchStart=null;
    if(Math.abs(dx)>45 && Math.abs(dx)>Math.abs(dy)*1.5) { suppressClickUntil=performance.now()+450;send('move',dx<0 ? 1 : -1); }
  },{passive:true});
  rail.addEventListener('click',event => { if(performance.now()<suppressClickUntil) { event.preventDefault();event.stopImmediatePropagation(); } },true);
  function spatial(key,scope=root) {
    const active = document.activeElement, origin = active && active.getBoundingClientRect();
    if (!origin || !origin.width) return false;
    const ox = origin.x + origin.width / 2, oy = origin.y + origin.height / 2;
    const candidates = [...scope.querySelectorAll('button,input')].filter(n => n !== active && !n.disabled).map(n => ({ n, r: n.getBoundingClientRect() })).filter(({ r }) => r.width > 0 && r.height > 0);
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
    if(!data) return;
    if (key === 'Escape') { closeView();return; }
    if (key === 'y') { if(!opened) nativeFavorite();return; }
    if (key === 'Enter') { const node = document.activeElement; if (node && node.tagName === 'INPUT') { clearTimeout(searchTimer);send('search', node.value); } else if (node && node.tagName === 'BUTTON' && root.contains(node)) node.click(); else if (!opened && current()) send('play', current().id); return; }
    if (key === 'x') { if (ps5) openView('search'); else send('search', ''); return; }
    if (key === 'm') { openView(ps5 ? 'center' : 'settings'); return; }
    if(key.startsWith('Arrow') && document.activeElement?.tagName==='INPUT' && (key==='ArrowLeft' || key==='ArrowRight')) {
      const input=document.activeElement,at=Math.max(0,Math.min(input.value.length,input.selectionStart+(key==='ArrowRight' ? 1 : -1)));input.setSelectionRange(at,at);return;
    }
    if (key.startsWith('Arrow') && opened) {
      const scope = ps5 && opened === 'center' ? document.querySelector('#center') : container();
      if(!scope.contains(document.activeElement)) scope.querySelector('button,input')?.focus();else spatial(key,scope);return;
    }
    if(key==='ArrowLeft' || key==='ArrowRight') {
      if(!document.activeElement?.matches('.tile')) { spatial(key);return; }
      const next=selected+(key==='ArrowRight' ? 1 : -1);
      if(next<0 || next>=visibleGames().length) { if(next<0 ? data.hasPrevious : data.hasNext) send('move',key==='ArrowRight' ? 1 : -1);return; }
      selected=next;render(true);return;
    }
    if(key==='ArrowUp' || key==='ArrowDown') spatial(key);
  };
  document.addEventListener('keydown',event => {
    if(event.isComposing || !['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Enter','Escape'].includes(event.key)) return;
    if(event.target.tagName==='INPUT' && (event.key==='ArrowLeft' || event.key==='ArrowRight')) return;
    event.preventDefault();event.stopImmediatePropagation();nativeKey(event.key);
  },true);
  window.nativeAdapterReady = true;
  document.title='xCLOUD';root.setAttribute('aria-label','xCLOUD');
  setInterval(account,60000);
})();
