# Legal-Maven-Plugin
A little Maven plugin to help build Apache-compliant files (NOTICE)

# Usage

Setup in your project like this : 

            <plugin>
                <groupId>org.jahia.tools.maven.plugins</groupId>
                <artifactId>legal-maven-plugin</artifactId>
                <version>1.0-SNAPSHOT</version>
                <configuration>
                    <outputDirectory>${project.build.directory}/directoryToScan</outputDirectory>
                </configuration>
                <executions>
                    <execution>
                        <id>aggregate-notices</id>
                        <goals>
                            <goal>aggregate-notices</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>

# Debugging the integration test

Launch with :
    mvn -P clean install

And connect a debugger on port 8000 (integration tests will block until debugger is connected)


