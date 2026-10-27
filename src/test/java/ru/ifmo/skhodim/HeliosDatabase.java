package ru.ifmo.skhodim;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Заливает psql-скрипты на helios и выполняет запросы от имени студента.
 * Пароль читается из src/test/resources/helios.local.properties.
 */
final class HeliosDatabase implements AutoCloseable {

    private final Session session;
    private final String database;
    private final String pgHost;
    private final String schema;

    private HeliosDatabase(Session session, String database, String pgHost, String schema) {
        this.session = session;
        this.database = database;
        this.pgHost = pgHost;
        this.schema = schema;
    }

    static HeliosDatabase connect() throws Exception {
        Properties file = new Properties();
        Path local = Path.of("src/test/resources/helios.local.properties");
        if (!Files.exists(local)) {
            throw new IllegalStateException(
                    "Нет " + local.toAbsolutePath() + ". Укажите host, port, user, password, schema, database, pgHost.");
        }
        try (var input = Files.newInputStream(local)) {
            file.load(input);
        }

        JSch jsch = new JSch();
        Session session = jsch.getSession(required(file, "user"), required(file, "host"), Integer.parseInt(required(file, "port")));
        session.setPassword(required(file, "password"));
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(30_000);
        return new HeliosDatabase(session, required(file, "database"), required(file, "pgHost"), required(file, "schema"));
    }

    void uploadScripts() throws Exception {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect(30_000);
        try {
            mkdir(sftp, "skhodim-etap2");
            mkdir(sftp, "skhodim-etap2/sql");
            for (String name : new String[]{
                    "91_reset_objects.sql", "01_schema.sql", "02_logic.sql",
                    "03_queries_indexes.sql", "04_seed.sql"
            }) {
                byte[] bytes = Files.readAllBytes(Path.of("sql", name));
                sftp.put(new java.io.ByteArrayInputStream(bytes), "skhodim-etap2/sql/" + name);
            }
        } finally {
            sftp.disconnect();
        }
    }

    void apply(String fileName) {
        exec(psql() + " -v ON_ERROR_STOP=1 -v app_schema=" + schema
                + " -f skhodim-etap2/sql/" + fileName, null);
    }

    String query(String sql) {
        String command = psql() + " -v ON_ERROR_STOP=1 -At -c " + quote(withSearchPath(sql));
        return lastLine(exec(command, null));
    }

    void script(String sql) {
        exec(psql() + " -v ON_ERROR_STOP=1", withSearchPath(sql).getBytes(StandardCharsets.UTF_8));
    }

    private String withSearchPath(String sql) {
        return "SET search_path TO " + schema + ", public;\n" + sql;
    }

    private String psql() {
        return "env PGCLIENTENCODING=UTF8 /usr/local/bin/psql -h " + pgHost + " -d " + database;
    }

    private String exec(String command, byte[] stdin) {
        try {
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOutputStream(stdout);
            channel.setErrStream(stderr);
            OutputStream remoteIn = channel.getOutputStream();
            channel.connect(30_000);
            if (stdin != null) {
                remoteIn.write(stdin);
                remoteIn.flush();
            }
            remoteIn.close();
            long deadline = System.currentTimeMillis() + 180_000;
            while (!channel.isClosed()) {
                if (System.currentTimeMillis() > deadline) {
                    channel.disconnect();
                    throw new IllegalStateException("psql не завершился: " + command);
                }
                Thread.sleep(50);
            }
            String out = stdout.toString(StandardCharsets.UTF_8);
            String err = stderr.toString(StandardCharsets.UTF_8);
            if (channel.getExitStatus() != 0) {
                throw new IllegalStateException(trim(err + "\n" + out));
            }
            channel.disconnect();
            return out;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    @Override
    public void close() {
        if (session != null) {
            session.disconnect();
        }
    }

    private static void mkdir(ChannelSftp sftp, String path) {
        try {
            sftp.mkdir(path);
        } catch (Exception ignored) {
            // Каталог уже есть.
        }
    }

    private static String required(Properties file, String key) {
        String value = file.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("В helios.local.properties нет " + key);
        }
        return value.trim();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String lastLine(String text) {
        String[] lines = text.trim().split("\\R");
        return lines[lines.length - 1].trim();
    }

    private static String trim(String text) {
        String value = text.trim();
        return value.length() <= 2000 ? value : value.substring(value.length() - 2000);
    }
}
