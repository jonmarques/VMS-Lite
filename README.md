# VMS Lite

Aplicacao Java 21 / Swing para monitoramento RTSP com VLCJ e descoberta ONVIF.

## Organizacao

- `VMSLite`: janela, montagem gradual das cameras, importacao/exportacao e coordenacao da interface.
- `CameraPanel`: superficie de video, estado visual e eventos do mouse.
- `playback/CameraPlayback`: inicio, reconexao, deteccao de congelamento e encerramento por camera.
- `playback/VideoPlayer` e `VlcVideoPlayer`: contrato de reproducao e adaptador VLCJ; permitem testar o ciclo de vida sem carregar VLC.
- `playback/PlaybackExecutors` e `CameraCommandQueue`: um trabalhador nativo por camera, fila limitada por tipo de comando e temporizador compartilhado sem chamadas VLC. Pedidos repetidos pendentes sao agrupados.
- `ui/CameraGridLayout`: calculo da grade sem matriz de ocupacao; limita a largura da camera as colunas disponiveis.
- `ui/CameraEditorDialog` e `OnvifDiscoveryDialog`: edicao e inclusao de cameras.
- `service/OnvifDiscoveryService`: descoberta multicast compartilhada, cache de 30 segundos e limite de 120 segundos para buscas de reconexao.
- `service/OnvifMediaService`, `OnvifSoapClient` e `OnvifXml`: perfis de video, transporte SOAP e leitura XML com namespaces.
- `service/CameraAddress`: extracao/substituicao do host sem alterar credenciais ou parametros.
- `service/ConfigService`: validacao e gravacao por arquivo temporario seguido de substituicao atomica quando suportada.
- `service/ConfigWriter`: gravacao fora da interface, agrupando alteracoes feitas em ate 300 ms; permanece bloqueada ate uma configuracao ser carregada ou restaurada com sucesso.
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

## Busca ONVIF

A descoberta usa WS-Discovery 2005/04 com WS-Addressing 2004/08, MessageID unico, ReplyTo anonimo e destino `urn:schemas-xmlsoap-org:ws:2005:04:discovery`, conforme os exemplos da [especificacao de testes ONVIF](https://www.onvif.org/wp-content/uploads/2021/06/ONVIF_Base_Device_Test_Specification_21.06.pdf).

Cada endereco IPv4 ativo com suporte a multicast envia consultas para `239.255.255.250:3702` pela propria interface. Uma unica thread recebe as respostas, com prazo global de 6 segundos e tres rodadas de consultas para os tipos NetworkVideoTransmitter e Device. As buscas simultaneas continuam compartilhadas; o limite de reconexao foi mantido. Resultados vazios nao recebem cache de 30 segundos. Respostas invalidas sao ignoradas e os endpoints anunciados no IP que respondeu tem preferencia.

A janela mostra a quantidade de interfaces pesquisadas, dispositivos encontrados e eventuais erros de acesso. Dispositivos ja cadastrados sao diferenciados de uma busca sem respostas. A busca nao altera regras de firewall. ONVIF desabilitado na camera, isolamento de rede, VLANs ou bloqueios de multicast ainda podem impedir a descoberta.

O teste `br.com.jonmarques.vmslite.service.OnvifDiscoveryChecks` verifica mensagens, XML, endpoints e respostas UDP locais com perda simulada de pacotes. O argumento opcional `--network` executa uma descoberta real e informa contagens, sem adicionar cameras ou acessar suas credenciais.

## Verificacao Local

### Correcoes de estabilidade

- Se a leitura inicial do JSON falhar, o arquivo permanece preservado e somente Importar/Tela Cheia ficam disponiveis. Uma importacao valida libera novamente a gravacao. Nao se cria uma configuracao vazia sobre o arquivo com erro.
- `ApplicationPaths` resolve o executavel real, incluindo os nomes `VMS Lite.exe` e `VMSLite.exe`, sem depender do diretorio de trabalho quando empacotado. Inicializacao automatica, bibliotecas VLC e watchdogs usam essa referencia. Heartbeat, scripts e registros do watchdog ficam em `%USERPROFILE%/.vmslite`; a tarefa antiga e atualizada na proxima inicializacao empacotada.
- Chamadas nativas ficam isoladas por camera. Uma chamada travada nao ocupa trabalhadores de outras cameras. Ha no maximo um comando pendente de cada tipo por camera, e consultas de metricas expiram em 3 segundos. A liberacao continua esperando a chamada nativa terminar; nao se libera uma superficie em uso. Isso usa um trabalhador por camera, portanto nao representa uma promessa de reducao de RAM.
- Reconexoes usam espera progressiva proxima de 5, 10, 20, 40 e ate 60 segundos, com variacao entre cameras. A espera volta ao inicio quando o video reproduz; consultas ONVIF de reconexao continuam limitadas. Caching e opcoes de substream nao foram alterados.
- SOAP tem limite de 2 MiB por resposta e prazo total de 10 segundos, incluindo o corpo. Erros de autenticacao, endereco, transporte e resposta possuem mensagens sem credenciais. O fallback de URL por modelo permanece disponivel para falhas que nao sejam de autenticacao, acompanhado de aviso.
- Metricas, tour e tela cheia compartilham um unico temporizador de mouse por janela. Texto, HTML e geometria dos indicadores so sao atualizados quando mudam.

Testes adicionais: `ApplicationPathsChecks`, `service.ConfigRecoveryChecks`, `service.OnvifSoapChecks`, `playback.PlaybackIsolationChecks` e `ui.HoverPointerChecks`, no pacote base `br.com.jonmarques.vmslite`. Nenhum deles registra tarefas, altera o startup ou inicia cameras reais.

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
