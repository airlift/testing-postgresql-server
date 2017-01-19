/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.testing.postgresql;

import com.google.common.collect.ImmutableMap;
import io.airlift.command.Command;
import io.airlift.command.CommandFailedException;
import io.airlift.log.Logger;
import io.airlift.units.Duration;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.StandardSystemProperty.OS_ARCH;
import static com.google.common.base.StandardSystemProperty.OS_NAME;
import static com.google.common.collect.Lists.newArrayList;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.testing.FileUtils.deleteRecursively;
import static java.lang.String.format;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createTempDirectory;
import static java.util.concurrent.Executors.newCachedThreadPool;

// forked from https://github.com/opentable/otj-pg-embedded
final class EmbeddedPostgreSql
        implements Closeable
{
    private static final Logger log = Logger.get(EmbeddedPostgreSql.class);

    private static final String JDBC_FORMAT = "jdbc:postgresql://localhost:%s/%s?user=%s";

    private static final String PG_SUPERUSER = "postgres";
    private static final Duration PG_STARTUP_WAIT = new Duration(10, TimeUnit.SECONDS);
    private static final Duration COMMAND_TIMEOUT = new Duration(30, TimeUnit.SECONDS);

    private final ExecutorService executor = newCachedThreadPool(daemonThreadsNamed("testing-postgresql-server-%s"));
    private final Path serverDirectory;
    private final Path dataDirectory;
    private final int port = randomPort();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, String> postgresConfig;
    private final Process postmaster;

    public EmbeddedPostgreSql()
            throws IOException
    {
        serverDirectory = createTempDirectory("testing-postgresql-server");
        dataDirectory = serverDirectory.resolve("data");

        postgresConfig = ImmutableMap.<String, String>builder()
                .put("timezone", "UTC")
                .put("synchronous_commit", "off")
                .put("max_connections", "300")
                .build();

        try {
            unpackPostgres(serverDirectory);

            pgVersion();
            initdb();
            postmaster = startPostmaster();
        }
        catch (Exception e) {
            close();
            throw e;
        }
    }

    public String getJdbcUrl(String userName, String dbName)
    {
        return format(JDBC_FORMAT, port, dbName, userName);
    }

    public int getPort()
    {
        return port;
    }

    public Connection getPostgresDatabase()
            throws SQLException
    {
        return DriverManager.getConnection(getJdbcUrl("postgres", "postgres"));
    }

    @Override
    public void close()
    {
        if (closed.getAndSet(true)) {
            return;
        }

        try {
            pgStop();
        }
        catch (Exception e) {
            log.error("could not stop postmaster in %s" + serverDirectory.toString(), e);
            if (postmaster != null) {
                postmaster.destroy();
            }
        }

        deleteRecursively(serverDirectory.toAbsolutePath().toFile());

        executor.shutdownNow();
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("serverDirectory", serverDirectory)
                .add("port", port)
                .toString();
    }

    private static int randomPort()
            throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void pgVersion()
    {
        log.info(system(pgBin("postgres"), "-V").trim());
    }

    private void initdb()
    {
        system(pgBin("initdb"),
                "-A", "trust",
                "-U", PG_SUPERUSER,
                "-D", dataDirectory.toString(),
                "-E", "UTF-8");
    }

    private Process startPostmaster()
            throws IOException
    {
        List<String> args = newArrayList(
                pgBin("postgres"),
                "-D", dataDirectory.toString(),
                "-p", String.valueOf(port),
                "-i",
                "-F");

        for (Entry<String, String> config : postgresConfig.entrySet()) {
            args.add("-c");
            args.add(config.getKey() + "=" + config.getValue());
        }

        Process process = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();

        log.info("postmaster started on port %s. Waiting up to %s for startup to finish.", port, PG_STARTUP_WAIT);

        waitForServerStartup(process);

        return process;
    }

    private void waitForServerStartup(Process process)
            throws IOException
    {
        Throwable lastCause = null;
        long start = System.nanoTime();
        while (Duration.nanosSince(start).compareTo(PG_STARTUP_WAIT) <= 0) {
            try {
                checkReady();
                log.debug("postmaster startup finished");
                return;
            }
            catch (SQLException e) {
                lastCause = e;
                log.debug("while waiting for postmaster startup", e);
            }

            try {
                // check if process has exited
                int value = process.exitValue();
                throw new IOException(format("postmaster exited with value %d, check stdout for more detail", value));
            }
            catch (IllegalThreadStateException ignored) {
                // process is still running, loop and try again
            }

            try {
                Thread.sleep(10);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IOException("postmaster failed to start after " + PG_STARTUP_WAIT, lastCause);
    }

    private void checkReady()
            throws SQLException
    {
        try (Connection connection = getPostgresDatabase();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT 42")) {
            checkSql(resultSet.next(), "no rows in result set");
            checkSql(resultSet.getInt(1) == 42, "wrong result");
            checkSql(!resultSet.next(), "multiple rows in result set");
        }
    }

    private static void checkSql(boolean expression, String message)
            throws SQLException
    {
        if (!expression) {
            throw new SQLException(message);
        }
    }

    private void pgStop()
    {
        system(pgBin("pg_ctl"),
                "stop",
                "-D", dataDirectory.toString(),
                "-m", "fast",
                "-t", "5",
                "-w");
    }

    private String pgBin(String binaryName)
    {
        return serverDirectory.resolve("bin").resolve(binaryName).toString();
    }

    private String system(String... command)
    {
        try {
            return new Command(command)
                    .setTimeLimit(COMMAND_TIMEOUT)
                    .execute(executor)
                    .getCommandOutput();
        }
        catch (CommandFailedException e) {
            throw new RuntimeException(e);
        }
    }

    private void unpackPostgres(Path target)
            throws IOException
    {
        String archiveName = format("/postgresql-%s.tar.gz", getPlatform());
        URL url = EmbeddedPostgreSql.class.getResource(archiveName);
        if (url == null) {
            throw new RuntimeException("archive not found: " + archiveName);
        }

        File archive = File.createTempFile("postgresql-", null);
        try {
            try (InputStream in = url.openStream()) {
                copy(in, archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            system("tar", "-xzf", archive.getPath(), "-C", target.toString());
        }
        finally {
            if (!archive.delete()) {
                log.warn("failed to delete %s", archive);
            }
        }
    }

    private static String getPlatform()
    {
        return (OS_NAME.value() + "-" + OS_ARCH.value()).replace(' ', '_');
    }
}
