# Contexto técnico para continuidade no Codex

Leia este arquivo junto com `AGENTS.md`. O usuário pediu explicitamente um resumo no repositório para continuar o trabalho em outro ambiente. Aqui estão decisões técnicas e pendências; gravações, conversas literais, dados de conta e diagnósticos pessoais completos continuam privados.

## Guia adicional de temas e compilação

Leia também `CODEX_THEME_BUILD_CONTEXT.md`. Ele documenta a arquitetura nativa dos Xbox, os limites dos protótipos PS4/PS5, a integração HTML posterior, a restauração da base e o processo de build/assinatura. O adiamento abaixo descreve o escopo da entrega original de navegação; consulte o pedido atual do usuário antes de iniciar outro trabalho de temas.

## Objetivo atual e limites

O Remote Play está em desenvolvimento na branch `feat/remote-play`, criada a partir de `feat/quick-settings-navigation` em `ef56126`. Consulte a seção “Remote Play experimental” ao final para os pontos de integração e o roteiro de validação.

Manter a navegação simplificada e os atalhos nativos da partida. O usuário retomou PS4/PS5 e pediu seguir `CODEX_THEME_BUILD_CONTEXT.md`, usando as simulações apenas como referência visual e a arquitetura nativa dos Xbox. `html-ps4-home.jpg` e `html-ps5-home.jpg` orientam a composição da home. Fotos reais continuam como apoio dos painéis. Priorizar a composição dos consoles em home, fila, transições e painéis internos. Não recuperar a integração HTML/WebView descartada.

O usuário prefere execução autônoma, comunicação em português e conclusões baseadas em evidência. Não pedir novamente autorização para ações já incluídas no pedido. Não dizer que um problema foi resolvido em aparelho real apenas porque testes locais passaram.

## Estado do projeto

- Cliente Android nativo de Xbox Cloud Gaming, Kotlin, Activity/Views nativas, WebRTC M137. Android mínimo API 24; compatibilidade Android 10 e TV é importante.
- Repositório: `Ricardex742/xCloudNative`.
- Última release pública confirmada no histórico desta conversa: **v0.38.0**. README deve listar recursos atuais, sem comparação obrigatória com 0.36.0 e sem histórico de testes pessoais.
- Esta implementação está na branch `feat/quick-settings-navigation`, baseada em `fix/primary-controller-startup-order` (`2482b45e35c5c89acf70d274e0544c1b8a1ffb05`). Inclui, portanto, a correção experimental de ordem dos controles descrita abaixo.
- Versão no código desta branch **0.40.16-network-indicator**, versionCode **111**, usando a identidade de atualização existente.
- Não presumir que a branch foi publicada ou que há release nova: conferir `git status`, remotos e releases no ambiente atual.

## Implementação da navegação

Arquivos principais:

- `ui/dashboard/GameMenuPanel.kt` define os atalhos da sessão ativa e a confirmação de saída. `ui/dashboard/GameShortcutBar.kt` apresenta essas ações em uma barra horizontal quando aberta pela seta no celular. A TV e os atalhos físicos continuam usando os painéis do tema. As configurações da próxima partida ficam no catálogo.
- `ui/dashboard/PlaybackSettingsPanel.kt`: seis categorias: Vídeo e conexão; Controles e toque; Áudio e microfone; Aparência e temas; Conta e amigos; Ajuda e diagnóstico. A página de vídeo usa abas para imagem/tela, rede/sessão e avançado, evitando duas árvores duplicadas.
- `ui/dashboard/ConsoleDialogs.kt`: X no cabeçalho fecha diretamente, sem chamar callbacks de Voltar. Voltar/B retorna ao pai. Restaura foco do item ao retornar a menus. Segurar X do controle limpa somente o campo de pesquisa, sem enviar a busca.
- `ui/GameTouchView.kt`: o acesso ao menu é uma seta nativa em um círculo pequeno, com nome acessível e alvo de 48×48 dp. Fica fora do recorte e aparece também com toque nativo ou HUD oculto. Preservar solturas de contatos e botões ao abrir menus e durante edição.
- `ui/dashboard/SessionOptionsPanel.kt` e `settings/SessionOptions.kt`: seletor Este jogo/Padrão das partidas no mesmo painel, indicação de herança por campo e suporte a `zh-CN` no idioma **do jogo**, separado do idioma da interface.
- `ui/MainActivity.kt`: liga os painéis ao jogo ativo, aplica alterações que podem mudar ao vivo e mantém marcações de próxima sessão. Ações de substituir catálogo/trocar conta ficam indisponíveis dentro da partida.
- `QuickSettingsNavigationTest.kt`, `SessionOptionsTest.kt` e testes de UI existentes cobrem navegação e persistência. As capturas geradas usam dados de demonstração em `app/build/reports/navigation`.

Não copiar cegamente o painel de referência: ele usa alguns ícones sem texto, mas nossa navegação precisa funcionar com toque, D-pad, controle físico e acessibilidade.

### Idioma do jogo e herança

O comentário sobre ausência de chinês simplificado era sobre idioma **solicitado ao jogo**, não sobre menus do app. A interface já tinha chinês simplificado. A lista de sessão não tinha `zh-CN` e a normalização substituía códigos fora da lista por `pt-BR`.

O relato de idioma voltando ao padrão não foi reproduzido para um idioma válido: inglês permaneceu salvo após mudar bitrate/região e recriar a Activity. Foi reproduzida uma confusão de escopo: alterar só o bitrate de um jogo salvava também idioma e região em um snapshot completo. Depois, mudar o padrão geral não afetava esse jogo.

A implementação nova salva exceções **por campo** na UI, sem apagar snapshots antigos. Esses snapshots continuam sendo exceções explícitas; o usuário pode retornar cada campo à herança ou restaurar os três. A API `save` completa foi mantida para compatibilidade. Contas e jogos continuam separados. Não apagar preferências antigas automaticamente nem inferir que uma escolha explícita era acidental.

Idioma, região e teto de bitrate são pedidos ao serviço para a **próxima sessão**. O jogo pode ignorar a solicitação de idioma e usar opções próprias. Resolução, codec, FPS solicitados e dados efetivamente recebidos também devem continuar distinguidos.

### Botão Xbox

Menu local, lista de amigos e guia remoto Xbox são funções diferentes. O renderer nativo sozinho não prova que o guia é impossível. O protocolo de controle já mapeia `KEYCODE_BUTTON_MODE` para o bit Nexus (`2`).

O atalho experimental envia pressionamento/soltura explicitamente pelo input da sessão, com proteção contra troca de geração/cliente. **Não está confirmado que todas as sessões abram o guia ou ofereçam convites, notificações e parties.** Não anunciar esses recursos como implementados. Nunca enviar botões de jogo automaticamente para esconder falhas de inicialização.

## Genshin: pendência independente dos menus

Os testes pessoais que motivaram o trabalho ocorreram em aparelhos Samsung/Android 16 e LG LM-K200/Android 10, com perfis diferentes de vídeo e rede. O problema mais reproduzido foi a inicialização do Genshin sem interação:

- No cliente oficial e no XStreaming, o jogo abre sem tocar. Outro jogo com toque nativo também abriu sem tocar no nosso cliente.
- No nosso cliente, toque nativo frequentemente precisava de interação logo na entrada. Controle virtual teve resultados variáveis: houve sucesso sem tocar e falhas em outras tentativas.
- Algumas sessões continuam ativas, mas ficam na capa/logotipo ou carregamento. FPS do decoder perto de 60 e bitrate muito baixo não provam que o jogo avançou: uma imagem estática pode continuar recebendo quadros.
- Em falhas rápidas, o trace passou de `Active/true` para `Closed/true` e `Closed/false` aproximadamente 2–2,5 s após o início; cerca de um segundo depois veio `KickForClosedGame`. Os canais estavam abertos no primeiro diagnóstico. Isso ajuda a distinguir encerramento informado pelo serviço de fechamento local de canais, mas **não identifica sozinho a causa do fechamento do título**.
- `TITLE_ACTIVATION/CONFIRMED` confirma uma etapa do protocolo, não que o jogo chegou a uma tela jogável.
- Pacotes neutros de manutenção, inicialização do slot de toque e remoção/adição de gamepad com atraso de cerca de 500 ms não resolveram consistentemente. Não repetir esses experimentos como se fossem correções comprovadas.
- A classificação das mensagens de touch controls e o comportamento de respostas automáticas foram investigados nas versões 0.37.x. Uma melhora em uma tentativa não foi confirmação suficiente nas seguintes.
- Algumas tentativas também apresentaram `ICE_FAILED` e `KEEPALIVE/TIMEOUT`; são observações de transporte diferentes do encerramento rápido `KickForClosedGame`.

Comparar a implementação do XStreaming é autorizado pelo pedido anterior, mas não afirmar equivalência sem examinar a sequência real de handshake/input/mensagens. Preservar diagnósticos e falhas explícitas. A entrega de navegação não pretende resolver essa inicialização.

## Fila após trocar o modo de controle

Outro relato: terminar uma partida, mudar HUD/toque nativo nas configurações e iniciar outra podia deixar a tela **Aguardando uma vaga**, estimativa **menos de 1 min**, sem avançar. Sair e entrar novamente na fila permitia tentar de novo. É na partida seguinte, não uma troca dentro do jogo.

A causa ainda não foi confirmada. Examinar encerramento da alocação anterior, cancelamento das tarefas e estado de sessão/polling. Estimativa de fila não é prazo garantido. Não implementar reinício automático que perca a posição ou trate todos os casos demorados como erro. Preservar a escolha de controle e distinguir sessão anterior, nova fila e transporte.

## Call of Duty: controle físico e ordem dos slots

Relato separado: controle físico funcionava nos menus e em outros jogos, mas não em Call of Duty; recarregar transmissão fazia funcionar. Havia um GameSir e um controle remoto Bluetooth; slots virtuais não devem ser contados automaticamente como quatro controles físicos reais.

Foi identificada uma diferença concreta entre primeira conexão e reload: controles secundários podiam ser anunciados enquanto o primário ainda estava em remoção/reinserção inicial. A correção experimental de base introduz uma barreira para registrar o primário antes dos secundários:

- `webrtc/GamepadConnections.kt`: gate do primário e registro pendente ordenado.
- `webrtc/XCloudWebRtcClient.kt`: anúncios secundários aguardam o registro primário antes do pump.
- Trace `GAMEPAD_ORDER` para observar a sequência aceita.

APK de teste anterior: `0.38.1-input-order`, versionCode 90. **A confirmação final do usuário na TV ainda estava pendente.** Evitar alterar essa ordem ao implementar atalhos. Não confundir o botão Xbox acionado pelo usuário com input automático durante a inicialização.

## Recursos já acrescentados antes desta etapa

- Tempo restante consultado no Xbox, incluindo ilimitado; não inferir ilimitado apenas por assinatura Ultimate. HTTP 400 de playtime foi observado nas versões anteriores; resultados posteriores confirmaram ilimitado.
- Selos de assinatura e saldo adaptados aos temas existentes. Estatísticas podem incluir saldo de nuvem; consulta a cada cinco minutos enquanto estão visíveis. Não é contagem regressiva local garantida.
- HUD por jogo, ocultação automática opcional (desativada por padrão), retorno sem apertar um comando, toque nativo quando negociado e seleção de modo para a próxima partida.
- Amigos: presença e atividade, com abertura do título em **sessão própria**. Entrar na mesma partida/grupo do amigo ainda não está implementado.
- Atalhos por jogo no launcher, pin quando suportado e APK de atalho para ambientes que exigem instalação.
- Quinze idiomas/regiões da interface com recursos offline. Manter as traduções completas e os placeholders consistentes.
- Temas Xbox e personalização preservados. PS4/PS5 usam cenas próprias, Kotlin, Views e Canvas; incluem variantes noturnas. Os protótipos HTML continuam como referências externas ao APK.

## Temas PlayStation nativos

