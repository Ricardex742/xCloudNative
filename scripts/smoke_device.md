# Smoke em Android e Android TV

Use o APK assinado da branch de teste em um celular e em uma TV, incluindo Android 10 quando disponível. Execute com o catálogo visível, pois a atualização do pacote pode interromper a partida atual.

```sh
adb devices -l
scripts/smoke_device.sh caminho/app-release.apk SERIAL phone --repeats 5 --report phone.json
scripts/smoke_device.sh caminho/app-release.apk SERIAL_DA_TV tv --repeats 5 --report tv.json
```

O script verifica a assinatura, o manifesto e as arquiteturas do APK. Instala como atualização, resolve o launcher e observa a Activity depois de abrir o app a frio, ir ao launcher do sistema e retomar no mesmo processo. Use uma instalação sem conta para medir a abertura com a tela de login nas mesmas condições do CI. Para as rodadas de catálogo e partida, use uma conta e títulos de teste.

1. No catálogo em paisagem, use toque ou D-pad para chegar a Todos os jogos. Selecione um título, abra os ajustes, feche com X ou Voltar e confirme que a seleção voltou ao mesmo jogo.
2. Abra um título pela capa e por um atalho de launcher já instalado. Confira idioma e entrada planejados, controle físico desde o primeiro quadro e saída da fila com Voltar. Repita com outro modo de controle. Abra o último diagnóstico após a desistência e confira a origem e os marcos alcançados.
3. Durante uma partida, abra e feche os atalhos do jogo. Volte ao launcher do sistema e retome a Activity. Confirme que a imagem e o controle retornaram. Repita o teste com Recarregar transmissão e confira os marcos em outra geração do transporte, dentro da mesma tentativa.
4. Encerre a partida, volte ao catálogo e confirme o foco. Em Ajuda e diagnóstico, abra o último relatório e use Exportar diagnóstico para salvar o JSON numa pasta de teste. Confira origem, codec observado quando disponível, estágios e percentis. Cancelar o seletor de arquivos deve preservar o relatório.
5. Se houver tela preta, atraso perceptível ou perda de foco, registre o passo, os marcos do JSON e a ação que recuperou a interface. Confira se `incidents` indica causa e último marco do transporte, `transports[].cleanup` indica a liberação ao recarregar e `cleanup` indica a liberação no encerramento. Para comparar frame pacing e retorno ao catálogo, use Perfetto com as trilhas de UI, escalonamento e SurfaceFlinger suportadas pelo dispositivo.

Os marcos de superfície indicam submissão ao EGL. A latência até a exibição física e a latência de ponta a ponta exigem medição no aparelho.

## Comparar perfis de vídeo

Use o mesmo aparelho, título e trecho de jogo em execuções alternadas. Compare 1080p e 720p com Menor atraso e o mesmo codec solicitado. Registre também a resolução e o codec recebidos. Repita primeiro sem pós-processamento. Compare Compatibilidade em separado, pois o perfil também altera o codec, os efeitos e a cadência solicitada.

Para avaliar pacing, faça o par imediato e suave em Adaptativo, com geração e filtros desligados. Para avaliar a geração experimental, faça outro par com a mesma configuração de imagem e conteúdo de origem conhecido, usando material com movimento a 30 FPS, cadência de 60 FPS e menus estáticos. Reabra a partida após mudar a geração. No relatório, compare os quadros RTP, decodificados, descartados e submetidos ao EGL, o número de consultas de assinatura e o trabalho medido no processamento. Os contadores RTP cobrem intervalos válidos observados no primeiro plano. O total EGL cobre toda a tentativa.

Registre a taxa de atualização disponível, a rota de áudio, a temperatura e o consumo quando houver instrumentação. Use Perfetto para cadência de apresentação e câmera de alta velocidade para latência física. A lista HDR do Android informa a capacidade anunciada pela tela. Uma trilha HDR depende de sinalização da origem, negociação, decoder e compositor.


## Linha de base para desempenho

