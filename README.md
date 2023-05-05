# geneseer

A search-based program-repair tool for Java.

## Running

Start a JVM with geneseer and all its dependencies on the classpath. We recommend to simply use the
`jar-with-dependencies` of geneseer, then you only need to provide that single jar file. The main class is
`net.ssehub.program_repair.geneseer.Geneseer`.

The following command line arguments must be specified in this order:

1. The path to the root directory of the project to repair. Execution of the test suite will be done with this as the
working directory.
2. The path to the directory that contains the source code files to repair. May be relative to the root directory of
the project to repair.
3. The classpath to use when compiling the source code files. This should include all the compile-time dependencies;
usually this is a list of jars. Entries may be relative to the root directory of the project to repair.
4. The classpath to execute the test suite of the project to repair. This should include the test classes themselves,
the required runtime dependencies (both for the program and for the test suite), but *not* the compiled classes of the
program under test. Note that Junit must not be specified, as the test driver of geneseer already includes that.
Entries may be relative to the root directory of the project to repair.

After these four, all further command line arguments are fully qualified class names of the test classes in the test
suite.

The classpaths (command line arguments 3 and 4) can be specified with the platform-specific file separator character
(`;` on Windows, `:` on Unix-likes). If `;` is not used, then `:` may also be used on Windows.

To specify the encoding of the source files of the project to repair, pass the JVM argument `file.encoding`. Note that
this is *not* a command line argument for this program, it needs to be specified before the main class or `-jar`
parameter with `-D`. For example `-Dfile.encoding=ISO-8859-1`. If this is not specified, the default encoding of the
operating system is used.

To get prettier and filtered log output, add the following JVM arguments:

* `-Djava.util.logging.config.class=net.ssehub.program_repair.geneseer.logging.LoggingConfiguration`
* `-Djava.util.logging.config.file=logging.properties`

`logging.properties` should point to the file included in this repository; if it is not available in the working
directory where you launch this program, copy it there or specify the full path to it in the JVM argument.

As an example, a full execution of geneseer may look like this:
```
java -Djava.util.logging.config.class=net.ssehub.program_repair.geneseer.logging.LoggingConfiguration -Djava.util.logging.config.file=logging.properties -Dfile.encoding=ISO-8859-1 -cp geneseer-jar-with-dependencies.jar net.ssehub.program_repair.geneseer.Geneseer /path/to/project-to-fix src/main/java lib/some-lib.jar:lib/other-lib.jar target/test-classes/:lib/some-lib.jar:lib/other-lib.jar:lib/test-lib.jar com.example.TestClass1 com.example.TestClass2
```

### Environment

geneseer will use the `javac` command to compile the source code files of the project to repair, and the `java` command
to run the test suite of the project to repair. Make sure that the `PATH` environment variable is set up in a way that
the proper version of JDK is used. For example, for defects4j 2.0.0, this should be Java 1.8. geneseer itself requires
a JRE of version 17 or later, which of course conflicts with this environment specification. Thus you may need to
specify the full path to the JRE 17 `java` executable when launching genesser (e.g. on Ubuntu, this is
`/usr/lib/jvm/java-1.17.0-openjdk-amd64/bin/java`).

## Output

The output of geneseer is currently undergoing rapid development and thus changes a lot.

## Compiling

This project uses [Maven](https://maven.apache.org/) for dependency management and the build process. To simply build
jars, run:
```
mvn package
```

This creates two jar files in the `target` folder (`$version` is the version that was built, e.g. `1.0.0`
or `1.0.1-SNAPSHOT`):

* `geneseer-$version.jar` just includes the class files of this program.
* `geneseer-$version-jar-with-dependencies.jar` includes the class files of this program, plus all
dependencies. This means that this jar can be used when you don't want to manually provide all dependencies of this
program each time you execute it.
