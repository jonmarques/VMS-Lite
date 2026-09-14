# VMS Lite

Aplicacao Java 21 / Swing para monitoramento RTSP com VLCJ e descoberta ONVIF.

## Organizacao

- `VMSLite`: janela, montagem gradual das cameras, importacao/exportacao e coordenacao da interface.
- `CameraPanel`: superficie de video, estado visual e eventos do mouse.
- `playback/CameraPlayback`: inicio, reconexao, deteccao de congelamento e encerramento por camera.
- `playback/VideoPlayer` e `VlcVideoPlayer`: contrato de reproducao e adaptador VLCJ; permitem testar o ciclo de vida sem carregar VLC.
- `playback/PlaybackExecutors`: pools compartilhados e remocao imediata de tarefas canceladas.
- `ui/CameraGridLayout`: calculo da grade sem matriz de ocupacao; limita a largura da camera as colunas disponiveis.
- `ui/CameraEditorDialog` e `OnvifDiscoveryDialog`: edicao e inclusao de cameras.
- `service/OnvifDiscoveryService`: descoberta multicast compartilhada, cache de 30 segundos e limite de 120 segundos para buscas de reconexao.
- `service/OnvifMediaService`, `OnvifSoapClient` e `OnvifXml`: perfis de video, transporte SOAP e leitura XML com namespaces.
- `service/CameraAddress`: extracao/substituicao do host sem alterar credenciais ou parametros.
- `service/ConfigService`: validacao e gravacao por arquivo temporario seguido de substituicao atomica quando suportada.
- `service/ConfigWriter`: gravacao fora da interface, agrupando alteracoes feitas em ate 300 ms.
- `ApplicationBootstrap`, `SingleInstance`, `AppWatchdog` e `ExternalWatchdogInstaller`: inicializacao e integracao com o sistema.

As alteracoes na lista de cameras e na interface ocorrem na thread do Swing. Operacoes nativas de reproducao ficam fora dela e sao serializadas por camera. O encerramento espera a liberacao dos players antes de liberar a fabrica VLC; se uma chamada nativa travar, o processo termina sem liberar a fabrica enquanto ainda ha players em uso.

O formato JSON e o caminho `%USERPROFILE%/.vmslite/vms-config.json` foram mantidos. Dimensoes validas ficam entre 1 e 20; arquivos invalidos nao substituem a configuracao durante a importacao. Campos de dimensao ausentes usam valores padrao.

`network-caching=1000` e `live-caching=1000` foram preservados, assim como as demais opcoes VLC existentes. Novas cameras usam substream por padrao no dialogo. Fallbacks conhecidos de Hikvision, Dahua/Intelbras, TP-Link, Reolink e Foscam respeitam a escolha de perfil; modelos genericos ainda dependem da URL fornecida pelo equipamento.

## Administrador e URLs

O URL fica mascarado no editor de camera. O botao de revelar abre a autenticacao de administrador; cancelar ou errar as credenciais mantem o URL oculto. A cada nova revelacao e exigido outro login. Ao fechar o editor, o campo e limpo.

Quando nao existe administrador, o dialogo apresenta "Login e Senha de Administrador não setados. Crie agora um:" e solicita login, senha e confirmacao. A senha deve ter de 12 a 1024 caracteres.

O cadastro fica em `%USERPROFILE%/.vmslite/admin-auth.json`, com hashes PBKDF2-HMAC-SHA256 (600.000 iteracoes) do login e da senha, cada um com seu proprio salt aleatorio. Nem o login nem a senha originais sao gravados. Nao ha vinculo criptografico com uma conta do Windows. O algoritmo e o fator de trabalho seguem a [orientacao de armazenamento de senhas da OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). A verificacao ocorre fora da thread da interface e somente ao autenticar. Cadastros da versao anterior, que continham login legivel, sao migrados para o formato 2 na proxima inicializacao ou consulta do cadastro. A migracao preserva a senha existente e substitui o arquivo por meio de um temporario; nao cria backup em texto aberto. Depois da migracao, a verificacao de cadastro na inicializacao apenas le o arquivo, sem recalcular hashes.

Cinco falhas consecutivas bloqueiam novas tentativas por 30 segundos nesta execucao do aplicativo. Um cadastro invalido ou ilegivel bloqueia a visualizacao, sem ser substituido automaticamente. Esta protecao da interface nao impede acesso por alguem capaz de ler ou modificar os arquivos locais.