1. Rode o smoke com o APK de referência antes da candidata no mesmo aparelho e com as mesmas animações, temperatura inicial, versão do sistema e condição de login. Use a mesma quantidade de rodadas. Guarde os dois JSONs. Compare medianas e amplitudes de `coldStart`, `resume` e `coldStartPssKiB`. Valores ausentes representam campos que o Android não informou. PSS é uma amostra do processo após abrir a Activity. Os tempos vêm do Activity Manager.
2. Em aparelho físico, faça rodadas alternadas de 30 minutos nas duas versões com o mesmo jogo, controles, rede, região, codec negociado, resolução e efeitos. Faça também ciclos de iniciar, recarregar e sair. Compare crescimento do PSS entre ciclos, pausas de GC, tempo de CPU, atividade de GPU, cadência e tendências de temperatura. Use Perfetto e `dumpsys meminfo com.xcloud.tclnative` nas etapas equivalentes. Registre modelo, API, firmware, RAM, arquitetura e decoder efetivo. Registre energia apenas com medição disponível.
3. Registre encerramentos, ANRs e reinícios do aparelho com a etapa em que ocorreram. Para falhas recuperáveis, conte do primeiro sinal de falha até o vídeo e o controle voltarem. Use os marcos da sessão e uma fonte externa para medir a latência física do comando. Guarde logs de dispositivo em local privado e retire dados de conta antes de compartilhar.

O CI executa abertura e retomada do APK assinado nos emuladores Android 10 e 14, sem conta Xbox. Chips, decodificadores por hardware, HDMI, controles físicos e sessões longas exigem a mesma rodada em hardware real. Variações de tempo ou memória em um emulador não comprovam ganho em aparelhos físicos.

## Comparar os testes de desempenho

Em Vídeo e conexão, abra Avançado e depois Testes de desempenho. Ative uma opção por rodada e inicie uma nova partida com os mesmos ajustes de rede, vídeo e saída. O modo Compatibilidade usa os caminhos já estabelecidos.

- **Decoder H.264.** Em Adaptativo, compare o componente observado, o primeiro quadro e a recuperação na mesma alocação. Registre `DECODER_TRIAL` e `DECODER_RECOVERY`. A escolha de outro hardware exige o perfil negociado e o tamanho de vídeo na lista de capacidades. A preferência aprendida vale na partida atual e só surge após produzir quadro. Repita em firmware que tenha dois componentes capazes e em aparelhos com apenas um.
- **Fence.** Com geração de quadros ativa, compare `GPU_HANDOFF` e `VIDEO_EGL_CADENCE`, além dos contadores de quadros sintéticos e descartes. Use vídeo de movimento conhecido e com cortes. Se o fence não sinalizar na espera de 1 ms, a apresentação usa o quadro original. Se o driver recusar a operação, a produção volta à sincronização padrão. Compare o vídeo exibido e a latência de comando em aparelhos físicos.
- **Controle.** Com toque e entrada nativa de mouse/teclado desligados, compare dez minutos em repouso e dez minutos de uso com o mesmo controle. Registre `INPUT_CADENCE`, `INPUT_EDGE`, CPU da thread xCloud-Input e resposta física do jogo. Envie toques curtos e movimentos imediatamente após o repouso. Confira solturas e o retorno à cadência ativa. Repita com outro slot e com congestionamento reproduzível.
- **Áudio.** Compare a 100% com o mesmo trecho, rota e modo de áudio. `AUDIO_PCM` indica se o módulo registrou o callback. Observe utilização da thread de áudio, alocações, cortes e sincronia. Acima de 100% ao iniciar, o módulo usa o controle de picos. Se o callback estiver desligado, aumentar acima de 100% durante o jogo grava o ganho para a próxima partida. Compare também mute, microfone, Bluetooth e HDMI.

## APK de comparação por arquitetura e R8

Use um diretório ou checkout de teste e guarde uma cópia do APK universal antes de gerar a candidata. Para comparar o R8 independentemente da arquitetura, use apenas `-Pxcloud.trialReleaseShrink=true`. As opções funcionam na variante release. A chave fixa continua necessária para empacotar qualquer APK de distribuição.

