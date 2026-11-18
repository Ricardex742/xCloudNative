# Guia para o Codex: temas nativos Xbox, referências PS4/PS5 e compilação do APK

Este documento complementa `CODEX_CONTEXT.md`, por solicitação explícita do usuário para continuidade em outro ambiente. O objetivo desta entrega é documentar a arquitetura e orientar uma implementação correta. Esta entrega não implementa temas PS4/PS5 nem altera o APK.

## 1. O que foi feito originalmente

Os seis dashboards Xbox foram implementados no cliente Android com Kotlin, Views nativas e desenhos Canvas/Drawable. A identidade de cada console envolve composição, tamanhos, tipografia, foco, painéis e fila, além da paleta de cores.

As opções Xbox são Original, 360 Blades, 360 NXE, 360 Metro, One e Series X|S. As sete paletas simples usam o caminho genérico sem uma cena de console.

PS4 e PS5 foram solicitados inicialmente como **simulações visuais**. Eu os preparei como documentos HTML/CSS/JavaScript independentes para testar composição e interação no navegador. Não eram temas Android prontos. Seus seletores de jogos, conta, assinatura, configurações e início de partida eram demonstrações. Não havia uma integração completa de fila e ciclo de sessão.

A orientação para levar o desenho ao aplicativo é reconstruir a apresentação nos componentes Android do projeto, usando os serviços e estados reais existentes.

## 2. Evidências verificadas no histórico

Referências fixas deste exame:

| Referência | Significado |
| --- | --- |
| `73f4f797d27ea8debb38626323ff1f8fa038151d` | Base com temas Xbox nativos e navegação simplificada; versão `0.38.2-menu-lab`, código 91 |
| `a0ec581` | Primeiro trabalho posterior de temas PlayStation nativos no histórico; examinar diferenças, sem presumir que está validado |
| `d7624e7` | Introdução posterior dos documentos HTML como temas no app |
| `004621ecce94c23d63ca9485bea293f31810e724` | Implementação HTML posterior examinada; versão `0.39.2-html-fix`, código 94 |
| `1cf14eb58b6fed93031af2582c34d1dde95046fd` | Restauração posterior da árvore `0.38.2-menu-lab`; os arquivos desse commit são idênticos aos de `73f4f79` |

No snapshot `004621e`, encontrei:

- `app/src/main/assets/themes/simulacao-ps4.html` e `simulacao-ps5.html`: os arquivos brutos são idênticos aos protótipos originais, respectivamente 232.463 e 1.315.934 bytes.
- `ui/dashboard/HtmlConsoleTheme.kt`: uma WebView com `loadDataWithBaseURL`, bridge `NativeTheme` e atualizações com `evaluateJavascript`.
- `native-adapter.js`: adaptação para dados reais e ações do app. Houve trabalho de integração: o documento preparado remove o catálogo de demonstração antes de carregar, há janela de cinco jogos, atualizações agrupadas e reduções de efeitos. Portanto, não é correto afirmar que não houve adaptação alguma ou que todas as imagens base64 do arquivo bruto chegavam à WebView.
- `UiTheme.PS4` e `UiTheme.PS5` sem uma `ConsoleScene` própria: herdavam `NONE`.
- `GameLoadingView`, `ConsolePanelSkin` e `ConsoleDialogs` sem mudanças em relação à base nativa examinada. O adaptador HTML oferecia parte dos ajustes e encaminhava outros aos painéis nativos, que continuavam com a apresentação genérica.

Isso confirma uma integração diferente da usada nos Xbox e uma lacuna de cobertura visual em fila/painéis. O relato de travamentos exige reprodução e medição no aparelho; a presença de WebView, sozinha, não determina a causa de todo engasgo.

No momento do exame, o último build remoto aprovado era do commit `004621e` (execução `44189268450`), anterior à restauração `1cf14eb`. Um APK desse build continua contendo a implementação anterior, mesmo depois de a branch ter sido restaurada. Sempre conferir SHA do build e SHA desejado.

## 3. Mapa da arquitetura Xbox a reutilizar

Os caminhos abaixo partem de `app/src/main/java/com/xcloud/tclnative/`.

