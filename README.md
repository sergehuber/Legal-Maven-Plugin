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
            
Then build your project using : 

    mvn clean install
    
If all went well a target/NOTICE-generated file will be created containing the aggregation of the contents of all the
NOTICE files found in JARs inside the directory specified in the coImpnfiguration.

# Debugging the integration test

Launch with :

    mvn -P clean install

And connect a debugger on port 8000 (integration tests will block until debugger is connected)


