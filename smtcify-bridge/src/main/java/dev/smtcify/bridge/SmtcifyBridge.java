package dev.smtcify.bridge;

import net.fabricmc.api.ClientModInitializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public final class SmtcifyBridge implements ClientModInitializer {
    private static final String STATUS_URL =
            "http://127.0.0.1:17643/status";

    private static final String COMPRESSED_RESOURCE =
            "/smtcify/windows/smtcify-windows.exe.gz";

    private static final String HASH_RESOURCE =
            "/smtcify/windows/smtcify-windows.exe.sha256";

    private static volatile Process helperProcess;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "SMTCify-Bridge-Startup");
                thread.setDaemon(true);
                return thread;
            });

    @Override
    public void onInitializeClient() {
        System.out.println("[SMTCify Bridge] Bridge inicializado.");

        if (!System.getProperty("os.name", "")
                .toLowerCase().contains("windows")) {
            System.out.println(
                    "[SMTCify Bridge] El auxiliar solo funciona en Windows."
            );
            executor.shutdown();
            return;
        }

        Runtime.getRuntime().addShutdownHook(
                new Thread(SmtcifyBridge::stopOwnedHelper,
                        "SMTCify-Bridge-Shutdown")
        );

        // Toda la extracción y el arranque ocurren fuera del hilo del juego.
        executor.execute(this::startHelperSafely);
        executor.shutdown();
    }

    private void startHelperSafely() {
        try {
            startHelper();
        } catch (Exception exception) {
            System.err.println(
                    "[SMTCify Bridge] No se pudo iniciar el auxiliar: "
                            + exception.getMessage()
            );
            exception.printStackTrace();
        }
    }

    private void startHelper() throws Exception {
        if (isApiResponding()) {
            System.out.println(
                    "[SMTCify Bridge] Ya hay una API SMTCify respondiendo. "
                            + "No se iniciará otra instancia."
            );
            return;
        }

        String localAppData = System.getenv("LOCALAPPDATA");

        Path dataDirectory = localAppData != null
                ? Paths.get(localAppData, "SMTCify")
                : Paths.get(System.getProperty("user.home"),
                        "AppData", "Local", "SMTCify");

        Path binDirectory = dataDirectory.resolve("bin");
        Path logDirectory = dataDirectory.resolve("logs");
        Path executable = binDirectory.resolve("smtcify-windows.exe");
        Path logFile = logDirectory.resolve("windows-helper.log");

        Files.createDirectories(binDirectory);
        Files.createDirectories(logDirectory);

        String expectedHash = readResourceText(HASH_RESOURCE).trim();

        if (!Files.isRegularFile(executable)
                || !expectedHash.equals(sha256(executable))) {
            extractHelper(executable, expectedHash);
            System.out.println(
                    "[SMTCify Bridge] Auxiliar instalado o actualizado."
            );
        } else {
            System.out.println(
                    "[SMTCify Bridge] El auxiliar ya está actualizado."
            );
        }

        Process process = new ProcessBuilder(executable.toString())
                .directory(binDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(
                        ProcessBuilder.Redirect.appendTo(logFile.toFile())
                )
                .start();

        helperProcess = process;

        System.out.println(
                "[SMTCify Bridge] Auxiliar iniciado. PID: "
                        + process.pid()
        );

        // Espera solo en este hilo auxiliar, nunca en el hilo del juego.
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(20);

        while (System.nanoTime() < deadline) {
            if (isApiResponding()) {
                System.out.println(
                        "[SMTCify Bridge] API del auxiliar lista."
                );
                return;
            }

            if (!process.isAlive()) {
                System.err.println(
                        "[SMTCify Bridge] El auxiliar terminó con código "
                                + process.exitValue()
                                + ". Revisa: " + logFile
                );
                helperProcess = null;
                return;
            }

            Thread.sleep(250);
        }

        System.err.println(
                "[SMTCify Bridge] La API no respondió en 20 segundos. "
                        + "Revisa: " + logFile
        );
    }

    private static void extractHelper(
            Path executable, String expectedHash) throws Exception {

        Path temporary = executable.resolveSibling(
                "smtcify-windows.exe.tmp"
        );

        try (InputStream resource =
                     SmtcifyBridge.class.getResourceAsStream(
                             COMPRESSED_RESOURCE)) {
            if (resource == null) {
                throw new IOException(
                        "No se encontró el auxiliar comprimido dentro del JAR."
                );
            }

            try (GZIPInputStream gzip = new GZIPInputStream(resource);
                 OutputStream output = Files.newOutputStream(
                         temporary,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                gzip.transferTo(output);
            }
        }

        if (!expectedHash.equals(sha256(temporary))) {
            Files.deleteIfExists(temporary);
            throw new IOException(
                    "La verificación SHA-256 del auxiliar falló."
            );
        }

        try {
            Files.move(
                    temporary,
                    executable,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary,
                    executable,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String readResourceText(String resource)
            throws IOException {
        try (InputStream input =
                     SmtcifyBridge.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException(
                        "No se encontró el recurso: " + resource
                );
            }

            return new String(
                    input.readAllBytes(), StandardCharsets.UTF_8
            );
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];

            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                digest.update(buffer, 0, count);
            }
        }

        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);

        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }

        return result.toString();
    }

    private static boolean isApiResponding() {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(STATUS_URL).openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(500);
            connection.setReadTimeout(500);
            connection.setUseCaches(false);

            if (connection.getResponseCode() != 200) {
                return false;
            }

            try (InputStream input = connection.getInputStream()) {
                String body = new String(
                        input.readAllBytes(), StandardCharsets.UTF_8
                );

                return body.contains("\"available\"");
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void stopOwnedHelper() {
        Process process = helperProcess;

        if (process == null || !process.isAlive()) {
            return;
        }

        process.destroy();

        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
