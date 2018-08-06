# cat-maven-plugin

This is a simple plugin designed to concatenate resources specified by their URI into a single file in an output 
directory.

Plugin provides a single goal `cat`, that is bound to `process-resouces ` lifecycle phase by default.

## supported URI schemas

Currently following URI schemas supported:
*   `data:` - plain data possibly encoded with base64,
    see [wikipedia article](https://en.wikipedia.org/wiki/Data_URI_scheme);
*   `file:` - files relative to project directory, schema may be omitted
*   `maven:` - link to a maven artifact or file inside a maven artifact (for jars and other zips) formatted as
    `maven:<groupId>:<artifactId>[<type>[<classifier>]]:<version>[!/<path>]`

## configuration

The following items may be specified in a plugin configuration:

*   `files` - files to construct during execution, may include the following parameters:
    - `file` - mandatory result file pathname relative to `outputDirectory`
    - `parts` - list of URIs for resources used to build file content
    - `skipExisting` - boolean flag used to determine if file should be processed if it already exists
    - `append` - boolean flag used to determine if file should be appended if it already exists
*   `outputDirectory` - directory to create files relative, default is build output directory

## Examples

```xml
<!-- insert into build/plugins section -->
<plugin>
    <groupId>io.solit.maven</groupId>
    <artifactId>cat-maven-plugin</artifactId>
    <version>${cat-plugin-version}</version>
    <executions>
        <execution>
            <id>cat</id>
            <goals><goal>cat</goal></goals>
            <configuration>
                <outputDirectory>${project.build.outputDirectory}</outputDirectory>
                <files>
                    <!-- create an empty file -->
                    <empty>
                        <file>empty.txt</file>
                    </empty>
                    <!-- append to a an existing file or create new one -->
                    <append>
                        <file>text.txt</file>
                        <parts>src/main/resources/source-file</parts>
                        <append>true</append>
                    </append>
                    <!-- do not modify file, if it exists already -->
                    <skip-existing>
                        <file>config</file>
                        <skipExisting>true</skipExisting>
                        <!-- assemble file from three parts -->
                        <parts>
                            <part>LICENCE</part>
                            <part>maven:foo:bar:jar:sources:1.0!/some/file</part>
                            <part>data:,prefix%20text</part>
                        </parts>                    
                    </skip-existing>
                </files>
            </configuration>
        </execution>
    </executions>
</plugin>
```
