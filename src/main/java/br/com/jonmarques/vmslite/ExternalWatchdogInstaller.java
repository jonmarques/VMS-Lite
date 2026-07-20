package br.com.jonmarques.vmslite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Instala automaticamente o watchdog EXTERNO (fora da JVM) via Tarefa
 * Agendada do Windows, sem precisar de nenhum passo manual.
 *
 * O que ele faz, na primeira execução do app:
 *  1. Escreve um script watchdog.ps1 na pasta de instalação, gerado a
 *     partir do template abaixo (nada de arquivo externo pra distribuir).
 *  2. Registra uma Tarefa Agendada que roda esse script a cada 1 minuto,
 *     via `schtasks`.
 *  3. O script verifica a idade do heartbeat.txt (gravado pelo AppWatchdog
 *     a cada 5s) e, se estiver velho demais OU o processo não existir mais,
 *     mata o processo (se existir) e reabre o VMSLite.exe.
 *
 * Por que isso complementa o AppWatchdog interno:
 * O AppWatchdog roda DENTRO da JVM. Se a JVM inteira travar (não só a EDT),
 * teoricamente as threads dele também podem não conseguir agir. Este
 * watchdog externo roda como processo do Windows totalmente separado, então
 * funciona mesmo que a JVM trave por completo.
 *
 * Chame ExternalWatchdogInstaller.install() uma vez no início do VMSLite
 * (mesmo lugar do AppWatchdog.start()). A instalação só roda de fato na
 * primeira vez; nas próximas execuções ele detecta que a tarefa já existe
 * e não faz nada.
 */
public final class ExternalWatchdogInstaller {

	private static final boolean DEBUG = Boolean.getBoolean("vmslite.debug");
	private static final String TASK_NAME = "VMSLiteWatchdog";

	private ExternalWatchdogInstaller() {
	}

	public static void install() {
		// Só faz sentido no Windows (Tarefa Agendada + schtasks são específicos dele)
		String os = System.getProperty("os.name", "").toLowerCase();
		if (!os.contains("win")) {
			logDebug("Sistema não é Windows, watchdog externo não instalado (sem suporte ainda).");
			return;
		}

		try {
			if (taskAlreadyExists()) {
				logDebug("Tarefa Agendada '" + TASK_NAME + "' já existe. Nada a fazer.");
				return;
			}

			Path scriptPath = writeWatchdogScript();
			registerScheduledTask(scriptPath);

			logDebug("Watchdog externo instalado com sucesso (Tarefa Agendada: " + TASK_NAME + ").");
		} catch (Exception e) {
			logDebug("Falha ao instalar watchdog externo: " + e.getMessage());
		}
	}

	// ---------- Verifica se a tarefa já existe ----------

	private static boolean taskAlreadyExists() throws IOException, InterruptedException {

		ProcessBuilder pb = new ProcessBuilder("schtasks", "/query", "/tn", TASK_NAME);
		pb.redirectErrorStream(true);
		Process p = pb.start();
		// Descarta a saída (não precisamos ler, só saber o exit code)
		p.getInputStream().readAllBytes();
		int exitCode = p.waitFor();
		return exitCode == 0; // 0 = achou a tarefa; != 0 = não existe
	}

	// ---------- Gera o watchdog.ps1 ----------

	private static Path writeWatchdogScript() throws IOException {

		Path appDir = getInstallDirectory();

		System.out.println("Diretório real do VMS Lite: " + appDir);


		String exePath = appDir.resolve("VMS Lite.exe").toString();

		String heartbeatPath = appDir.resolve("heartbeat.txt").toString();

		Path scriptPath = appDir.resolve("watchdog.ps1");


		String script = buildScriptContent(
				exePath,
				heartbeatPath
				);


		Files.writeString(
				scriptPath,
				script,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
				);

		Path vbsPath = appDir.resolve("watchdog.vbs");

		String vbsContent =
				"Set sh = CreateObject(\"WScript.Shell\")\r\n" +
						"sh.Run \"powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"\""
						+ scriptPath.toString()
						+ "\"\"\", 0, False\r\n";

		Files.writeString(
				vbsPath,
				vbsContent,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
				);

		return vbsPath;

	}

