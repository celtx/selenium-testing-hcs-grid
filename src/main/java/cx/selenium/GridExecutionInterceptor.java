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

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.platform.commons.util.AnnotationUtils;
import org.junit.platform.commons.util.ReflectionUtils;
import software.amazon.awssdk.services.codebuild.model.Build;
import software.amazon.awssdk.services.codebuild.model.StatusType;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class GridExecutionInterceptor implements InvocationInterceptor {
    private static final GridExecution gridExecution = GridExecution.getInstance();
    private static final String GRID_TEST_UNIQUE_ID = System.getProperty(GridExecution.TEST_INVOCATION_ID_PROPERTY);

    private static final Logger logger = Logger.getLogger(GridExecutionInterceptor.class.getName());
    private static final boolean useGrid;

    static {

        boolean grid = false;
        String value = null;
        try {
            value = System.getenv("CXS_TEST_USE_GRID");
            if (null != value) {
                grid = 1 == Long.parseLong(value);
            }
        } catch (NumberFormatException e) {
            logger.severe(String.format("error parsing env CXS_TEST_USE_GRID [%s]", value));
        } finally {
            useGrid = grid;
        }
    }

    private Map<ExtensionContext, List<ReflectiveInvocationContext<Method>>> beforeAll = Collections.synchronizedMap(new HashMap<>());
    private Map<ExtensionContext, List<ReflectiveInvocationContext<Method>>> beforeEach = Collections.synchronizedMap(new HashMap<>());
    private Map<ExtensionContext, ReflectiveInvocationContext<Method>> testMethod = Collections.synchronizedMap(new HashMap<>());

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        String testInvocationId = getInvocationId(invocationContext);
        logger.fine(String.format("test-invocation-id:%s", testInvocationId));
        if (useGrid) {
            invocation.skip();
            String className = invocationContext.getTargetClass().getName();
            String methodName = invocationContext.getExecutable().getName();
            Build b = gridExecution.executeTest(String.format("%s.%s", className, methodName), testInvocationId).join();
            if (!StatusType.SUCCEEDED.equals(b.buildStatus())) {
                logger.severe(String.format("%s -> %s -> status %s for build=%s", className, extensionContext.getDisplayName(), b.buildStatus(), b.id()));
            }
            Assertions.assertEquals(StatusType.SUCCEEDED, b.buildStatus(), () -> gridExecution.fetchTestLogs(b));
        } else {
            if (null == GRID_TEST_UNIQUE_ID) {
                invocation.proceed();
            } else {
                if (GRID_TEST_UNIQUE_ID.equals(testInvocationId)) {
                    dockerComposeFreshUp().join();
                    beforeAll.getOrDefault(extensionContext, Collections.emptyList()).forEach(GridExecutionInterceptor::invokeMethod);
                    beforeEach.getOrDefault(extensionContext, Collections.emptyList()).forEach(GridExecutionInterceptor::invokeMethod);
                    testMethod.put(extensionContext, invocationContext); // afterEach will get called when this is set
                    invocation.proceed();
                } else {
                    invocation.skip();
                    throw new TestAbortedException(String.format("SKIP %s", extensionContext.getDisplayName()));
                }
            }
        }
    }

    private void maybeSkipInvocation(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        if (useGrid) {
            invocation.skip();
        } else {
            boolean proceed = false;
            if (null == GRID_TEST_UNIQUE_ID) {
                proceed = true;
            } else {
                beforeAll.computeIfAbsent(extensionContext, k -> Collections.synchronizedList(new ArrayList<>()));
                beforeEach.computeIfAbsent(extensionContext, k -> Collections.synchronizedList(new ArrayList<>()));
                String testInvocationId = getInvocationId(invocationContext);
                if (GRID_TEST_UNIQUE_ID.equals(testInvocationId)) {
                    proceed = true;
                } else if (!extensionContext.getRequiredTestMethod().equals(invocationContext.getExecutable())) {
                    /*
                     * Want to avoid any expensive setup/teardown related to tests other than the one we are
                     * interested in, especially in the case of tests that are parameterized.  To make this a reality,
                     * I skip the invocations of all BeforeAll/BeforeEach etc, and manually run them for the test
                     * that we are interested in.  I don't like all of this messing with before/after methods; if
                     * others can code better ways to solve this, yay!
                     */
                    if (null == testMethod.get(extensionContext)) {
                        if (AnnotationUtils.isAnnotated(invocationContext.getExecutable(), BeforeAll.class))
                            beforeAll.get(extensionContext).add(invocationContext);
                        if (AnnotationUtils.isAnnotated(invocationContext.getExecutable(), BeforeEach.class))
                            beforeEach.get(extensionContext).add(invocationContext);
                    }

                    if (null != testMethod.get(extensionContext)) {
                        if (AnnotationUtils.isAnnotated(invocationContext.getExecutable(), AfterEach.class)) {
                            proceed = true;
                        } else if (AnnotationUtils.isAnnotated(invocationContext.getExecutable(), AfterAll.class)) {
                            proceed = true;
                        }
                    }
                }
            }

            if (proceed) {
                invocation.proceed();
            } else {
                invocation.skip();
            }
        }
    }


    private String getInvocationId(ReflectiveInvocationContext<Method> invocationContext) throws GridException {
        StringBuilder sb = new StringBuilder();
        Method executable = invocationContext.getExecutable();
        sb.append(executable.getDeclaringClass().getName());
        sb.append('.');
        sb.append(executable.getName());
        sb.append('(');
        Parameter[] parameters = executable.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            Object paramArg = invocationContext.getArguments().get(i);
            if (!(paramArg instanceof String)) {
                try {
                    paramArg.getClass().getDeclaredMethod("toString");
                } catch(NoSuchMethodException err) {
                    throw new GridException("toString() must be defined on all non-string param types for ParameterizedTest");
                }
            }
            sb.append(String.format("[%s=%s]", parameter.getType(), paramArg.toString()));
            if (i != parameters.length - 1) sb.append(',');
        }
        sb.append(')');
        return sb.toString();
    }

    private static void invokeMethod(ReflectiveInvocationContext<Method> m) {
        Object target = null;
        if (m.getTarget().isPresent()) target = m.getTarget().get();
        try {
            ReflectionUtils.makeAccessible(m.getExecutable()).invoke(target, m.getArguments().toArray());
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private CompletableFuture<Void> dockerComposeFreshUp() {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        try {
            Process process = new ProcessBuilder()
                    .command(
                            "docker-compose",
                            "--no-ansi",
                            "up",
                            "--detach",
                            "--remove-orphans",
                            "--force-recreate",
                            "--always-recreate-deps"
                    )
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .start();
            int startStatus = process.waitFor();
            if (0 != startStatus) {
                logger.severe("docker-compose failed");
                cf.completeExceptionally(new GridException("docker-compose failed"));
                return cf;
            }
            ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor();
            sch.schedule(() -> {
                cf.complete(null);
                sch.shutdown();
            }, 5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            cf.completeExceptionally(new GridException(e));
        }
        return cf;
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        interceptTestMethod(invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptDynamicTest(Invocation<Void> invocation, ExtensionContext extensionContext) throws Throwable {
        interceptTestMethod(invocation, null, extensionContext);
    }

    @Override
    public <T> T interceptTestClassConstructor(Invocation<T> invocation, ReflectiveInvocationContext<Constructor<T>> invocationContext, ExtensionContext extensionContext) throws Throwable {
        return invocation.proceed();
    }

    @Override
    public <T> T interceptTestFactoryMethod(Invocation<T> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        return invocation.proceed();
    }

    @Override
    public void interceptBeforeAllMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        maybeSkipInvocation(invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        maybeSkipInvocation(invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        maybeSkipInvocation(invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptAfterAllMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        maybeSkipInvocation(invocation, invocationContext, extensionContext);
    }

}
