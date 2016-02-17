# Legal-Maven-Plugin
A little Maven plugin to help build Apache-compliant files (NOTICE and LICENSE)

# Usage

Setup in your project like this : 

            <plugin>
                <groupId>org.jahia.tools.maven.plugins</groupId>
                <artifactId>legal-maven-plugin</artifactId>
                <version>1.0-SNAPSHOT</version>
                <configuration>
                    <outputDirectory>${project.build.directory}/directoryToScan</outputDirectory>
                    <verbose>false</verbose> <!-- true to have the plugin output information on what it's doing -->
                    <outputDiagnotics>true</outputDiagnostics> <!-- true to have the plugin output diagnostics about found notices and licenses -->
                </configuration>
                <executions>
                    <execution>
                        <id>aggregate-legal-artifacts</id>
                        <goals>
                            <goal>aggregate-legal-artifacts</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            
Then build your project using : 

    mvn clean install
    
If all went well a target/NOTICE-aggregated and target/LICENSE-aggregated files will be created containing the aggregation
of the contents of all the NOTICE and LICENSE files found with duplicates removed in JARs inside the directory specified
in the configuration.

To run the plugin directly, just launch `mvn legal:aggregate-legal-artifacts`

# Debugging the integration test

Launch with :

    mvn -P debug clean install

And connect a debugger on port 8000 (integration tests will block until debugger is connected)


