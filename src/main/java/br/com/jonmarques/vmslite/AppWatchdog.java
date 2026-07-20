package br.com.jonmarques.vmslite;

import javax.swing.Timer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Watchdog de aplicação inteira para o VMS Lite.
 *
 * Objetivo: detectar quando a EDT (Event Dispatch Thread) do Swing trava
 * por completo — cenário em que o app fica "pendurado" sem fechar, sem
 * responder a clique nenhum, mesmo que o processo continue vivo no SO.
 *
 * Isso é DIFERENTE do que você já trata no CameraPanel (reconexão de câmera
 * individual via listeners error/stopped/videoOutput). Este watchdog é a
 * última camada de defesa, para quando o travamento é global (ex: deadlock
 * nativo no libvlc que trava a EDT inteira, não só uma câmera).
 *
 * Estratégia:
 *  1. Uma thread própria (fora da EDT) agenda repetidamente um "ping" na EDT
 *     via SwingUtilities.invokeLater, e a EDT responde atualizando um timestamp.
 *  2. Uma segunda thread checa periodicamente se esse timestamp está muito
 *     velho. Se estiver, a EDT está travada -> reinicia o processo inteiro.
 *  3. Também grava um heartbeat em arquivo (heartbeat.txt), independente
 *     da EDT, para servir de sinal a um watchdog EXTERNO (ex: Tarefa
 *     Agendada do Windows) — camada extra, caso até essas threads internas
 *     travem (deadlock de JVM inteira, bem mais raro, mas possível se o
 *     libvlc travar um lock global usado por várias threads).
 *  4. Tem proteção contra "loop de restart": se reiniciar demais em pouco
 *     tempo, para de tentar e só loga (evita ficar reiniciando infinitamente
 *     caso o travamento seja determinístico, ex: sempre trava 5s após abrir).
 */
public final class AppWatchdog {

    private static final long EDT_TIMEOUT_MS = 15_000;       // EDT sem responder por 15s = travada
    private static final long CHECK_INTERVAL_SEC = 3;         // intervalo de checagem
    private static final long EDT_PING_INTERVAL_MS = 1_000;   // frequência do ping na EDT