- As cenas `PS4` e `PS5` são acrescentadas ao enum existente; as preferências anteriores continuam por nome. `PS4_NIGHT` e `PS5_NIGHT` reaproveitam a geometria com paletas próprias.
- O catálogo permanece no mesmo `RecyclerView`, adaptador, IDs estáveis e serviços. A biblioteca nativa permite acessar todas as categorias. As opções ao lado de Jogar no PS5 abrem os grupos de controles e vídeo já existentes, sem outra árvore de configurações.
- A geometria da faixa PS5 deve ser recalculada quando o catálogo assíncrono muda de tamanho, inclusive ao limpar/pesquisar; a biblioteca acompanha a faixa sem depender de girar/redimensionar a tela. Os testes aguardam o worker real e a devolução do foco antes de enviar D-pad após retomar.
- Fundos usam `ConsoleScenePainter`/`ConsoleThemeBackdrop` e a política de movimento/ciclo de vida existente. Arte usa o cache, limites e cancelamento de `PosterLoader`. Não criar WebView, cache ou decoder próprios para os temas.
- Painéis usam `ConsolePanelSkin` em todos os níveis. A fila mantém estimativa e etapas do serviço; as transições mostram estados reais. O menu da partida segue nativo: guia lateral PS4 e centro inferior PS5; fechar não encerra e desconectar exige confirmação.
- Corrigido um ramo inalcançável da tentativa nativa anterior: o cartão PlayStation era sobrescrito por `bindConsole` e acabava usando o cartão genérico. A rotina própria deve ser aplicada dentro do binding de console. Manter as verificações de geometria e de restauração exata do tema Verde.
- Testes `NativePlayStationSurfacesTest` cobrem TV, celular, retrato, 48 dp, fila sem arte, transições, grupos de ajustes, níveis profundos nos 15 idiomas, foco, navegação rápida e pausa do fundo sem rebind do catálogo. Os menus da partida são exercitados em celulares de alta densidade, nas duas orientações; HUD, X, Voltar e botões de retomar/encerrar devem permanecer acessíveis. Menu/Start na home abre os ajustes; o perfil tem seu painel próprio.
- Não há aparelho acessível por ADB neste ambiente; o usuário também não conhece um disponível. Frame time/jank, memória e interação física na TV/celular continuam pendentes. Não tratar testes JVM nem capturas nativas como essa confirmação.
- Fotos usadas para composição: [home PS4](https://www.gamesradar.com/ps4-external-hard-drive-transfer-games/), [ajustes PS4](https://www.techsolutions.support.com/how-to/how-to-fix-video-issues-on-a-playstation-4-10745), [home e centro PS5, PlayStation Blog](https://blog.de.playstation.com/2021/01/11/playstation-5-tipps-und-tricks-zum-control-center/) e [ajustes PS5](https://www.tomsguide.com/how-to/how-to-enable-120hz-on-ps5). Após os prints enviados pelo usuário, a home PS4 usa marca à esquerda, abas de catálogo, faixa de capas e título abaixo; a PS5 usa destaque e três cartões inferiores. Os cartões abrem grupos existentes e mostram somente plano/quota reais, com indisponível/consultando/ilimitado distintos. Ajustes usam listas nativas em tela cheia, ícones e separadores. As fotos são referências externas, sem inclusão no APK. Não afirmar fidelidade pixel a pixel sem comparação equivalente de conteúdo, resolução e fonte.
- Referências oficiais adicionais: [guia PS4, área de conteúdo e funções](https://www.playstation.com/content/dam/global_pdc/en/corporate/support/manuals/ps4-docs/cuh-2202ab-cuh-2208ab-cuh-2216a/EN_PS4_CUH-2216AB_QSG_Web.pdf) e [apresentação da interface PS5](https://blog.playstation.com/2020/10/15/first-look-playstation-5s-next-generation-user-experience/). Os temas não implementam serviços PSN.

## Revisão de desempenho anterior

Uma revisão de cerca de 197 arquivos de produção identificou oportunidades a medir antes de mudanças amplas: cancelamento durante leitura do corpo HTTP, limites de JSON, tamanho das bibliotecas nativas/ABIs, escrita de preferências no input, reutilização de serviços, alocações na thread de input e decomposição da Activity. Não foi autorizada uma reescrita geral indiscriminada nem foram implementadas todas essas sugestões.

Manter o app leve: evitar WebViews novos, bibliotecas de interface adicionais ou efeitos pesados para atalhos. Preservar o caminho de input separado da renderização. Testes de desempenho locais são evidência do cenário medido, não prova de latência física.

## Verificação e entrega em outro ambiente

- Ferramentas esperadas: JDK 21, Gradle 8.7, Android SDK 34. O fluxo de CI está em `.github/workflows/build-apk.yml`.
- Executar `python3 scripts/verify_translations.py`, os testes em `scripts/tests`, `:app:testDebugUnitTest`, `:app:lintDebug` e verificações de shader quando pertinentes.
- Examinar as capturas novas em TV, celular paisagem e retrato, incluindo temas claros e foco de controle. Back e X têm semânticas diferentes.
- Para APK instalável como atualização, usar a assinatura permanente preparada por `scripts/prepare_signing.py`, com o segredo autorizado do ambiente/CI. O SHA-256 esperado do certificado está em `signing/certificate-sha256.txt`. **Não gerar uma chave temporária nem remover a validação para contornar falta de acesso.**
- Não publicar gravações, diagnósticos pessoais ou esteiras privadas de pesquisa. README documenta funções e caminhos atuais, não conversas pessoais.
- Conferir ao final os testes realmente executados e o estado da branch. Este arquivo não substitui os resultados atuais nem confirma publicação remota.

## Build de teste nativo conferido

Referência anterior da versão 0.40.0/código 95; este artefato antecede a revisão de celular descrita abaixo.

- Branch: `feat/quick-settings-navigation`; código compilado: `1921c6df4e7c6810b72f0860b5ba4a8b9883a93c`. As atualizações posteriores de contexto/prévias não alteram esse código do aplicativo.
- [Build 44229022903](https://github.com/Ricardex742/xCloudNative/actions/runs/44229022903): concluído com sucesso e `headSha` conferido. **817 testes JVM**, zero falhas/erros/ignorados; lint, traduções, proteção da assinatura e verificações de filtros/reconstrução/laboratório aprovados no workflow existente, sem alterações nele.
- APK efetivamente baixado e verificado: `com.xcloud.tclnative`, **0.40.0-native-ps**, código **95**, mínimo Android API **24**. A identidade de atualização permanente foi confirmada criptograficamente.
- Certificado SHA-256: `efd33d9a3122ab5df2a7f0932e80b8fb51449bb1d3206fddc300b2766db73107`.
- APK SHA-256: `eb4f4a57cb47890831c9a8fe0ac98108f6dc2b94edcf694d0ac34cf073cc6ce0`.
- [Download do artefato](https://github.com/Ricardex742/xCloudNative/actions/runs/44229022903/artifacts/14395265788): ZIP com APK, SHA-256 e metadata. O digest do ZIP também foi comparado ao informado pelo GitHub.
- As quatro prévias PlayStation do README foram extraídas das capturas Android nativas desse build, com catálogo/perfil de demonstração; não são os prints HTML enviados como referência.
- Validação em TV/celular real continua **pendente**: não há dispositivo conectado por ADB. Testes JVM, capturas e contadores de invalidação/rebind não medem jank, memória nem latência física no aparelho. Este APK é de teste na feature branch; não foi incorporado à main nem publicado como release.

## Revisão nativa da área útil em celular

Esta seção registra a revisão anterior, entregue no código 96. A composição em paisagem foi corrigida novamente na seção “Composição dos consoles e janelas imersivas”, abaixo; não recuperar o layout separado de celular como padrão.

- A Activity continua declarada somente em paisagem no manifesto. Capturas históricas em retrato são testes extras de componentes, não um modo oferecido pelo app.
- PS4/PS5 usam dp/sp em celulares (menor dimensão abaixo de 600 dp, excluindo TV). A composição de referência continua no caminho de TV. `ConsoleLayout.kt` centraliza ajuste limitado do texto, incluindo fallback API 24/25, sem trabalho por quadro.
- “Todos os jogos” tem aba própria e abre diretamente o catálogo completo. “Biblioteca” mantém o seletor das demais categorias; recentes e favoritos continuam separados. Preservar os IDs e a seleção do adaptador compartilhado.
- Título principal, legenda da capa e identificação da plataforma têm áreas separadas. Fila e transições reservam o espaço dos textos finais; os painéis permitem rótulos maiores e mantêm alvos de 48 dp. A decoração de um painel não deve ultrapassar seus limites.
- O X físico fecha os painéis do contexto, assim como o × do cabeçalho; a pesquisa mantém a exceção já existente de segurar X para limpar o campo. B/Voltar continua retornando um nível, com foco da categoria restaurado após processar o evento nativo de cancelamento.
- `PlayStationPhoneReviewTest` gera capturas Android/Robolectric em paisagem a 1280×720, 1520×720 e 2400×1080, com densidades reais de teste e áreas úteis menores simulando barras/recortes. Exercita os quatro temas, home, favoritos, busca, perfil, seis grupos de ajustes, níveis profundos, fila, preparação, saída e retorno; verifica também fonte ampliada, 15 idiomas, catálogo completo, toque, D-pad, A/Enter, B, X, contraste com arte clara e carregamento limitado de imagens. As barras e o recorte são simulados; isso não é captura de um aparelho físico.
- Cache, paralelismo limitado, cancelamento de imagens e pausa do fundo durante gameplay/ocultação permanecem nos componentes compartilhados. Não foram acrescentados WebView, bibliotecas de interface, decoder ou novos workflows. O workflow existente recebeu os ajustes de recursos e download descritos abaixo.
- Sem aparelho ADB nem aceleração de emulador neste ambiente. Medições de jank/frame time, consumo de memória e uso real do controle continuam pendentes; os testes automatizados não confirmam fluidez física nem resolvem as pendências de Genshin, fila ao trocar controles ou ordem do controle no CoD.

- A faixa vazia do PS5 reserva largura para a mensagem de busca sem resultados. Jogar/favoritar ficam ocultos sem seleção e reaparecem quando chegam resultados; o adaptador invalida a geometria de ambas as cenas PlayStation após mudanças assíncronas de tamanho. Os testes cobrem os estados vazio, preenchido e retorno.
- Nos celulares, os pedidos de capas usam o tamanho da capa selecionada exibida; mantêm a política compartilhada de cache e cancelamento. Os atalhos de recentes e biblioteca têm descrições acessíveis diferentes.

- A execução completa no CI exige limitar a retenção dos gráficos/sandboxes do Robolectric: processo de testes único, heap de 1 GiB, renovado a cada oito classes, com liberação explícita do Canvas das capturas. Kotlin compila no processo do Gradle no workflow. São limites do processo de build/teste; a política de memória do Android não foi aumentada. Assinatura, testes, lint e verificações de filtros permanecem obrigatórios.

## Build da revisão de celular conferido

- Branch: `feat/quick-settings-navigation`; código compilado: `410da834db94b3af9a30c5438c2efee6af686da3`. Atualizações posteriores somente de documentação não alteram o APK identificado aqui.
- [Build 44320037015](https://github.com/Ricardex742/xCloudNative/actions/runs/44320037015): sucesso, com SHA de origem conferido. **822 testes JVM**, zero falhas/erros/ignorados; lint, traduções, proteção da assinatura e os cinco verificadores de filtros/reconstrução/laboratório aprovados.
- [Download do artefato](https://github.com/Ricardex742/xCloudNative/actions/runs/44320037015/artifacts/14435206827): ZIP com APK, SHA-256 e metadata. APK efetivamente baixado: `com.xcloud.tclnative`, **0.40.1-theme-review**, código **96**, mínimo API **24**, ARM32/ARM64. O código anterior distribuído era 95.
- Assinatura permanente confirmada novamente no arquivo baixado; certificado SHA-256: `efd33d9a3122ab5df2a7f0932e80b8fb51449bb1d3206fddc300b2766db73107`.
- APK SHA-256: `1e0d33f56b1625869ac8662bfdd95cd11e65469bb5b125655ccc4b67f4a7fdf5`. ZIP SHA-256: `501210826d7bc2e0e861246ae379efd98729423ef1b3faddf21e2a95ab0b025a`, comparado ao digest informado pelo GitHub.
- Capturas Android nativas foram abertas e inspecionadas durante as correções e novamente nos relatórios do build final. A matriz nova gera 440 capturas em quatro temas e quatro áreas úteis de celular em paisagem, com barras/recorte simulados. Inclui títulos longos, catálogo vazio, ajustes, fila e menu da partida. Nenhum HTML de tema integra o APK.
- O workflow existente limita os processos de compilação/teste e baixa dependências GPU por mirrors HTTPS oficiais do Ubuntu, com chave padrão e prazos limitados. As verificações de shader e assinatura não foram removidas.
- Sem dispositivo ADB ou aceleração de emulador disponíveis. Desempenho, memória e controle físico no aparelho continuam pendentes; não tratar os resultados acima como confirmação dessas medições. APK de teste da feature branch, sem merge na main ou release pública.

## Composição dos consoles e janelas imersivas

- Foram comparados os caminhos das sete paletas simples e dos seis dashboards Xbox: grade genérica; lista/esfera Original; painéis Blades; faixa NXE; mosaico Metro; destaque One; catálogo e atalhos Series. Todos usam os mesmos modelos, Views, adaptador, carregamento de arte e ciclo de sessão. A identidade visual pertence à cena e não deve criar outro mecanismo de catálogo/configurações.
- PS4/PS5 em paisagem usam a mesma abordagem de composição de referência 1280×720 dos Xbox. A densidade Android não deve trocar a home por outra disposição: PS4 mantém Iniciar na capa selecionada, título e plataforma abaixo; PS5 mantém destaque, descrição e Jogar à esquerda, e três cartões inferiores. “Todos os jogos” continua separado de Biblioteca. As referências são os dois prints e protótipos fornecidos; adaptações em retrato permanecem apenas como cobertura extra de componentes.
- `ConsoleHitTargets` amplia a região de toque de controles compactos sem aumentar seu desenho; a escolha nas margens sobrepostas usa o controle mais próximo. Encaminha as margens antes do `ScrollView`, preservando a prioridade de outro controle visível sob o toque. O foco é obtido em DOWN: um `TextView` focável em modo de toque podia consumir o primeiro UP somente para obter foco, sem acionar o botão. O teste da margem do X usa um gesto nativo e verifica o fechamento no primeiro toque. A delegação é retirada ao voltar a outros temas; cliques, cancelamento e acessibilidade continuam nas Views reais.
- A fila PlayStation usa o mesmo canvas de referência: arte, estimativa/etapas reais, confirmação, atualização, cancelar e nota. O bloco opaco foi removido; as etapas usam linhas discretas. Cancelar mantém sua área de toque, com desenho menor. Isso não resolve a pendência independente de fila após trocar controles.
- Ajustes, seletores, amigos e menus profundos passam pelo mesmo `ConsoleDialog`/`ConsolePanelSkin`: azul do protótipo PS4, superfícies escuras sóbrias PS5, texto e linhas proporcionais. A lista e o guia PS4 são compactos, com regiões ampliadas de toque; seis categorias devem caber na área de celular em português. Traduções maiores continuam podendo rolar, com indicador visível. X, B/Voltar, ações e restauração de foco preservam as semânticas existentes.
- `ui/ImmersiveWindow.kt` aplica modo imersivo na Activity e em todas as janelas próprias, incluindo login: antes/depois de mostrar e quando recuperam foco. API 30+ usa também `WindowInsetsController`; API 24–29 mantém fullscreen/hide-navigation/immersive-sticky. Abrir um painel não deve revelar as barras do Android. Gestos de borda continuam sob controle do sistema, com barras transitórias.
- `ConsoleFidelityReviewTest` acrescenta comparação de todos os temas em paisagem, usando arte de demonstração dos protótipos somente nos recursos de teste. Gera home, ajustes, níveis internos e fila/menus PlayStation; verifica seis categorias visíveis, composição das ações, tamanho do círculo e toque no menu. Inclui testes de imersão em API 28/30/34. `PlayStationPhoneReviewTest` inclui também uma área imersiva 1280×600 com recorte lateral simulado. A inspeção de imagens continua obrigatória além das asserções.
- Não foram acrescentados bibliotecas de UI, WebView de tema, decoder, cache ou timers decorativos. Limites de arte e pausa durante gameplay/ocultação continuam compartilhados. Não há dispositivo ADB ou KVM neste ambiente; validação física e medições de desempenho permanecem pendentes.

## Build da composição e imersão conferido

- Branch: `feat/quick-settings-navigation`; fonte compilada: `3302564d4b9da35858e8fe131e1629c9118d7037`. Atualizações posteriores somente de documentação não alteram este APK.
- [Build 44361124859](https://github.com/Ricardex742/xCloudNative/actions/runs/44361124859): sucesso, SHA e branch conferidos. **828 testes JVM**, zero falhas/erros/ignorados nos XMLs baixados; lint, 15 idiomas, oito testes de proteção da assinatura e os cinco verificadores de filtros/reconstrução/laboratório aprovados.
- [Download do artefato](https://github.com/Ricardex742/xCloudNative/actions/runs/44361124859/artifacts/14455856933): ZIP com APK, SHA-256 e metadata. Arquivo efetivamente baixado: `com.xcloud.tclnative`, **0.40.2-console-layout**, código **97**, mínimo API **24**, target **34**, ARM32 e ARM64, 60.318.511 bytes. Código superior ao 96 anteriormente distribuído.
- A assinatura permanente foi conferida novamente no APK baixado; certificado SHA-256: `efd33d9a3122ab5df2a7f0932e80b8fb51449bb1d3206fddc300b2766db73107`.
- APK SHA-256: `28a81b09c8a1f7732cd16ee2419b508a4a00e59aa84e3e31aa096008eb380135`. ZIP SHA-256: `943220b20882d7d1162077ebb8ae4aee082634fa9cad46d035fe001ed1bc7c17`, comparado ao digest informado pelo GitHub. O ZIP dos relatórios também foi baixado e teve o digest conferido.
- A matriz de comparação gerou 161 capturas; a revisão de celular, 526. As superfícies de PS4/PS5 e a comparação com os demais temas foram abertas e inspecionadas durante as correções. Capturas do CI final foram abertas novamente, incluindo composição com arte dos protótipos, título longo sem imagem, seis categorias, fila e guia lateral. São renderizações Android/Robolectric, com áreas úteis e recortes simulados, não capturas de um aparelho físico.
- As quatro prévias PlayStation do README foram atualizadas com capturas nativas de demonstração. As imagens dos protótipos usadas nos testes não integram o APK; a ausência de HTML/JS de tema e de fixtures no pacote foi conferida.
- O código permanece na feature branch. Não houve incorporação à main nem release pública. Sem aparelho disponível: jank/frame time, memória, aplicação do modo imersivo no aparelho e interação física permanecem pendentes; as pendências independentes de Genshin/fila/CoD continuam válidas.

## Estabilização da sessão e distribuição

- Versão de teste **0.40.3-session-stability**, código **98**, na branch `feat/quick-settings-navigation`. A variante `release` usa a mesma identidade permanente e tem depuração desabilitada. O pipeline de publicação exige essa variante, além da origem na main. A variante debug continua disponível para diagnóstico.
- A política de backup exclui credenciais OAuth e diagnósticos, inclusive na transferência de dispositivo para Android 12+. Preferências comuns seguem as regras de backup do sistema.
- O cancelamento das requisições passa a permanecer ligado ao socket durante a leitura do corpo HTTP. Catálogo e APIs menores têm orçamentos de corpo separados, após descompressão. A previsão de fila roda com uma consulta por vez e não bloqueia o polling do estado.
- Uma nova tentativa aguarda a limpeza das anteriores. Um atalho para outro jogo enquanto há sessão ou fila ativa pede confirmação. Atalhos sem sessão ativa usam o mesmo fluxo de abertura do catálogo. O diagnóstico registra origem do lançamento, idioma solicitado e tipo da fonte primária, sem IDs do controle.
- O sinal de primeiro vídeo e o watchdog usam o callback do EGL após a submissão do buffer na superfície. A pausa do app suspende o watchdog de imagem e o retorno começa uma nova janela de observação. O hook usa o callback de `SurfaceEglRenderer` da versão WebRTC M137 e tem teste de integração do registro. Submissão EGL não é prova de apresentação pelo compositor.
- A ordem experimental do slot zero e a ausência de comandos de jogo artificiais durante a inicialização foram preservadas. Rejeições de anúncio de controles secundários não confirmam a alteração local do slot. A prioridade inicial no CoD e a abertura sem interação no Genshin ainda precisam ser reproduzidas em aparelho.
- Restam validação em TV/celular, instalação como atualização, backup/restauração reais e medições de latência e desempenho. Mudanças de orçamento de mídia e refatorações de maior alcance continuam pendentes.
- Os testes de previsão pendente e lançamentos sucessivos falharam na base `dfdfef7` nos cenários esperados e passaram no código da versão 98.
- [Build 44374564350](https://github.com/Ricardex742/xCloudNative/actions/runs/44374564350), commit de código `b23d4ae404c6644cd7163bd22b624ae0ebd86b59`. **836 testes JVM** sem falhas, erros ou ignorados, lint, 10 testes Python, 15 idiomas e cinco validadores de GPU em software aprovados.
- [Artefato de teste do Actions](https://github.com/Ricardex742/xCloudNative/actions/runs/44374564350/artifacts/14462301341), com `app-release.apk`. O arquivo baixado tem pacote `com.xcloud.tclnative`, versão **0.40.3-session-stability**, código **98**, minSdk **24**, targetSdk **34**, ARM32/ARM64 e x86/x86_64. O manifesto empacotado tem as referências às duas regras de backup e a depuração está desabilitada.
- Certificado permanente SHA-256 `efd33d9a3122ab5df2a7f0932e80b8fb51449bb1d3206fddc300b2766db73107`. APK SHA-256 `64cf845753a3e05a983dc1e3f5d32ffaf561763e3d915409f5da5e3f6c4f5db1`. ZIP SHA-256 `4a7fab75386f2f90de5cd82c2948e0a6e4d037aa04e881fa8cfd369de0afac09`, igual ao digest do Actions.

## Segunda etapa do plano de otimização

- Favoritos são gravados em I/O e em ordem de solicitação. A interface lê um snapshot compartilhado por conta, atualizado após a gravação durável. Uma falha mantém a seleção anterior. Enquanto o catálogo está pausado, a escrita pode concluir sem atualizar a UI oculta.
- A pausa de uma tela continua cancelando seus pedidos e soltando as imagens exibidas. As capas decodificadas seguem no cache compartilhado e nos limites existentes. Em aparelhos classificados como restritos, a submissão do primeiro vídeo libera esse cache. Pressão de memória continua acionando a limpeza.
- O envio de controles reutiliza os snapshots de slots por ciclo e serializa slots na mesma ordem do protocolo, sem ordenar um mapa por pacote. O teste sintético a 250 Hz na JVM HotSpot 21 mediu cerca de 1.136 para 360 bytes alocados por ciclo com quatro controles. Os pacotes de referência permaneceram idênticos. Esse cenário não mede latência ou uso de memória do app em Android.
- As estatísticas continuam sendo coletadas enquanto a partida está ativa. O overlay só recebe atualização periódica com a Activity visível. Ganho de áudio de até 100% pula o trabalho do limitador local. A cópia PCM interna do AAR continua porque o callback é fixado na criação do módulo e suporta mudanças de ganho durante a partida.
- APKs auxiliares cancelados explicitamente antes da instalação são descartados. A criação de novos atalhos limpa APKs gerados há mais de um dia, preservando o pacote pendente e a janela para o instalador ler um arquivo recém-entregue.
- A versão de teste mantém o APK universal e a minificação anterior. Na versão 98, as bibliotecas x86 e x86_64 somam 28.031.544 bytes no APK. Uma variante ARM separada ou minificação exige validação de instalação, compatibilidade e atualização. CPU, GC, jank, underruns e memória continuam pendentes em aparelhos reais.
- [Build 44413085513](https://github.com/Ricardex742/xCloudNative/actions/runs/44413085513), commit de código `948321d787da0687ab562aead6eee3ee36ca6e41`. **839 testes JVM** sem falhas, erros ou ignorados. Lint, 10 testes Python, traduções e cinco validadores de GPU em software aprovados. As capturas locais de Favoritos PS4/PS5 em paisagem com barras simuladas foram abertas para inspeção.
- [Artefato da versão 99](https://github.com/Ricardex742/xCloudNative/actions/runs/44413085513/artifacts/14479531841). O APK baixado tem pacote `com.xcloud.tclnative`, versão **0.40.4-performance**, código **99**, minSdk **24**, targetSdk **34**, ARM32/ARM64 e x86/x86_64. A depuração está desabilitada e o certificado permanente foi conferido.
- Certificado SHA-256 `efd33d9a3122ab5df2a7f0932e80b8fb51449bb1d3206fddc300b2766db73107`. APK SHA-256 `31a0a1b34eb35914c03c08963f7eb8f9b37d412a38fdaec21f43d013b4c496be`. ZIP SHA-256 `44195b27cae62929f939721cefdd365751cbac135b20717ea48165ff9827b6ff`, igual ao digest do Actions.

## Terceira etapa do plano de otimização

- Cada alocação de streaming possui identidade, região, resolução, idioma e estado de negociação próprios. Pedidos e limpeza permanecem associados à tentativa de origem. Uma resposta atrasada não pode encerrar a alocação substituta, mesmo com o mesmo caminho em outra região.
- A renovação de credenciais é serializada por conta e invalidada ao sair ou trocar de login. A nova conta pode prosseguir enquanto uma resposta da anterior ainda está pendente. O catálogo e a abertura por atalho usam o contexto da conta autenticada.
- O coordenador de reprodução aguarda a limpeza da tentativa anterior, controla a espera do primeiro quadro e ordena a liberação do vídeo, WebRTC e alocação. As chamadas da Activity que operam sobre Views e EGL mantêm sua afinidade de thread.
- O catálogo nativo usa regras testáveis de foco por tema, com restauração por chave do jogo. A escala de referência é compartilhada entre home, diálogos, fila e transições. A comparação em Robolectric encontrou 525 de 530 capturas de celular idênticas à versão 99. As cinco diferenças inspecionadas são destaques de foco em Favoritos e a ordem de carregamento das capas de teste. As capturas de PS4 e PS5 abertas incluíram paisagem em 1280×600, 2400×1080 com insets simulados e referência de TV de 1280×720.
- [Build 44417706399](https://github.com/Ricardex742/xCloudNative/actions/runs/44417706399), commit `177c010d8e7c7c8c192a41301977404005409047`. **848 testes JVM** sem falhas, erros ou ignorados. Lint, traduções, verificações Python e cinco validadores gráficos em software aprovados. Controle físico, partida no serviço e desempenho em aparelho continuam pendentes.
- [Artefato do APK 100](https://github.com/Ricardex742/xCloudNative/actions/runs/44417706399/artifacts/14480547335). O arquivo baixado foi conferido com o SHA-256 do build, depuração desabilitada, pacote `com.xcloud.tclnative` e certificado permanente. A versão é **0.40.5-session-architecture**. APK SHA-256 `b2d0fb190d3cbcdba0408ad45274b3ee9b73549ac9e6e0ef431bb579dde44771`. ZIP SHA-256 `a2b4cc725b3e74fe6777f84294cd8eb32907005d0bc0315daf4dee4ef8e71e31`.

## Quarta etapa do plano de otimização

- Cada tentativa mede marcos relativos ao mesmo relógio monotônico. O diagnóstico diferencia abertura pelo catálogo e por atalho, solicitação PLAY, fila confirmada, provisionamento, negociação, primeiro quadro recebido no track e primeira submissão aceita no EGL. Reconexões têm seu próprio epoch, resolução e codec observado pelo RTC. O armazenamento retém o primeiro transporte e os três mais recentes.
- RTT, jitter, buffer e decodificação reportam p50, p95 e p99 das últimas 120 amostras válidas no primeiro plano. Os percentis descrevem amostras por intervalo do WebRTC. A latência física, o sincronismo A/V e a resposta completa do controle continuam dependendo de instrumentação em aparelho.
- Em Ajuda e diagnóstico, Último diagnóstico, a ação Exportar diagnóstico usa o seletor de arquivos do Android para salvar JSON. A exportação usa campos tipados e exclui identidade da conta, título e texto livre. Relatórios anteriores continuam legíveis.
- Os testes de smoke cobrem manifesto de celular/TV, falha antes do primeiro quadro, transporte rejeitado, descarte de callback de uma Surface antiga e exportação. A captura de conexão usa o mesmo relógio de teste da fila. O roteiro em `scripts/smoke_device.md` descreve o fluxo com APK e aparelho. Nenhum dispositivo ADB estava disponível para a execução física.
- O gate de lint cobre APIs, permissões e flags de receptores dinâmicos. O modo `-Pxcloud.lintAudit :app:lintDebug` gera o relatório amplo XML/HTML. O relatório local registrou sete erros em recursos, internacionalização e componentes Android. As exceções de receptores USB correspondem a fluxos protegidos pela versão do SDK, com registro explícito não exportado no Android 13 ou superior.
- [Build 44423087739](https://github.com/Ricardex742/xCloudNative/actions/runs/44423087739), commit `5c7a813fc578362a16354d5b0b094737c9c0af87`. **856 testes JVM** sem falhas, erros ou ignorados. Lint, traduções, testes Python e cinco validadores gráficos em software aprovados.
- [Artefato do APK 101](https://github.com/Ricardex742/xCloudNative/actions/runs/44423087739/artifacts/14484171405). O APK baixado foi conferido com o SHA-256 do build, depuração desabilitada, pacote `com.xcloud.tclnative` e certificado permanente. A versão é **0.40.6-playback-diagnostics**. APK SHA-256 `a50d2cd202f74bec1a08f0b82ba909d8fbb554ce3adadef009e9756592a79034`. ZIP SHA-256 `0bcd4f7cc9bd4c408e81ed06599f3dd9cb56839b8b9c2d59c79c567b1743203a`.

## Quinta etapa do plano de otimização

- A geração de quadros continua opcional. Após 12 comparações que confirmam imagens distintas a 13–20 ms, o modo experimental entrega os originais diretamente e pula até 48 consultas de assinatura. A consulta é retomada no primeiro quadro com intervalo fora dessa faixa. O histórico de interpolação é invalidado ao entrar na pausa. Preservar a sincronização das texturas entre contextos EGL até obter evidência em hardware.
- Um replay JVM com scheduler e backend sintéticos comparou a versão 101 e a candidata. Com 240 quadros distintos a 60 FPS, as consultas caíram de 240 para 52 e todos os originais foram entregues em ordem. Os replays de 30 FPS nativos e 30 FPS duplicados a 60 mantiveram os mesmos totais entregues e gerados. Esses números contam chamadas e quadros do fixture, sem medir GPU, consumo ou apresentação em aparelho.
- O replay de pacing, a 30 e 60 FPS com variação de chegada definida, entregou todos os 240 quadros em cada opção. O p95 do erro de intervalo caiu de 6 para 0 ms com pacing suave, que acrescentou 10 ms de espera local no p95. A resolução, pós-processamento e modo Compatibilidade são avaliados separadamente no roteiro em `scripts/smoke_device.md`. O pacing suave exige o perfil Adaptativo.
- O diagnóstico pós-partida separa texto de entrada e texto de áudio/vídeo, cada um com limite próprio. Isso preserva a linha de áudio mesmo quando o bloco de controles é longo. O relatório e o JSON trazem quadros RTP observados, decodificados, descartados e submissões totais ao EGL. A exportação permanece restrita a dados estruturados.
- O diagnóstico identifica os tipos HDR anunciados pelo Android. A confirmação de HDR, Dolby Vision, baixa latência de TV, sincronismo A/V e desempenho físico depende da negociação, do pipeline e de aparelhos. O ADB não listou dispositivo disponível para a candidata.
- [Build 44450815018](https://github.com/Ricardex742/xCloudNative/actions/runs/44450815018), commit `0279bb7b832e83fb417b7fdef5e7aa7f0cbdcd46`. **861 testes JVM** sem falhas, erros ou ignorados. Lint, traduções, testes Python e cinco validadores gráficos em software aprovados.
- [Artefato do APK 102](https://github.com/Ricardex742/xCloudNative/actions/runs/44450815018/artifacts/14495469805). O APK baixado foi conferido com o SHA-256 do build, depuração desabilitada, pacote `com.xcloud.tclnative` e certificado permanente. A versão é **0.40.7-streaming-lab**. APK SHA-256 `81e30736f2dc528f340fb4cee7550fb13071eb48ceec7a0631898e88d42d3996`. ZIP SHA-256 `8831083683b918af3eeb753c3b91119b56d6c7ee94c125c9227dabe42f7bc27e`.

## Sexta etapa do plano de otimização

- O pacote 102 inferia `android.hardware.wifi` como obrigatório pela permissão de estado de rede. A versão 103 torna Wi-Fi opcional para catálogos que filtram aparelhos pelo manifesto. O build verifica a declaração no APK.
- [Build 44453124428](https://github.com/Ricardex742/xCloudNative/actions/runs/44453124428), commit `2eccc9037ec669e4b59c3af66b7f7f9032736b21`. **861 testes JVM** sem falhas, erros ou ignorados, lint obrigatório, traduções, 10 testes de assinatura e cinco verificadores gráficos em software aprovados. A análise ampliada do lint manteve os sete erros já registrados.
- A reexecução local com o mesmo código de reprodução aprovou a suíte e gerou 530 capturas da matriz de celular e 161 da matriz de composições. A inspeção de PS4/PS5 incluiu paisagem com barras e recortes simulados e referência de TV. Sete replays sintéticos de 216.000 quadros terminaram com zero referências pendentes de buffers simulados.
- [Artefato do APK 103](https://github.com/Ricardex742/xCloudNative/actions/runs/44453124428/artifacts/14497152475). O ZIP e o APK baixados conferem com o build. O pacote `com.xcloud.tclnative` mantém o certificado permanente, depuração desabilitada, minSdk 24 e targetSdk 34. Foram conferidos os launchers móvel e Leanback, as exclusões de backup e transferência e o alinhamento ELF/ZIP em 16 KiB para ARM64 e x86_64.
- APK SHA-256 `e239293763a1d9ef6a334d97253caff961e4f60cf583d3287fd90b0981fdaa0a`. ZIP SHA-256 `df7e07870a636897207fe455d348f27d352443e90a508d1bb8bcb0a1b2941002`.
- A candidata está na feature branch. O ADB não listou aparelho conectado. Instalação como atualização, runtime de 16 KiB, imersão e controles físicos, partida e fila no serviço, medição térmica e latência até a tela continuam pendentes.

## Foco, catálogo do PS5 e rotação em paisagem

- As abas de PS4 e PS5, inclusive nas variantes Night, reservam margem interna para o contorno de foco. No PS4, o sublinhado indica a aba ativa quando o foco está em outro controle.
- A home do PS5 exibe até cinco jogos por categoria. O botão de grade tem o tamanho da capa comum, acompanha a última capa e abre a lista completa de Todos os jogos. A grade reutiliza o adapter nativo e as miniaturas existentes. Voltar restaura a seleção e o foco do atalho de origem.
- O manifesto usa `userLandscape`. A Activity continua em paisagem e permite os dois sentidos com a rotação automática do Android. Os testes de launcher validam a declaração. A inspeção nativa cobriu 1280×600 e 2400×1080, com barras e recortes simulados. A rotação pelo sensor segue pendente em aparelho real.
- A versão **0.40.9-landscape**, **versionCode 104**, foi compilada no commit `5b5b4de3132dcae61dc774e1517098cc1d129a04`. Passaram **862 testes JVM**, o lint obrigatório, a validação de traduções, assinatura e filtros gráficos. [Build 44541620430](https://github.com/Ricardex742/xCloudNative/actions/runs/44541620430)
- O APK baixado conferiu com o build, com o certificado permanente e depuração desabilitada. O SHA-256 do APK é `62d8980d1e1ecebef754bd6043d0b2150522eed680dc352cd253ec8af64a25e9`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44541620430/artifacts/14539094247)

## Recortes em paisagem e atalhos da partida

- `ImmersiveWindow` permite desenhar nas bordas curtas da tela a partir da API 28. Catálogo, fila, preparação e login calculam o conteúdo dentro da área segura. Arte e fundos continuam até as bordas da janela. Painéis próprios, estatísticas e seta do menu respeitam os insets do recorte. A proporção do vídeo continua sob controle do renderer.
- No celular, a seta compacta abre uma lista lateral nativa com rótulos. A interface usa as cores do tema. Imagem e tela usam o modo efetivo da sessão. Áudio inclui volume, ganho e microfone quando negociado. Controles, estatísticas, recuperação e encerramento usam os callbacks da sessão atual. Opções da próxima sessão continuam no catálogo. Teclado e controle mantêm as rotas existentes.
- A comparação móvel usou instruções e imagens oficiais da NVIDIA. O botão da documentação expande a sobreposição de controles no canto superior esquerdo. A documentação de qualidade descreve ajustes que valem na próxima abertura do jogo. [Controles no celular](https://nvidia.custhelp.com/app/answers/detail/a_id/5108) · [Qualidade da transmissão](https://nvidia.custhelp.com/app/answers/detail/a_id/5340)
- Foram inspecionadas capturas nativas de 1280×600 com recorte simulado em ambos os lados. Os testes cobrem janelas, áreas seguras, alvos de toque e escopo dos ajustes da partida. O ADB não listou aparelho conectado.
- A versão **0.40.10-live-menu**, **versionCode 105**, foi compilada no commit `234d4cd67837b381795b7ddb6d05ff90851aac79`. Passaram **864 testes JVM**, lint obrigatório, traduções, testes de assinatura e verificadores gráficos. [Build 44550920846](https://github.com/Ricardex742/xCloudNative/actions/runs/44550920846)
- O ZIP e o APK baixados conferem com o build e o APK mantém a assinatura permanente. SHA-256 do APK `1db60d9af16868e1cf6fea5ca2cfa3171f52c3ee59899551bfbb1715b85eaeae`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44550920846/artifacts/14542588757)

## Janela de ponta a ponta em paisagem

- A versão 105 permitia o desenho na borda curta, mas mantinha o ajuste padrão de insets do conteúdo. Em Android 11 ou superior, a Activity e os diálogos de tela inteira solicitam `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`, `fitInsetsTypes=0` e `setDecorFitsSystemWindows(false)`. A compatibilidade anterior usa os flags de layout imersivo. As áreas seguras continuam protegendo os controles e o teclado dos diálogos de tela inteira.
- `WINDOW_GEOMETRY` registra dimensões e posições do decor e do conteúdo, bounds atuais e máximos, insets e política de recorte. O diagnóstico de vídeo mantém a geometria da Surface separada para identificar uma janela menor que a tela física.
- Foram abertas capturas nativas de 2340×1080 com recortes simulados à esquerda e à direita. Os testes também verificam as janelas imersivas, a largura da área do vídeo e os insets da pesquisa com teclado. As tentativas no emulador Android 16 sem aceleração terminaram com falhas durante a instalação. A validação em aparelhos Galaxy continua pendente.
- A versão **0.40.11-fullscreen**, **versionCode 106**, foi compilada no commit `cedb134eca48811b7b2ff4da46f981370781b40e`. A segunda tentativa do Actions passou **865 testes JVM**, lint obrigatório, traduções, assinatura e verificadores gráficos. [Build 44557959908](https://github.com/Ricardex742/xCloudNative/actions/runs/44557959908)
- O APK baixado manteve a assinatura permanente e conferiu com o build. SHA-256 `194aab31bbff5038fe9aab75ab5f4338dafc495c4892aa18916365526f666aaa`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44557959908/artifacts/14550630350)

## Barra de atalhos durante a partida

- No celular, a seta abre a barra nativa com Imagem, Áudio, Controles e Mais opções, conforme as ações disponíveis na sessão. Mais opções abre uma lista rolável abaixo da barra com encerramento, estatísticas, diagnóstico, recuperação e controles adicionais quando disponíveis. Encerrar exige confirmação. A composição usa a paleta e os ícones do xCLOUD.
- A janela imersiva usa a área segura da Activity e recebe suas atualizações de recorte. A seta recolhida fica oculta enquanto a barra está aberta. A abertura solta os comandos em andamento. Voltar recolhe a lista ou retorna de um ajuste ao atalho que o abriu. X encerra a navegação no menu.
- O teste nativo `GameShortcutBarTest` passou com recorte simulado, troca de lado do recorte, primeiro toque, busca de foco direcional, A/Enter, B/Voltar, X e confirmação de saída. Foram abertas as três capturas nativas de 2340×1080. O ADB não listou aparelho conectado para validação física.
- A versão **0.40.12-shortcut-bar**, código **107**, foi compilada no commit `98b40c3a586c1b472ec5e4da4614389d0fa312c6`. Passaram **866 testes JVM**, lint obrigatório, as verificações de assinatura, as traduções e os validadores gráficos. [Build 44595940475](https://github.com/Ricardex742/xCloudNative/actions/runs/44595940475)
- O ZIP e o APK baixados conferiram com o build. O APK mantém o certificado permanente, vem com a depuração desabilitada e declara `userLandscape`. SHA-256 do APK `bba37f21e0860155e1944362f777b15ce91f6fa1af482072161b94c4b89c83b9`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44595940475/artifacts/14567605523)

## Controle virtual, bitrate e capa na fila

- No celular, a barra exibe Mostrar ou Ocultar controle virtual conforme o estado do HUD. HUD e controles deste jogo fica em Mais opções, logo abaixo de Encerrar partida.
- Bitrate máximo em Mais opções aplica o valor somente à partida atual. Após confirmação, o app renegocia o transporte WebRTC na sessão Xbox existente. O diagnóstico distingue o teto solicitado do bitrate recebido.
- O carregamento do catálogo preserva a capa já resolvida para o jogo em preparação quando uma resposta anterior chega incompleta. A fila tenta as artes alternativas do título se a primeira imagem falhar.
- Passaram 70 testes direcionados, incluindo o fluxo atalho → catálogo → segundo jogo com resposta atrasada e a negociação simulada de bitrate na mesma sessão. As capturas nativas da barra foram verificadas. A confirmação do fluxo de bitrate em uma partida no Xbox Cloud Gaming continua pendente.
- A versão **0.40.13-bitrate-artwork**, código **108**, foi compilada no commit `3ff432d84d0b4d4a90d68dd6a0b6196980fdfa7a`. Passaram 867 testes JVM, lint, traduções e verificações gráficas. [Build 44610112319](https://github.com/Ricardex742/xCloudNative/actions/runs/44610112319)
- O APK baixado mantém o certificado permanente e declara `userLandscape`. SHA-256 `8f0ace935bfe20f387237b3595c71d8918084281eb959e50b0f65ae5f8ca18f8`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44610112319/artifacts/14572802610)

## Entrada na TV e retorno pelo atalho

- A seleção de controles no Android TV considera eixos de joystick e botões dedicados antes de reservar uma vaga. O controle direcional pode jogar quando a vaga do jogador 1 está livre. O gamepad pode assumir a vaga ao primeiro comando quando se conecta durante a partida. O registro do novo dispositivo limpa o estado físico de reserva.
- Ao retornar após abrir pelo atalho, a Activity reaproveita a lista validada e a biblioteca da conta. A restauração de foco usa a apresentação existente. Os cartões visíveis aceitam navegação durante a atualização de metadados, e o carrossel Xbox Series recalcula o layout dos cartões cujo tamanho mudou.
- A seleção do jogador 1 com dispositivos simulados e a navegação enquanto chegam metadados têm testes de regressão. A confirmação em uma TV física continua pendente.
- A versão **0.40.14-tv-shortcut**, código **109**, foi compilada no commit `a0f1a09767da287f518de5fe44b5a6072e6a6938`. Passaram 869 testes JVM, lint, traduções e verificações gráficas. [Build 44649562714](https://github.com/Ricardex742/xCloudNative/actions/runs/44649562714)
- O APK baixado mantém o certificado permanente e declara `userLandscape`. SHA-256 `38cdd57830774fccfd42b125387ac53ccbb47d22c2671959f70ed453df70721b`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44649562714/artifacts/14590792819)

## Barra personalizada e avisos de rede

- Mais opções → Editar atalhos permite definir até quatro posições, trocar a ordem, deixar posições vazias e restaurar a configuração inicial. As preferências usam IDs estáveis. A barra começa com Imagem, Áudio e Controle virtual, com a quarta vaga livre. Ações fora da barra continuam disponíveis em Mais opções conforme a sessão.
- Avisos de rede é um interruptor direto e opcional que começa desativado. Usa as amostras existentes de RTT, jitter e perda do WebRTC. Três amostras consecutivas fora dos limites locais ativam o aviso. Três amostras de recuperação recolhem o aviso. Reinícios do transporte limpam o estado.
- Tocar no aviso abre Estatísticas da rede. O painel também pode ser aberto em Mais opções ou fixado na barra e mostra RTT, jitter, perda de pacotes e bitrate recebido com as amostras da sessão atual.
- Os testes direcionados cobrem as preferências, as duas rotas do interruptor, os dados ausentes e a navegação do painel. As capturas nativas do menu e do editor foram inspecionadas. O gesto sobre o aviso em uma transmissão no aparelho continua pendente.
- A versão **0.40.15-shortcut-network**, código **110**, foi compilada no commit `f82b77bbbf67deb0d3311e661159aa17c9e114b4`. Passaram 871 testes JVM, lint, traduções e verificações gráficas. [Build 44664772165](https://github.com/Ricardex742/xCloudNative/actions/runs/44664772165)
- O APK baixado mantém o certificado permanente e declara `userLandscape`. SHA-256 `43fb33a56d996033f465200bd6057d76a419f751e5ca65eb737a1f362bc37b48`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44664772165/artifacts/14597513473)

## Indicador de qualidade de rede no canto superior direito

- Com Avisos de rede ativo, o indicador mostra três sinais verdes para rede estável, dois amarelos para rede moderada e um vermelho para rede ruim. Um ponto cinza indica que a avaliação ainda está pendente. Usa RTT, jitter e perda da transmissão. A confirmação em três amostras reduz mudanças por picos isolados.
- O painel nativo de Estatísticas da rede tem até 248 dp de largura, altura ajustada ao conteúdo e posição no canto superior direito. O indicador e o painel respeitam o recorte. O toque no indicador e o item em Mais opções continuam abrindo o painel.
- Passaram 55 testes direcionados. As capturas simuladas dos três estados e do painel foram inspecionadas. A nova posição ainda depende de confirmação em aparelho físico.
- A versão **0.40.16-network-indicator**, código **111**, foi compilada no commit `67b0ad29bbae3e6eba600b838b3da4bdcc91842b`. Passaram 872 testes JVM, lint, traduções e verificações gráficas. [Build 44670381233](https://github.com/Ricardex742/xCloudNative/actions/runs/44670381233)
- O APK baixado mantém o certificado permanente e declara `userLandscape`. SHA-256 `c679f17c6875f3de85068c92e2bdffbfb4e01cf397a94be7dee8def4fae6384a`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44670381233/artifacts/14600601362)

## Remote Play experimental

- A primeira versão, `0.41.0-remote-play`, código 112, usava um painel **Meus consoles** acessível na navegação do Xbox Series e das paletas simples, na **Biblioteca** PlayStation e em **Conta e amigos**. A interface atual está descrita abaixo.
- `XalAuthenticator.authenticateToRemotePlay` solicita o bearer da oferta `xhome` com o login Xbox da conta. A descoberta consulta os consoles associados a esse bearer. `XCloudSignalingClient` reaproveita o transporte de sinalização com rota própria de sessões home, identidade do console, espera limitada, negociação SDP/ICE e limpeza da alocação. Se a sessão pedir `ReadyToConnect`, a credencial de transferência é solicitada naquele momento. [Referência de autenticação e sinalização](https://github.com/unknownskl/greenlight) · [Referência do cliente Android](https://github.com/Geocld/XStreaming)
- O perfil inicial usa o player nativo, H.264 em 720p ou 1080p, reprodução de áudio, gamepad e controle virtual. Os diagnósticos têm origem `REMOTE_PLAY` e usam o nome genérico Remote Play. Fechar a sessão cancela ou libera a alocação e restaura o painel.
- Passaram 92 testes JVM direcionados, incluindo interface nativa, foco, autenticação, identificação da sessão home, cancelamento durante a criação e a espera, reconexão e regressões de catálogo, atalhos e sinalização cloud. Passaram também o lint obrigatório, os 10 testes Python e a verificação das traduções nos 15 idiomas.
- Na primeira validação física, habilitar **Recursos remotos** no Xbox Series, usar a mesma conta Microsoft no console e no app e começar na mesma rede. Confirmar descoberta, primeiro quadro, áudio, gamepad desde o primeiro comando, cancelamento durante a preparação, desconexão e nova conexão. Em seguida repetir no Android 10 e na Android TV, no Xbox One, em espera e fora da rede local. Os testes automatizados usam um servidor simulado. Compatibilidade e qualidade da conexão com o serviço exigem Xbox físico.
- O APK de teste **0.41.0-remote-play**, código **112**, foi compilado no commit `47c07444754ee620c324fe6a905ab86de3ef8fe2`. Passaram **879 testes JVM**, lint, traduções, testes de assinatura e validadores gráficos. [Build 44753720377](https://github.com/Ricardex742/xCloudNative/actions/runs/44753720377)
- O APK baixado tem pacote `com.xcloud.tclnative`, minSdk 24, targetSdk 34, launcher Android TV e depuração desabilitada. O certificado permanente foi conferido e os hashes correspondem aos do build. APK SHA-256 `b7cfd6b047c576a41129d5db90090b3382755f2def6f38abad128ee9bcb95678`. ZIP SHA-256 `36b2f9c00d680ca51f094610d0bdb78c18a4f35116c49171d5cbbb554c4bc2e0`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44753720377/artifacts/14637666132)

### Cabeçalho e guia de configuração

- A versão `0.41.1-remote-setup`, código 113, introduziu **Reprodução remota** pelo seletor **Cloud Gaming**. O seletor devolve o foco ao fechar. A posição atual está abaixo. A tela nativa em tela cheia mostra **Meus consoles** com a conta configurada ou **Prepare-se para jogar** quando a lista está vazia.
- **Configurar console** abre o guia nativo com o caminho de Recursos remotos e as ações de ajuda e configuração no site do Xbox. Ao voltar do guia, **Atualizar** consulta os consoles novamente. O app mantém a transmissão pela sessão nativa `xhome`. [Configuração oficial](https://www.xbox.com/pt-br/play/consoles)
- Nas paletas com o layout genérico, o bloco de perfil ficou mais compacto. A dimensão de referência do avatar passou de 80 para 64. Nome, gamertag, selos e engrenagem acompanham o tamanho do bloco.
- Os testes de interface exercitam o seletor por controle, a preparação com lista vazia, o guia, a volta à tela anterior, a conexão com console de exemplo e as dimensões do perfil. A confirmação em aparelho está pendente.
- O APK da versão 113 foi compilado no commit `50e7e3fac9074e9c58bde5997651f56f9999ec60`. Passaram **881 testes JVM**, lint, traduções, assinatura e validadores gráficos. O arquivo baixado preserva a identidade de atualização, minSdk 24 e launcher Android TV. APK SHA-256 `3b1d4b28b9ba7fbab2b01f4df3208afb0714a4078705f78880e4f7412c0bbeed`. ZIP SHA-256 `a4a68c95a6b0781fbb3202b341cdc898358bee6be6efb0645436e5b185a1749f`. [Build 44760914796](https://github.com/Ricardex742/xCloudNative/actions/runs/44760914796) · [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44760914796/artifacts/14642112286)

### Acesso à Reprodução remota por tema

- Desde **0.41.2-remote-menu**, código 114, o catálogo volta a ocupar o painel inteiro. Nas paletas genéricas, **Cloud Gaming** fica no canto superior direito junto ao perfil compacto. Nos temas Xbox, uma seta abre o mesmo menu ao lado da marca do tema. Nos temas PlayStation, fica junto à marca xCLOUD.
- A ilustração de **Prepare-se para jogar** usa o arquivo oficial `brand_logo.png` no primeiro plano, com o monitor e o console ao fundo.
- A suíte de interface cobre a posição nos layouts genérico e Xbox Series, a abertura do seletor por toque e controle, o fechamento com B e a conexão explícita. A revisão em celular e TV físicos permanece pendente.
- Passaram 17 testes JVM direcionados e lint locais. As capturas nativas simuladas do perfil, do Xbox Series e da preparação foram conferidas. O CI aprovou a suíte completa, os validadores gráficos e a compatibilidade Android 10 e TV no commit `3f5e6f6e960ed280e31fa9eb085f648f565b1ce4`. [Build 44774424433](https://github.com/Ricardex742/xCloudNative/actions/runs/44774424433)
- O APK baixado preserva o certificado permanente e tem minSdk 24, launcher Android TV e depuração desabilitada. APK SHA-256 `4be8fab9628ddbb2c75ebea3470427f6a9da162c9f1af42ab1aef411a07938d0`. ZIP SHA-256 `d6d8b8dee13394024ace7b35fa81e02386b406b72963f55dd3e8c97a13ba4d86`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44774424433/artifacts/14649413571)

## Primeiro controle ao entrar por atalho no Android TV

- A versão **0.41.3-tv-shortcut**, código 115, aguarda um comando de gamepad para associar um dispositivo Android ao jogador 1 quando a sessão começa por atalho. O direcional e o OK do controle da TV podem jogar enquanto essa vaga estiver livre. A chegada de um botão identificado como gamepad ou de movimento efetivo no joystick assume a vaga e limpa o estado anterior do direcional.
- Dispositivos que apenas anunciam eixos e enviam estado neutro podem permanecer na lista do Android sem bloquear os controles. Pelo catálogo, um controle identificado durante a navegação ainda pode ser o primeiro da partida. O sinal de registro do jogador 1 no transporte continua independente da descoberta do dispositivo Android.
- O teste de regressão simula uma abertura por atalho com um dispositivo inativo que anuncia joystick, um controle de TV e um gamepad. A reprodução em TV física permanece pendente.
- Passaram 23 testes direcionados e lint localmente. O commit `322975687c96b3f4dfef397e45a1fefcb2bc8094` passou 881 testes JVM, lint, traduções e verificações gráficas. [Build 44817456504](https://github.com/Ricardex742/xCloudNative/actions/runs/44817456504)
- O APK baixado preserva o certificado permanente e declara launcher Android TV, minSdk 24 e depuração desabilitada. APK SHA-256 `9e746c6ac2262a43284a649a61aed80a3634ee9f6f1c0ff1950e8ba945bbe245`. ZIP SHA-256 `bd33249fe7d0cf3793853948b0515b03fa4df1c1d419d262e9a0f08f4e245305`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44817456504/artifacts/14670791689)

## Salvar e retomar a sessão aberta

- A versão **0.41.4-saved-session**, código 116, oferece **Salvar sessão** nos atalhos da partida. O catálogo mostra **Retomar sessão**, inclusive depois de entrar direto por um atalho externo. Selecionar outro título exige confirmação.
- O app mantém a alocação Xbox e o transporte atual, interrompe os comandos locais, esconde a superfície e silencia áudio e microfone enquanto o catálogo está à frente. A retomada volta à mesma alocação. Se o transporte cair, usa a recarga existente da mesma sessão. Se a alocação expirar, o botão de retomada é removido.
- Na retomada, o upscaling nativo e o FSR usam as preferências atualizadas. A mudança de resolução envia a solicitação `userRequestedResolutionUpdate` no canal de controle. O tamanho real depende do vídeo devolvido pelo serviço. As opções de vídeo que dependem de outra sessão continuam sujeitas à configuração ativa.
- A sessão existe na Activity e no serviço Xbox. O recurso não cria save do jogo, não pausa o jogo no servidor e não mantém a alocação após o encerramento do processo. Testar alterações de resolução e manutenção da posição com serviço e aparelho reais.
- Passaram 53 testes direcionados e lint locais. O build do commit `e481b68dcc27a542c3bddfbdf67cfcefb3748fde` passou 883 testes JVM, lint, traduções e validadores gráficos. [Build 44847165182](https://github.com/Ricardex742/xCloudNative/actions/runs/44847165182)
- O APK baixado mantém o certificado permanente, minSdk 24, launcher Android TV e depuração desabilitada. APK SHA-256 `9c7c1bab769b404384971a060f0a039c2979a6adb10e371dcc59ab01b7008377`. ZIP SHA-256 `accd531c6de087927885b8413b0fa2d0c769ef877d69c7de013d364e3f83778c`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44847165182/artifacts/14683389977)

## Login Microsoft pelo navegador e por QR Code

- A versão **0.41.5-device-login**, código 117, usa a autorização por código de dispositivo também em **Entrar com Microsoft**. A tela mostra o código e permite copiá-lo e abrir a página oficial no navegador do aparelho. O QR Code continua disponível em **Entrar com QR Code**.
- O fluxo anterior enviava uma URI de retorno sem correspondência no cadastro do cliente OAuth. A nova solicitação usa o endpoint de código de dispositivo sem `redirect_uri`. Uma consulta sem credenciais ao endpoint de produção retornou HTTP 200 e uma página de verificação da Microsoft. A aprovação com uma conta real permanece pendente.
- Endereços de aprovação precisam usar HTTPS e um host da Microsoft. O código só vai para a área de transferência após a ação do usuário. As credenciais salvas continuam usando o cliente e a renovação existentes.
- A consulta da aprovação mantém o código ativo durante falhas temporárias de DNS, conexão ou disponibilidade da Microsoft. Usa o intervalo original do provedor como mínimo, aumenta a espera em falhas consecutivas e respeita `Retry-After`. A tela continua exibindo o código até a aprovação ou a expiração. Negativas da Microsoft encerram a tentativa.
- A versão **0.41.7-login-retry**, código 119, passou 887 testes JVM, lint, traduções e verificações gráficas no commit `6a0daad370dbcc294fdb82439f76660cfdd6dc3c`. A retomada com o mesmo código após DNS indisponível foi simulada. A confirmação no aparelho permanece pendente. O APK baixado mantém a assinatura permanente, minSdk 24, launcher Android TV e depuração desabilitada. APK SHA-256 `f8ed930ee9a69ed51dc562bf196fb8ffb20c1077e2484af39cf25e3e8aaf9610`. [Build 44942687302](https://github.com/Ricardex742/xCloudNative/actions/runs/44942687302) · [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44942687302/artifacts/14722218463)
- Passaram 61 testes direcionados e lint localmente. O build do commit `30b8cd6a3bed366d6357d0f713b3b313ddd16f87` aprovou 879 testes JVM, lint, traduções e verificações gráficas. [Build 44913858055](https://github.com/Ricardex742/xCloudNative/actions/runs/44913858055)
- O APK baixado mantém o certificado permanente, minSdk 24, launcher Android TV e depuração desabilitada. APK SHA-256 `44a4c500dcf6819119c1fade6ed200d28b5539e2f2c95b01fcf0068f0a5354f0`. ZIP SHA-256 `7a4b2fc130e4bfffc86de0f2b6eeec08d1957ab7028a17eafe2376d6cc25f8d3`. [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44913858055/artifacts/14710544435)

## Revisão de catálogo e manutenção da sessão

- **Transmita seu jogo** consulta as licenças da galeria ao abrir a aba e prioriza compras confirmadas, seguidas por jogos sem compra confirmada pelo serviço e, por último, licenças indeterminadas. Dentro de cada grupo, usa o título em inglês do Store com comparação alfabética dos EUA e recorre ao nome exibido quando o título em inglês está indisponível. O ✓ e a descrição acessível aparecem somente na compra confirmada para o ID exato do produto.
- A navegação vertical conserva o foco dentro da grade durante a rolagem e restaura pelo identificador do jogo quando as licenças reordenam a aba. A busca do catálogo abre com o teclado recolhido. Confirmar o campo abre o teclado e Voltar/B pode fechá-lo. Segurar X no catálogo limpa a busca aplicada. Dentro da busca, segurar X limpa o campo para edição.
- HTTP 401 no keep-alive do Cloud Gaming permite uma renovação da autorização da mesma conta e nova tentativa na alocação original. A identidade e o endereço da alocação são validados antes de usar a nova autorização. O caso de recuperação é simulado no teste de sinalização. Partida física e comportamento do serviço após uma recusa real seguem sem validação.
- O build de **0.41.6-catalog-session**, código 118, usou o commit `6d6523cc8a09646d5621d8ed191e3ca5770e5701` e aprovou 884 testes JVM, lint, traduções e verificações gráficas. O APK baixado mantém a assinatura permanente, minSdk 24, launcher Android TV e depuração desabilitada. O SHA-256 do APK é `b2c281a1339aced58e0f534a7ab26762227bb262c7d1c22472b5932cfe859c09`. [Build 44917841893](https://github.com/Ricardex742/xCloudNative/actions/runs/44917841893) · [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44917841893/artifacts/14712911886)

## Compras da conta e toque no celular

- A verificação de compras usa o snapshot autenticado de **Content Access**, também usado na consulta de horas. O leitor extrai as licenças diretas da conta por ID de produto. Compra, assinatura e acesso compartilhado têm indicadores próprios. A aba mostra o ✓ para licença de compra confirmada e informa no rodapé quando nenhuma licença foi confirmada. A aplicação a uma conta Xbox real permanece pendente. [Xbox Cloud Gaming](https://www.xbox.com/pt-BR/play)
- Botões e capas do celular executam o clique no primeiro toque completo. O toque usa a entrega nativa do Android para preservar rolagem, cancelamento e toque longo. A navegação por controle continua usando o foco normal.
- A versão **0.41.8-ownership-touch**, código 120, passou 887 testes JVM, lint, traduções e verificações gráficas. O APK baixado foi conferido com a assinatura permanente, minSdk 24, launcher Android TV e depuração desabilitada. O teste de toque usou eventos Android em modo de toque e a compra foi verificada com respostas simuladas. O SHA-256 do APK é `02eb22b28e926777428d0ff96a8b5f3c79af7b44aef34eb85bfcbca72e387886`. [Build 44945749658](https://github.com/Ricardex742/xCloudNative/actions/runs/44945749658) · [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/44945749658/artifacts/14724340933)

## Controles e manutenção da transmissão

- Teclas Android e o eixo do direcional mantêm estados separados durante a partida. Relatórios neutros do joystick preservam uma tecla ainda pressionada. Repetições do Android e solturas sem pressão registrada não criam comandos adicionais. Abrir o menu ou perder o foco descarta transições de controle ainda na fila.
- O atalho do botão Xbox aguarda o retorno do foco e mantém o pressionamento separado do HUD, do joystick e da mira até a soltura. O app encaminha o botão físico quando o Android entrega o evento correspondente. A reação do guia depende da sessão Xbox.
- A manutenção do Remote Play pode renovar a autorização `xhome` após um HTTP 401 e repetir a solicitação na mesma alocação. A renovação exige a mesma geração de credencial e o endereço original da sessão. A verificação no serviço Xbox físico permanece pendente.
- O APK de teste `0.41.9-session-controls` (`versionCode 122`) passou por 892 testes JVM, lint de APIs, validações de renderização por software e verificação da assinatura fixa. O teste automatizado de renovação mantém a alocação original. A validação em partidas reais e com controles físicos segue pendente. [Build e artefatos](https://github.com/Ricardex742/xCloudNative/actions/runs/45030379046).

## Atalhos e entrada durante a partida

- O menu local aceita Menu + View nos controles associados à partida, inclusive quando o jogador 1 está reservado ao toque. Em celular com toque, uma interrupção recuperável conserva o botão de menu e a ação de reconectar. Os diagnósticos diferenciam eventos Xbox entregues pelo Android, pulsos do menu e pressionamentos e solturas aceitos pelo canal WebRTC.
- Giroscópio pode ser ajustado durante a partida. O teste local mostra amostras do sensor e a resposta do analógico direito calculada pelo app. A prévia suspende o sensor quando perde foco. A sessão retoma a mira após fechar painéis e trocar o modo de toque.
- O HUD de Call of Duty oferece direcional opcional e compatível com o editor. Jogos Cloud Gaming que anunciam toque nativo podem salvar uma preferência por título para abrir com toque e controle físico no jogador 1. A troca durante a partida usa a negociação de toque já existente. A resposta do Genshin, o guia Xbox e as quedas intermitentes seguem pendentes de validação em partidas reais.
- O APK `0.41.10-session-input` (`versionCode 123`) passou 897 testes JVM, lint, traduções e validações gráficas no CI. O APK baixado preserva a assinatura permanente, minSdk 24, launcher Android TV e depuração desabilitada. SHA-256 `e38cae1fbe837fec62bbc4c85e2bb6f00b714877e09200b7683c853e09e7acef`. [Build](https://github.com/Ricardex742/xCloudNative/actions/runs/45061167455) · [APK assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/45061167455/artifacts/14769554367)

## Controles por toque do Guia Xbox

- Em telas de toque, o atalho Botão Xbox do menu da partida mostra o direcional, A e B no canto inferior direito. Cada toque envia um pressionamento curto pelo canal de gamepad. O HUD e os contatos do jogo ficam suspensos enquanto a sobreposição está visível; a saída restaura o modo de toque que a sessão já usava.
- A sobreposição abre pela ação explícita do usuário. Uma transição de foco do título de falso para verdadeiro fecha os controles. O botão × permite fechar os controles quando o serviço não informa a transição.
- O APK de teste `0.41.11-guide-touch` (`versionCode 124`) passou 899 testes JVM, lint, traduções e verificações gráficas. O APK baixado preserva a assinatura permanente, minSdk 24, launcher Android TV e depuração desabilitada. SHA-256 `d4c9e32b49d78fdf8e7d11d2e05108b147845e90a82a6ae6e0535284d4c89fdd`. [Build](https://github.com/Ricardex742/xCloudNative/actions/runs/45065853080) · [APK assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/45065853080/artifacts/14771846616)
- A navegação pelo Guia foi confirmada em sessão real na `0.41.11-guide-touch`. A `0.41.12-guide-style` usa um direcional em cruz no canto inferior esquerdo, A e B circulares no inferior direito e × no superior direito. O indicador de rede abre espaço para o botão × enquanto o Guia está aberto. As áreas interativas continuam com pelo menos 48 dp e os testes exercitam os toques pela hierarquia de views.
- O APK de teste `0.41.12-guide-style` (`versionCode 125`) passou pela suíte completa, lint, validações de tradução e renderização por software. A assinatura permanente, minSdk 24, launcher Android TV e depuração desabilitada foram conferidos após o download. SHA-256 `5b6c7360b6490b79dae5206b94a9bfc42e49561c8c7a0cc18ce6d6eb8804abaa`. [Build](https://github.com/Ricardex742/xCloudNative/actions/runs/45070999540) · [APK assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/45070999540/artifacts/14773123539)

## Preferências, HUD e navegação alfabética

- O HUD divide o jogador 1 com o controle físico, inclusive com co-op local ativo. Trocar de layout arquiva as personalizações por família de HUD e mantém a escolha de usar apenas controle disponível no painel. A validação da troca em partidas reais continua pendente.
- Os painéis durante a partida herdam a resolução/taxa de saída e a preferência de pós-processamento da janela do vídeo. As transições e o escurecimento do sistema ficam desativados nesses painéis. A estabilidade da saída HDMI e a cintilação relatada ainda precisam de teste em aparelho físico.
- Preferências de sessão associadas ao identificador legado são incorporadas ao XUID confirmado, preservando ajustes já existentes nesse XUID e exceções por jogo. A sessão aceita o pedido de idioma `zh-TW`. A Reprodução remota lê o idioma padrão da conta. O idioma exibido pelo jogo, console e Guia continua sob controle do Xbox.
- Todos os jogos, Game Pass e Transmita seu jogo oferecem gêneros da Microsoft Store e um índice à direita para as iniciais visíveis. A seleção de gênero é independente por aba. O arraste navega direto e a confirmação por controle ou acessibilidade abre a lista de letras. Em Transmita seu jogo, vale a inicial do título em inglês quando disponível e o salto encontra a primeira ocorrência na ordem de aquisição.
- A rodada final validou 143 testes dos fluxos alterados, incluindo os sete casos que falharam na rodada inicial de 904 testes. Lint e os 15 pacotes de tradução passaram. A inspeção visual foi feita nas capturas Robolectric de Xbox Series e PS4, sem dispositivo ADB conectado.
- O APK de teste `0.41.13-catalog-browse` (`versionCode 126`) foi compilado a partir de `8ddab86ba657f002f3620c242d77377808bb9ee0`. O CI aprovou 905 testes JVM, lint, traduções e validações gráficas. A conferência do arquivo baixado confirmou a assinatura permanente, API mínima 24, launcher Android TV e depuração desabilitada. SHA-256 `15c01b67b7b105fbc1efa9e3c2e219404701a108209b8918fe44bb7d3c70f596`. [Build](https://github.com/Ricardex742/xCloudNative/actions/runs/45097229294) · [APK assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/45097229294/artifacts/14783859033)

## Faixa de ferramentas sobre o catálogo

- A–Z, contagem e Gêneros ocupam a faixa acima das capas, com as cores do tema. A–Z abre uma grade com as letras disponíveis selecionáveis por toque ou controle. Em Transmita seu jogo, as iniciais continuam seguindo o título em inglês quando disponível e a prioridade de aquisição.
- No Xbox Series, Game Pass e Transmita seu jogo usam o mesmo layout vertical de seis colunas da opção Grade em Todos os jogos. A opção de personalização de Todos os jogos e as demais abas conservam suas geometrias.
- O APK de teste `0.41.14-catalog-toolbar` (`versionCode 127`) foi compilado no commit `3b718e7ca6f722af0fb2303fed1279a68a6e8e1a`. Passaram 905 testes JVM, lint, traduções e verificações gráficas. As capturas Robolectric mostram a faixa no Xbox Series e PS4 e a grade nas duas abas do Xbox Series. A validação em aparelhos físicos segue pendente. O arquivo baixado foi verificado com a assinatura permanente, API mínima 24 e launcher Android TV. SHA-256 `857e6e6eaa45e7185653653fccfa6b07fc2fb8131bb60f30ac90b623c608dc9e`. [Build](https://github.com/Ricardex742/xCloudNative/actions/runs/45100061895) · [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/45100061895/artifacts/14785871009)

## Coleção de toque, sensor e limite da partida

- O filtro Jogar com toque cruza os produtos do catálogo com a galeria SIGL oficial em Todos os jogos, Game Pass e Transmita seu jogo. Pode ser combinado com gênero. A ação de controles oficiais abre o título selecionado no navegador ou a coleção. A sobreposição oficial do Xbox ainda precisa de validação de disponibilidade dos pacotes na sessão nativa.
- A preferência por jogo pode selecionar controle virtual desde o início da sessão, sem anunciar o toque nativo. A opção de toque com controle físico conserva a chave legada. A resposta do Minecraft Dungeons em sessão real permanece pendente.
- O giroscópio oferece o sensor do aparelho ou o do controle compatível exposto pelo Android 12+. O teste local usa a fonte escolhida. O mapeamento da mira inclui o eixo Z e uma pequena zona morta. A seleção de sensor e a resposta do jogo precisam de validação com controle físico.
- O contador da partida segue `ShowGameTimeNotification` do canal de mensagens. Se ainda não houver notificação, usa o limite positivo em segundos ou minutos recebido em `/state`, contando a partir do primeiro quadro. Recarga e sessão guardada preservam o prazo. O aviso de cinco minutos é exibido uma vez quando a partida está em primeiro plano. `CloudHoursExhausted` tem mensagem específica e mantém os detalhes técnicos.
- O painel de configurações e o laboratório de imagem gravam apenas as chaves editadas. A atualização mantém as chaves existentes, inclusive as preferências de reprodução antigas e os padrões guardados para aparelhos de pouca memória.
- O APK de teste `0.41.15-touch-gyro` (`versionCode 128`) foi compilado no commit `15f86bb6b4430b6d811774e9c3c2b902cd4ea7c9`. Passaram 907 testes JVM, lint, traduções e validações gráficas. O arquivo baixado preserva assinatura permanente, API mínima 24, launcher Android TV e depuração desabilitada. SHA-256 `2698bfd0f214dc35399cfdaa02e6d0704aff188d08a3a56820205c12ad0c79e6`. [Build](https://github.com/Ricardex742/xCloudNative/actions/runs/45191024348) · [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/45191024348/artifacts/14825189219)

## Avisos de fim da partida e layouts de toque no player nativo

- O tempo da partida permanece interno. Os avisos de cinco e um minuto são transitórios, aparecem uma vez por limite e somem ao sair do vídeo, perder foco ou guardar a sessão. O saldo da conta continua no perfil.
- O Cloud Gaming carrega os pacotes de toque anunciados em `supportedTabs` ou na configuração de entrada do título. A sessão consulta a referência no serviço Xbox e baixa o pacote sem enviar o token para o endereço de distribuição. Arquivos, tamanhos e imagens são limitados em memória.
- O HUD nativo usa os botões, combinações, direcionais, analógicos e imagens do pacote compatíveis com a implementação. O cliente aplica notificações de troca, ocultação e estado do layout. A entrada de toque nativo, quando negociada, continua no vídeo fora dos controles. O modo Automático pode usar o pacote e o menu do HUD oferece seleção manual. O teste de entrega de pacotes por título no serviço real permanece pendente.
- O APK de teste `0.41.16-native-touch-layouts` (`versionCode 129`) foi compilado no commit `fd7eb186304f5177de0519a97e1495a274eb0f8a`. Passaram 910 testes JVM, lint, traduções e verificações gráficas. O arquivo baixado preserva a assinatura permanente, API mínima 24, launcher Android TV e depuração desativada. SHA-256 `7cb6dcce300e569f5926efea713790b7adbc65779114c380fe179428aa81ce8f`. [Build](https://github.com/Ricardex742/xCloudNative/actions/runs/45198965653) · [Artefato assinado](https://github.com/Ricardex742/xCloudNative/actions/runs/45198965653/artifacts/14831602200)

### Seleção manual dos controles oficiais

- Em `0.41.17-touch-layout-retry`, escolher o layout pelo HUD usa o pacote já carregado ou aciona uma nova tentativa na sessão atual. A seleção manual pode mostrar o layout inicial mesmo após uma notificação anterior de ocultação. Novas notificações do Xbox continuam atualizando a camada de toque.
- O carregamento consulta a configuração atual do título quando os IDs do catálogo falham, prioriza as versões mais recentes e aceita redirecionamentos HTTPS de distribuição sem encaminhar a credencial. O leitor processa os arquivos do idioma selecionado, aceita pastas de idioma aninhadas e mantém limites por arquivo.
- `TOUCH_LAYOUT/` no diagnóstico ao vivo e pós-partida informa a etapa de falha e, quando disponível, o status HTTP. Os testes simulam o retry, o download e a seleção manual no HUD nativo. A entrega de pacotes em partidas reais ainda precisa ser confirmada.

### Aparência dos controles oficiais no player nativo

- `OfficialTouchBundle` interpreta imagens, cores, opacidade e estilos de repouso e interação dos controles compatíveis. `OfficialTouchSkin` aplica essas propriedades no Canvas. A escala, a opacidade, a ocultação automática e a edição do HUD local não afetam a camada do pacote. A troca e a ocultação solicitadas pelo Xbox continuam vinculadas à sessão.
- `AssassinsCreedTouchLayout` concentra o posicionamento para Origins e Odyssey. Usa a ação ou tipo dos controles já oferecidos pelo pacote e conserva os comandos, ícones e estados do jogo. As medidas são um ajuste do player nativo para paisagem. A comparação em aparelho continua pendente.
- Em Origins, o ajuste prioriza o analógico do centro da roda quando há mais de um, amplia movimento e direcional e inclui LB e RT de reserva durante o combate quando o pacote não oferece essas ações separadamente. Ações associadas ao próprio analógico respeitam o limiar declarado pelo pacote.
- O editor oficial salva só posição e escala por jogo e layout, em chaves separadas do HUD local. Durante a edição, o Xbox pode trocar o layout da sessão sem mudar o alvo do rascunho. Ao sair do editor, a sobreposição volta ao layout atual. Em Origins, LB e os comandos da direita usam imagens maiores dentro dos aros.
- O leitor aceita eixos em listas e controles nas áreas central e inferior e mantém as regras de visibilidade e habilitação do pacote. A camada de aventura de Minecraft Dungeons usa um analógico local de movimento quando o pacote não apresenta um. A validação com os pacotes reais fica para o teste em aparelho.

### Entrada do HUD, aviso da sessão e detalhes do catálogo

- O modo Automático de Minecraft Dungeons prioriza a entrada de gamepad com a sobreposição oficial. O analógico de reserva aparece no centro esquerdo da tela. Em sessões de toque nativo com sobreposição oficial, o envio preserva pressões e solturas pendentes até a neutralização do gamepad auxiliar.
- Gears 5 aplica referências de posição aos controles reconhecidos do pacote, com ações e imagens fornecidas pelo Xbox. O leitor usa a escala declarada, analógicos relativos, câmera por arrasto em touchpads que comandam o analógico direito e botões de alternância. A validação em partidas reais e de controles que dependem de camadas e gestos mais complexos continua pendente.
- O aviso de início do Cloud Gaming surge após o primeiro quadro e dura cinco segundos. Mostra o tempo até o fim da sessão a partir do prazo confirmado pelo Xbox, em minutos ou horas e minutos. Sinaliza a ausência desse dado.
- Segurar uma capa ou confirmar a seleção por controle abre a tela de detalhes. Um toque curto inicia o jogo. A tela organiza capa, descrição e até quatro imagens em colunas quando há espaço horizontal, com ações fixas no rodapé. A consulta da Microsoft Store ocorre por produto ao abrir a tela. O fluxo de lançamento preserva o título autenticado ou verifica a edição da galeria. “Atalho na tela inicial” reutiliza o fluxo do título associado.
- A tela de detalhes desfoca uma cópia reduzida da arte em segundo plano e aplica uma camada escura para manter o contraste. Usa a imagem panorâmica, capturas ou capa do jogo conforme a disponibilidade. O filtro roda uma vez por imagem e funciona desde a API 24.
- O cartão mostra ano da edição e classificação brasileira com descritores públicos, além do estado do catálogo de nuvem. O rodapé usa ações do próprio app para opções por jogo, loja oficial, favoritos e criação de atalho pela seta. Em telas estreitas o rodapé pode ser rolado horizontalmente.
- As conquistas são consultadas sob demanda com autorização Xbox Live da conta atual e o identificador Xbox do título. O painel fecha a consulta ao sair, soma apenas listas completas e tenta a rota legada de Xbox 360 quando a consulta moderna retorna vazia ou inconclusiva por ausência do título. O fluxo de rede com uma conta real continua pendente de validação.
- Em Aparência e temas, a lista de layouts agrupa as paletas do xCLOUD e as variantes do PS4 e PS5 em **Cores**. A preferência de renderização continua usando os valores existentes de `UiTheme`. A seleção salva por layout permite voltar à paleta anterior, inclusive ao trocar pela primeira vez a partir de uma instalação com preferência antiga.

### Toque, DNS e presença no cartão

- Minecraft Dungeons segue o modo de toque negociado. Aplicar o pacote preserva o toque nativo. O canal de entrada envia comandos do HUD como gamepad e contatos diretos no vídeo como toque. No mesmo layout, mudanças apenas visuais conservam pressões e solturas pendentes. Toques rápidos nos botões do HUD de Dungeons duram até completar 64 ms. A validação em sessão Xbox real permanece pendente.
- Em modo híbrido, o HUD oficial sozinho no slot 0 envia as máscaras virtual e física como uint32 little-endian. Cada botão, gatilho e eixo ativo tem seu bit virtual. Minecraft Dungeons conserva a última sobreposição oficial quando selecionada manualmente e o Xbox pede para ocultá-la. O modo automático segue esse pedido. O diagnóstico registra notificações de toque e o último comando do HUD. A resposta no jogo real ainda precisa ser confirmada.
- O aviso de início mede título e tempo com a largura útil e a margem do recorte da tela. O cartão de jogo usa cantos menores e um bloco de amigos com fotos redondas. A presença é filtrada por título Xbox e estado online. A ficha de conquistas lista os itens bloqueados e desbloqueados sem carregar imagens. A linha selecionada abre descrição, progresso e raridade conforme a resposta do serviço.
- A opção de DNS tem padrão por conta e ajuste por jogo. Em modo automático, dois testes por resolvedor comparam sistema, Cloudflare, Google, Quad9, AdGuard e OpenDNS para o host da sinalização de nuvem. O diagnóstico `DNS/` registra o resolvedor da sessão. VPN, proxy e DNS privado mantêm o resolvedor do sistema ao iniciar. Áudio e vídeo seguem a rota ICE do WebRTC.

### Cartão por tema e uso de memória

- O aviso de início mede a frase inteira com a fonte preferida e ajusta o tamanho ao espaço real. O cartão de detalhes compartilha o layout entre temas e aplica a paleta ativa, incluindo foco, botões e progresso. Series X|S oferece cinco variantes de cor no seletor existente e restaura a última cor ao voltar ao tema.
- Janelas móveis acima da área de 2560 × 1440 usam o escalonamento da Surface do WebRTC no caminho padrão e limitam buffers dos escaladores opcionais a 1920 × 1080 ou 1080 × 1920. O limite preserva a proporção do buffer e as coordenadas de toque da janela. Ao entrar na partida, o cache de arte é reduzido e o cache em disco permanece disponível.
- **Memória e última saída do app** lê PSS e memória disponível sob demanda. Usa a API de histórico do processo no Android 11 ou superior. Os números do processo e as dimensões dos buffers são evidências distintas. A causa de reinícios de aparelhos permanece pendente de diagnóstico do sistema.

### Identificação de conta gratuita e tempo da sessão

- O perfil usa `passesByPuid` da mesma resposta autenticada de Content Access que informa o saldo. Uma resposta válida de uma única conta sem Game Pass ativo pode apresentar `FREE` mesmo quando os títulos não trazem os campos de acesso. O catálogo autenticado também identifica planos e acesso gratuito por `userPrograms` e assinaturas por título. Um plano identificado tem prioridade sobre o selo gratuito da outra fonte. A validação com a conta Xbox real permanece pendente.
- Uma entrada autenticada de saldo para uma única conta usa zero como valor padrão protobuf para campos de horas sem anúncios omitidos. A ausência dessa entrada ou uma falha na consulta preserva o estado indisponível. Horas com anúncios e tempo de sessão são informações distintas do saldo sem anúncios.
- O handshake de nuvem anuncia `ShowGameTimeNotification` (`42`). O aviso inicial acrescenta o prazo do Xbox assim que chegar e pode reaparecer uma vez nos primeiros 30 segundos. `SESSION_TIME/` indica a fonte no relatório. A recepção do prazo em uma sessão real continua pendente de validação.

### Contas salvas, compra de horas e menu da partida

- O seletor em **Conta e amigos** preserva os logins no armazenamento privado de autenticação, excluído do backup. A conta ativa mantém o formato anterior. Adicionar e alternar aguardam a rotação de credenciais, invalidam tarefas da conta anterior e carregam o catálogo e a biblioteca da conta escolhida. O Xbox identifica duplicatas de login após a autenticação.
- **Comprar horas de jogo** abre o checkout público do Xbox no navegador e oferece QR Code na TV. O login, as ofertas por região e a transação são tratados pela Microsoft. Nenhum token da conta do app acompanha o link.
- O menu padrão e a barra de toque consultam o prazo de `SessionTimeLimit` enquanto estão abertos. O prazo da sessão e o saldo da conta continuam independentes. O teste local simula a chegada tardia do prazo e a troca entre duas contas salvas. A troca com contas reais e o checkout autenticado permanecem pendentes de validação.

### Estabilidade do ciclo da sessão

- O encerramento aguarda a cadeia renderizador, transporte e alocação. A retirada da View continua na thread da interface. A liberação bloqueante de EGL e a finalização do WebRTC são aguardadas em I/O. O transporte aguarda o término do pump antes de descartar o canal. `RENDERER_RELEASE`, `RTC_RELEASE` e `PEER_RELEASE` registram etapas e duração no log Android.
- O pump mantém até oito transições digitais recentes por slot durante congestionamento e expira as que passam de 100 ms. O estado atual e as solturas permanecem representados. `INPUT_EDGE`, `TOUCH_HUD_VIEW` e `TOUCH_HUD_CHANNEL` distinguem comandos vencidos, hits do HUD e transições aceitas no canal.
- A proteção de buffers aplica limite local também em saída acima de QHD em TV sob a política de memória restrita ou com pressão informada no início da partida. `VIDEO_DEVICE` e `VIDEO_MEMORY` ajudam a cruzar firmware, SDK, RAM e buffer com o decoder recebido.
- O arquivo de contas salvas continua no formato compatível com as versões anteriores. Registros inválidos são ignorados de forma isolada e a conta ativa válida permanece utilizável.
- Validar em aparelhos reais atualização com dados preservados, instalação limpa, Dungeons e uma sessão além da marca de 30 minutos. Registrar SDK, RAM, decoder efetivo e firmware. A causa das desconexões e a estabilidade física continuam pendentes.

### Custo em aparelhos com pouca memória

- O cache de arte do catálogo usa um índice das informações públicas por título e produto. A leitura completa é reutilizada até mudar a revisão do arquivo. A gravação substitui o cache por um arquivo completo no mesmo diretório. O catálogo autenticado continua responsável pelo acesso a jogos.
- Em aparelhos classificados com memória restrita, o Android 9 ou superior recebe o tamanho final da imagem na chamada a ImageDecoder. O caminho BitmapFactory continua disponível para versões anteriores e falhas de formato. Ao trocar a arte de uma célula, a entrega pendente da imagem antiga é removida.
- Ao sair do primeiro plano, o catálogo pausa novas requisições em segundo plano e libera a arte das Views pausadas. O retorno reapresenta os metadados que chegaram durante a pausa. A tela é mantida acesa automaticamente enquanto há partida em primeiro plano. O WebRTC reduz a frequência de coleta de estatísticas com a partida salva ou com o app em segundo plano.
- As escritas do HUD local e oficial e das opções de preset passam por I/O serializado. O editor aguarda confirmação e mantém o rascunho se uma gravação falhar. Um retorno tardio pertence à edição que iniciou a escrita.
- O pump publica métricas periódicas a cada 100 ms, com publicação antecipada para mudanças selecionadas e uma amostra final. Os pacotes continuam usando a cadência e o despertar por comando já estabelecidos. A análise de pico de áudio calcula o maior valor inteiro e normaliza uma vez por janela. O callback PCM da versão WebRTC fixada ainda copia amostras antes de chamar o app. A API disponível só registra esse callback ao construir o módulo.
- A proteção de vídeo aplica um orçamento de efeitos opcionais quando o Android informa pressão de memória no início da preparação da sessão. As preferências e os parâmetros enviados ao serviço continuam separados do orçamento local. A comparação de PSS, fluidez, retomada e áudio em aparelhos reais continua pendente.

### Recuperação e medição do pipeline

- Em `0.41.32-stream-pipeline`, a recuperação local reinicializa o decoder no quadro-chave. Quando a configuração de baixa latência falha, a rota padrão reaproveita o codec e perfil negociados e só é aberta após a liberação do decoder anterior. O fallback JNI só recebe um decoder por software compatível com o mesmo formato, quando oferecido. A recuperação de imagem mantém o transporte e a alocação Xbox. O relatório registra o caminho em `DECODER_RECOVERY/`.
- Cada transporte mantém uma coleta WebRTC em andamento. Após timeout, a coleta aguarda o callback nativo e descarta o resultado atrasado. Fechar o transporte cancela a espera. `RTC_STATS/` registra resultados, timeout e tempo de callback. `AUDIO_RTP_DELTA/` registra mascaramento de perdas e descartes de áudio quando o WebRTC fornece os contadores.
- `VIDEO_EGL_CADENCE/` contabiliza envios ao EGL e intervalos entre eles, com o mesmo ponto de observação nos modos imediato e suave. O modo suave informa espera planejada, observada e atraso do agendador. O tempo de decode e o do agendador de entrada permanecem em campos próprios. A latência até a tela e o sincronismo A/V continuam pendentes de medição externa em aparelhos reais.
- O DNS adaptativo guarda por cinco minutos o resolvedor para a combinação de rede Android e host da sinalização. Uma nova conexão resolve o hostname novamente. Mudanças de rede invalidam a escolha em uso. VPN, proxy, DNS privado e rede ainda não validada usam o DNS do sistema. `DNS/` mostra o custo da consulta em um campo separado do RTT ICE. `DNS_SELECTION/` preserva a decisão mais recente depois de outras consultas na mesma sessão.
- A validação local aprovou JVM, lint, traduções e as verificações gráficas de referência do FSR em Mesa. As falhas de decoder, a troca de rede e os atrasos do coletor foram simulados. A comparação física com latência até a tela, sincronismo A/V e firmware continua pendente.

### Compatibilidade de aparelho, janela e navegação

- Em `0.41.33-device-compatibility`, o perfil de plataforma combina modo de interface e recursos Android. Entrada, política de saída e componentes de catálogo usam a mesma detecção de TV. O toque direto ainda exige a configuração e a fonte de entrada correspondentes. O canal de favoritos exige API 26 e Leanback.
- O ajuste automático da tela pode ser desligado em Vídeo e conexão. Os pedidos de modo usam o display da janela e dependem de uma lista de modos consistente. Em outra saída, a preferência devolve a decisão ao sistema. A partida salva, segundo plano, tela dividida e encerramento restauram a preferência anterior na saída original e liberam a indicação de cadência da Surface. `DISPLAY_MODE/` registra o pedido e se o modo físico corresponde ao anunciado.
- A geometria de toque segue o mesmo arredondamento da Surface. Ao mudar a geometria da janela ou a proporção da fonte, os contatos anteriores são encerrados. A composição do HUD e a identificação dos controles físicos continuam vinculadas aos slots existentes.
- O catálogo preserva a capa selecionada por ID ao redimensionar a janela e ao sair dos detalhes. A ação acessível de detalhes usa o mesmo caminho do toque prolongado. O toque curto preserva a ação de iniciar o jogo.
- O canal de favoritos usa a rota Leanback quando disponível e grava o logo com a API de bitmap a partir do drawable existente. Os selos de catálogo usam o renderizador vetorial público do Android. As classes baseadas na interface Material do framework mantêm os widgets nativos.
- A validação local passou em JVM, traduções, verificações gráficas do FSR e lint ampliado. O teste de temas confirmou igualdade de pixels após restaurar o tema inicial. Mudanças HDMI, TalkBack e launchers físicos seguem pendentes.

### Propriedade da sessão, catálogo e transações de conta

- Em `0.41.34-session-ownership`, a tentativa do `PlaybackCoordinator` é dona do transporte WebRTC e da alocação do Xbox. Registra o transporte no dispatcher que o cria, antes de voltar à interface. A publicação ao app só ocorre depois do retorno. A recarga retira o transporte e mantém a alocação. A tentativa finaliza Surface, transporte e alocação em ordem mesmo quando uma etapa falha. A Activity libera o contexto EGL compartilhado depois que as tentativas terminam.
- `DashboardCatalogWork` cancela o carregamento anterior e entrega metadados, progresso e galerias apenas ao carregamento atual. O catálogo autenticado e a biblioteca da conta continuam sendo as fontes de lançamento, favoritos e histórico. Dialogs, temas e atalhos usam os mesmos pontos de entrada.
- A inclusão e a troca de contas atualizam a anterior e a ativa juntas, usando o mesmo formato do arquivo de preferências legado. Uma falha atrasada de autenticação só pode remover o login da geração que a iniciou. A atualização de nome e identidade preserva um arquivo de contas ilegível quando puder atualizar apenas a parte ativa. O valor textual do token oculta credenciais.
- O arquivo de autenticação permanece no armazenamento privado excluído de backup e da transferência Android. A adoção do Android Keystore depende da validação em aparelho da criação e recuperação da chave, do cancelamento da migração e da volta à versão anterior com os logins preservados.
- A verificação local passou em JVM, lint e traduções. Os cenários simulados incluíram cancelamento após a construção do peer, falha na ordem de liberação, recarga na mesma alocação, resposta obsoleta de catálogo e rejeição atrasada de credencial. Partidas e contas reais seguem pendentes.

## Fase 6 — Diagnóstico e validação Android

- A tentativa de reprodução registra o primeiro incidente de cada tipo por transporte, com milissegundos desde o início, causa normalizada e último marco observado. O relatório também inclui as etapas de liberação do transporte retirado e da sessão. O histórico mantém o início e as três recargas mais recentes. A exportação usa os campos tipados existentes de `SessionFailure`.
- `scripts/validate_android.sh` executa suite JVM, lint ampliado e verificação SHA-256 dos artefatos de runtime listados em `gradle/runtime-artifacts.sha256`. O baseline de lint admite o volume revisado por regra e arquivo. Mudanças de versão de lint ou de artefato verificado requerem revisar o baseline ou hash correspondente.
- Pull requests executam a validação sem chave de assinatura. O workflow de APK assinado verifica a identidade, o manifesto e as ABIs, e executa instalação, abertura e retomada no Android 10 e 14 virtualizados. `scripts/smoke_device.md` cobre o uso via ADB com celular ou TV e descreve a coleta pareada. As rodadas físicas e a medição de streaming continuam pendentes.

## Fase 7 — Ensaios de desempenho

- Vídeo e conexão › Avançado › Testes de desempenho guarda quatro opções independentes, desativadas para instalações existentes e aplicadas na próxima partida. O modo Compatibilidade ignora as opções.
- Em Adaptativo, o ensaio de H.264 restringe a tentativa a outro componente de hardware com capacidade declarada para o perfil negociado e a dimensão em uso. A preferência só é registrada após um quadro, por formato e tamanho, com até oito entradas durante a reprodução e suas recargas. Não altera a seleção de formato SDP.
- A geração de quadros pode usar um fence de produção antes de entregar a textura compartilhada. Espera limitada a 1 ms e descarte do sintético no timeout. Em falha de sincronização, restaura `glFinish`. O readback da assinatura e a barreira antes de reutilizar um buffer retornado são preservados.
- O ensaio de cadência envia estados de gamepad neutros a 60 Hz sem toque e retoma o agendamento regular para atividade. `INPUT_CADENCE` mostra consultas ao canal e relatórios neutros. O ensaio de áudio evita registrar o callback PCM se a partida começar com ganho até 100%. Um ganho maior salvo durante essa partida passa a valer quando o próximo módulo de áudio for criado.
- `xcloud.trialReleaseAbi` e `xcloud.trialReleaseShrink` permitem comparar ABI única e R8 em builds release de teste. A publicação normal usa o APK universal, sem R8. A assinatura permanece obrigatória e `--expected-abi` é uma verificação explícita da variante. Instalação, atualização, atalhos, desempenho e firmware precisam de rodada física.

## Fase 8 — Validação de candidata

- O build release congela revisão, SHA-256 e identidade do APK, matriz, inventário de dependências, baseline do lint e recibos de JVM e gráficos em `app-release.apk.build-evidence.json`. A conclusão dos smokes Android 10 e 14 gera `release-validation.json` somente quando ambos usaram esse APK. A publicação revalida recibo, assinatura, SHA, revisão, versão e tag.
- `scripts/release_matrix.json` define cobertura física por plataforma. O comparador prepara um plano e resultados privados e exige pares com as mesmas condições, configuração de vídeo comparável, margens de P95 congeladas, verificações funcionais, sessão longa e ciclos de recuperação. O SHA só é retornado quando a matriz passa. O operador usa esse SHA na publicação. Dados do aparelho, conta e sessão permanecem fora dos artefatos públicos.
- Os procedimentos estão em `scripts/smoke_device.md`. A validação local e os testes sintéticos da esteira são separados do smoke de APK e da execução física. A candidata de app permanece `0.41.36-performance-trials`. A geração do binário assinado e a execução em aparelhos são etapas pendentes neste workspace.
- O baseline de lint usa avisos estáveis por regra e arquivo. Sugestões de atualização cujo resultado depende da consulta ao catálogo Maven são mantidas no relatório bruto. O comparador exclui somente esse tipo de aviso e continua bloqueando erros e as demais ocorrências de dependência.

## FSR e navegação das configurações

- As configurações nativas usam abas fixas para Geral, Vídeo, Rede, Controles e Áudio. O conteúdo muda dentro da mesma janela ao trocar de aba. Os ajustes comuns têm descrições curtas junto aos valores, e os seletores avançados continuam acessíveis pelas categorias correspondentes. As linhas clicáveis aceitam foco de controle e permitem tocar e rolar sem forçar o foco durante o gesto. Seletores de rede e estatísticas usam a mesma lista compacta.
- FSR é o único método alternativo de ampliação. O tratamento de detalhes funciona antes do EASU na resolução recebida. A etapa RCAS adaptativa usa contraste local na luminância para limitar ruído e halos, com as amostras que o RCAS já consulta. O perfil de FSR usa estabilidade temporal, respeitando os limites de memória, desempenho e geração de quadros. Se o tratamento de detalhes for recusado, o FSR ainda pode usar o caminho de ampliação e nitidez.
- Preferências de SGSR e Reconstrução xCloud migradas para FSR preservam saída, resolução, controles e parâmetros salvos. A comparação da antiga reconstrução migra para a opção comum quando era a comparação ativa. A validação física do efeito visual e da rolagem continua pendente.
- A execução `45868084601` compilou e assinou o APK `0.41.37-fsr-settings` (código 150) da revisão `e5391cf`. O APK foi conferido pelo SHA-256 `4dad6be0ba7a0afc7fa3718b6f00d39f916d18fbe42c4147436d0b906c03241e`. Passaram 977 testes JVM, seis suítes gráficas e o lint com zero erros. O Activity Manager dos emuladores Android 10 e 14 não confirmou a abertura, então a validação de abertura e retomada ficou pendente.
- O diagnóstico `45873892013` identificou que `am start -W` com a intenção implícita por pacote não resolvia a Activity após `force-stop`. O script confirma o launcher declarado e usa o componente explícito para abrir e retomar. Ele inclui a resposta do Android e a repetição quando falha. A execução `45874275239` confirmou três aberturas e retomadas no mesmo processo em cada API (29 e 34) com o mesmo SHA-256 do APK anterior.

## Ícones de configurações e giroscópio

- As cinco abas fixas usam ícones vetoriais com texto, foco e seleção. As linhas de Geral e os ajustes principais de imagem usam os mesmos desenhos nativos do tema. Controles mostra o modo do giroscópio junto do acesso ao teste.
- A mira combina movimento com o analógico direito do primeiro jogador. A fonte Aparelho usa o sensor calibrado e aceita o não calibrado com remoção do viés informado. A fonte Controle físico usa o gerenciador de sensores do Android 12+, inclusive um dispositivo separado de movimento quando pode ser associado de forma única ao controle. A versão inicial mantém opt-in para toque nativo sem controle físico. Com controle físico no primeiro jogador, a mira pode usar o mesmo slot sem esse opt-in.
- O teste reaplica a seleção ao conectar ou remover controles. O diagnóstico registra amostras recebidas, amostras com movimento e o identificador do sensor de controle quando disponível. A nova escala, a suavização e a compensação de 8% do analógico só afetam o sinal do sensor. A resposta no jogo com hardware real continua pendente. [XStreaming](https://github.com/Geocld/XStreaming/tree/7fc4e41) · [NXCloud](https://github.com/denpingus/NXCloud/blob/de7268e/stream/source/pad.cpp) · [InputDevice](https://developer.android.com/reference/android/view/InputDevice#getSensorManager())
- O APK assinado `0.41.38-gyro-settings` (código 151) foi gerado da revisão `d956c31` na execução `45880018440`. O SHA-256 conferido é `d3ec1bd8d21ffc836f018e31de4d4ea7324b6a22a8d22df3976fae676554a3ae`. Passaram 979 testes JVM, seis suítes gráficas, o lint sem erros e a abertura e retomada nos emuladores Android 10 e 14.

## Ícones nas subseções e nas capas

- As seções, subseções e seletores das configurações nativas usam os ícones de `ConsoleDialogs`. Os controles mantêm o texto, os estados de foco e a rolagem. Ajustes avançados, aparência, temas e opções das partidas compartilham esse padrão.
- As capas mostram o selo de toque quando o título anuncia toque nativo ou um layout oficial, ou quando o produto consta na coleção oficial Jogar com toque. O selo acompanha a atualização assíncrona do catálogo e é anunciado na descrição de acessibilidade da capa. A posição varia com a disposição da capa para deixar visíveis as outras marcas.
- A revisão final passou em 101 testes JVM/Robolectric de catálogo, foco, rolagem e navegação das configurações. O lint ampliado ficou sem erros e dentro do baseline, e as 15 opções de idioma passaram na verificação. A validação em aparelho real segue pendente.

## Fluidez do FSR

- O perfil AMD FSR usa a escala padrão com intensidade geral de 0%. A decisão é aplicada na preparação, na retomada e nas mudanças ao vivo, preservando o perfil e os ajustes de cor salvos. Ele volta a processar ao subir a intensidade.
- Na configuração avançada, o FSR pode ampliar com nitidez zerada. Com cores padrão e sem dithering, EASU desenha diretamente no destino. A textura intermediária e a passagem de acabamento só são necessárias quando há acabamento ativo. O modo Automático limita a saída do escalador a 1920×1080, respeitando a proporção. A resolução solicitada ao Xbox é independente da saída local.
- Durante uma queda sustentada na cadência, a proteção suspende primeiro o histórico temporal quando ele está em uso, antes de reduzir os níveis de restauração do FSR. As reduções valem somente para a sessão.
- Passaram 87 testes JVM/Robolectric relacionados e seis suítes gráficas em Mesa, com 344 verificações de FSR. O lint ampliado ficou sem erros e dentro do baseline. As 15 opções de idioma passaram na verificação. A cadência em aparelhos reais segue pendente.
- O perfil comum da candidata `0.41.39-fsr-fluency` volta a usar apenas ampliação e nitidez adaptativa. A intensidade controla a nitidez e a restauração dos detalhes e o filtro temporal podem ser ativados em Avançado. A migração preserva a intensidade dos perfis anteriores reconhecidos e zera o valor automático de detalhes que antes era preenchido por padrão quando não havia filtros adicionais. Os ajustes feitos depois da migração prevalecem.