| Arquivo | Responsabilidade |
| --- | --- |
| `settings/AppearanceSettings.kt` | `UiTheme`, `ConsoleScene`, persistência da aparência e opção de movimento |
| `ui/dashboard/UiPalette.kt` | Cores por função: texto, fundo, painel, fila, busca, seleção e transição |
| `ui/dashboard/DashboardTheme.kt` | Aplica a aparência às Views e janelas; componentes de texto, botões e foco |
| `ui/dashboard/GameDashboardView.kt` | Catálogo real, layout de cada cena, listas, seleção, foco, cabeçalhos e ações |
| `ui/dashboard/ConsoleDashboardChrome.kt` | Estrutura decorativa e geometria dos controles/cartões de cada console |
| `ui/dashboard/ConsoleGraphics.kt` | Ícones vetoriais e drawables de apoio |
| `ui/dashboard/ConsoleBrandView.kt` | Marca e composição do cabeçalho de acordo com a cena |
| `ui/dashboard/ConsoleScenePainter.kt` | Desenho vetorial dos fundos Xbox |
| `ui/dashboard/ConsoleThemeBackdrop.kt` | Nó de renderização do fundo e agendamento de animação com ciclo de vida |
| `ui/dashboard/PosterLoader.kt`, `ArtworkRequest.kt` | Imagens reais, cache compartilhado, tamanho limitado e cancelamento |
| `ui/dashboard/ConsoleDialogs.kt` | Janelas nativas, cabeçalho, X, Voltar, pesquisa, listas e atalhos |
| `ui/dashboard/ConsolePanelSkin.kt` | Fundo, contorno, fontes, estados e geometria dos painéis de cada cena |
| `ui/dashboard/GameLoadingView.kt` | Fila, estimativa real, tempo aguardado, etapas e cancelamento |
| `ui/dashboard/SessionTransitionView.kt` | Telas de preparação inicial e retorno ao catálogo, separadas da fila |
| `ui/dashboard/GameMenuPanel.kt` | Atalhos reais durante a partida e encerramento explícito |
| `ui/dashboard/PlaybackSettingsPanel.kt` | Categorias e ajustes reais; o tema não deve criar outra árvore de configurações |
| `ui/dashboard/AccountStatusBadge.kt` | Plano e tempo de nuvem confirmados, apresentados conforme o tema |
| `ui/MainActivity.kt` | Integra catálogo, fila, janelas, sessão e renderização; não concentra os desenhos dos temas |

### Paleta, cena e estado são responsabilidades diferentes

1. A paleta define cores por função.
2. A cena define composição e geometria do console.
3. O estado e as ações vêm dos serviços/camadas existentes do app.

Nos Xbox, os botões que parecem diferentes continuam sendo controles Android com clique, foco e acessibilidade. Decorativos não roubam toque ou D-pad. O catálogo continua usando `RecyclerView` e seus dados reais.

Adicionar uma cor azul ou um logo a uma paleta não cria um dashboard PS4. Criar uma home PS5 também não conclui filas, pesquisa, configurações e menus internos.

## 4. Como foram organizadas as simulações PS4 e PS5

### PS4

- Fundo azul com movimento discreto; opção noturna.
- Barra de funções, faixa horizontal de capas e identificação do jogo selecionado.
- Ações de jogar, favoritar, perfil e configurações demonstrativas.
- Navegação por setas, Enter e Escape; escolha de formato de prévia para celular.

### PS5

- Fundo panorâmico do jogo selecionado, com scrim para manter a leitura.
- Faixa superior de jogos, destaque do título e ações rápidas.
- Centro de controle, perfil e painel de configurações demonstrativos.
- Opção noturna, apresentação para celular e movimento opcional.
- Tipografia e cartões distintos do PS4.

### Limites dos dois protótipos

O layout foi estudado numa área de referência de 1280×720 com escala CSS. Os arquivos contêm imagens e dados ilustrativos, toasts e ações de demonstração. Respeitam redução de movimento e pausam efeitos em algumas situações do navegador, mas isso não implementa o ciclo de vida do Android.

As imagens de demonstração ajudam a julgar composição. Na versão nativa, capas, hero, nome da conta, plano, favoritos e quota devem ser fornecidos pelas camadas reais do aplicativo. O valor “Ilimitado nesta prévia” não é uma regra de assinatura.

CSS com blur, sombras, transparência, partículas, transições e manipulação de DOM não deve ser traduzido literalmente em trabalho contínuo por quadro no aparelho. Usar formas, gradientes, imagens dimensionadas e animações limitadas para obter o desenho desejado.

### Recuperar somente as referências visuais

