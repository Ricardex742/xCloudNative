#!/usr/bin/env python3
"""Exercise the lightweight runtime documents and their real-data update paths in Chromium."""
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
import json
import shutil
import threading
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets/themes'
report = root / 'app/build/reports/themes'
report.mkdir(parents=True, exist_ok=True)
adapter = (assets / 'native-adapter.js').read_text()
documents, fixtures, metrics = {}, {}, {}
for mode in ('ps4', 'ps5'):
    source = (assets / f'simulacao-{mode}.html').read_text()
    start = source.index('const games=')
    end = source.index('\n', start)
    fixtures[mode] = json.loads(source[start + len('const games='):end].strip().removesuffix(';'))
    shell = source[:start] + 'const games=[];' + source[end:]
    bootstrap = 'render();scale();motion();'
    at = shell.rindex(bootstrap)
    document = shell[:at] + f'window.nativeTheme="{mode}";\n' + adapter + '\n;scale();motion();' + shell[at + len(bootstrap):]
    assert 'data:image/' not in document
    documents[f'/simulacao-{mode}.html'] = document.encode()
    metrics[mode] = {'sourceBytes': len(source.encode()), 'runtimeBytes': len(document.encode())}

class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        document = documents.get(self.path)
        if document is None:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.end_headers()
        self.wfile.write(document)

    def log_message(self, *args):
        pass