**A criptografia de `vms-config.json` e dos backups ainda nao foi implementada: aguarda aprovacao da forma de guardar/desbloquear a chave. Esses arquivos continuam contendo os URLs em texto aberto.** A exportacao atual tambem nao e protegida pelo login de revelacao.

## Camera Tour

O botao `Tour` permite ativar uma area fixa de 2 linhas por 2 colunas no canto superior esquerdo, selecionar cameras, ordenar a sequencia com as setas e definir um intervalo de 5 a 3600 segundos (60 = 1 minuto). Grades menores usam pelo menos 2 linhas e 2 colunas enquanto o tour estiver ativo.

Todas as cameras permanecem conectadas e reproduzindo. A camera da vez ocupa o tour; as outras, inclusive as selecionadas que aguardam sua vez, aparecem na grade comum. A troca altera somente as dimensoes e a posicao dos paineis no mesmo container, sem recriar a superficie nativa, reiniciar o player ou abrir uma conexao duplicada. Desativar o tour restaura todas na grade comum. Se houver apenas uma selecionada, ela permanece ampliada e o temporizador fica desligado.

Os controles de pausa/retomada e avanco aparecem sobre o canto inferior direito do video somente enquanto o mouse esta sobre a camera do tour. Nao reservam espaco nem reduzem a imagem. Uma janela pertencente ao aplicativo permite sobrepor os controles ao video nativo; ela fica oculta quando o aplicativo esta inativo. O botao direito abre o editor da camera exibida e pausa a alternancia durante a edicao. Configuracao, ordem e intervalo sao salvos e restaurados na inicializacao; a pausa e temporaria. Cada camera possui um identificador local persistente, independente do UUID ONVIF. Configuracoes antigas iniciam com o tour desativado.

O intervalo e contado entre trocas de destaque. O tour nao solicita recarregamento nas trocas; cameras ainda conectando ou instaveis podem continuar mostrando carregamento. Todas permanecem decodificando, portanto CPU/RAM podem aumentar em relacao ao tour anterior de player unico. As opcoes existentes de caching permanecem inalteradas. Camera offline nao interrompe a sequencia.

## Testes Locais

Ao passar o mouse sobre qualquer camera, um indicador sobreposto mostra o estado de conexao, FPS exibido e taxa de midia recebida em Mbps. As taxas sao calculadas pela diferenca dos contadores VLC entre amostras, aproximadamente uma vez por segundo, apenas para a camera sob o mouse. A primeira amostra, reconexoes e estatisticas indisponiveis mostram `--`. A taxa de midia nao inclui todo o overhead de rede. O indicador nao reduz a imagem e fica oculto fora da camera ou com o aplicativo inativo.

O layout valida os componentes internos imediatamente apos reposicionar os paineis e o VLC usa escala automatica, preservando a proporcao da imagem ao entrar e sair do tour.

Com Java 21 e as dependencias locais em `target/installer-input/lib`, execute no PowerShell:

```powershell
$sources = (Get-ChildItem -Recurse src/main/java,src/test/java -Filter *.java).FullName
javac -encoding UTF-8 --release 21 -cp "target/installer-input/lib/*" -d target/checks $sources
java -cp "target/checks;target/installer-input/lib/*" br.com.jonmarques.vmslite.service.RefactoringChecks
java -cp "target/checks;target/installer-input/lib/*" br.com.jonmarques.vmslite.playback.PlaybackChecks
java -cp "target/checks;target/installer-input/lib/*" br.com.jonmarques.vmslite.playback.CameraTourChecks
java -cp "target/checks;target/installer-input/lib/*" br.com.jonmarques.vmslite.ui.TourSurfaceChecks
java -cp "target/checks;target/installer-input/lib/*" br.com.jonmarques.vmslite.ui.TourHoverControlsChecks
java -cp "target/checks;target/installer-input/lib/*" br.com.jonmarques.vmslite.service.AdministratorChecks
java -cp "target/checks;target/installer-input/lib/*" br.com.jonmarques.vmslite.ui.ProtectedUrlChecks
```

Os testes nao acessam cameras, nao registram tarefas do Windows e usam arquivos temporarios dentro de `target`. Cobrem grades aleatorias, URLs com credenciais, IPv6, XML, fallback de substream, configuracoes invalidas e o ciclo de vida de um player simulado.

Para a compilacao habitual, use `mvn compile`. O empacotamento existente permanece em `mvn package`.

A reproducao nativa, os drivers de video, tela cheia em multiplos monitores e a recuperacao de cameras instaveis precisam ser conferidos com os equipamentos reais. Nao ha medicao comparativa de CPU/RAM incluida nesta refatoracao.
