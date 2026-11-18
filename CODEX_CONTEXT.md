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
- Versão no código desta branch: **0.40.2-console-layout**, versionCode **97**, superior ao 96 já distribuído, usando a identidade de atualização existente. Conferir o build efetivamente aprovado antes da entrega.
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
- `ConsoleHitTargets` amplia a região de toque de controles compactos sem aumentar seu desenho; a escolha nas margens sobrepostas usa o controle mais próximo. A delegação é retirada ao voltar a outros temas. Foco, cliques e acessibilidade continuam nas Views reais.
- A fila PlayStation usa o mesmo canvas de referência: arte, estimativa/etapas reais, confirmação, atualização, cancelar e nota. O bloco opaco foi removido; as etapas usam linhas discretas. Cancelar mantém sua área de toque, com desenho menor. Isso não resolve a pendência independente de fila após trocar controles.
- Ajustes, seletores, amigos e menus profundos passam pelo mesmo `ConsoleDialog`/`ConsolePanelSkin`: azul do protótipo PS4, superfícies escuras sóbrias PS5, texto e linhas proporcionais. A lista e o guia PS4 são compactos, com regiões ampliadas de toque; seis categorias devem caber na área de celular em português. Traduções maiores continuam podendo rolar, com indicador visível. X, B/Voltar, ações e restauração de foco preservam as semânticas existentes.
- `ui/ImmersiveWindow.kt` aplica modo imersivo na Activity e em todas as janelas próprias, incluindo login: antes/depois de mostrar e quando recuperam foco. API 30+ usa também `WindowInsetsController`; API 24–29 mantém fullscreen/hide-navigation/immersive-sticky. Abrir um painel não deve revelar as barras do Android. Gestos de borda continuam sob controle do sistema, com barras transitórias.
- `ConsoleFidelityReviewTest` acrescenta comparação de todos os temas em paisagem, usando arte de demonstração dos protótipos somente nos recursos de teste. Gera home, ajustes, níveis internos e fila/menus PlayStation; verifica seis categorias visíveis, composição das ações, tamanho do círculo e toque no menu. Inclui testes de imersão em API 28/30/34. `PlayStationPhoneReviewTest` inclui também uma área imersiva 1280×600 com recorte lateral simulado. A inspeção de imagens continua obrigatória além das asserções.
- Não foram acrescentados bibliotecas de UI, WebView de tema, decoder, cache ou timers decorativos. Limites de arte e pausa durante gameplay/ocultação continuam compartilhados. Não há dispositivo ADB ou KVM neste ambiente; validação física e medições de desempenho permanecem pendentes.