As imagens PNG dos protótipos acompanham o pacote privado entregue ao usuário. Para examinar o HTML original pelo histórico, os caminhos acima existem em `004621e`; usar `git show COMMIT:caminho` para leitura. O pacote de contexto usa PNGs para referência, e o código Android continua sendo implementado a partir dos componentes nativos desta base.

## 5. Plano de implementação nativa dos temas PlayStation

Esta seção é uma orientação para o próximo trabalho autorizado de implementação.

### Primeiro: concluir a estrutura estática

- Acrescentar cenas próprias de PS4 e PS5 no modelo de aparência, ou uma estratégia equivalente que atenda aos mesmos componentes nativos. Não usar `ConsoleScene.NONE` para temas que exigem fila e painéis próprios.
- Acrescentar os ramos necessários em todos os componentes que dependem de cena. Procurar os `when` e os fallbacks: alguns caminhos genéricos de skin usam o visual Xbox Original ou verde como padrão e não podem ser herdados por acidente.
- Cobrir paleta, home, chrome/cartões, marca, busca, painéis, atalhos da partida, fila e transições. Atualizar enums sem reorganizar ou apagar preferências existentes.
- Reaproveitar as ações e os modelos do app. O tema muda apresentação; conta, catálogo, Xbox Cloud Gaming, input, login e WebRTC continuam nos caminhos atuais.
- Consolidar componentes de apresentação quando isso reduzir duplicação. Evitar uma segunda implementação de configurações só para PS4/PS5.

### Depois: foco e navegação

- Um item selecionado deve continuar identificável mesmo sem foco; o foco de interação também deve permanecer visível nos quatro lados.
- D-pad/setas, A/Enter, B/Voltar, controle remoto da TV e toque precisam funcionar.
- Depois de fechar um painel, restaurar a seleção e o foco anteriores. Retornar da fila/jogo não pode voltar a um item errado ou perder o catálogo.
- Não interceptar teclas do controle durante gameplay para navegar no tema. Respeitar o roteamento da Activity e das janelas.
- Manter os seis grupos de ajustes e as abas de vídeo/conexão da base. X fecha todos os painéis pertencentes ao contexto; Voltar retorna um nível. Encerrar a partida é separado e confirmado.
- No celular, preservar alvos de toque de pelo menos 48 dp, recortes/barras do sistema e rótulos legíveis. A edição do HUD tem seu próprio espaço de interação.

### Só então: animação

Animar uma versão estática já funcional. Preferir movimentos pequenos e um plano de fundo separado; medir o custo antes de aumentar complexidade. Uma cena bonita com interação travada não está pronta.

## 6. Cobertura mínima: home, fila e configurações

Usar esta matriz como requisito de conclusão para cada novo tema:

| Área | O que precisa preservar |
| --- | --- |
| Home/catálogo | Capas reais, busca, abas, favoritos, biblioteca, seleção e fallback de arte |
| Perfil | Conta real, plano, tempo limitado/ilimitado/consultando/indisponível |
| Configurações raiz | Mesmas seis categorias e ações, com apresentação coerente ao tema |
| Configurações profundas | Seletores, imagem avançada, áudio, controle físico, toque, estatísticas, conta e diagnóstico |
| Pesquisa | Campo real, teclado Android/controle conforme suporte, limpar, buscar e retornar |
| Fila | Estimativa do serviço, tempo aguardado, informação de atualização e cancelar |
| Preparação/conexão | Etapas reais e transição até o vídeo; não exibir sucesso por um temporizador |
| Partida | Atalhos nativos, controle virtual/toque nativo, estatísticas e retomada |
| Recuperação/erro | Mensagens reais, ações disponíveis e retorno ao contexto correto |
| Saída/retorno | Encerrar, liberar recursos da tela anterior e restaurar catálogo/foco |

### A fila tem estado real

`GameLoadingView.begin`, `setGame`, `updateQueue`, `updateProgress` e `render` recebem o jogo e os estados existentes. Sua apresentação por cena fica em `refreshAppearance`, `onMeasure` e `layoutConsole`.

Implementar PS4/PS5 nesses pontos ou numa composição nativa equivalente. Manter o botão cancelar clicável e focável. A estimativa “menos de 1 min” não é uma contagem regressiva garantida: não inventar segundos, avanço automático ou entrada fictícia no jogo.

A fila deve funcionar com e sem capas, em várias proporções e com texto comprido. O bug de fila após trocar controles é uma pendência de ciclo de sessão: redesenhar a fila não confirma a correção desse bug.

### Configurações têm vários níveis

