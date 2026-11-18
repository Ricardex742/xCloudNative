# Contexto técnico para continuidade no Codex

Leia este arquivo junto com `AGENTS.md`. O usuário pediu explicitamente um resumo no repositório para continuar o trabalho em outro ambiente. Aqui estão decisões técnicas e pendências; gravações, conversas literais, dados de conta e diagnósticos pessoais completos continuam privados.

## Guia adicional de temas e compilação

Leia também `CODEX_THEME_BUILD_CONTEXT.md`. Ele documenta a arquitetura nativa dos Xbox, os limites dos protótipos PS4/PS5, a integração HTML posterior, a restauração da base e o processo de build/assinatura. O adiamento abaixo descreve o escopo da entrega original de navegação; consulte o pedido atual do usuário antes de iniciar outro trabalho de temas.

## Objetivo atual e limites

Manter a navegação simplificada e os atalhos nativos da partida. O usuário retomou PS4/PS5 e pediu seguir `CODEX_THEME_BUILD_CONTEXT.md`, usando as simulações apenas como referência visual e a arquitetura nativa dos Xbox. O pedido mais recente fornece `html-ps4-home.jpg` e `html-ps5-home.jpg` e pede aproximar a home desses prints. Eles passam a orientar a composição da home; fotos reais continuam como apoio dos painéis. Priorizar a composição dos consoles em home, fila, transições e painéis internos. Não recuperar a integração HTML/WebView descartada.

O usuário prefere execução autônoma, comunicação em português e conclusões baseadas em evidência. Não pedir novamente autorização para ações já incluídas no pedido. Não dizer que um problema foi resolvido em aparelho real apenas porque testes locais passaram.

## Estado do projeto

- Cliente Android nativo de Xbox Cloud Gaming, Kotlin, Activity/Views nativas, WebRTC M137. Android mínimo API 24; compatibilidade Android 10 e TV é importante.
- Repositório: `Ricardex742/xCloudNative`.
- Última release pública confirmada no histórico desta conversa: **v0.38.0**. README deve listar recursos atuais, sem comparação obrigatória com 0.36.0 e sem histórico de testes pessoais.
- Esta implementação está na branch `feat/quick-settings-navigation`, baseada em `fix/primary-controller-startup-order` (`2482b45e35c5c89acf70d274e0544c1b8a1ffb05`). Inclui, portanto, a correção experimental de ordem dos controles descrita abaixo.
- Versão no código desta branch: **0.40.6-playback-diagnostics**, versionCode **101**, usando a identidade de atualização existente. Conferir o build efetivamente aprovado antes da entrega.
- Não presumir que a branch foi publicada ou que há release nova: conferir `git status`, remotos e releases no ambiente atual.

## Implementação da navegação

Arquivos principais:

- `ui/dashboard/GameMenuPanel.kt`: painel da partida com atalhos nativos, ícones e nomes. HUD e estatísticas alternam diretamente; áudio, vídeo, amigos e configurações têm acesso próprio. Toque nativo e seleção para a próxima sessão são ações distintas. Encerrar exige confirmação; fechar o painel não encerra a sessão.
- `ui/dashboard/PlaybackSettingsPanel.kt`: seis categorias: Vídeo e conexão; Controles e toque; Áudio e microfone; Aparência e temas; Conta e amigos; Ajuda e diagnóstico. A página de vídeo usa abas para imagem/tela, rede/sessão e avançado, evitando duas árvores duplicadas.
- `ui/dashboard/ConsoleDialogs.kt`: X no cabeçalho fecha diretamente, sem chamar callbacks de Voltar. Voltar/B retorna ao pai. Restaura foco do item ao retornar a menus. Segurar X do controle limpa somente o campo de pesquisa, sem enviar a busca.
- `ui/GameTouchView.kt`: acesso ao menu é um botão nativo com nome acessível e alvo de 48×48 dp, presente também com toque nativo ou HUD oculto. O desenho volta ao círculo pequeno proporcional à altura da tela usado antes da navegação nova; a área transparente de toque é maior que o desenho. Preservar solturas de contatos/botões ao abrir menus e durante edição.
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