```sh
gradle :app:assembleRelease -Pxcloud.trialReleaseAbi=arm64-v8a
python3 scripts/verify_apk_signing.py app/build/outputs/apk/release/app-release.apk \
  --require-nondebuggable --expected-abi arm64-v8a
scripts/smoke_device.sh app/build/outputs/apk/release/app-release.apk SERIAL phone \
  --expected-abi arm64-v8a --report teste-arm64.json
```

Faça atualização por cima do APK universal com a mesma identidade, ou use uma instalação de teste limpa. Registre tamanho do APK, DEX e bibliotecas e confirme login, primeiro vídeo, controle, criação e execução de atalho. Para aparelhos de páginas de 16 KiB, registre `adb shell getconf PAGE_SIZE` e a inspeção de alinhamento ZIP/ELF. Use a versão universal sem as propriedades experimentais como referência.

## Validar uma candidata de distribuição

O workflow de compilação gera o APK assinado e a evidência da revisão, dos testes JVM, do lint e das seis suítes gráficas. O recibo `release-validation.json` só é gerado quando os emuladores Android 10 e 14 abrem e retomam o mesmo APK. Os testes de emulador cobrem a abertura e a retomada da Activity. Os testes JVM usam fixtures simulados para sinalização, sessão e entrada.

Guarde o recibo e o APK da mesma execução. Confirme a assinatura da base e da candidata com `verify_apk_signing.py`. Use o hash do APK de referência para gerar o plano privado.

```sh
python3 scripts/compare_release_matrix.py draft --baseline-sha256 HASH_DA_BASE \
  --receipt release-validation.json --output /caminho/privado/plano.json
python3 scripts/compare_release_matrix.py prepare --plan /caminho/privado/plano.json \
  --output /caminho/privado/rodadas.json
python3 scripts/compare_release_matrix.py compare --plan /caminho/privado/plano.json \
  --results /caminho/privado/rodadas.json --output /caminho/privado/comparacao.json
```

1. Antes de `prepare`, preencha aparelho, API, RAM, ABI, firmware e condições por categoria. Registre título e trecho, plano de acesso sem dados de conta, rede, região, resolução e FPS solicitados, codec, efeitos, saída, entrada e áudio. Use as repetições da base para definir a margem absoluta de P95 de cada métrica, na unidade do campo. Uma margem opcional nula indica medição indisponível ou fora do cenário.
2. Registre pelo menos cinco aberturas a frio e cinco retomadas para cada versão. Use três rodadas pareadas de 15 minutos e uma sessão de 60 minutos por aparelho. Nos alvos mais frágeis, execute vinte ciclos de abrir, cancelar, recuperar, retomar e encerrar. Em cada ciclo, confira o retorno ao catálogo, o controle e a capacidade de iniciar outro jogo.
3. Preencha o componente de decodificação efetivo de cada versão, contadores de incidentes e as verificações funcionais com `pass` ou `fail`. Compare Dungeons, HUD oficial, controles, HDMI, atalhos, contas salvas e Remote Play nas categorias correspondentes. Use amostras pareadas e preserve o protocolo que produziu cada uma. Em `droppedFrames`, conte descartes em janelas de mesma duração. Preencha em `observedStream` o perfil recebido, largura, altura e Hz do display. O comparador deixa a rodada pendente até os dois fluxos serem comparáveis. Registre o tamanho de página com `adb shell getconf PAGE_SIZE` no alvo de 16 KiB.
4. Revise o relatório por categoria. Células ausentes, sessões curtas ou amostras incompletas ficam pendentes. Uma falha já presente na base aparece separada de regressões da candidata. Para investigar um P95 pior, localize a etapa no diagnóstico de sessão, refaça a rodada pareada e congele um novo plano se mudar a candidata ou as condições.
5. Quando todas as categorias passarem, o comparador imprime o SHA-256 do APK aprovado. A publicação manual recebe esse SHA e confere também o recibo do CI, a revisão, a tag, a assinatura e os bytes do APK. Os registros de aparelho e os resultados das partidas permanecem privados.

Os nomes e as unidades das métricas seguem a [matriz do teste](release_matrix.json). Para validar uma região ou outro perfil, registre uma nova rodada com as condições correspondentes.
