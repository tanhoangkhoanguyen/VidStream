package io.github.tanhoangkhoanguyen.vidstream.storage;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import software.amazon.awssdk.core.sync.RequestBody;

/** A single Garage node, as in docs/spikes/clip-storage.md. */
public final class GarageContainer extends GenericContainer<GarageContainer> {

  private static final String CONFIG =
      """
      metadata_dir = "/var/lib/garage/meta"
      data_dir = "/var/lib/garage/data"
      db_engine = "sqlite"
      replication_factor = 1
      rpc_bind_addr = "[::]:3901"
      rpc_public_addr = "127.0.0.1:3901"
      rpc_secret = "%s"

      [s3_api]
      s3_region = "garage"
      api_bind_addr = "[::]:3900"
      """
          .formatted("0".repeat(64));

  public GarageContainer() {
    super("dxflrs/garage:v2.5.0");
    withCopyToContainer(Transferable.of(CONFIG), "/etc/garage.toml");
    withExposedPorts(3900);
    waitingFor(Wait.forLogMessage(".*S3 API server listening.*", 1));
  }

  private S3Properties props;

  public URI endpoint() {
    return URI.create("http://" + getHost() + ":" + getMappedPort(3900));
  }

  /** Lays out the node, then creates the buckets and one key that reads and writes them. */
  public S3Properties bootstrap(String... buckets) throws IOException, InterruptedException {
    var node = garage("node", "id", "-q").split("@")[0].strip();
    garage("layout", "assign", "-z", "dc1", "-c", "1G", node);
    garage("layout", "apply", "--version", "1");
    var key = garage("key", "create", "test");
    garage("key", "allow", "--create-bucket", "test");
    for (var bucket : buckets) {
      garage("bucket", "create", bucket);
      garage("bucket", "allow", "--read", "--write", bucket, "--key", "test");
    }
    props =
        new S3Properties(
            endpoint(),
            null,
            "garage",
            field(key, "Key ID"),
            field(key, "Secret key"),
            DataSize.ofGigabytes(1));
    return props;
  }

  /** Writes an object with the bootstrap key. */
  public void write(String bucket, String key, byte[] bytes) {
    try (var s3 = ClipStore.client(props)) {
      s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromBytes(bytes));
    }
  }

  /** An object's bytes, read with the bootstrap key. */
  public byte[] read(String bucket, String key) {
    try (var s3 = ClipStore.client(props)) {
      return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
    }
  }

  private String garage(String... args) throws IOException, InterruptedException {
    var command = new String[args.length + 1];
    command[0] = "/garage";
    System.arraycopy(args, 0, command, 1, args.length);
    var result = execInContainer(command);
    if (result.getExitCode() != 0) throw new IllegalStateException(result.getStderr());
    return result.getStdout();
  }

  private static String field(String output, String name) {
    var m = Pattern.compile(name + ":\\s*(\\S+)").matcher(output);
    if (!m.find()) throw new IllegalStateException("no " + name + " in garage output");
    return m.group(1);
  }
}