`ConsoleDialogs` resolve a janela e a navegação; `ConsolePanelSkin` resolve a apresentação. A aparência deve alcançar fundo, cabeçalho, campos, linhas, botões, foco, busca e texto selecionado.

Um painel HTML de aparência com botões que abrem janelas nativas genéricas deixa uma diferença visível entre níveis. A versão nativa precisa aplicar a identidade também aos níveis internos. Testar textos longos e os 15 idiomas; idioma da interface e idioma solicitado ao jogo são preferências diferentes.

O botão Xbox continua sendo uma ação do serviço Xbox, ainda experimental. Uma aparência PlayStation não acrescenta serviços PSN, convites ou funcionalidades sociais inexistentes.

## 7. Como os Xbox mantêm o trabalho de renderização limitado

### Fundo separado do catálogo

`ConsoleThemeBackdrop` é uma View própria. Seu tick invalida o fundo; ele não deve reconstruir a lista, rebaixar dados do catálogo ou recarregar todas as capas a cada quadro.

`ConsoleScenePainter` e os drawables mantêm objetos de desenho em campos e reutilizam geometria/gradientes quando possível. Calcular geometrias em mudanças de tamanho/estado, evitando criar bitmaps, caminhos complexos ou sombras grandes repetidamente no desenho.

### Política e ciclo de vida

`BackgroundMotionPolicy` recebe modo, pouca memória, economia de energia, animações do sistema, tamanho e visibilidade efetiva. A base usa intervalos diferentes conforme o trabalho, por exemplo 33/84/100/167/250 ms; esses números não são uma obrigação para um tema novo nem uma medição de FPS do jogo.

No modo Automático, condições como pouca memória, tela muito grande, economia de energia ou animações desativadas suspendem movimento. `NONE` e Desativado não têm timer decorativo.

O trabalho depende de anexação, foco, visibilidade agregada, janela visível e apresentação habilitada. `onDetachedFromWindow`, perda de visibilidade e `close` precisam cancelar callbacks e liberar recursos próprios.

`GameDashboardView.pauseArtwork()` desativa a apresentação do fundo e pausa imagens/trabalhos ao entrar no jogo. `resumeArtwork()` retoma quando o catálogo volta. Preservar isso em qualquer tema novo; uma home escondida não deve concorrer com decoder/input.

Um modal pode manter a última imagem do fundo enquanto suspende trabalho. Não deixar o frame desaparecer nem manter uma animação ilimitada escondida atrás do painel.

### Arte e memória

Usar `PosterLoader`, `ArtworkRequest`, `ArtworkPolicy` e o backend compartilhado. Eles oferecem pedidos dimensionados, cache, decodificação fora da UI e cancelamento de requisições antigas.

No modo de memória restrita da base, a política limita cache a 4 MiB e paralelismo a um trabalho, com imagens reduzidas. Essas proteções precisam continuar valendo. Evitar outro cache/cliente/decoder por tema e imagens gigantes para cada cartão.

O fundo do jogo selecionado deve substituir requisições antigas rapidamente, sem descarregar/decomprimir todos os jogos atravessados ao segurar o D-pad. Reutilizar miniaturas; não embutir o catálogo real inteiro como base64 em HTML/JSON.

Decoração não solicita foco, não intercepta toques e não executa serviço de rede no desenho. Bitmap compartilhado pelo cache não deve ser reciclado enquanto outra View ainda o utiliza.

## 8. Verificação visual, funcional e de desempenho

Os testes de referência estão em `app/src/test/java/com/xcloud/tclnative/`:

- `AppearanceUiTest`, `ConsoleDashboardUiTest`, `ConsoleUiTest`: persistência, ações e navegação.
- `ConsoleIdentityUiTest`: identidade própria dos painéis, contraste, áreas interativas e retorno.
- `ConsolePolishUiTest`: foco nas bordas, títulos longos, busca, geometrias e fila com dados reais.
- `ConsoleThemeTest`: política de animação, movimento de cena, pausa ao perder foco/visibilidade, economia de energia e gameplay.
- `LoadingScreenTest`: fila, etapas e cancelamento em tamanhos diferentes.
- `SeriesPersonalizationUiTest`: escolhas, imagens, plano de fundo e retorno dos painéis; usar os invariantes relevantes, sem copiar recursos exclusivos do Series sem necessidade.
- `ArtworkLoadingTest`: limites, cancelamento e falhas de imagens.
- `QuickSettingsNavigationTest`: atalhos, X, Voltar, escopos, idiomas e alvos de toque.

