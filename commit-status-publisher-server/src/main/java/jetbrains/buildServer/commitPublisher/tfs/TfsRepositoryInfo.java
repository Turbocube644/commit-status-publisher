package jetbrains.buildServer.commitPublisher.tfs;

import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TfsRepositoryInfo {

  // Captures the following groups: (protocol) (username) (hostname) (path)
  // Example: (ssh) :// (test) @ (vs-ssh.visualstudio.com:22) (/DefaultCollection/Project/_ssh/Repository)
  private static final Pattern TFS_URL_PATTERN = Pattern.compile(
    "(?:(https?|ssh)\\:\\/\\/)?(?:([^@]+)@)?([^\\/\\:]+(?:\\:\\d+)?)(?:\\:v?\\d+)?(\\/.+)?"
  );

  // Captures the following groups: (project path) (repository name)
  // Example: (/tfs/collection) /_git/ (git_project)
  private static final Pattern TFS_GIT_PROJECT_PATH_PATTERN = Pattern.compile("(\\/.+)?\\/_(?:git|ssh)\\/([^\\/]+)");

  // Captures the following groups: (organization) (project) (repository)
  // Example: / (organization) / (project) / (repository)
  private static final Pattern TFS_DEVOPS_PATH_PATTERN = Pattern.compile("\\/([^\\/]+)\\/([^\\/]+)\\/([^\\/]+)");

  private static final String[] TFS_HOSTED_DOMAINS = new String[]{"visualstudio.com", "dev.azure.com"};
  private static final String TEAMCITY_TFS_HOSTED_DOMAINS = "teamcity.tfs.hosted.domains";
  private static final Pattern TFS_HOSTS_SEPARATOR = Pattern.compile(",");

  private final String myServer;
  private final String myRepository;
  private final String myProject;

  TfsRepositoryInfo(@NotNull String server, @NotNull String repository, @Nullable String project) {
    myServer = server;
    myRepository = repository;
    myProject = project;
  }

  @Nullable
  public static TfsRepositoryInfo parse(@Nullable final String repositoryUrl) {
    return parse(repositoryUrl, null);
  }

  @Nullable
  public static TfsRepositoryInfo parse(@Nullable final String repositoryUrl, @Nullable final String serverUrl) {
    if (StringUtil.isEmptyOrSpaces(repositoryUrl)) {
      return null;
    }

    final Matcher urlMatcher = TFS_URL_PATTERN.matcher(repositoryUrl.trim());
    if (!urlMatcher.find()) {
      return null;
    }

    String schema = urlMatcher.group(1);
    String username = urlMatcher.group(2);
    String hostname = urlMatcher.group(3);
    String urlPath = StringUtil.notEmpty(urlMatcher.group(4), StringUtil.EMPTY);

    String server;
    if (StringUtil.isEmpty(schema) || "ssh".equalsIgnoreCase(schema)) {
      // DevOps URL
      if (StringUtil.endsWithIgnoreCase(hostname, "dev.azure.com")) {
        final Matcher pathMatcher = TFS_DEVOPS_PATH_PATTERN.matcher(urlPath);
        if (!pathMatcher.find()) {
          return null;
        }
        return new TfsRepositoryInfo(
          "https://dev.azure.com/" + pathMatcher.group(1),
          pathMatcher.group(3),
          pathMatcher.group(2)
        );
      }
      if (StringUtil.endsWithIgnoreCase(hostname, ".visualstudio.com:22")) {
        // VSTS URL
        if (StringUtil.isEmpty(username)) {
          return null;
        }
        server = String.format("https://%s.visualstudio.com", username);
      } else if (!StringUtil.isEmpty(serverUrl)) {
        // Has configured server URl for on-premises TFS
        final Matcher serverUrlMatcher = TFS_URL_PATTERN.matcher(serverUrl.trim());
        if (!serverUrlMatcher.find()) {
          return null;
        }
        server = serverUrlMatcher.group(1) + "://" + serverUrlMatcher.group(3);
      } else {
        return null;
      }
    } else {
      server = schema + "://" + hostname;
    }

    final Matcher pathMatcher = TFS_GIT_PROJECT_PATH_PATTERN.matcher(urlPath);
    if (!pathMatcher.find()) {
      return null;
    }

    String path = StringUtil.notEmpty(pathMatcher.group(1), StringUtil.EMPTY);
    String repository = pathMatcher.group(2);

    int lastSlash = path.lastIndexOf('/');
    String project = null;

    if (lastSlash >= 0) {
      final String lastPathSegment = path.substring(lastSlash + 1);
      if (!"defaultCollection".equalsIgnoreCase(lastPathSegment)) {
        final String collection = path.substring(0, lastSlash);
        if (StringUtil.isNotEmpty(collection) && !collection.equals("/tfs") || isHosted(server)) {
          project = lastPathSegment;
          path = collection;
        }
      }
    }

    return new TfsRepositoryInfo(server + path, repository, project);
  }

  @NotNull
  public String getServer() {
    return myServer;
  }

  @NotNull
  public String getRepository() {
    return myRepository;
  }

  @NotNull
  public String getProject() {
    return StringUtil.notEmpty(myProject, myRepository);
  }
  
  private static boolean isHosted(final String host) {
    for (String domain : getTfsHostedDomains()) {
      if (host.endsWith(domain)) return true;
    }
    return false;
  }

  private static String[] getTfsHostedDomains() {
    final String domainsList = TeamCityProperties.getPropertyOrNull(TEAMCITY_TFS_HOSTED_DOMAINS);
    if (StringUtil.isEmpty(domainsList)) {
      return TFS_HOSTED_DOMAINS;
    }

    return TFS_HOSTS_SEPARATOR.split(domainsList);
  }

  @Override
  public String toString() {
    final StringBuilder builder = new StringBuilder(myServer);
    if (StringUtil.isNotEmpty(myProject)) {
      builder.append('/').append(myProject);
    }
    builder.append("/_git/").append(myRepository);
    return builder.toString();
  }
}
