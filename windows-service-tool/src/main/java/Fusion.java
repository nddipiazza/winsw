import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

public class Fusion {

  private static final Logger LOG = LoggerFactory.getLogger(Fusion.class);

  @Option(name = "-fusionHome", usage = "Fusion home", required = true)
  private String fusionHome;

  @Option(name = "-action", usage = "Action", required = true)
  private String action;

  @Option(name = "-javaPath", usage = "Path to java.exe")
  private String javaPath = "java";

  @Option(name = "-startSecs", usage = "Number of seconds to wait for the service to start")
  private Integer startSecs = 1200;

  @Option(name = "-stopSecs", usage = "Number of seconds to wait for the service to stop")
  private Integer stopSecs = 600;

  public static void main(String[] args) throws Exception {
    Fusion fusion = new Fusion();
    CmdLineParser parser = new CmdLineParser(fusion);
    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      parser.printUsage(System.out);
      throw e;
    }
    fusion.go();
  }

  public void go() throws Exception {
    if ("start".equalsIgnoreCase(action)) {
      startServices();
    } else if ("stop".equalsIgnoreCase(action)) {
      stopServices();
    } else if ("install".equalsIgnoreCase(action)) {
      installServices();
    } else if ("uninstall".equalsIgnoreCase(action)) {
      uninstallServices();
    }
  }

  private void installServices() throws IOException {
    System.out.println("Choose an option:");
    System.out.println(" [a] Each Lucidworks Fusion service is installed as its own Windows service.");
    System.out.println(" or ");
    System.out.println(" [b] All Lucidworks Fusion services are installed as a single Windows Service.");
    Scanner scanner = new Scanner(System.in);

    String choice = scanner.nextLine();

    String username;
    do {
      System.out.println("Enter Windows service account username in DOMAIN\\USERNAME format:");
      username = scanner.nextLine();
    } while (StringUtils.isEmpty(username) || username.indexOf('\\') < 1);

    Console console = System.console();
    if (console == null) {
      System.out.println("Couldn't get Console instance");
      System.exit(0);
    }

    String password = new String(console.readPassword("Enter Windows service account password: "));

    if (StringUtils.startsWithIgnoreCase(choice, "a")) {
      String[] defaultServices = getServicesFromFusionProperties();
      for (String service : defaultServices) {
        if (runWindowsServiceWrapper(service, "install", Arrays.asList(username, password))) {
          return;
        }
      }
    } else {
      runWindowsServiceWrapper("fusion", "install", Arrays.asList(username, password));
    }
  }

  private void uninstallServices() throws IOException {
    List<String> defaultServices = Lists.newArrayList(getServicesFromFusionProperties());
    Collections.reverse(defaultServices);
    for (String service : defaultServices) {
      if (runWindowsServiceWrapper(service, "uninstall", null)) {
        return;
      }
    }
    runWindowsServiceWrapper("fusion", "uninstall", null);
  }

  /**
   * Run service wrapper with specified arguments.
   *
   * @return Return true if it failed
   */
  private boolean runWindowsServiceWrapper(String service, String action, List<String> args) throws IOException {
    service = service.trim();
    LOG.info("Running {}-windows-service-wrapper.exe {}", service, action);
    List<String> commandList = Lists.newArrayList(fusionHome + File.separator + "bin" + File.separator + service.trim
            () +
            "-windows-service-wrapper.exe",
        action);
    if (args != null) {
      commandList.addAll(args);
    }
    Process serviceInstall = new ProcessBuilder().
        directory(new File(fusionHome))
        .command(commandList)
        .start();
    try {
      if (!serviceInstall.waitFor(500, TimeUnit.SECONDS)) {
        LOG.error("Could not install {} - timed out waiting for service to {}", service, action);
        return true;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return false;
  }

  private void startServices() throws IOException, InterruptedException {
    String[] defaultServices = getServicesFromFusionProperties();
    for (String service : defaultServices) {
      service = StringUtils.trim(service);
      runWindowsServiceWrapper(service, "start", null);
      Stopwatch stopwatch = Stopwatch.createStarted();
      do {
        Process wrapperProcess = new ProcessBuilder().
            directory(new File(fusionHome))
            .command(Arrays.asList(javaPath,
                "-jar",
                fusionHome + File.separator + "apps" + File.separator + "lucidworks-agent.jar",
                "status",
                service))
            .start();
        inheritIO(wrapperProcess.getInputStream());
        inheritIO(wrapperProcess.getErrorStream());
        String result = runCommandForOutput(wrapperProcess);
        LOG.info("Service {} status = {}", service, result);
        if (StringUtils.containsIgnoreCase(result, service + " is running")
            || StringUtils.containsIgnoreCase(result, "failed")) {
          break;
        }
        Thread.sleep(1500L);
      } while (stopwatch.elapsed(TimeUnit.SECONDS) < startSecs);
    }
  }

  private void stopServices() throws IOException, InterruptedException {
    String[] defaultServices = getServicesFromFusionProperties();
    for (String service : defaultServices) {
      service = StringUtils.trim(service);
      runWindowsServiceWrapper(service, "stop", null);
      Stopwatch stopwatch = Stopwatch.createStarted();
      do {
        Process serviceStatus = new ProcessBuilder().
            directory(new File(fusionHome))
            .command(Arrays.asList(javaPath,
                "-jar",
                fusionHome + File.separator + "apps" + File.separator + "lucidworks-agent.jar",
                "status",
                service))
            .start();
        String result = runCommandForOutput(serviceStatus);
        LOG.info("Service {} status = {}", service, result);
        if (StringUtils.containsIgnoreCase(result, service + " is not running")
            || StringUtils.containsIgnoreCase(result, service + " is stopped")
            || StringUtils.containsIgnoreCase(result, "failed")) {
          break;
        }
        Thread.sleep(1500L);
      } while (stopwatch.elapsed(TimeUnit.SECONDS) < stopSecs);
    }
  }

  private String[] getServicesFromFusionProperties() throws IOException {
    return StringUtils.trim(FileUtils.readLines(new File(fusionHome +
        File.separator + "conf" + File.separator + "fusion" + ".properties"), Charset.defaultCharset())
        .stream().filter(s -> StringUtils.trim(s).startsWith("group.default")).findFirst().get().split("=")[1]).split
        (",");
  }

  public static String runCommandForOutput(Process p) {
    String result = "";
    try {
      final BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

      StringJoiner sj = new StringJoiner(System.getProperty("line.separator"));
      reader.lines().iterator().forEachRemaining(sj::add);
      result = sj.toString();

      p.waitFor();
      p.destroy();
    } catch (Exception e) {
      e.printStackTrace();
    }
    return result;
  }

  private static void inheritIO(final InputStream src) {
    new Thread(() -> {
      Scanner sc = new Scanner(src);
      while (sc.hasNextLine()) {
        LOG.info(sc.nextLine());
      }
    }).start();
  }
}