server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=shutil.which('chromium') or shutil.which('google-chrome'), headless=True, args=['--no-sandbox', '--disable-dev-shm-usage'])
        for mode in ('ps4', 'ps5'):
            page = browser.new_page(viewport={'width': 1280, 'height': 720})
            errors = []
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.add_init_script('window.nativeCalls=[];window.NativeTheme={post:(name,value)=>nativeCalls.push({name,value})}')
            page.goto(f'http://127.0.0.1:{server.server_port}/simulacao-{mode}.html')
            assert page.evaluate('nativeAdapterReady && games.length===0')
            assert page.locator('.tile').count() == 0
            games = [dict(g, genre='Xbox Cloud Gaming', favorite=False) for g in fixtures[mode]]
            payload = {'games': games, 'selected': 0, 'total': len(games), 'hasPrevious': False, 'hasNext': True, 'tab': 'recent', 'profile': {'name': 'Jogador real', 'gamertag': 'Demo de teste', 'plan': 'GAME PASS ULTIMATE'}, 'quota': {'label': 'Tempo restante: indisponível', 'unlimited': False}, 'settings': {'resolution': '1080p', 'touch': 'Controle virtual', 'image': 'Imagem original', 'audio': 'Padrão'}, 'inGame': False, 'night': False, 'motion': False, 'lowEffects': True}
            page.evaluate('value=>nativeReceive(value)', payload)
            assert page.locator('.studio-head').is_hidden()
            rail = '#rail' if mode == 'ps5' else '#tiles'
            # Focus caused by a touch must select the tile before a second tap starts it.
            page.locator(rail + ' .tile').nth(2).click()
            assert not any(c['name'] == 'play' for c in page.evaluate('nativeCalls'))
            page.locator(rail + ' .tile').nth(2).click()
            assert sum(c['name'] == 'play' for c in page.evaluate('nativeCalls')) == 1
            page.evaluate('value=>nativeReceive(value)', payload)
            page.evaluate('window.nativeCalls=[]')
            if mode == 'ps5':
                page.evaluate('window.heroLoads=[];new MutationObserver(records=>heroLoads.push(...records.filter(r=>r.attributeName==="src"))).observe(document.querySelector("#hero-art"),{attributes:true})')
            nav = page.evaluate('''() => {
                const rail=document.querySelector(window.nativeTheme==='ps5'?'#rail':'#tiles');
                const first=rail.firstElementChild, observer=new MutationObserver(()=>{});
                observer.observe(rail,{childList:true,subtree:true});
                const start=performance.now();for(let i=0;i<100;i++)nativeKey(i%2?'ArrowLeft':'ArrowRight');
                const navigationMs=performance.now()-start;
                const addedNodes=observer.takeRecords().reduce((n,r)=>n+r.addedNodes.length,0);observer.disconnect();
                return {navigationMs,addedNodes,reusedFirstTile:first===rail.firstElementChild};
            }''')
            assert nav['reusedFirstTile'] and nav['addedNodes'] == 0, nav
            metrics[mode].update(nav)
            if mode == 'ps5':
                page.wait_for_timeout(180)
                assert page.evaluate('heroLoads.length') <= 1
            page.evaluate('nativeKey("ArrowRight")')
            assert any(c['name'] == 'select' and c['value'] == games[1]['id'] for c in page.evaluate('nativeCalls'))
            page.locator(rail + ' .tile.selected').click()
            assert any(c['name'] == 'play' and c['value'] == games[1]['id'] for c in page.evaluate('nativeCalls'))
            page.evaluate('nativeKey("y")')
            assert any(c['name'] == 'favorite' for c in page.evaluate('nativeCalls'))
            # Neither quota refresh nor a preference update may lose the focused settings row.
            page.evaluate('openView("settings")')
            if mode == 'ps5':
                page.get_by_role('button', name='Vídeo e transmissão', exact=True).click()
                page.evaluate('nativeKey("ArrowRight")')
                assert page.evaluate('document.activeElement.dataset.focus') in ('Resolução solicitada', 'Perfil de imagem')
            else:
                page.get_by_role('button', name='Movimento do fundo', exact=False).focus()
            focus = page.evaluate('document.activeElement.dataset.focus')
            payload['quota']['label'] = 'Tempo restante: 42 min'
            payload['quota']['value'] = '42min'
            page.evaluate('value=>nativeReceive(value)', payload)
            assert page.evaluate('document.activeElement.dataset.focus') == focus
            if mode == 'ps5':
                assert page.locator('.settings-nav .active').inner_text() == 'Vídeo e transmissão'
                page.evaluate('nativeKey("Enter")')
                assert any(c['name'] == 'settings' and c['value'] in ('VIDEO', 'IMAGE') for c in page.evaluate('nativeCalls'))
            plays = sum(c['name'] == 'play' for c in page.evaluate('nativeCalls'))
            page.evaluate('document.activeElement.blur();nativeKey("Enter")')
            assert sum(c['name'] == 'play' for c in page.evaluate('nativeCalls')) == plays
            page.evaluate('closeView();openView("profile")')
            panel = '#panel' if mode == 'ps5' else '#dialog'
            assert 'Jogador real' in page.locator(panel).inner_text()
            assert not page.locator('.infinity').filter(has_text='∞').is_visible()
            page.evaluate('closeView()')
            # Crossing the bounded window asks Kotlin for the next page.
            page.evaluate('for(let i=0;i<5;i++)nativeKey("ArrowRight")')
            assert any(c['name'] == 'move' and c['value'] == '1' for c in page.evaluate('nativeCalls'))
            # A horizontal swipe must not also launch the touched tile.
            plays = sum(c['name'] == 'play' for c in page.evaluate('nativeCalls'))
            page.evaluate('''() => {
                const rail=document.querySelector(window.nativeTheme==='ps5'?'#rail':'#tiles');
                const start=new Event('touchstart',{bubbles:true});start.touches=[{clientX:300,clientY:180}];rail.dispatchEvent(start);
                const end=new Event('touchend',{bubbles:true});end.changedTouches=[{clientX:180,clientY:185}];rail.dispatchEvent(end);
                rail.querySelector('.tile.selected').click();
            }''')
            assert sum(c['name'] == 'play' for c in page.evaluate('nativeCalls')) == plays
            page.evaluate('openView("settings")')
            if mode == 'ps5': page.get_by_role('button', name='Biblioteca e conta', exact=True).click()
            page.get_by_role('button', name='Transmita seu jogo', exact=True).click()
            assert any(c['name'] == 'category' and c['value'] == 'PURCHASED' for c in page.evaluate('nativeCalls'))
            if mode == 'ps5':
                page.evaluate('openView("search")')
                search = page.get_by_role('textbox', name='Pesquisar jogos')
                search.fill('Forza')
                page.wait_for_timeout(220)
                payload['games'] = games[1:2]
                payload['selected'] = 0
                page.evaluate('value=>nativeReceive(value)', payload)
                assert search.input_value() == 'Forza'
                assert any(c['name'] == 'search' and c['value'] == 'Forza' for c in page.evaluate('nativeCalls'))
                page.evaluate('closeView()')
            page.wait_for_timeout(180)
            page.screenshot(path=str(report / f'html-{mode}-home.png'))
            assert not errors, errors
            print(mode, 'startup without demo, stable tiles/focus, four-way controller, paging, touch and search: OK', metrics[mode])
            page.close()
        browser.close()
finally:
    server.shutdown()
    server.server_close()
(report / 'html-validation.json').write_text(json.dumps(metrics, indent=2) + '\n')