	private static String buildScriptContent(String exePath, String heartbeatPath) {
		// Script "de tiro único": roda, checa, e sai. A Tarefa Agendada é quem
		// se encarrega de rodar ele de novo a cada 1 minuto (via /sc MINUTE).
		// Isso evita ficar com um processo PowerShell em loop infinito rodando
		// o tempo todo em segundo plano.
		return ""
		+ "$ErrorActionPreference = 'SilentlyContinue'\r\n"
		+ "$exePath = \"" + escapeForPs(exePath) + "\"\r\n"
		+ "$heartbeatFile = \"" + escapeForPs(heartbeatPath) + "\"\r\n"
		+ "$maxAgeSeconds = 45\r\n"
		+ "\r\n"
		+ "$proc = Get-Process | Where-Object { $_.Path -eq $exePath } | Select-Object -First 1\r\n"
		+ "\r\n"
		+ "$precisaReiniciar = $false\r\n"
		+ "\r\n"
		+ "if (-not $proc) {\r\n"
		+ "    # Processo nem está rodando -> reabre\r\n"
		+ "    $precisaReiniciar = $true\r\n"
		+ "} elseif (Test-Path $heartbeatFile) {\r\n"
		+ "    $conteudo = Get-Content $heartbeatFile -ErrorAction SilentlyContinue\r\n"
		+ "    if ($conteudo) {\r\n"
		+ "        $ultimoHeartbeat = [long]$conteudo\r\n"
		+ "        $agoraMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()\r\n"
		+ "        $idadeSegundos = ($agoraMs - $ultimoHeartbeat) / 1000\r\n"
		+ "        if ($idadeSegundos -gt $maxAgeSeconds) {\r\n"
		+ "            $precisaReiniciar = $true\r\n"
		+ "        }\r\n"
		+ "    }\r\n"
		+ "}\r\n"
		+ "\r\n"
		+ "if ($precisaReiniciar) {\r\n"
		+ "    if ($proc) {\r\n"
		+ "        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue\r\n"
		+ "        Start-Sleep -Seconds 2\r\n"
		+ "    }\r\n"
		+ "    Add-Content -Path (Join-Path (Split-Path $exePath) 'watchdog-external.log') -Value (\"Tentando abrir: \" + $exePath)\r\n"
		+ "    Start-Process -FilePath $exePath -WorkingDirectory (Split-Path $exePath)\r\n"
		+ "    $logLine = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + \" - Watchdog externo reiniciou o VMSLite.\"\r\n"
		+ "    Add-Content -Path (Join-Path (Split-Path $exePath) 'watchdog-external.log') -Value $logLine\r\n"
		+ "}\r\n";
	}

	private static String escapeForPs(String value) {
		return value.replace("\"", "`\"");
	}

	// ---------- Registra a Tarefa Agendada ----------

	private static void registerScheduledTask(Path vbsPath)
			throws IOException, InterruptedException {

		String taskCommand = vbsPath.toAbsolutePath().toString();

		ProcessBuilder pb = new ProcessBuilder(
				"schtasks",
				"/create",
				"/tn", TASK_NAME,
				"/tr", taskCommand,
				"/sc", "MINUTE",
				"/mo", "1",
				"/rl", "LIMITED",
				"/f"
				);

		pb.redirectErrorStream(true);

		Process p = pb.start();

		String output = new String(
				p.getInputStream().readAllBytes(),
				StandardCharsets.UTF_8
				);

		int exitCode = p.waitFor();

		if (exitCode != 0) {
			throw new IOException(
					"schtasks retornou código "
							+ exitCode + ": " + output);
		}
	}

	/**
	 * Opcional: remove a Tarefa Agendada, caso você queira dar essa opção
	 * ao usuário num botão de "desinstalar watchdog" ou no desinstalador
	 * do app.
	 */
	public static void uninstall() {
		try {
			ProcessBuilder pb = new ProcessBuilder("schtasks", "/delete", "/tn", TASK_NAME, "/f");
			pb.redirectErrorStream(true);
			Process p = pb.start();
			p.getInputStream().readAllBytes();
			p.waitFor();
			logDebug("Tarefa Agendada removida.");
		} catch (Exception e) {
			logDebug("Falha ao remover Tarefa Agendada: " + e.getMessage());
		}
	}

	private static Path getInstallDirectory() {

		try {

			Path exe = Paths.get(
					ExternalWatchdogInstaller.class
					.getProtectionDomain()
					.getCodeSource()
					.getLocation()
					.toURI()
					);

			String path = exe.toString();

			// remove \app\arquivo.jar
			if (path.contains("\\app\\")) {

				return Paths.get(
						path.substring(
								0,
								path.indexOf("\\app\\")
								)
						);
			}

			return exe.getParent();

		} catch(Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static void logDebug(String message) {
		if(DEBUG) {
			System.out.println("[ExternalWatchdogInstaller] " + message);
		}
	}
}