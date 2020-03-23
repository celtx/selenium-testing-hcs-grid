/*
 * Copyright (c) Celtx Inc. <https://www.celtx.com>
 *
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

package cx.selenium;

import com.google.common.collect.Lists;
import cx.utils.concurrent.CompletableFutureSemaphore;
import cx.utils.git.GitUtils;
import cx.utils.zip.ZipUtils;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.OutputLogEvent;
import software.amazon.awssdk.services.codebuild.CodeBuildAsyncClient;
import software.amazon.awssdk.services.codebuild.model.*;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.Security;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class GridExecution {

    CompletableFuture<Build> executeTest(final String test, final String testUniqueId) {
        CompletableFuture<Build> cf = new CompletableFuture<>();
        maxSimultaneous.acquire().join();
        synchronized (mutex) {
            if (projectInitializedCalled.compareAndSet(false, true)) {
                try {
                    initializeProject().join();
                } catch (CompletionException err) {
                    throw new RuntimeException(err);
                }
            }

            if (!projectInitialized.get()) {
                logger.severe("Could not initialize AWS CodeBuild Project, check AWS config");
                Stream.of(logger.getHandlers()).forEach(Handler::close);
                System.exit(2);
            }

            // A little anti-herding
            try {
                Thread.sleep(Math.round(500 * Math.random()));
            } catch (InterruptedException ignored) {
            }

            StartBuildRequest startBuildRequest = createStartBuildRequest(test, testUniqueId);
            StartBuildResponse startBuildResponse = submitBuild(startBuildRequest).join();
            Build build = startBuildResponse.build();
            testCompletions.put(build.id(), cf);
            if (pollingBuilds.compareAndSet(false, true)) pollBuilds();

        }
        return cf;
    }

    private void markBuildAsComplete(CompletableFuture<Build> cf, Build build) {
        maxSimultaneous.release().join();
        cf.complete(build);
    }

    private void pollBuilds() {
        logger.fine("started polling builds...");
        scheduler.scheduleAtFixedRate(this::checkForCompletedBuilds, 90, 10, TimeUnit.SECONDS);
    }

    private void checkForCompletedBuilds() {
        synchronized (mutex) {
            List<String> inProgressBuilds = testCompletions.keySet()
                    .stream().filter(b -> !testCompletions.get(b).isDone()).collect(Collectors.toList());
            long totalBuildsFinished = testCompletions.size() - inProgressBuilds.size();

            List<Build> testBuilds = new ArrayList<>();
            try {
                if (!inProgressBuilds.isEmpty()) {
                    // batchGetBuilds can only process 100 build ids at a time
                    List<List<String>> buildListPartitions = Lists.partition(inProgressBuilds, 100);
                    for (int i = buildListPartitions.size() - 1; i > -1; i--) {
                        List<String> chunk = buildListPartitions.get(i);
                        logger.fine(String.format("getting %d of %d builds, chunk=%d", chunk.size(), inProgressBuilds.size(), i));
                        List<Build> testBuildsChunk = batchGetBuilds(chunk.toArray(new String[]{})).join().builds();
                        testBuilds.addAll(testBuildsChunk);
                    }
                }
            } catch (RuntimeException e) {
                logger.severe(e.getMessage());
            }

            logger.info(String.format("polling builds: total=%d completed=%d in_progress=%d completed_this_poll=%d",
                    testCompletions.size(), totalBuildsFinished, inProgressBuilds.size(),
                    testBuilds.stream().filter(Build::buildComplete).count()));

            testBuilds.stream()
                    .filter(Build::buildComplete)
                    .forEach(b -> markBuildAsComplete(testCompletions.get(b.id()), b));
        }
    }

    private CompletableFuture<Boolean> initializeProject() {
        CompletableFuture<Boolean> cf = new CompletableFuture<>();

        CompletableFuture<PutObjectResponse> putObjectResponse;
        try {

            codeBuildAsyncClient = CodeBuildAsyncClient.builder()
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .retryPolicy(RetryPolicy.defaultRetryPolicy())
                            .build())
                    .build();

            s3AsyncClient = S3AsyncClient.builder()
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .retryPolicy(RetryPolicy.defaultRetryPolicy())
                            .build())
                    .build();

            cloudWatchLogsAsyncClient = CloudWatchLogsAsyncClient.builder()
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .retryPolicy(RetryPolicy.defaultRetryPolicy())
                            .build())
                    .build();

            File workingDir = new File(System.getProperty("user.dir"));
            String latestCommitId = GitUtils.getLatestCommitId(workingDir);
            codeBuildProjectName = makeValidAWSCodeBuildProjectName(String.format("%s-tests-%s", testHost, latestCommitId));
            logger.info(String.format("Using AWS CodeBuild Project name=[%s] ", codeBuildProjectName));

            projectZip = createProjectZip();
            putObjectResponse = uploadProjectZip();
            putObjectResponse.join();
            createProject().join();

            projectInitialized.set(true);
            cf.complete(true);
        } catch (Exception err) {
            logger.severe(String.format("Error during Grid Execution Setup [%s]", err.getMessage()));
            cf.completeExceptionally(err);
        } finally {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Stream.of(logger.getHandlers()).forEach(Handler::close);
                try {
                    if (null != s3AsyncClient) s3AsyncClient.close();
                } catch (SdkClientException ignore) {
                }
                try {
                    if (null != cloudWatchLogsAsyncClient) cloudWatchLogsAsyncClient.close();
                } catch (SdkClientException ignore) {
                }
                try {
                    if (null != codeBuildAsyncClient) codeBuildAsyncClient.close();
                } catch (SdkClientException ignore) {
                }
                try {
                    scheduler.shutdownNow();
                } catch (RuntimeException ignore) {
                }
            }));
        }
        return cf;
    }

    private File createProjectZip() throws IOException {
        String zipFileString = ZipUtils.generateFileName(String.format("%s-", codeBuildProjectName), ".zip", null);

        String[] archiveThese = {
                "etc",
                "src",
                "gradle",
                "gradlew",
                "gradle.properties",
                "settings.gradle",
                "build.gradle",
                "docker-compose.yml"
        };

        ZipUtils.createZip(zipFileString, archiveThese);

        return new File(zipFileString);
    }

    private CompletableFuture<PutObjectResponse> uploadProjectZip() {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(CODE_BUILD_BUCKET)
                .contentType("application/zip")
                .key("test/source/" + projectZip.getName())
                .expires(Instant.now().plus(3, ChronoUnit.DAYS))
                .build();
        S3AsyncClient client = s3AsyncClient;
        return client.putObject(putObjectRequest, AsyncRequestBody.fromFile(Paths.get(projectZip.getAbsolutePath())));
    }

    private StartBuildRequest createStartBuildRequest(String testMethod, String testUniqueId) {
        return StartBuildRequest.builder()
                .projectName(codeBuildProjectName)
                .privilegedModeOverride(true)
                .timeoutInMinutesOverride(MAX_TEST_DURATION_MINUTES)
                .sourceTypeOverride(SourceType.S3)
                .sourceLocationOverride(CODE_BUILD_BUCKET + "/test/source/" + projectZip.getName())
                .buildspecOverride(getBuildSpec(testMethod, testUniqueId))
                .build();
    }

    private CompletableFuture<StartBuildResponse> submitBuild(StartBuildRequest startBuildRequest) {
        if (!projectInitialized.get()) {
            CompletableFuture<StartBuildResponse> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("Cannot continue, bad initialization, AWS credentials?"));
            return failed;
        }
        CodeBuildAsyncClient client = codeBuildAsyncClient;
        return client.startBuild(startBuildRequest);
    }

    private CompletableFuture<BatchGetBuildsResponse> batchGetBuilds(String[] projects) {
        CodeBuildAsyncClient client = codeBuildAsyncClient;
        BatchGetBuildsRequest batchGetBuildsRequest = BatchGetBuildsRequest.builder()
                .ids(projects)
                .build();
        return client.batchGetBuilds(batchGetBuildsRequest);
    }

    private CompletableFuture<Project> createProject() {
        CompletableFuture<Project> cf = new CompletableFuture<>();
        CodeBuildAsyncClient client = codeBuildAsyncClient;

        BatchGetProjectsRequest batchGetProjectsRequest = BatchGetProjectsRequest.builder()
                .names(codeBuildProjectName)
                .build();

        BatchGetProjectsResponse batchGetProjectsResponse = client.batchGetProjects(batchGetProjectsRequest).join();
        List<Project> projectList = batchGetProjectsResponse.projects();
        if (!projectList.isEmpty()) {
            Project project = batchGetProjectsResponse.projects().get(0);
            cf.complete(project);
        } else {
            CreateProjectResponse createProjectResponse = client.createProject(configureCreateProjectRequest()).join();
            cf.complete(createProjectResponse.project());
        }
        return cf;
    }

    private CreateProjectRequest configureCreateProjectRequest() {

        ProjectSource projectSource = ProjectSource.builder()
                .buildspec(getBuildSpec())
                .type(SourceType.NO_SOURCE)
                .build();

        ProjectCache projectCache = ProjectCache.builder()
                .type(CacheType.S3)
                .location(String.format("%s/cache", CODE_BUILD_BUCKET))
                //.modes(CacheMode.LOCAL_SOURCE_CACHE, CacheMode.LOCAL_DOCKER_LAYER_CACHE)
                .modes(CacheMode.LOCAL_SOURCE_CACHE)
                .build();

        ProjectArtifacts projectArtifacts = ProjectArtifacts.builder()
                .type(ArtifactsType.NO_ARTIFACTS)
                .build();

        ProjectEnvironment projectEnvironment = ProjectEnvironment.builder()
                .type(CODE_BUILD_ENV_TYPE)
                .computeType(CODE_BUILD_COMPUTE_TYPE)
                .image(CODE_BUILD_IMAGE)
                .privilegedMode(false)
                .build();

        CloudWatchLogsConfig cloudWatchLogsConfig = CloudWatchLogsConfig.builder()
                .groupName(CODE_BUILD_LOG_GROUP)
                .status(LogsConfigStatusType.ENABLED)
                .build();

        S3LogsConfig s3LogsConfig = S3LogsConfig.builder()
                .status(LogsConfigStatusType.DISABLED)
                .build();

        LogsConfig logsConfig = LogsConfig.builder()
                .cloudWatchLogs(cloudWatchLogsConfig)
                .s3Logs(s3LogsConfig)
                .build();

        return CreateProjectRequest.builder()
                .environment(projectEnvironment)
                .name(codeBuildProjectName)
                .serviceRole(CODE_BUILD_SERVICE_ROLE)
                .source(projectSource)
                .cache(projectCache)
                .artifacts(projectArtifacts)
                .logsConfig(logsConfig)
                .build();
    }

    private static String makeValidAWSCodeBuildProjectName(String name) {
        return name.replaceAll("[^A-z0-9_-]", "_");
    }

    public String fetchTestLogs(Build build) {
        StringBuilder sb = new StringBuilder();
        CloudWatchLogsAsyncClient client = cloudWatchLogsAsyncClient;
        String logStreamName = build.id();
        logStreamName = logStreamName.substring(1 + logStreamName.lastIndexOf(':'));

        boolean started = false;
        boolean finished;
        String nextToken = null;

        GetLogEventsRequest.Builder requestBuilder = GetLogEventsRequest.builder()
                .logGroupName(CODE_BUILD_LOG_GROUP)
                .limit(1000)
                .startFromHead(true)
                .logStreamName(logStreamName);

        int page = 0;
        final int MAX_LOG_RESPONSE_PAGES = 5;
        do {
            if (null != nextToken) requestBuilder.nextToken(nextToken);
            page++;
            logger.info(String.format("fetching cloudwatch page %d of log entries for %s", page, build.id()));
            try {
                GetLogEventsResponse response = client.getLogEvents(requestBuilder.build()).join();
                finished = !response.hasEvents();
                for (OutputLogEvent outputLogEvent : response.events()) {
                    String message = outputLogEvent.message();
                    if (message.contains("Phase complete: BUILD")) finished = true;
                    if (message.contains("Entering phase BUILD")) started = true;
                    boolean skippedTest = false;
                    if (message.endsWith(" SKIPPED")) skippedTest = true;
                    if (started && !finished && !skippedTest) sb.append(outputLogEvent.message());
                }
                if (!finished) nextToken = response.nextForwardToken();
                if (null == nextToken) finished = true;
            } catch (RuntimeException err) {
                logger.severe(err.getMessage());
                finished = true;
            }
        } while (!finished && page < MAX_LOG_RESPONSE_PAGES);
        logger.fine(String.format("done fetching cloudwatch log entries for %s", build.id()));
        return sb.toString();
    }

    private static String getEnv(String key, String fallback) {
        String value = System.getenv(key);
        return null == value ? fallback : value;
    }

    private final static Logger logger = Logger.getLogger(GridExecution.class.getName());

    private final static CompletableFutureSemaphore maxSimultaneous;

    // TODO possibly make these configurable via environment variables?
    private final static int MAX_TEST_DURATION_MINUTES = 20;

    private final static EnvironmentType CODE_BUILD_ENV_TYPE = EnvironmentType.fromValue("LINUX_CONTAINER");
    private final static ComputeType CODE_BUILD_COMPUTE_TYPE = ComputeType.fromValue("BUILD_GENERAL1_SMALL");
    private final static String CODE_BUILD_IMAGE = "aws/codebuild/amazonlinux2-x86_64-standard:2.0";
    private final static String CODE_BUILD_JAVA = "corretto11";

    private final static String CODE_BUILD_BUCKET = getEnv("CXS_TEST_CODEBUILD_S3", "Missing S3 bucket for codebuild");
    private final static String CODE_BUILD_SERVICE_ROLE = getEnv("CXS_TEST_CODEBUILD_ROLE_ARN", "Missing role arn for codebuild");
    private final static String CODE_BUILD_LOG_GROUP = getEnv("CXS_TEST_CODEBUILD_LOG_GROUP", "Missing log group for codebuild");

    static {
        Security.setProperty("networkaddress.cache.ttl", "60");
        long tokens = 1;
        String value = null;
        try {
            value = System.getenv("CXS_TEST_CONCURRENCY");
            if (null != value) {
                tokens = Long.parseLong(value);
            }
        } catch (NumberFormatException e) {
            logger.severe(String.format("error parsing env CXS_TEST_CONCURRENCY [%s]", value));
        } finally {
            maxSimultaneous = new CompletableFutureSemaphore(tokens);
        }
    }

    /**
     * This provides the mechanism whereby parameterized tests have each
     * parameter run in their own AWS CodeBuild instance
     * TODO: FOR NOW, THIS MUST MATCH what is in build.gradle file
     */
    final static String TEST_INVOCATION_ID_PROPERTY = "cx.grid.test.invocationId";

    private final static AtomicBoolean projectInitializedCalled = new AtomicBoolean(false);
    private final static AtomicBoolean projectInitialized = new AtomicBoolean(false);
    private final static AtomicBoolean pollingBuilds = new AtomicBoolean(false);
    public final static Object mutex = new Object();
    private static String testHost = getEnv("TESTHOST", "localhost");
    private static String codeBuildProjectName = "NOT_YET_INITIALIZED";
    private static File projectZip = null;
    private static CodeBuildAsyncClient codeBuildAsyncClient;
    private static S3AsyncClient s3AsyncClient;
    private static CloudWatchLogsAsyncClient cloudWatchLogsAsyncClient;
    private final static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private Map<String, CompletableFuture<Build>> testCompletions = Collections.synchronizedMap(new HashMap<>());

    private String getBuildSpec() {
        return getBuildSpec(null, null);
    }

    private String getBuildSpec(String testString, String testUniqueId) {
        final String argString;
        if (null == testString) {
            argString = "";
        } else if (null != testUniqueId) {
            argString = String.format("-D%s='%s' test --tests '%s'", TEST_INVOCATION_ID_PROPERTY, testUniqueId, testString);
        } else {
            argString = String.format("test --tests '%s'", testString);
        }
        return String.format(BUILDER_YAML, argString);
    }

    private static String BUILDER_YAML = "version: 0.2\n" +
            "env:\n" +
            "  variables:\n" +
            "    TESTHOST: " + testHost + "\n" +
            "    BROWSER: " + getEnv("BROWSER", "chrome") + "\n" +
            "    GRADLE_USER_HOME: .gradle/userHome\n" +
            "phases:\n" +
            "  install:\n" +
            "    runtime-versions:\n" +
            "      java: " + CODE_BUILD_JAVA + "\n" +
            "  pre_build:\n" +
            "    commands:\n" +
            "      - export LC_ALL=\"en_US.utf8\"\n" +
            "      - echo $PATH\n" +
            "      - java -version\n" +
            "      - echo $HOME \n" +
            "      - echo \"Testing repo\"\n" +
            "      - pwd\n" +
            "      - ls -al\n" +
            "      - docker-compose --no-ansi up --detach\n" +
            "      - chmod u+x ./gradlew\n" +
            "      - ./gradlew -v\n" +
            "      - sleep 5\n" +
            "  build:\n" +
            "    commands:\n" +
            "      -  ./gradlew cleanTest %s\n" +
            "  post_build:\n" +
            "    commands:\n" +
            "      - echo \"Post build cleanup\"\n" +
            "      - docker-compose --no-ansi down --remove-orphans\n" +
            "cache:\n" +
            "  paths:\n" +
            "  - '.gradle/**/*'\n";

    private GridExecution() {
    }

    private static class SingletonHelper {
        private static final GridExecution INSTANCE = new GridExecution();
    }

    public static GridExecution getInstance() {
        return SingletonHelper.INSTANCE;
    }
}