Ao acrescentar cenas, adaptar as matrizes de testes para os novos temas, mantendo verificações significativas. Não deixar a contagem antiga de seis cenas excluir PS4/PS5, e não remover as asserções que mostram um controle cortado ou sem foco.

Gerar capturas nativas de home, fila, ajustes raiz e ajustes profundos para cada tema. Incluir TV 1280×720/1920×1080, celular largo, retrato quando suportado, imagens ausentes, títulos longos e idiomas com textos maiores. Uma captura do HTML no Chromium não testa o layout Android.

Robolectric e screenshots não comprovam fluidez na TV. No aparelho, medir frame time/jank, memória e atividade da UI com fundo parado/em movimento; testar D-pad segurado, mudanças rápidas de seleção, abrir/fechar ajustes e retorno da partida. `adb shell dumpsys gfxinfo com.xcloud.tclnative framestats` e `adb shell dumpsys meminfo com.xcloud.tclnative` ajudam; Perfetto/Profiler podem aprofundar os pontos medidos.

Não chamar os engasgos de problema de rede ou do decoder sem evidência: primeiro separar home, UI de fila e a transmissão. FPS do decoder e latência da rede não medem a fluidez do catálogo.

## 9. Compilar o APK com a identidade correta

### Ferramentas e caminho aprovado

A referência operacional é `.github/workflows/build-apk.yml`:

- JDK 21 para o build; bytecode Java/Kotlin configurado para 17.
- Gradle 8.7.
- Android SDK: `platforms;android-34`, `build-tools;34.0.0`, platform-tools.
- `compileSdk=34`, `targetSdk=34`, `minSdk=24` na base examinada.
- `applicationId=com.xcloud.tclnative`.
- Mesmo runtime WebRTC M137 já fixado no projeto; um tema não exige trocar decoder/bibliotecas de input.

### Assinatura permanente

A chave privada está no segredo autorizado `XCLOUD_ANDROID_KEYSTORE_BASE64`. `scripts/prepare_signing.py` prepara o keystore em diretório privado, valida seu certificado e disponibiliza `XCLOUD_KEYSTORE_PATH` e `XCLOUD_KEYSTORE_TYPE`.

No CI, o script escreve essas variáveis em `GITHUB_ENV`. Em build local autorizado, é necessário disponibilizar as variáveis ao processo Gradle; a saída impressa pelo script não exporta variáveis automaticamente para o shell pai. Ele também aceita `--secret-file` apontando para um arquivo privado base64.

O certificado público esperado está em `signing/certificate-sha256.txt`:

`efd33d9a3122ab5df2a7f0932e80b8fb51449bb1d3206fddc300b2766db73107`

Preservar essa identidade. Não gerar outra chave para contornar falha de assinatura, desativar `verifyPersistentSigning`, commitar o keystore ou imprimir seu conteúdo. A senha/alias padrão do debug não são a chave privada; não tornam intercambiáveis keystores diferentes.

Na configuração atual, o APK principal distribuído é `:app:assembleDebug` com a chave permanente. `assembleRelease` não tem a mesma configuração de assinatura preparada. O `shortcut-release-unsigned.apk` produzido pelo módulo `shortcut` é um template intermediário dos atalhos; não é o aplicativo principal para o usuário.

### Comandos do build local, com SDK/JDK/assinatura já preparados

```sh
python3 -m unittest discover -s scripts/tests -v
python3 scripts/verify_translations.py
gradle --no-daemon --max-workers=2 --stacktrace -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify_image_filter.py
python3 scripts/verify_fsr1.py
python3 scripts/verify_image_treatment.py
python3 scripts/verify_reconstruction.py
python3 scripts/verify_image_lab.py
python3 scripts/verify_apk_signing.py app/build/outputs/apk/debug/app-debug.apk
```

Os verificadores de shaders usam EGL/Mesa e ffmpeg conforme o workflow. O APK principal sai em `app/build/outputs/apk/debug/app-debug.apk`. Os testes XML precisam ter zero falhas/erros e, no fluxo do projeto, zero ignorados. Conferir a contagem efetivamente executada, sem copiar o total de um build anterior.

### CI: branch e commit precisam corresponder à entrega

