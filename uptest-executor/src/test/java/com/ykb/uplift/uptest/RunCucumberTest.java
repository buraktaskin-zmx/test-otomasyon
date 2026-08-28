package com.ykb.uplift.uptest;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PUBLISH_QUIET_PROPERTY_NAME;

/**
 * JUnit Platform entry point so Surefire can discover and run the Cucumber
 * features via `mvn test`. Feature files are discovered on the classpath under
 * com/ykb/uplift/uptest (src/test/resources); step definitions and hooks are
 * picked up from the playwright glue package.
 *
 * Run everything:      mvn test
 * Run one feature:     mvn test -Dcucumber.features=src/test/resources/com/ykb/uplift/uptest/<name>.feature
 * Filter by scenario:  mvn test -Dcucumber.filter.name="<scenario name regex>"
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("com/ykb/uplift/uptest")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.ykb.uplift.uptest.playwright")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "json:target/cucumber-reports/cucumber.json, html:target/cucumber-reports/cucumber.html")
@ConfigurationParameter(key = PLUGIN_PUBLISH_QUIET_PROPERTY_NAME, value = "true")
public class RunCucumberTest {
}
 