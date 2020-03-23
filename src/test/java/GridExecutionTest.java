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

import cx.selenium.GridExecutionInterceptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@ExtendWith(GridExecutionInterceptor.class)
class GridExecutionTest {

    @Test
    @DisplayName("failed on purpose")
    void failingOnPurpose() {
        Assertions.fail("failed on purpose");
    }

    @ParameterizedTest(name = "Test Website {0}")
    @MethodSource("testWebsiteArguments")
    void testWebsites(WebsiteInfo website) {
        driver.get(website.host);
        Assertions.assertNotNull(driver.getTitle());
        Assertions.assertTrue(driver.getTitle().contains(website.title));
    }

    private static Stream<WebsiteInfo> testWebsiteArguments () {
        return Stream.of(
                WebsiteInfo.build("https://www.google.com", "Google"),
                WebsiteInfo.build("https://wwww.bing.com", "Bing")
        );
    }

    private static class WebsiteInfo {
        final String host;
        final String title;
        private WebsiteInfo(String host, String title) {
            this.host = host;
            this.title = title;
        }
        static WebsiteInfo build(String host, String title) {
            return new WebsiteInfo(host, title);
        }

        /**
         * Parameterized tests may require some special handling related to
         * the toString() method of the test method parameters.
         *
         * If a type used as a test method parameter doesn't declare one
         * explicitly, you will see a GridException.  Types like String,
         * Integer, etc all declare toString methods, so it usually only
         * required for your own complex test method parameterization.
         *
         * If requires, you must return a unique identifier per parameterized test param.
         */
        @Override
        public String toString() {
            return String.format("WebsiteInfo<host=%s,title=%s>", host, title);
        }
    }

    @BeforeEach
    void driverInit() {
        DesiredCapabilities capabilities = new DesiredCapabilities();
        capabilities.setBrowserName("chrome");
        driver = new RemoteWebDriver(capabilities);
        driver.manage().timeouts().pageLoadTimeout(10L, TimeUnit.SECONDS);
    }

    @AfterEach
    void driverQuit() {
        if (null != driver) {
            driver.quit();
            driver = null;
        }
    }

    private WebDriver driver = null;

}
