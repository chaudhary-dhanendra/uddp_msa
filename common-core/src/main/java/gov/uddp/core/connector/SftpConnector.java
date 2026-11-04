package gov.uddp.core.connector;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;
import gov.uddp.core.security.SecretRedactor;
import gov.uddp.core.studio.CsvProfiler;
import gov.uddp.core.studio.CsvProfiler.Table;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Vector;
import org.springframework.stereotype.Component;

/**
 * SFTP folder pickup (Track C5). Settings: {@code host}, {@code port}, {@code user}, the credential
 * {@code passwordRef}, {@code directory}, {@code pattern} (a glob, default *.csv), optional {@code archiveDirectory}
 * (the picked-up file moves there once its delivery was ingested) and the server's host key as
 * {@code hostKeySha256} (SHA256:...) unless {@code trustUnknownHost} is true. The newest matching file is the delivery.
 */
@Component
public class SftpConnector implements SourceConnector {
    private final CsvProfiler profiler;

    public SftpConnector(CsvProfiler profiler) {
        this.profiler = profiler;
    }

    @Override
    public String type() {
        return "sftp";
    }

    @Override
    public void test(Config config) {
        session(config, (channel, directory) -> {
            channel.cd(directory);
            return null;
        });
    }

    @Override
    public Table preview(Config config, int limit) {
        Table table = download(config).table();
        return new Table(table.header(), table.rows().subList(0, Math.min(limit, table.rows().size())));
    }

    @Override
    public Delivery fetch(Config config) {
        return download(config);
    }

    private Delivery download(Config config) {
        String pattern = config.optional("pattern", "*.csv");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        String archive = config.optional("archiveDirectory", null);
        Picked picked = session(config, (channel, directory) -> {
            channel.cd(directory);
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = channel.ls(".");
            var newest = entries.stream()
                    .filter(e -> !e.getAttrs().isDir() && matcher.matches(Path.of(e.getFilename())))
                    .max(Comparator.comparingInt(e -> e.getAttrs().getMTime()))
                    .orElseThrow(() -> new IllegalArgumentException("No file matching '" + pattern + "' in " + directory));
            Path local = Files.createTempFile("uddp-sftp", ".csv");
            try {
                channel.get(newest.getFilename(), local.toString());
                return new Picked(newest.getFilename(), profiler.read(local));
            } finally {
                Files.deleteIfExists(local);
            }
        });
        Runnable completed = () -> {
            if (archive != null) {
                session(config, (channel, directory) -> {
                    channel.cd(directory);
                    String target = archive + "/" + Instant.now().toEpochMilli() + "-" + picked.name();
                    channel.rename(picked.name(), target);
                    return null;
                });
            }
        };
        return new Delivery(picked.table(), completed);
    }

    private record Picked(String name, Table table) {
    }

    private interface Action<T> {
        T run(ChannelSftp channel, String directory) throws SftpException, IOException;
    }

    private <T> T session(Config config, Action<T> action) {
        String password = config.requiredSecret("password");
        try {
            JSch jsch = new JSch();
            Session session = jsch.getSession(config.required("user"), config.required("host"),
                    config.integer("port", 22));
            session.setPassword(password);
            Properties options = new Properties();
            if ("true".equalsIgnoreCase(config.optional("trustUnknownHost", "false"))) {
                options.put("StrictHostKeyChecking", "no");
            } else {
                String fingerprint = config.optional("hostKeySha256", null);
                if (fingerprint == null) {
                    throw new IllegalArgumentException(
                            "Set hostKeySha256 (the server's SHA256 host key) or explicitly trust the host");
                }
                jsch.setHostKeyRepository(new PinnedHostKey(fingerprint));
                options.put("StrictHostKeyChecking", "yes");
            }
            session.setConfig(options);
            session.setTimeout(15000);
            session.connect(15000);
            try {
                ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
                channel.connect(15000);
                try {
                    return action.run(channel, config.required("directory"));
                } finally {
                    channel.disconnect();
                }
            } finally {
                session.disconnect();
            }
        } catch (JSchException | SftpException | IOException e) {
            throw new IllegalArgumentException("Cannot read the SFTP source: "
                    + SecretRedactor.redact(String.valueOf(e.getMessage()), List.of(password)), e);
        }
    }

    /** Accepts exactly one host key, given as its SHA256 fingerprint. */
    private static final class PinnedHostKey implements HostKeyRepository {
        private final String expected;

        PinnedHostKey(String expected) {
            this.expected = expected.strip();
        }

        @Override
        public int check(String host, byte[] key) {
            try {
                String actual = "SHA256:" + Base64.getEncoder().withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
                return actual.equals(expected) ? OK : NOT_INCLUDED;
            } catch (java.security.NoSuchAlgorithmException e) {
                return NOT_INCLUDED;
            }
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
        }

        @Override
        public void remove(String host, String type) {
        }

        @Override
        public void remove(String host, String type, byte[] key) {
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "pinned";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }
}
