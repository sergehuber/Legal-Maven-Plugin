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

# Generated reports/databases

The plugin will also generate multiple reports in the target directory.

## JAR database

The JAR database report will generate a JSON database that contains all the collected metadata for all the JARs 
(including embedded JARs) found in the directory tree that was scanned. This database is generated in the 

    target/jar-database.json
    
file and might be quite large.

## Package lists

A list of packages encountered in all the JARs that were scanned. The reports are generated in two files that uses
different file formats : 

    target/package-licenses.json
    target/flat-package-list.csv
    
The data contained in these files is essentially the same. The JSON format is destined to be able to use the data
programmatically while the CSV format is destined to use the data in a spreadsheet such as Microsoft Excel, Apple Numbers
or Google Spreadsheet.

## Known licenses

The known licenses report is an update of the embedded know-licenses.json database that lists all the known licenses, but
the generated report suggests new entries to facilitate updating the database. You should review the result of this 
report to decide whether you want to add these licenses to the default database. You can find the known licenses JSON
database in the following files and directories :

    target/known-licenses.json
    target/known-licenses/

# Debugging the integration test

Launch with :

    mvn -P debug clean install

And connect a debugger on port 8000 (integration tests will block until debugger is connected)


