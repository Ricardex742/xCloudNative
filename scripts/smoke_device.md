# Smoke em Android e Android TV

Use o APK assinado da branch de teste em um celular e em uma TV, incluindo Android 10 quando disponível. Execute com o catálogo visível, pois a atualização do pacote pode interromper a partida atual.

```sh
adb devices -l
scripts/smoke_device.sh caminho/app-release.apk SERIAL phone
scripts/smoke_device.sh caminho/app-release.apk SERIAL_DA_TV tv
```

O script verifica a assinatura e o manifesto do APK, instala como atualização, resolve o launcher correspondente e abre o app. Registre modelo, API, versão, hash do APK, tipo de conexão e resultado. Use uma conta e títulos de teste.

1. No catálogo em paisagem, use toque ou D-pad para chegar a Todos os jogos. Selecione um título, abra os ajustes, feche com X ou Voltar e confirme que a seleção voltou ao mesmo jogo.
2. Abra um título pela capa e por um atalho de launcher já instalado. Confira idioma e entrada planejados, controle físico desde o primeiro quadro e saída da fila com Voltar. Repita com outro modo de controle. Abra o último diagnóstico após a desistência e confira a origem e os marcos alcançados.
3. Durante uma partida, abra e feche os atalhos do jogo. Volte ao launcher do sistema e retome a Activity. Confirme que a imagem e o controle retornaram. Repita o teste com Recarregar transmissão e confira os marcos em outra geração do transporte, dentro da mesma tentativa.
4. Encerre a partida, volte ao catálogo e confirme o foco. Em Ajuda e diagnóstico, abra o último relatório e use Exportar diagnóstico para salvar o JSON numa pasta de teste. Confira origem, codec observado quando disponível, estágios e percentis. Cancelar o seletor de arquivos deve preservar o relatório.
5. Se houver tela preta, atraso perceptível ou perda de foco, registre o passo, os marcos do JSON e a ação que recuperou a interface. Para comparar frame pacing e retorno ao catálogo, use Perfetto com as trilhas de UI, escalonamento e SurfaceFlinger suportadas pelo dispositivo. Registre as condições do teste e repita nas duas versões.

Os marcos de superfície indicam submissão ao EGL. A latência até a exibição física e a latência de ponta a ponta exigem medição no aparelho.