    private static final Path HEARTBEAT_FILE = Paths.get(System.getProperty("user.dir"), "heartbeat.txt");
    private static final Path RESTART_MARKER_FILE = Paths.get(System.getProperty("user.dir"), "watchdog-restarts.log");
    private static final int MAX_RESTARTS_IN_WINDOW = 3;
    private static final long RESTART_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);

    private static final AtomicLong lastEdtResponse = new AtomicLong(System.currentTimeMillis());
    private static volatile boolean started = false;

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
        private int count = 1;

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "AppWatchdog-" + count++);
            t.setDaemon(true);
            return t;
        }
    });

    private AppWatchdog() {
    }

    /**
     * Inicia o watchdog. Chame uma única vez, no construtor do VMSLite,
     * depois que a janela já estiver visível.
     */
    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;

        startEdtPing();
        startFileHeartbeat();
        startEdtWatchdog();

        logDebug("AppWatchdog iniciado.");
    }

    // ---------- 1. Ping periódico na EDT ----------

    private static void startEdtPing() {
        // Usa javax.swing.Timer porque ele já roda o callback na própria EDT,
        // então se a EDT travar, o timer simplesmente para de disparar —
        // é exatamente o sinal que queremos detectar.
        Timer edtPing = new Timer((int) EDT_PING_INTERVAL_MS, e -> lastEdtResponse.set(System.currentTimeMillis()));
        edtPing.setRepeats(true);
        edtPing.start();
    }

    // ---------- 2. Heartbeat em arquivo (para watchdog externo opcional) ----------

    private static void startFileHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                Files.writeString(
                        HEARTBEAT_FILE,
                        String.valueOf(System.currentTimeMillis()),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (IOException e) {
                logDebug("Falha ao gravar heartbeat.txt: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    // ---------- 3. Checagem e restart ----------

    private static void startEdtWatchdog() {
        scheduler.scheduleAtFixedRate(() -> {
            long age = System.currentTimeMillis() - lastEdtResponse.get();
            if (age > EDT_TIMEOUT_MS) {
                logDebug("EDT sem responder há " + age + "ms. Iniciando restart da aplicação.");
                handleFreezeDetected();
            }
        }, CHECK_INTERVAL_SEC, CHECK_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private static void handleFreezeDetected() {
        if (!allowedToRestart()) {
            logDebug("Limite de restarts automáticos atingido nos últimos "
                    + (RESTART_WINDOW_MS / 60000) + " minutos. Não vou tentar reiniciar de novo "
                    + "(provável travamento recorrente/determinístico). Encerrando apenas.");
            // Ainda assim mata o processo, para não deixar um app travado consumindo recursos.
            // O watchdog EXTERNO (se configurado) pode decidir se reinicia ou não.
            Runtime.getRuntime().halt(1);
            return;
        }

        registerRestartAttempt();

        try {
            // IMPORTANTE: não usamos ProcessHandle.current().info().command() aqui.
            // Quando o app roda via IDE (ou qualquer lançador que chame javaw.exe
            // diretamente), esse comando retorna o caminho do javaw.exe da JDK,
            // SEM os argumentos de classpath/main class -- reiniciar "isso" abriria
            // um javaw.exe vazio, sem nada pra rodar.
            //
            // Em produção (empacotado via jpackage) o app sempre roda como
            // VMSLite.exe na pasta de instalação -- é o mesmo caminho que o
            // próprio VMSLite.java já usa no addToStartup(). Usamos o mesmo aqui,
            // por consistência e confiabilidade.
            String exePath = System.getProperty("user.dir") + File.separator + "VMSLite.exe";
            File exeFile = new File(exePath);

            if (exeFile.exists()) {
                ProcessBuilder pb = new ProcessBuilder(exePath);
                pb.directory(new File(System.getProperty("user.dir")));
                pb.inheritIO();
                pb.start();
                logDebug("Novo processo iniciado: " + exePath);
            } else {
                // Provavelmente rodando via IDE/dev, sem o .exe empacotado presente.
                // Não há como reiniciar de forma confiável nesse cenário -- loga e
                // deixa o watchdog externo (heartbeat.txt) como única rede de segurança.
                logDebug("VMSLite.exe não encontrado em " + exePath
                        + " (provável ambiente de desenvolvimento). Restart automático abortado.");
            }
        } catch (Exception e) {
            logDebug("Falha ao iniciar novo processo: " + e.getMessage());
        } finally {
            // halt() em vez de exit(): não roda shutdown hooks nem tenta liberar
            // players VLC (que é exatamente o que pode estar travado). Mata a
            // JVM imediatamente para garantir que o restart aconteça.
            Runtime.getRuntime().halt(1);
        }
    }

    // ---------- 4. Proteção contra loop de restart ----------

    private static boolean allowedToRestart() {
        try {
            if (!Files.exists(RESTART_MARKER_FILE)) {
                return true;
            }
            long cutoff = System.currentTimeMillis() - RESTART_WINDOW_MS;
            long count = Files.readAllLines(RESTART_MARKER_FILE).stream()
                    .map(AppWatchdog::parseLongSafe)
                    .filter(ts -> ts != null && ts > cutoff)
                    .count();
            return count < MAX_RESTARTS_IN_WINDOW;
        } catch (IOException e) {
            return true; // se não conseguir ler o histórico, não bloqueia o restart
        }
    }

    private static void registerRestartAttempt() {
        try {
            Files.writeString(
                    RESTART_MARKER_FILE,
                    System.currentTimeMillis() + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            logDebug("Falha ao registrar tentativa de restart: " + e.getMessage());
        }
    }

    private static Long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static void logDebug(String message) {
        // Sempre loga (mesmo sem -Dvmslite.debug=true), pois é informação
        // crítica de diagnóstico de travamento — vale a pena manter registrado.
        System.out.println("[AppWatchdog] " + message);
    }
}