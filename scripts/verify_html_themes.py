#!/usr/bin/env python3
"""Browser verification for the copied HTML themes. Requires Playwright and Chromium; no APK dependencies."""
from pathlib import Path
from functools import partial
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
import threading
import shutil
from playwright.sync_api import sync_playwright
import json
root=Path(__file__).resolve().parents[1]
server=ThreadingHTTPServer(('127.0.0.1',0),partial(SimpleHTTPRequestHandler,directory=str(root/'app/src/main/assets/themes')))
threading.Thread(target=server.serve_forever,daemon=True).start()
adapter=(root/'app/src/main/assets/themes/native-adapter.js').read_text()
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=shutil.which('chromium') or shutil.which('google-chrome'),headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
 for mode,file in [('ps4','simulacao-ps4.html'),('ps5','simulacao-ps5.html')]:
  page=browser.new_page(viewport={'width':1280,'height':720});errors=[]
  page.on('pageerror',lambda e:errors.append(str(e)))
  page.add_init_script('window.nativeCalls=[];window.NativeTheme={post:(name,value)=>nativeCalls.push({name,value})}')
  page.goto(f'http://127.0.0.1:{server.server_port}/'+file)
  games=page.evaluate('games.map(g=>({...g,genre:"Xbox Cloud Gaming",favorite:false}))')
  page.evaluate('(name)=>window.nativeTheme=name',mode)
  page.add_script_tag(content=adapter)
  payload={'games':games,'selected':0,'total':len(games),'hasPrevious':False,'hasNext':False,'tab':'recent','profile':{'name':'Jogador real','gamertag':'Demo de teste','plan':'GAME PASS ULTIMATE'},'quota':{'label':'Tempo restante: indisponível','unlimited':False},'settings':{'resolution':'1080p','video':'Equilibrado','touch':'Controle virtual','image':'Imagem original','audio':'Padrão'},'inGame':False,'night':False,'motion':False,'actions':['hud','touch','xbox']}
  page.evaluate('value=>nativeReceive(value)',payload)
  assert page.evaluate('nativeAdapterReady')
  assert page.locator('.studio-head').is_hidden()
  assert page.locator('.screen').evaluate('n=>getComputedStyle(n).getPropertyValue("--motion").trim()')=='paused'
  page.evaluate('nativeKey("ArrowRight")')
  assert any(x['name']=='select' and x['value']==games[1]['id'] for x in page.evaluate('nativeCalls'))
  page.locator('#rail .tile.selected' if mode=='ps5' else '#tiles .tile.selected').click()
  assert any(x['name']=='play' and x['value']==games[1]['id'] for x in page.evaluate('nativeCalls'))
  page.evaluate('nativeKey("y")')
  assert any(x['name']=='favorite' for x in page.evaluate('nativeCalls'))
  page.evaluate('openView("profile")')
  assert 'Jogador real' in page.locator('#panel' if mode=='ps5' else '#dialog').inner_text()
  assert not page.locator('.infinity').filter(has_text='∞').is_visible()
  page.evaluate('closeView()')
  page.screenshot(path=str(root/'app/build/reports/themes'/('html-'+mode+'-home.png')))
  payload['inGame']=True;page.evaluate('value=>nativeReceive(value)',payload)
  page.screenshot(path=str(root/'app/build/reports/themes'/('html-'+mode+'-game-menu.png')))
  if mode=='ps5':page.locator('[data-action="home"]').click()
  page.get_by_role('button',name='Encerrar partida',exact=True).click()
  assert not any(x['name']=='end' for x in page.evaluate('nativeCalls'))
  page.get_by_role('button',name='Encerrar partida',exact=True).click()
  assert sum(x['name']=='end' for x in page.evaluate('nativeCalls'))==1
  assert not errors,errors
  print(mode, 'literal HTML, real catalog bridge, controller, profile, quota and end confirmation: OK')
  page.close()
 browser.close()
server.shutdown();server.server_close()