1. Verificar `git status`, branch, `git rev-parse HEAD` e alterações locais.
2. Criar o commit que realmente contém os temas nativos e enviá-lo à branch pretendida.
3. Executar `gh workflow run build-apk.yml --ref NOME_DA_BRANCH`.
4. Obter o ID correto com `gh run list --workflow build-apk.yml --branch NOME_DA_BRANCH --event workflow_dispatch`.
5. Verificar `headSha`, status e conclusão dessa execução. O SHA deve ser o commit que se pretende testar.
6. Aguardar todas as etapas, incluindo filtros e upload do artefato.
7. Baixar `TCL-xCloud-App-Debug-Android10` dessa execução, preservando APK, SHA-256 e metadata.
8. Verificar assinatura, package, versionName, versionCode e SDK do APK efetivamente baixado.

Não selecionar uma execução só porque é verde ou a mais recente no painel. Ela pode ter compilado a branch errada ou o commit anterior à correção.

`aapt dump badging` informa package/versão/SDK/ABIs. `apksigner verify --print-certs` confere assinatura; o script do projeto compara com o certificado fixado e grava metadata. O hash do arquivo precisa corresponder ao artefato entregue. Builds distintos podem gerar bytes diferentes de DEX; usar SHA para identificar cada arquivo e conferir também origem, versão e certificado.

### Atenção ao retorno para uma versão anterior

O snapshot `004621e` usa código **94**; o retorno `1cf14eb` usa código **91**. Voltar ao código-fonte antigo não cria automaticamente um APK de atualização aceito por quem já instalou 94.

Para distribuir uma correção baseada no visual antigo, escolher um novo `versionCode` maior que o maior já distribuído, mantendo package e certificado. Conferir o número instalado/últimos artefatos antes de escolher. Alterar apenas `versionName` não resolve a ordem de atualização. Não orientar a desinstalar o app e perder dados como solução padrão para esse problema.

### Build de teste e release pública são etapas distintas

O workflow `publish-release.yml` recebe o `run_id`, a tag e as notas. Na versão examinada, exige build aprovado da **main**, verifica package/assinatura/versão e associa a release ao SHA compilado. Não apontar para um APK de feature branch para contornar essa validação.

Quando houver autorização para publicar, revisar e incorporar a implementação à main, compilar esse commit e publicar o artefato correspondente. Notas e README descrevem recursos entregues; relatos pessoais, logs de conta e esteiras privadas de pesquisa não entram na release.

## 10. Critério de conclusão para o próximo ambiente

A entrega de temas estará pronta para revisão quando existir uma implementação Android conectada às ações reais, com identidade própria em home, fila e configurações, navegação e foco corretos, política de animação/ciclo de vida preservada, capturas nativas e evidência de desempenho no aparelho-alvo.

Entregar também commit, branch, ID do build, versão/código, certificado e SHA do APK. Distinguir o que foi validado no CI do que foi observado na TV/celular. Não declarar um tema pronto apenas porque compila ou porque o HTML parece bom no navegador.


## Continuação na feature branch

A implementação nativa posterior e o build de teste conferido estão descritos em `CODEX_CONTEXT.md`, na seção “Build de teste nativo conferido”. O exame histórico deste guia continua servindo de referência. Para a home, o pedido mais recente usa os prints `html-ps4-home.jpg` e `html-ps5-home.jpg`; as simulações orientam a composição, com Views/Canvas, catálogo e estados reais. O fluxo de assinatura e o workflow foram preservados. A validação física em TV/celular permanece pendente.


### Revisão de geometria no celular

O app permanece em paisagem, conforme o manifesto e a orientação atual do usuário. A revisão usa áreas úteis menores que a resolução total, incluindo barras e recorte simulados. Consultar a seção “Revisão nativa da área útil em celular” de `CODEX_CONTEXT.md` e `PlayStationPhoneReviewTest`. Não confundir essas capturas Android/Robolectric com inspeção ou medição em aparelho real. O próximo APK usa código 96, superior ao 95 distribuído anteriormente, com a mesma assinatura permanente e o mesmo workflow.


### Recursos da suíte de capturas

A suíte completa usa um único processo de testes por vez, heap máximo de 1 GiB e renovação a cada oito classes para limitar retenção dos sandboxes/gráficos nativos do Robolectric. As capturas liberam a superfície do Canvas antes de reciclar o bitmap. O workflow compila Kotlin no processo do Gradle, evitando outro daemon de compilação residente. Essas opções afetam a execução dos testes/build, não os limites de memória do aplicativo Android nem o cache de imagens. A assinatura permanente e as verificações continuam obrigatórias.
